package com.appsquadz.educryptmedia.lifecycle

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.appsquadz.educryptmedia.core.EducryptSdkState
import com.appsquadz.educryptmedia.core.SdkState
import com.appsquadz.educryptmedia.logger.EducryptEvent
import com.appsquadz.educryptmedia.logger.EducryptEventBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

internal object EducryptLifecycleManager : DefaultLifecycleObserver {

    // AtomicBoolean.compareAndSet — atomic check-and-set, no race condition
    private val isInitialized = AtomicBoolean(false)

    // @Volatile — visibility guaranteed across threads
    @Volatile
    private var scope: CoroutineScope? = null

    /**
     * Called once from EducryptMedia.init(applicationContext) or getInstance().
     * Idempotent — safe to call multiple times, initialises only once.
     * Should be called on the main thread (Application.onCreate() guarantees this).
     *
     * No context stored — lifecycle is anchored to ProcessLifecycleOwner,
     * not to any Activity or Application reference.
     *
     * DEPRECATION NOTE:
     * ProcessLifecycleOwner.get() is deprecated in lifecycle-process:2.8.0+
     * in favour of ProcessLifecycle.get(). This is safe with 2.7.0 (current).
     * When upgrading lifecycle-process to 2.8.0+, update this call site.
     * One call site only — easy to find and fix.
     */
    internal fun init() {
        // compareAndSet(false, true) — atomic. Returns false if already initialized.
        if (!isInitialized.compareAndSet(false, true)) return

        // Observe process lifecycle.
        // ON_DESTROY fires ONLY on actual process death — not Activity/Fragment destruction.
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        // SupervisorJob — child coroutine failure does not cancel the scope.
        // Dispatchers.Main.immediate — delivers events on main thread without re-posting
        // if already on main.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        // Transition SDK to READY state
        EducryptSdkState.transitionTo(SdkState.READY)

        // Start the self-healing internal collector
        startInternalCollection()
    }

    /**
     * Self-healing internal collector.
     *
     * Key fix — CancellationException is ALWAYS re-thrown. It is how coroutines
     * signal cancellation. Swallowing it creates an infinite loop after
     * scope.cancel() is called. This is a critical correctness requirement.
     *
     * On any other exception: wait 500ms, restart. This makes the collector
     * resilient to unexpected errors without pinning the thread.
     */
    private fun startInternalCollection() {
        scope?.launch {
            while (true) {
                try {
                    EducryptEventBus.events.collect { event ->
                        onInternalEvent(event)
                    }
                } catch (e: CancellationException) {
                    throw e   // ALWAYS re-throw — this terminates the coroutine cleanly
                } catch (e: Exception) {
                    // Genuine error in the collector — wait and restart
                    delay(500)
                }
            }
        }
    }

    /**
     * Internal event handler — used by Phase 3 and Phase 4 components.
     * Phase 3 stall watchdog reads PlaybackBuffering events here.
     * Phase 4 ABR controller reads QualityChanged events here.
     * Currently a no-op — Phase 3/4 will wire into this.
     */
    internal fun onInternalEvent(event: EducryptEvent) {
        // Phase 3: stall detection hook
        // Phase 4: ABR quality decision hook
    }

    /**
     * Exposes the managed scope for internal SDK components.
     * Phase 3 StallRecoveryManager and Phase 4 AbrController
     * launch their coroutines here — not in their own scopes.
     * All SDK coroutines live and die together.
     */
    internal fun scope(): CoroutineScope? = scope

    /**
     * ProcessLifecycleOwner.ON_DESTROY — process is actually dying.
     * Scope cancels, all child coroutines stop cleanly via CancellationException.
     */
    override fun onDestroy(owner: LifecycleOwner) {
        performShutdown()
    }

    /**
     * Explicit shutdown from EducryptMedia.shutdown().
     * Idempotent — safe to call multiple times.
     */
    internal fun shutdown() {
        performShutdown()
    }

    private fun performShutdown() {
        if (!isInitialized.compareAndSet(true, false)) return  // already shut down

        // Remove observer first — prevent onDestroy double-fire
        try {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        } catch (e: Exception) {
            // Ignore — process may already be dying
        }

        // Cancel the scope — CancellationException propagates to all children
        // which re-throw it correctly — they terminate cleanly
        scope?.cancel()
        scope = null

        // Clear the event buffer
        EducryptEventBus.clearBuffer()

        // Reset SDK state — allows re-initialisation if client calls init() again
        EducryptSdkState.reset()
    }
}

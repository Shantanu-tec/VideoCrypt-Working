package com.appsquadz.educryptmedia.logger

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicLong

internal object EducryptEventBus {

    // ─────────────────────────────────────────────────────────────────────
    // Sequence counter
    //
    // Monotonically increasing. Stamped onto every event at emission time.
    // Atomic — safe from any thread. getAndIncrement() is a single CPU
    // instruction on modern hardware — effectively zero overhead.
    //
    // Why needed:
    // Buffer (synchronized write) and SharedFlow (tryEmit outside lock) are
    // two separate operations. Under thread scheduling pressure, two concurrent
    // emit() calls could write to buffer in order A→B but broadcast via
    // SharedFlow in order B→A. Sequence numbers let clients detect and
    // correct ordering if their use case requires it.
    // ─────────────────────────────────────────────────────────────────────
    private val sequenceCounter = AtomicLong(0)

    internal var appSessionId: String = ""
    internal var playbackSessionId: String = ""
    internal var lastEmittedEventName: String = ""
    internal var onDegradingEvent: (() -> Unit)? = null

    /** Updated on every NetworkMetaSnapshot emit. Used by ABR and retry policy. */
    internal var currentSignalStrength: String = "UNKNOWN"

    // ─────────────────────────────────────────────────────────────────────
    // Layer 1 — Circular write-ahead buffer
    //
    // Always written. Never drops. Thread-safe via dedicated lock object.
    // In-memory only — cleared on process death or shutdown().
    // Holds last MAX_BUFFER events from the current session.
    // Buffer is written under lock → buffer order is always correct.
    // ─────────────────────────────────────────────────────────────────────
    private const val MAX_BUFFER = 200
    private val buffer = ArrayDeque<IndexedValue<EducryptEvent>>(MAX_BUFFER)
    private val bufferLock = Any()

    // ─────────────────────────────────────────────────────────────────────
    // Layer 2 — Live SharedFlow streams
    //
    // events        : EducryptEvent only — for clients who don't need ordering
    // indexedEvents : IndexedValue<EducryptEvent> — for clients who need
    //                 guaranteed ordering (sort by IndexedValue.index)
    //
    // replay = 0         : no replay for new collectors (real-time events only)
    //                      late collectors use recentEvents() to backfill
    // extraBufferCapacity: holds up to 128 events if collectors are slow
    // DROP_OLDEST        : never blocks any emitting thread under any circumstance
    // ─────────────────────────────────────────────────────────────────────
    private val _events = MutableSharedFlow<EducryptEvent>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val _indexedEvents = MutableSharedFlow<IndexedValue<EducryptEvent>>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    internal val events: SharedFlow<EducryptEvent> = _events.asSharedFlow()
    internal val indexedEvents: SharedFlow<IndexedValue<EducryptEvent>> =
        _indexedEvents.asSharedFlow()

    /**
     * Thread-safe, non-blocking emit.
     * Safe from any thread: main, WorkManager, Handler, coroutine, Java thread.
     *
     * Sequence is stamped atomically before buffer write.
     * Buffer write is under lock — buffer order is always strictly sequential.
     * SharedFlow broadcast is outside lock — live stream order approximates
     * buffer order but may differ under extreme thread scheduling pressure.
     * Clients requiring strict ordering should use indexedEvents and sort by index.
     */
    internal fun emit(event: EducryptEvent) {
        // Stamp sequence atomically — single operation, no lock needed
        val sequence = sequenceCounter.getAndIncrement()

        // Stamp session IDs onto every event before buffering
        event.appSessionId = appSessionId
        event.playbackSessionId = playbackSessionId
        lastEmittedEventName = event::class.simpleName ?: ""

        if (event is EducryptEvent.ErrorOccurred || event is EducryptEvent.DrmSessionError) {
            onDegradingEvent?.invoke()
        }

        val indexed = IndexedValue(sequence.toInt(), event)

        // Layer 1 — write to buffer under lock (always in sequence order)
        synchronized(bufferLock) {
            if (buffer.size >= MAX_BUFFER) buffer.removeFirst()
            buffer.addLast(indexed)
        }

        // Layer 2 — non-blocking broadcast to active collectors
        // tryEmit on MutableSharedFlow is documented thread-safe — no lock needed
        _events.tryEmit(event)
        _indexedEvents.tryEmit(indexed)
    }

    /**
     * Returns the last [count] events from the current session.
     * Thread-safe. Returns a snapshot — independent of live emission.
     * Buffer is always in strict sequence order (written under lock).
     *
     * Use for:
     *   - Diagnostic dumps after an error
     *   - Late-starting collectors that missed early events
     *   - Crash report context (events are in correct order here)
     *
     * @param count number of events to return (clamped to 1..MAX_BUFFER)
     */
    internal fun recentEvents(count: Int = 50): List<EducryptEvent> {
        val clamped = count.coerceIn(1, MAX_BUFFER)
        return synchronized(bufferLock) {
            buffer.takeLast(clamped).map { it.value }.toList()
        }
    }

    /**
     * Returns the last [count] events with their sequence numbers.
     * Use when ordering correlation between playback and download events is needed.
     */
    internal fun recentIndexedEvents(count: Int = 50): List<IndexedValue<EducryptEvent>> {
        val clamped = count.coerceIn(1, MAX_BUFFER)
        return synchronized(bufferLock) {
            buffer.takeLast(clamped).toList()
        }
    }

    /**
     * Clears the buffer and resets the sequence counter.
     * Called by EducryptLifecycleManager.shutdown() only.
     * Does NOT close the SharedFlow — collectors are unaffected.
     * The SharedFlow lives for the process lifetime and is never closed.
     */
    internal fun clearBuffer() {
        synchronized(bufferLock) {
            buffer.clear()
        }
        sequenceCounter.set(0)
    }
}

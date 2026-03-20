# Project Audit Report
Date: 2026-03-20
Branch: enhance (current working branch — feature/CrashLytics shown in git status)
Context: MODULE

---

## Executive Summary

The EducryptMedia SDK is a well-structured, four-phase Android media library covering DRM playback (Widevine/PallyCon), non-DRM VOD/live streaming, background AES-encrypted downloads, adaptive bitrate control, stall recovery, and a typed event bus. The architecture is clean — a Facade pattern with good separation between playback, download, error, lifecycle, and observability layers. The core playback path (DRM, non-DRM, offline) is correct and the network recovery mechanism is well implemented. The single most critical defect to fix immediately is three unguarded `println()` calls in `EducryptMedia.kt` at lines 1129–1137 that emit to `System.out` in **release builds**, leaking vdcId values to client logcat with no `BuildConfig.DEBUG` guard.

---

## What the SDK Does

**DRM Playback (Widevine + PallyCon):** Fetches content metadata from the VideoCrypt API, acquires a Widevine licence via `HttpMediaDrmCallback` with a `pallycon-customdata-v2` token, builds a `DashMediaSource`, and passes it to the client via `onDrm` callback. The licence URL defaults to `https://license.videocrypt.com/validateLicense` and is client-overridable.

**Non-DRM Playback:** For content without a PallyCon token the SDK builds a `MediaItem` via `DefaultMediaSourceFactory` and returns it via `onNonDrm`. Supports VOD and live streams (live URLs are constructed with `?start=` / `?start=&end=` query parameters from API response).

**Offline Playback:** Downloaded files are AES-128-encrypted on disk. Playback uses a custom `AesDataSource` that decrypts on-the-fly using a key derived from `vdcId.split("_")[2]`. Clients call `prepareForPlayback()` then `initializeNonDrmDownloadPlayback()`.

**Adaptive Bitrate (Phase 4):** `EducryptAbrController` starts at mid-quality, probes bandwidth every 5 s via `DefaultBandwidthMeter`, steps up one tier when bandwidth allows, drops one tier immediately on stall, and enters safe mode (locks to lowest quality) after 3 stalls in 60 s. Exits safe mode after 5 min of stable playback.

**Buffer Tuning (Phase 3):** `EducryptLoadControl` replaces ExoPlayer defaults with 15 s min / 50 s max / 3 s start / 5 s rebuffer. `StallRecoveryManager` watches for buffering > 8 s and drives the ABR quality drop path.

**Network Recovery:** `NetworkRecoveryManager` registers a `ConnectivityManager.NetworkCallback` on fatal network errors, waits for `NET_CAPABILITY_VALIDATED`, then automatically re-prepares the player at the last known position.

**Downloads:** `VideoDownloadWorker` (WorkManager `CoroutineWorker`) streams files over HTTP with 32 KB buffer, supports `Range` resume, updates `DownloadProgressManager` (LiveData/StateFlow) and `LocalBroadcastManager` on progress. Concurrent download limit enforced in-memory via `DownloadProgressManager.getActiveDownloadCount()`.

**Error Classification (Phase 2):** `EducryptExoPlayerErrorMapper` converts all `PlaybackException` codes to 13 typed `EducryptError` subtypes. DRM licence errors distinguish 401 (AUTH_INVALID) from 403 (AUTH_EXPIRED) via HTTP status code extraction from the cause chain — no string parsing. Retry policy: 3 attempts, exponential backoff 1 s / 2 s / 4 s via `EducryptLoadErrorPolicy`.

**Event Bus (Phase 1):** `EducryptEventBus` is a `MutableSharedFlow` with a 200-event circular buffer, sequence numbering, and non-blocking `tryEmit`. 24 event subtypes cover playback lifecycle, stalls, ABR, errors, downloads, metadata snapshots, and SDK usage errors. `recentEvents()` allows late-joining collectors to backfill.

**Observability:** `MetaSnapshotBuilder` emits paired `PlayerMetaSnapshot` + `NetworkMetaSnapshot` at 6 trigger points: LOADING, DRM_READY, READY, ERROR, STALL_RECOVERY, NETWORK_RECOVERY. Network snapshot includes transport type, operator, generation (2G–5G), signal strength, bandwidth estimates.

---

## What Works Well

- **Clean Facade pattern** — `EducryptMedia` hides all internal complexity. Public API surface is well-defined and almost entirely accessed through one class.
- **Thread safety** — `EducryptEventBus` uses atomics and locks correctly. `EducryptLifecycleManager` uses `AtomicBoolean.compareAndSet` to prevent double-init. `NetworkRecoveryManager` uses `@Volatile isWatching`.
- **DRM error path is thorough** — HTTP status code extraction via cause chain walk is correct and avoids fragile string parsing. 401/403 distinguished properly.
- **Retry policy is correctly scoped** — `EducryptLoadErrorPolicy` is attached to both `DefaultMediaSourceFactory` (non-DRM) and `DashMediaSource.Factory` (DRM), so both paths get retry coverage.
- **Network recovery is correct** — `onCapabilitiesChanged` (not `onAvailable`) is used for `NET_CAPABILITY_VALIDATED`, callback is captured before `stopWatching()` (race-free pattern). Position is captured before recovery to restore seek position.
- **ABR uses TrackSelectionOverride** — correct approach; `setMaxVideoSize` only sets a ceiling, override forces exact track. Fallback to `setMaxVideoSize` for unresolved tracks is a reasonable safety net.
- **`EducryptGuard` validates before every public call** — SDK state, main-thread requirement, string/range param checks, all emit `SdkError` rather than throwing.
- **Download resume works correctly** — Range header sent for partial files, server 200 vs 206 handled, partial file deleted on full restart.
- **`EducryptLifecycleManager` self-healing collector** — re-throws `CancellationException` correctly (critical correctness requirement) and restarts on other exceptions without blocking.
- **DownloadProgressManager is reactive and dual-mechanism** — LiveData + StateFlow per-download and aggregate, backward-compatible `LocalBroadcastManager` kept.
- **`MetaSnapshotBuilder` is permission-safe** — all restricted API calls wrapped in try/catch, returns safe fallbacks without requiring extra permissions.
- **`DownloadStatus` constants are clean** — all usages in SDK now use constants (raw strings cleaned up in prior sessions).
- **`consumer-rules.pro` is complete** — all public classes have ProGuard keep rules.

---

## Gaps & Missing Features

### Playback

| Gap | Why It Matters | Effort |
|-----|----------------|--------|
| HLS not implemented | `HlsMediaSource` is imported but never used. Many CDNs deliver HLS only; content that requires HLS will fail with `UNSUPPORTED_FORMAT`. | Medium |
| Subtitle/caption support absent | No CEA-608/708, WebVTT, or SRT rendering control. Required for accessibility compliance (WCAG 2.1, ADA). | Medium |
| Audio track selection not exposed | `PlayerSettingsBottomSheetDialog` shows quality and speed only; no language/audio track picker. Critical for multilingual content. | Low |
| Offline DRM not supported | Only AES-128 download encryption. True Widevine offline licensing (L1/L3 key download) is not implemented. Required for premium content offline distribution. | High |
| No cross-session position persistence | When a user closes and re-opens the app, playback restarts from 0. Resume-from-last-position is a standard OTT feature. | Low |
| No content pre-loading | No API to pre-buffer the next video before the current one ends. | Medium |
| No CDN failover | `MediaLoaderBuilder` sends a single URL; if the CDN is down, the error surfaces to the user. No multi-CDN fallback. | Medium |
| Live stream detection not surfaced as event | `isLive` is set internally after API response, but no event is emitted when a stream is detected as live. Clients must poll after callbacks. | Low |

### Downloads

| Gap | Why It Matters | Effort |
|-----|----------------|--------|
| `deleteDownload()` does not cancel active worker | If called on an in-progress download, file is deleted but `VideoDownloadWorker` continues running. Worker will either error on write or recreate the file. Data inconsistency. | Low |
| `STORAGE_INSUFFICIENT` error code exists but is never emitted | `VideoDownloadWorker` has no disk space check — insufficient storage fails with a generic `IOException`. The typed error code is misleading. | Low |
| `isResuming` uses file presence, not Realm status | If a file from a prior session exists at the same path with a different content, the worker attempts to resume it via Range request. Could produce a corrupted file. | Low |
| No `DownloadResumed` event | `resumeDownload()` has no corresponding event. Clients cannot differentiate resume from fresh start via the event stream alone. | Low |
| Network check in write loop is expensive | `isNetworkAvailable()` is called inside the byte-copy loop whenever a network error is detected (checked on every buffer read). Should be event-driven, not polled. | Low |
| `DownloadProgressManager` never cleared | `clearAll()` exists but is never called by the SDK itself. On very long-lived apps downloading many videos, the `activeDownloads` map grows unbounded. | Low |

### Observability

| Gap | Why It Matters | Effort |
|-----|----------------|--------|
| No buffered duration in snapshots | When diagnosing stalls, knowing how much was buffered at stall time is critical. `ExoPlayer.bufferedPosition` is available. | Low |
| No playback position in ERROR / READY snapshots | `PlayerMetaSnapshot` has no `currentPositionMs` field. Stall position is in `StallDetected` separately, but snapshots lack it. | Low |
| No SDK version in snapshots | Clients receiving support requests cannot easily determine which AAR version is in use from event data alone. | Low |
| No timestamp in events | `EducryptEvent` subtypes carry no wall-clock timestamp. Client must timestamp on receipt, which is imprecise for events emitted on background threads. | Low |
| No `PlaybackEnded` event | Clients must attach a raw `Player.Listener` to detect `STATE_ENDED`. | Low |
| `DownloadProgressChanged` only at 25/50/75% | Too coarse for smooth progress bars; most UIs update every second or every few percent. | Low |

### Security

| Gap | Why It Matters | Effort |
|-----|----------------|--------|
| SSL pinning disabled | `createCertificatePinner()` is implemented but commented out. MITM attack would expose API credentials and content URLs. | Low |
| DRM internals exposed via public methods | `getDrmSessionManager()`, `getDrmCallback()`, `getDataSourceFactory()` are `@UnstableApi public`. Clients can extract internal DRM objects; no reason for these to be public. | Low |
| AES key material hardcoded in `AES.kt` | Key derivation algorithm and base material baked into AAR. Motivated attacker can decompile and decrypt downloaded files. | High (requires backend coordination) |
| `accessKey` / `secretKey` logged in debug builds | `headerLoggingInterceptor()` logs all HTTP headers in `BuildConfig.DEBUG`. Both API keys appear in logcat on every API call during development. | Low |
| DRM token recovery risk | After a long network outage, `attemptPlaybackRecovery()` re-prepares with the original DRM session. If the PallyCon token expired during the outage, recovery will silently fail with a DRM error rather than re-authenticating. | Medium |

### API Surface

| Gap | Why It Matters | Effort |
|-----|----------------|--------|
| `downloadableName` dropped in `resumeDownload()` | `DownloadListener.resumeDownload()` accepts `downloadableName` but `EducryptMedia.resumeDownload()` does not. Parameter is silently ignored, notification title is blank on resume. | Low |
| `currentSpeedPosition` / `currentResolutionPosition` are public companion `var` | Mutable global state on the companion object — any code can corrupt these. Should be internal. | Low |
| `observeAllDownloads()` is private dead code | Private method that is never called. Dead code that suggests an unfinished feature or an accidental privatization. | Low |
| `isLive` is a public `var` | Clients can accidentally overwrite this. Should be `private set` or read-only. | Low |
| `liveEdgeJob == null` bug in `PlayerActivity.onDestroy()` | Comparison instead of assignment at line 204. Demo-only, but sets a misleading example for integrators. | Low |

---

## Code Quality Issues

### !! Operators (Risk Classification)

| File | Line | Expression | Classification | Risk |
|------|------|-----------|----------------|------|
| `SpeedAdapter.kt` | 36 | `speed.text!!` | **Risk** | `SpeedModel.text` is nullable; if client creates `SpeedModel(null, ...)` the adapter crashes with NPE | Medium |
| `EducryptMedia.kt` | 739–746 | `accessKey!!`, `secretKey!!`, `userId!!`, `deviceType!!`, `deviceId!!`, `deviceName!!`, `version!!`, `accountId!!` | **Safe (fragile)** | Each is checked by `requireField()` just above which throws `IllegalArgumentException` on null/blank. Safe today, but the `!!` creates an implicit dependency on call order that a future refactor could silently break. Should use `checkNotNull()` with a message. | Medium |
| `EducryptMedia.kt` | 1221 | `it.vdcId!!` in `deleteAllDownloads()` loop | **Risk** | `DownloadMeta.vdcId` is a nullable Realm field. A partially-written record (e.g., from a crash during insert) would NPE here and abort deletion of all remaining downloads. | Medium |
| `DownloadMetaImpl.kt` | 21–23 | `it.vdcId!!`, `it.percentage!!`, `it.status!!` | **Risk** | Same as above — nullable Realm fields forced non-null. Any partially-written record crashes here. | Medium |
| `AesDataSource.kt` | 26 | `File(uri.path!!).canonicalFile` | **Risk** | `uri.path` returns null for `content://` URIs and opaque URIs. If the client ever passes a content URI for offline playback, this NPEs. Currently only `file://` URIs are used, but there's no enforcement. | Low |

### Hardcoded Status Strings

No raw hardcoded status strings found in SDK source. Grep confirms only the constants definition file matches. ✅ Clean after prior session fixes.

### TODO / FIXME

Zero found. ✅ SDK source is clean of TODO/FIXME/HACK/XXX markers.

### Logging in Release Code

| File | Lines | Issue | Severity | Fix |
|------|-------|-------|----------|-----|
| `EducryptMedia.kt` | 1129, 1133, 1137 | `println("Download Complete $vdcId")`, `println("Download Cancelled $vdcId")`, `println("Download Failed $vdcId")` — **`println()` goes to `System.out`, NOT guarded by `BuildConfig.DEBUG`** — these emit to client logcat in release AAR builds | **High** | Replace with `Log.d(MEDIA_TAG, ...)` inside `if (BuildConfig.DEBUG)` guard, or remove entirely since events already cover these transitions |
| `PlayerSettingsBottomSheetDialog.kt` | 101, 108, 116 | `Log.e("--->", "quality adapter")`, `Log.e("--->", "map: ...")`, `Log.e("--->", "currentMappedTrackInfo is null")` — unguarded `Log.e` with debug-level content | **Medium** | Wrap in `if (BuildConfig.DEBUG)` or remove |
| `utils/forceSkip.kt` | 189, 197 | `Log.e("--->", "Video Renderer not found!")`, `Log.e("--->", "No video track groups available!")` — unguarded `Log.e` with placeholder tag | **Low** | Use `MEDIA_TAG` and wrap in debug guard |
| `NetworkManager.kt` | 161 | `Log.w("NetworkManager", "Request failed...")` — unguarded retry log | **Low** | Wrap in `if (BuildConfig.DEBUG)` |
| `EducryptMedia.kt` | Multiple | ~15 `Log.d/w/e(MEDIA_TAG, ...)` calls — unguarded, emit in production | **Low** | Consolidate behind debug flag or remove lower-priority ones |
| `NetworkManager.kt` | 207–225 | `headerLoggingInterceptor()` logs ALL request/response headers including `accessKey` and `secretKey` — already guarded by `if (BuildConfig.DEBUG)` | **Low (guarded)** | Already guarded, but consider adding `redactHeader("accessKey"); redactHeader("secretKey")` as extra protection |

### Duplicate Event Emission

| Issue | Location | Severity |
|-------|----------|----------|
| `SafeModeEntered` emitted TWICE for a single safe mode trigger | `StallRecoveryManager.handleStall()` line ~112 emits it; `EducryptAbrController.onSafeModeRequired()` line 104 also emits it. Both fire when stalls exceed the threshold. | Medium |

---

## Improvement Recommendations

Ranked by value (highest first):

### 1. Fix `println()` calls in release builds — IMMEDIATE
- **What:** Replace three `println()` calls in `EducryptMedia.kt` (lines 1129, 1133, 1137) with guarded `Log.d()` or remove them.
- **Why:** These emit to `System.out` in production release AAR builds. Every client's logcat receives `"Download Complete $vdcId"` etc. leaking vdcId values. The event bus already covers these transitions (`DownloadCompleted`, `DownloadCancelled`, `DownloadFailed`).
- **Effort:** Low (3 lines)
- **Dependencies:** None.

### 2. Fix `deleteDownload()` worker cancellation gap
- **What:** Call `cancelWorkerForVdcId(vdcId)` at the start of `deleteDownload()` before `removeDownloads()`.
- **Why:** Currently an active download worker can outlive the deletion, write to the now-deleted file path (recreating it), or crash. Leaves the download in an undefined state.
- **Effort:** Low (1 line)
- **Dependencies:** None.

### 3. Fix `SafeModeEntered` double-emission
- **What:** Remove the `EducryptEventBus.emit(EducryptEvent.SafeModeEntered(...))` call from `StallRecoveryManager.handleStall()` — keep only the emission in `EducryptAbrController.onSafeModeRequired()`.
- **Why:** Collectors receive two `SafeModeEntered` events per safe-mode trigger. Client analytics will double-count safe-mode entries.
- **Effort:** Low (1 line removed)
- **Dependencies:** Verify BaseApp demo still handles the event correctly after the removal.

### 4. Fix remaining `!!` operators — Risk category
- **What:** Replace the five **Risk** category `!!` operators (see Code Quality Issues above) with null-safe guards or `checkNotNull()` with descriptive messages.
- **Why:** `DownloadMeta` fields being nullable means any corrupted Realm record crashes `deleteAllDownloads()` and `DownloadMetaImpl`. `SpeedAdapter` would crash if a client passes a null-text speed model.
- **Effort:** Low (5 sites)
- **Dependencies:** None.

### 5. Expose `downloadableName` in `resumeDownload()` / fix API inconsistency
- **What:** Add `downloadableName: String = ""` parameter to `EducryptMedia.resumeDownload()` and pass it through to the worker's input data.
- **Why:** `DownloadListener.resumeDownload()` accepts this parameter which is silently dropped. The download notification title is empty on resume. This is an existing API contract that isn't fulfilled.
- **Effort:** Low
- **Dependencies:** None (additive, not breaking).

### 6. Add disk space check before download start
- **What:** Check available disk space against content length before enqueuing the worker. Use `StatFs` on `getExternalFilesDir(null)` to get available bytes.
- **Why:** Currently `STORAGE_INSUFFICIENT` error code exists but is never emitted. Downloads fail mid-way with a generic IOException, and clients receive no actionable `onError` message to surface to users.
- **Effort:** Low
- **Dependencies:** None.

### 7. Guard `PlayerSettingsBottomSheetDialog` debug logs
- **What:** Wrap `Log.e("--->", ...)` calls in `PlayerSettingsBottomSheetDialog` in `if (BuildConfig.DEBUG)` guards.
- **Why:** These appear in client release builds. The placeholder `"--->` tag is also unprofessional in a shipped AAR.
- **Effort:** Low (3 lines)
- **Dependencies:** None.

### 8. Enable SSL pinning
- **What:** Add real certificate hashes for `api.videocrypt.com` and `license.videocrypt.com` to `createCertificatePinner()`, then uncomment the `certificatePinner()` call in `buildOkHttpClient()`.
- **Why:** Without pinning, a MITM attack (or compromised CA) can intercept API credentials (`accessKey`, `secretKey`) and video URLs. The infrastructure to do this is already written — it just needs real hashes.
- **Effort:** Low (requires certificate fingerprints from ops team)
- **Dependencies:** Certificate fingerprints from backend/infra.

### 9. Add HLS support
- **What:** Wire `HlsMediaSource.Factory` for `.m3u8` URLs in `initializeNonDrmPlayback()`. Attach `EducryptLoadErrorPolicy` to the HLS factory as documented in `SCRATCHPAD.md`.
- **Why:** HLS is the dominant streaming format for mobile. Many content providers deliver HLS-only. Currently `.m3u8` content would be attempted via `ProgressiveMediaSource` and likely fail.
- **Effort:** Medium
- **Dependencies:** Test content in HLS format; may also require DRM DASH+HLS bifurcation in `initializeDrmPlayback`.

### 10. ABR bandwidth smoothing
- **What:** Replace the single `bandwidthMeter.bitrateEstimate` point sample with an exponential weighted moving average (EWMA) over the last N probe cycles.
- **Why:** A single-sample estimate on variable mobile networks causes quality thrashing. Netflix/YouTube use EWMA with a short window (10–20 s) to smooth transient fluctuations.
- **Effort:** Medium
- **Dependencies:** None; the EWMA can be maintained inside `EducryptAbrController`.

### 11. Add `DownloadResumed` event
- **What:** Emit `EducryptEvent.DownloadResumed(vdcId)` (new subtype) at the start of `resumeDownload()`.
- **Why:** Clients cannot distinguish a resumed download from a newly started one via the event stream. This also enables accurate session tracking in analytics.
- **Effort:** Low — add subtype to `EducryptEvent`, emit in `resumeDownload()`, add `consumer-rules.pro` keep rule, handle in BaseApp collector.
- **Dependencies:** New public event = new keep rule in `consumer-rules.pro`. Any client collecting `else -> {}` is unaffected.

### 12. Fix `isLive` visibility
- **What:** Change `var isLive: Boolean = false` to `var isLive: Boolean = false; private set`.
- **Why:** Clients can accidentally set `educryptMedia.isLive = true` which would cause incorrect live-edge monitoring and snapshot `isLive = true` when content is VOD.
- **Effort:** Low (1 modifier)
- **Dependencies:** None. Not a breaking change (read access is unchanged, only write access restricted).

### 13. Remove internal DRM getter exposure
- **What:** Make `getDrmSessionManager()`, `getDrmCallback()`, and `getDataSourceFactory()` `internal` (or remove them).
- **Why:** These expose internal `DefaultDrmSessionManager`, `HttpMediaDrmCallback`, and `DefaultHttpDataSource.Factory` to client code — unintended and unneeded. Clients use `getMediaSource()` and `getPlayer()`.
- **Effort:** Low — but check if any external client code uses these before removing.
- **Dependencies:** If any client calls these, deprecate first.

---

## Suggested Session Plan

**Session 25 (Quick wins — no API changes):**
Focus: Fix the three critical code quality issues that require no API changes and ship as a hotfix.
- Fix `println()` → guarded `Log.d()` or remove (lines 1129/1133/1137)
- Fix `deleteDownload()` worker cancellation gap
- Fix `SafeModeEntered` double-emission
- Guard `PlayerSettingsBottomSheetDialog` debug logs
- Fix `liveEdgeJob == null` assignment bug in `PlayerActivity`

**Session 26 (Hardening — additive changes):**
Focus: Fix remaining `!!` operators, add disk space check, fix `downloadableName` in `resumeDownload`.
- Replace 5 Risk-category `!!` operators
- Add disk space pre-check in `startDownload()`
- Add `downloadableName` to `resumeDownload()` signature
- Add `DownloadResumed` event (new subtype — add consumer-rules.pro entry)
- Fix `isLive` to `private set`

**Session 27 (Security):**
Focus: Security hardening before any production distribution.
- Enable SSL pinning (requires certificate hashes from ops)
- Remove or internalize `getDrmSessionManager()` / `getDrmCallback()` / `getDataSourceFactory()`
- Make `currentSpeedPosition` / `currentResolutionPosition` internal
- Audit accessKey/secretKey logging — add redactHeader if not already done

**Session 28 (ABR quality):**
Focus: Production-grade ABR.
- Add EWMA bandwidth smoothing in `EducryptAbrController`
- Add bandwidth-triggered quality drops (not just stall-triggered)
- Add per-track bitrate matching (supplement height-based matching)
- Add configurable safe mode exit criteria (bandwidth check, not just timer)

**Session 29 (HLS + subtitles):**
Focus: Format coverage.
- Wire `HlsMediaSource.Factory` for `.m3u8` URLs
- Add audio track selection to `PlayerSettingsBottomSheetDialog`
- Add subtitle/caption track selection (WebVTT minimum)

**Session 30 (Observability upgrades):**
Focus: Richer diagnostics.
- Add `currentPositionMs` and `bufferedDurationMs` to `PlayerMetaSnapshot`
- Add wall-clock timestamp to event emission
- Add SDK version constant and include in `PlayerMetaSnapshot`
- Add `PlaybackEnded` event
- Tighten `DownloadProgressChanged` to fire more frequently (configurable interval vs. hard milestones)

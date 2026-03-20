# Scratchpad — Open Questions & Reminders

---

## SDK Integration Quick Reference

### Minimal Client Setup (copy-paste)

```kotlin
// 1. Application.onCreate() — required
EducryptMedia.getInstance(this).cleanupStaleDownloads()
createDownloadNotificationChannel(this) // channel name: "download_channel"

// 2. Fetch and play (SDK resolves DRM vs non-DRM automatically)
val sdk = EducryptMedia.getInstance(activity)

sdk.MediaLoaderBuilder()
    .setVideoId(videoId)
    .setAccessKey(accessKey).setSecretKey(secretKey)
    .setUserId(userId).setDeviceType("1").setDeviceId(deviceId)
    .setVersion("2").setDeviceName(deviceName).setAccountId(accountId)
    .onDrm   { exoPlayer.setMediaSource(sdk.getMediaSource()!!); exoPlayer.prepare(); exoPlayer.playWhenReady = true }
    .onNonDrm { exoPlayer.setMediaItem(sdk.getMediaItem()!!); exoPlayer.prepare(); exoPlayer.playWhenReady = true }
    .onError { message -> showError(message) }
    .load()

// 3. Offline playback
sdk.initializeNonDrmDownloadPlayback(videoId, localFileUri)
exoPlayer.setMediaSource(sdk.getMediaSource()!!)
exoPlayer.prepare()
```

### DRM Setup Pattern

```kotlin
// SDK handles DRM automatically via MediaLoaderBuilder.
// To play a DRM video directly (if you already have URL + PallyCon token):
sdk.setDrmLicenceUrl("https://license.videocrypt.com/validateLicense") // optional override
sdk.initializeDrmPlayback(videoUrl, pallyconToken)
exoPlayer.setMediaSource(sdk.getMediaSource()!!)
exoPlayer.prepare()
```

### Download Pattern

```kotlin
// Step 1: Fetch available download URLs for a video
sdk.MediaDownloadBuilder()
    .setVideoId(videoId).setAccessKey(key).setSecretKey(secret)
    .onDownload { downloads ->
        val urls = downloads.data?.download_url  // List<DownloadableUrl>
        // Let user pick a quality, then:
        sdk.setDownloadableName("Lecture 01")
        sdk.startDownload(vdcId = videoId, url = selectedUrl, fileName = "unique_file_name")
    }
    .onError { message -> }
    .execute()

// Step 2: Observe progress
DownloadProgressManager.getProgressLiveData(vdcId).observe(owner) { p: DownloadProgress ->
    updateUI(p.progress, p.getFormattedSpeed(), p.status)
}

// Step 3: Play offline
val localUri = File(getExternalFilesDir(null), "$fileName.mp4").toUri().toString()
sdk.initializeNonDrmDownloadPlayback(videoId, localUri)
exoPlayer.setMediaSource(sdk.getMediaSource()!!)
exoPlayer.prepare()
```

---

## Snapshot Notes (Session 20)

- `videoUrl` is populated at LOADING (linter corrected it from "" to `url`), DRM_READY, READY. Empty string at ERROR / STALL_RECOVERY / NETWORK_RECOVERY — URL not re-passed to keep callback API simple; use `videoId` to correlate.
- Track info (height/width/bitrate) is always 0 at LOADING/READY/DRM_READY — ExoPlayer hasn't selected tracks yet. Reliable at ERROR / STALL / NETWORK_RECOVERY.
- `NETWORK_TYPE_IDEN` deprecation warning in MetaSnapshotBuilder — harmless, legacy 2G constant.

---

## Open Questions

### Client Integration
- [ ] Does the SDK require calling `getInstance()` with `applicationContext` vs `Activity`? (Current code accepts either — uses `applicationContext` internally. Recommend always passing `applicationContext` to avoid memory leaks.)
- [ ] Are there threading requirements for `MediaLoaderBuilder.load()`? (It runs on `Dispatchers.IO` internally — safe to call from any thread.)
- [ ] What happens if `load()` is called before network is available? (Returns error via `onError` callback — "No internet connection".)
- [ ] Is `setDrmLicenceUrl()` per-instance or persisted? (Per-instance — resets if singleton is recreated, which shouldn't happen in normal use.)
- [ ] Does the SDK need to be initialized before WorkManager schedules workers? (WorkManager is app-level; workers use `RealmManager.init(context)` lazily. No explicit SDK init needed before enqueuing — but `EducryptMedia.getInstance()` must have been called at least once to set the context for `isNetworkAvailable()`.)

### DRM
- [ ] Is offline Widevine license download ever planned? (Current: non-DRM AES download only. DRM-protected content cannot be downloaded offline with current implementation.)
- [ ] What happens when a DRM license expires mid-playback? (ExoPlayer DRM error → `Player.Listener.onPlayerError()` fires. No SDK-level retry logic exists.)
- [ ] Is `pallycon-customdata-v2` the only supported PallyCon token format? (Only format currently used — check with backend if v1 support is needed.)

### Downloads
- [ ] What is the exact file path for downloaded videos? `context.getExternalFilesDir(null)/<fileName>.mp4` — this is in the app's external storage, accessible without READ_EXTERNAL_STORAGE on API 29+.
- [ ] Are downloaded files encrypted? Yes — AES-128, key derived from `vdcId.split("_")[2]` using `AES.generateLibkeyAPI()`. Files are unplayable without the SDK's `AesDataSource`.
- [ ] What happens to downloads when the user uninstalls the app? External files in `getExternalFilesDir` are deleted on uninstall. Realm DB (in `context.filesDir/realm/`) is also deleted.
- [ ] What happens to Realm records when AAR is upgraded to a new schema version? Realm will throw `RealmMigrationNeededException` if schema version bumped without providing migration. Current schema is version 1 — add migration before bumping.
- [x] Max concurrent downloads: configurable via `EducryptMedia.setMaxConcurrentDownloads(max)`, default 3, clipped to 1–10. **NOTE: the concurrent limit check is currently broken** (case mismatch bug — see TASKS.md).

### Realm
- [ ] Current schema version: **1** (in `RealmManager.kt` line 18). Next change needs `schemaVersion(2)` + migration.
- [ ] Realm database location: `context.filesDir/realm/educrypt.realm` (internal storage — not accessible to clients directly).
- [ ] Is Realm exposed to client apps? Via `api()` dependency — yes. Clients can query `DownloadMeta` objects directly if they import Realm.

### API / Network
- [ ] Why is `EncryptionData.flag = "1"` hardcoded for playback but not downloads? (Playback uses `name` + `flag="1"`; downloads use `id` only. Backend-specific — don't change without backend confirmation.)
- [x] ~~The retry interceptor doesn't retry POST~~ — ✅ Fixed 2026-03-19. Both POST endpoints verified idempotent (read-only lookups). POST added to `isRetriableMethod()`. All current POST endpoints verified idempotent as of 2026-03-19.

---

## Phase 4 Notes (for Release / Phase 5)

- `EducryptAbrController` bandwidth thresholds (500 K / 1 M / 2.5 M / 5 M bps) are starting estimates — adjust after real `BandwidthEstimated` event data from production logger
- Safe mode exit time (5 min) may need tuning — watch `SafeModeExited` events; if exits happen too early and stalls resume, increase the window
- `getPlayer()` is now public — clients MUST use the SDK-managed ExoPlayer. Creating own ExoPlayer bypasses all Phase 3/4 infrastructure. Demo app updated to use `getPlayer()` exclusively.
- `getTrackSelector()` is now public — use with `PlayerSettingsBottomSheetDialog`.
- `stop()` is now public — call from `onDestroy()` of the player screen.
- `EducryptMedia.prepareForPlayback()` is now public — call before `initializeNonDrmDownloadPlayback()` when offline playback is not preceded by `load()`.
- `initializeNonDrmDownloadPlayback()` now has `onReady`/`onError` callbacks — eliminates async race where `getMediaSource()` was called before Realm callback fired.
- `QualityChanged` is backward-compatible: old callers using `QualityChanged(qualityLabel = "720p")` still compile; new callers can read `fromHeight`/`toHeight`/`reason`
- **TrackSelectionOverride is the correct quality forcing mechanism** — `setMaxVideoSize` only sets a ceiling; ExoPlayer's internal ABR still runs within it. Use `TrackSelectionOverride(group.mediaTrackGroup, trackIndex)` via `trackSelector.parameters.buildUpon().setOverrideForType(...)`. Call `clearOverridesOfType(C.TRACK_TYPE_VIDEO)` to restore auto mode.
- **QualityChanged source** — emitted from `applyQuality()` only. `EducryptPlayerListener` no longer emits it; doing so produced `0p→0p` events during track transitions (ExoPlayer reports height=0 briefly during quality switches).
- All 4 phases ship together in one AAR version — do not ship partial phases

## AAR Release Readiness (all 4 phases)
- [ ] Phase 1 events firing in demo app (PlaybackStarted, DrmReady, PlaybackBuffering, DownloadStarted, etc.)
- [ ] Phase 2 error callbacks working; retry policy active (verify with network throttling)
- [ ] Phase 3 buffer values applied (ExoPlayer logcat shows custom load control)
- [ ] Phase 4 ABR starting at mid quality; `BandwidthEstimated` events emitted; safe mode triggers at 3 stalls
- [x] All consumer-rules.pro keep rules present for public classes from all 4 phases — ✅ verified 2026-03-20
- [x] `./gradlew :EducryptMediaSdk:assembleRelease` → clean BUILD SUCCESSFUL — ✅ 2026-03-20
- [x] `./gradlew :app:assembleDebug` → clean BUILD SUCCESSFUL — ✅ 2026-03-20

## Phase 3 Notes (for Phase 4)

- `StallRecoveryManager.onStallDetected` — Phase 4 hook for quality drop via `DefaultTrackSelector`; currently a no-op placeholder in `EducryptMedia.initPlayer()`
- `StallRecoveryManager.onSafeModeRequired` — Phase 4 hook for safe mode activation (lock to lowest quality); currently a no-op placeholder
- Buffer values in `EducryptLoadControl` are starting points — adjust after real stall frequency data is available from `EducryptEvent.StallDetected` events in production
- `EducryptPlayerListener` is now `internal` (was `public`) — no consumer-rules.pro update needed; `getPlayerListener()` was already `internal`
- `HlsMediaSource` is imported in `EducryptMedia.kt` but never instantiated — if HLS is added in the future, attach `EducryptLoadErrorPolicy` to `HlsMediaSource.Factory` at that point

---

## Reminders

- ⚠️ PUBLIC API = breaking change risk — deprecate before removing
- ⚠️ Realm schema version 1 — MUST be bumped with migration when any entity field changes
- ⚠️ DRM license logic is backend-coupled — never change `Apis.kt` or `EncryptionData` without backend confirmation
- ⚠️ Test every SDK change via the app module BEFORE building AAR
- ⚠️ AES key material is hardcoded in `AES.kt` — changing it breaks playback of all existing downloads
- ⚠️ All `DownloadStatus` comparisons must use constants, never raw strings. Future audit grep pattern:
  `grep -rn '"Paused"\|"paused"\|"Downloading"\|"downloading"\|"Completed"\|"completed"\|"downloaded"\|"Cancelled"\|"cancelled"\|"Failed"\|"failed"' EducryptMediaSdk/src/main/java/`

---

## Realm Schema

**Current version: 3**

`DownloadMeta` fields (7 total):

| Field | Type | Notes |
|---|---|---|
| `vdcId` | `String?` | Primary key |
| `fileName` | `String?` | Without `.mp4` extension |
| `url` | `String?` | Original HTTP URL |
| `percentage` | `String?` | `"0"`–`"100"` |
| `status` | `String?` | `DownloadStatus` constants |
| `totalBytes` | `Long` | Added v2, default `0L` |
| `downloadedBytes` | `Long` | Added v2, default `0L` |

`ChunkMeta` fields (7 total — added v3):

| Field | Type | Notes |
|---|---|---|
| `id` | `String` | Primary key — `"$vdcId-$chunkIndex"` |
| `vdcId` | `String` | Parent download ID |
| `chunkIndex` | `Int` | 0–3 |
| `startByte` | `Long` | Byte range start (inclusive) |
| `endByte` | `Long` | Byte range end (inclusive) |
| `downloadedBytes` | `Long` | Bytes written so far for this chunk |
| `completed` | `Boolean` | True when chunk finished |

Migrations:
- v1→v2: `AutomaticSchemaMigration` sets `totalBytes=0L` and `downloadedBytes=0L` on all existing `DownloadMeta` records. Guarded by `oldRealm.schemaVersion() < 2L`.
- v2→v3: `ChunkMeta` class added — Realm creates the table automatically. Empty migration body.

Next migration will be **v4**.

---

## Download System (Session A)

- **Adaptive buffer**: `getBufferSize()` returns 128 KB on WiFi (`NET_CAPABILITY_NOT_METERED`), 32 KB on cellular/metered. `BufferedInputStream` and write `ByteArray` always the same size.
- **Network check throttle**: `networkCheckCounter % 50 == 0` — ~1 check per 1.6 MB cellular / 6.4 MB WiFi (was every 32 KB chunk).
- **ETA smoothing**: `ArrayDeque<Long>(5)` rolling average of the last 5 speed samples (1-second windows). Avoids ETA jitter on bursty connections.
- **Queue**: `pendingDownloadQueue: ArrayDeque<Triple<String,String,String>>` in `EducryptMedia` — never public. Downloads beyond `maxConcurrentDownloads` are queued, not dropped. `drainQueue()` fires from `DownloadProgressManager` on DOWNLOADED/FAILED/CANCELLED.
- **`broadcastRetrying()`**: used on all `Result.retry()` paths. Keeps Realm/DownloadProgressManager status as `DOWNLOADING` — no transient FAILED flicker during WorkManager backoff.
- **`getInstance()`**: no-arg companion method added to `EducryptMedia` — returns `INSTANCE?` for internal cross-package calls (e.g., `DownloadProgressManager → drainQueue()`).

---

## Parallel Downloading (Session C)

- **NUM_CHUNKS = 4** connections, each writing to a non-overlapping byte range via `RandomAccessFile.seek()`.
- **Chunk state** persisted in `ChunkMeta` (Realm v3) — enables resume across worker restarts.
- **Fallback**: if server does not return `Accept-Ranges: bytes` on HEAD request, falls back to single-connection download.
- **Progress** reported by a dedicated coroutine every 1 s, aggregated across all chunks via `AtomicLong`.

ChunkMeta cleanup must happen in four places:
1. `downloadParallel()` — on successful completion (and failure)
2. `deleteDownload()` — permanent removal
3. `cancelDownload()` — permanent removal
4. `cleanupStaleDownloads()` — orphan sweep on app start

**Pause does NOT delete chunks** — they are preserved for resume.

---

## AES Encryption

AES-CBC/NoPadding — AES-128, key and IV derived from `videoId.split("_")[2]`
via `AES.generateLibkeyAPI()` and `AES.generateLibVectorAPI()`.

**Seeking: O(1) block seek** — IV for block N = ciphertext bytes `(N-1)*16` to `N*16-1`.
`RandomAccessFile` used directly (no `CipherInputStream`).
`forceSkip` extension: deleted — no longer needed.
`getCipher()` function: deleted — replaced by direct `toByteArray()` calls at the call site.

Seek algorithm:
```
blockIndex  = position / 16
blockOffset = position % 16
seekIv      = raw file bytes [(blockIndex-1)*16 .. blockIndex*16-1]   (or original IV if blockIndex==0)
RAF.seek(blockIndex * 16)
cipher.init(DECRYPT, key, seekIv)
if blockOffset > 0: decrypt one block, buffer bytes [blockOffset..]
```

---

## Technical Debt Discovered

| Item | Location | Severity | Notes |
|------|----------|----------|-------|
| ~~consumer-rules.pro incomplete~~ | `EducryptMediaSdk/consumer-rules.pro` | ✅ Fixed 2026-03-19 | Added keep rules for all 7 public classes/interfaces |
| ~~Download status case mismatch~~ | `DownloadProgressManager.kt:168,174` | ✅ Fixed 2026-03-19 | Replaced `"Downloading"` with `DownloadStatus.DOWNLOADING` |
| ~~pauseDownload() DownloadProgressManager not updated~~ | `EducryptMedia.kt:pauseDownload` | ✅ Fixed 2026-03-20 | Added `updateProgress(vdcId, currentProgress.copy(status=PAUSED))` after `cancelWorkerForVdcId()` |
| ~~pauseDownload() hardcoded `"Paused"` string~~ | `EducryptMedia.kt:934` | ✅ Fixed 2026-03-20 | Replaced `"Paused"` with `DownloadStatus.PAUSED` |
| ~~getVideoStatusByVdcId() raw `"downloaded"` string~~ | `EducryptMedia.kt:1148` | ✅ Fixed 2026-03-20 | Replaced `status == "downloaded" \|\| status == DownloadStatus.DOWNLOADED` with just `status == DownloadStatus.DOWNLOADED` |
| ~~`drmSessionManager!!` / `mediaItem!!`~~ | `EducryptMedia.kt:206,207` | ✅ Fixed 2026-03-19 | Replaced with `checkNotNull()` + local vals; Category A (in-function assignment) |
| ~~`dataSourceFactory!!` (×2) + `drmCallback!!`~~ | `EducryptMedia.kt:185,192,204` | ✅ Fixed 2026-03-19 | `localDataSourceFactory` (single val, reused) + `localDrmCallback` via `checkNotNull()` |
| `!!` operators in rest of `EducryptMedia.kt` | `EducryptMedia.kt` (outside `initializeDrmPlayback`) | ⚠️ Medium | Not yet scanned — classify before fixing |
| ~~POST not retried~~ | `NetworkManager.kt:184-191` | ✅ Fixed 2026-03-19 | Added POST to `isRetriableMethod()`; both endpoints are idempotent |
| `downloadableName` ignored | `DownloadListener.kt`, `EducryptMedia.kt:500-508` | ⚠️ Medium | Interface accepts param that SDK drops — API inconsistency |
| Dead code: `observeAllDownloads()` | `EducryptMedia.kt:646` | Low | Private, never called |
| `liveEdgeJob == null` bug | `PlayerActivity.kt:334` | Low | Demo-only; comparison instead of assignment |
| Hardcoded AES keys | `AES.kt:7-8` | Low | Baked into AAR — security-by-obscurity only |
| SSL pinning disabled | `NetworkManager.kt:52-55` | Low | Commented out — should be enabled for production |

---

## AAR Release Checklist

Use this before sending a new AAR version to clients:

- [x] ~~Fix consumer-rules.pro~~ — ✅ Done 2026-03-19
- [x] ~~Fix download status case mismatch bug~~ — ✅ Done 2026-03-19
- [ ] **When adding any new public class: add `-keep` rule to consumer-rules.pro immediately** (don't wait until release)
- [ ] All public API changes are backward compatible (or properly deprecated)
- [ ] Realm schema version bumped (if any entity model changes) + migration provided
- [ ] consumer-rules.pro updated (if new public classes added)
- [ ] AAR tested in demo app: `./gradlew :app:assembleDebug`
- [x] Release AAR built: `./gradlew :EducryptMediaSdk:assembleRelease` — ✅ 2026-03-20
- [ ] Test DRM playback with release AAR (not just debug)
- [ ] Test download flow start-to-completion with release AAR
- [ ] Test offline playback with release AAR (AES decryption must work)
- [ ] Release notes written (what changed, any migration needed by client)

---

## Client Integration Requirements

Versions clients must use to avoid runtime crashes (version-coupled code generation / ABI):

| Dependency | Version | Notes |
|---|---|---|
| `io.realm.kotlin:library-base` | **3.0.0** | Must match exactly — Realm code generation is version-coupled |
| `androidx.media3:*` | **1.4.1** | All media3 artifacts must use same version |
| `androidx.work:work-runtime-ktx` | **2.9.0** | WorkManager for download workers |

Clients do NOT need to add Realm, Retrofit, or OkHttp separately — these are bundled in the SDK (Realm via `api()`, others via `implementation()`).

---

## Notes from Audit

- **SDK design is a clean Facade pattern** — `EducryptMedia` hides all internal complexity. Good for distribution.
- **Dual progress mechanism** (LiveData/Flow + LocalBroadcastManager) adds backward compatibility but clients should prefer `DownloadProgressManager` going forward.
- **`MediaLoaderBuilder` and `MediaDownloadBuilder` are inner classes** of `EducryptMedia` — clients must call `educryptMedia.MediaLoaderBuilder()`, not `EducryptMedia.MediaLoaderBuilder()`. This is slightly unusual but works fine.
- **Demo app `DownloadFragment` uses LocalBroadcastManager** for progress (legacy). A new demo could use `DownloadProgressManager.allDownloadsLiveData` instead — cleaner and no manual register/unregister.
- **The demo only stores one download in SharedPreference** — this is a demo limitation, not an SDK limitation. The SDK supports multiple concurrent downloads in Realm.
- **`isLive` is a public `var`** on `EducryptMedia` — clients could accidentally set it. Consider making it internal or read-only.

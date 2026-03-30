# EducryptMedia SDK — Android

EducryptMedia is an Android SDK (distributed as an AAR) that provides DRM-protected and non-DRM video playback (VOD and LIVE) plus background video downloading with AES encryption. It targets content platforms that use the VideoCrypt DRM infrastructure (Widevine + PallyCon licensing).

SDK Package:  `com.appsquadz.educryptmedia`
Demo Package: `com.drm.videocrypt`
Last Updated: 2026-03-26

---

## Project Architecture

This is a **dual-module SDK project**:

| Module | Role | Output |
|--------|------|--------|
| `EducryptMediaSdk/` | The SDK — the actual product | `.aar` file for clients |
| `app/` | Demo app — SDK consumer reference | `.apk` for testing only |

> ⚠️ GOLDEN RULE: `app/` exists only to test `EducryptMediaSdk/`.
> The SDK is the product. The demo is the test harness.
> Any change to the SDK must be validated via the app module.

---

## Stack

**SDK Module (`EducryptMediaSdk/`):**
- **Language**: Kotlin 2.0.21
- **Media**: Media3 / ExoPlayer 1.4.1 (DASH, HLS, Progressive)
- **DRM**: Widevine via `C.WIDEVINE_UUID` + PallyCon token auth (`pallycon-customdata-v2` header)
- **DRM License URL**: `https://license.videocrypt.com/validateLicense` (default; client-overridable)
- **Downloads**: Custom HTTP worker (WorkManager `CoroutineWorker`) — NOT ExoPlayer DownloadManager
- **Local DB**: Realm Kotlin 3.0.0 (`io.realm.kotlin:library-base`) — exposed as `api()` dep
- **Networking**: Retrofit 2.11.0 + OkHttp 4.12.0 (internal to SDK)
- **DI**: Manual (singleton objects — no Hilt/Koin)
- **Encryption**: AES-128 for downloaded files (AesDataSource + custom key derivation in AES.kt)
- **Background**: androidx.work 2.9.0
- **Min SDK**: 24 | Target SDK: 35 | Compile SDK: 35

**Demo App (`app/`):**
- Depends on SDK via: `implementation(project(":EducryptMediaSdk"))`
- Demo-only: `SharedPreference` wrapper (EncryptedSharedPreferences), `Const.kt` (test credentials)

---

## SDK Package Structure

```
com.appsquadz.educryptmedia/
├── playback/
│   ├── EducryptMedia                    ← MAIN SDK ENTRY POINT (public singleton)
│   └── PlayerSettingsBottomSheetDialog  ← Public UI — quality/speed picker
├── player/                              ← Internal ExoPlayer infrastructure (Phase 3 & 4)
│   ├── EducryptLoadControl              ← Custom LoadControl (15 s min / 50 s max buffer)
│   ├── StallRecoveryManager             ← Stall watchdog (8 s threshold, 2 s poll)
│   ├── EducryptAbrController            ← ABR controller (constraint-based, hybrid BBA-2 + dash.js DYNAMIC)
│   └── NetworkRecoveryManager           ← ConnectivityManager callback for auto recovery
├── logger/                              ← Event bus & player listener (Phase 1)
│   ├── EducryptEvent                    ← PUBLIC sealed class — all SDK events (25 subtypes)
│   ├── EducryptEventBus                 ← Internal SharedFlow bus (emit / collect)
│   └── EducryptPlayerListener           ← Internal Player.Listener → EventBus bridge
├── error/                               ← Error classification (Phase 2)
│   ├── EducryptError                    ← PUBLIC sealed class — typed error codes
│   ├── EducryptExoPlayerErrorMapper     ← Internal ExoPlayer → EducryptError mapper
│   └── EducryptLoadErrorPolicy          ← Internal retry policy (exp backoff 1/2/4 s, max 3)
├── core/                                ← SDK state + usage guards
│   ├── EducryptGuard                    ← Internal — validates SDK is ready before calls
│   └── EducryptSdkState                 ← Internal — SDK lifecycle state enum
├── lifecycle/                           ← ProcessLifecycle integration
│   └── EducryptLifecycleManager         ← Internal — app foreground/background callbacks
├── downloads/
│   ├── VideoDownloadWorker              ← Internal WorkManager CoroutineWorker
│   ├── DownloadProgressManager          ← PUBLIC — reactive LiveData/StateFlow progress
│   ├── DownloadProgress                 ← PUBLIC data class — per-download state
│   └── DownloadListener                 ← PUBLIC interface — download action callbacks
├── adapter/
│   ├── QualityAdapter                   ← Internal RecyclerView adapter (BottomSheet)
│   └── SpeedAdapter                     ← Internal RecyclerView adapter (BottomSheet)
├── interfaces/
│   ├── Apis                             ← Internal API endpoint constants (HARDCODED URLs)
│   └── ApiInterface                     ← Internal Retrofit service definition
├── models/
│   ├── VideoPlayback / Data / PlayUrls  ← Internal API response models
│   ├── Downloads / DownloadsData / DownloadableUrl  ← PUBLIC (via MediaDownloadBuilder)
│   ├── ResolutionModel                  ← PUBLIC (used with PlayerSettingsBottomSheetDialog)
│   └── SpeedModel                       ← PUBLIC (used with PlayerSettingsBottomSheetDialog)
├── module/
│   └── RealmManager                     ← Internal Realm singleton
├── realm/
│   ├── entity/DownloadMeta              ← Realm entity (schema v3) — returned to clients
│   ├── dao/DownloadMetaDao              ← Internal repository interface
│   └── impl/DownloadMetaImpl            ← Internal Realm implementation
├── utils/
│   ├── DownloadStatus                   ← PUBLIC status constants object
│   ├── AES                              ← Internal AES key derivation (hardcoded key material!)
│   ├── AesDataSource                    ← Internal custom Media3 DataSource
│   └── NetworkUtils.kt                  ← Internal extensions (hitApi, isDownloadExistForVdcId)
├── EncryptionData                       ← Internal API request body (name/id/flag fields)
└── NetworkManager                       ← Internal Retrofit/OkHttp singleton (hardcoded base URL)
```

---

## 🚨 PUBLIC API SURFACE (What Clients Call)

### 1. SDK Initialization
The SDK uses a thread-safe singleton. Must be called with a `Context` before any other call.
```kotlin
val educryptMedia = EducryptMedia.getInstance(context)
```
No explicit `init()` call needed. Realm is lazily initialized on first DB access.

**Client Application class must:**
```kotlin
// Create notification channel for download progress (required on API 26+)
override fun onCreate() {
    super.onCreate()
    createDownloadNotificationChannel(this) // helper in demo BaseApp.kt
    EducryptMedia.getInstance(this).cleanupStaleDownloads()
}
```

---

### 2. Playback — Fetch & Play via API (recommended flow)

Client passes credentials to `MediaLoaderBuilder`. The SDK calls the VideoCrypt API, determines if the content is DRM or non-DRM, and invokes the appropriate callback.

```kotlin
educryptMedia.MediaLoaderBuilder()
    .setVideoId(videoId)          // VideoCrypt video ID
    .setAccessKey(accessKey)
    .setSecretKey(secretKey)
    .setUserId(userId)
    .setDeviceType(deviceType)    // "1" = Android
    .setDeviceId(deviceId)        // unique device identifier
    .setVersion(version)          // API version string e.g. "2"
    .setDeviceName(deviceName)
    .setAccountId(accountId)
    .onDrm {
        // SDK-managed player is ready — bind to PlayerView and start
        val player = educryptMedia.getPlayer()!!
        playerView.player = player
        player.setMediaSource(educryptMedia.getMediaSource()!!)
        player.prepare()
        player.playWhenReady = true
    }
    .onNonDrm {
        // SDK-managed player is ready — bind to PlayerView and start
        val player = educryptMedia.getPlayer()!!
        playerView.player = player
        player.setMediaItem(educryptMedia.getMediaItem()!!)
        player.prepare()
        player.playWhenReady = true
    }
    .onError { errorMessage ->
        // Handle error on main thread
    }
    .load()
```

**Live stream detection:** After `onNonDrm` / `onDrm`, check `educryptMedia.isLive` to know if the content is a live stream. Live URLs are appended with `?start=...` or `?start=...&end=...` automatically by the SDK.

---

### 3. Playback — Direct (when client has URL/token already)

```kotlin
// DRM playback (client has videoUrl + PallyCon token)
educryptMedia.initializeDrmPlayback(videoUrl, token)
exoPlayer.setMediaSource(educryptMedia.getMediaSource()!!)

// Non-DRM playback (client has videoUrl)
educryptMedia.initializeNonDrmPlayback(videoUrl)
exoPlayer.setMediaItem(educryptMedia.getMediaItem()!!)
```

---

### 4. Playback — Offline (play a downloaded file)

```kotlin
// Prepare SDK player (required before offline playback — no MediaLoaderBuilder.load() here)
EducryptMedia.prepareForPlayback()

educryptMedia.initializeNonDrmDownloadPlayback(
    videoId = videoId,
    videoUrl = localFileUri,
    onReady = {
        // mediaSource is now set — safe to call getMediaSource()
        val player = educryptMedia.getPlayer()!!
        playerView.player = player
        player.setMediaSource(educryptMedia.getMediaSource()!!)
        player.prepare()
        player.playWhenReady = true
    },
    onError = { message ->
        // Download not found in Realm
    }
)
```

---

### 5. Player Settings UI (Quality / Speed)

```kotlin
@OptIn(UnstableApi::class)
val builder = PlayerSettingsBottomSheetDialog.Builder(context)
    .setPlayer(educryptMedia.getPlayer())      // SDK-managed player
    .setSpeedList(speedModelList)              // MutableList<SpeedModel>
educryptMedia.getTrackSelector()?.let { builder.setTrackSelector(it) }
val dialog = builder.build()

dialog.show(supportFragmentManager, "Player Settings")
```

`SpeedModel(text: String?, isSelected: Boolean, messageCode: String?)`
`ResolutionModel(text: String?, isSelected: Boolean, position: String?)`

---

### 6. Download — Fetch Available URLs

```kotlin
educryptMedia.MediaDownloadBuilder()
    .setVideoId(videoId)
    .setAccessKey(accessKey)
    .setSecretKey(secretKey)
    .onDownload { downloads: Downloads ->
        // downloads.data?.download_url → List<DownloadableUrl>
        // DownloadableUrl: url, title, size
        // Choose a quality and call startDownload()
    }
    .onError { errorMessage -> }
    .execute()
```

---

### 7. Download — Start / Pause / Resume / Cancel / Delete

```kotlin
// Configure before starting (optional)
educryptMedia.setNotificationVisibility(true)      // default true
educryptMedia.setDownloadableName("Lecture 01")    // notification title
educryptMedia.setMaxConcurrentDownloads(3)         // companion, default 3, max 10

// Start
educryptMedia.startDownload(
    vdcId = videoId,
    url = downloadUrl,
    fileName = fileNameWithoutExtension,  // e.g. "lecture_01" (stored as lecture_01.mp4)
    onError = { message -> },
    onSuccess = { }
)

// Pause (cancels worker, updates Realm status to "Paused")
educryptMedia.pauseDownload(vdcId)

// Resume (re-enqueues worker; HTTP Range resume if server supports it)
educryptMedia.resumeDownload(vdcId, url, fileName, onError, onSuccess)

// Cancel (stops worker + removes Realm record + deletes file)
educryptMedia.cancelDownload(vdcId)

// Delete (removes Realm record + deletes file; no worker cancellation)
educryptMedia.deleteDownload(vdcId)

// Delete all
educryptMedia.deleteAllDownloads()
```

---

### 8. Download Progress — Reactive Observation

```kotlin
// Option A: LiveData (per download)
DownloadProgressManager.getProgressLiveData(vdcId)
    .observe(lifecycleOwner) { progress: DownloadProgress -> }

// Option B: StateFlow (per download, Kotlin coroutines)
lifecycleScope.launch {
    DownloadProgressManager.getProgressFlow(vdcId).collect { progress: DownloadProgress? -> }
}

// Option C: All downloads aggregate (LiveData)
DownloadProgressManager.allDownloadsLiveData
    .observe(lifecycleOwner) { map: Map<String, DownloadProgress> -> }

// Option D: All downloads aggregate (StateFlow)
lifecycleScope.launch {
    DownloadProgressManager.allDownloadsFlow.collect { map: Map<String, DownloadProgress> -> }
}
```

`DownloadProgress` fields: `vdcId`, `progress (Int 0–100)`, `speedBps`, `etaSeconds`, `downloadedBytes`, `totalBytes`, `status`, `errorMessage?`
Helpers: `getFormattedSpeed()`, `getFormattedEta()`, `getFormattedProgressSize()`

---

### 9. Download — Query Methods

```kotlin
educryptMedia.getVideoStatusByVdcId(vdcId): String?    // "downloading","downloaded","Paused","failed"
educryptMedia.getVideoPercentageByVdcId(vdcId): Int?   // 0–100 or -1
educryptMedia.getVideoFileNameByVdcId(vdcId): String?  // stored filename (without .mp4)
educryptMedia.getVideoUrlByVdcId(vdcId): String?       // original download URL
educryptMedia.getVideoByVdcId(vdcId): DownloadMeta?    // full Realm entity
educryptMedia.getAllDownloadedVideo(): List<DownloadMeta?>?
educryptMedia.isDownloadValid(vdcId): Boolean          // checks Realm record AND file on disk
educryptMedia.cleanupStaleDownloads(onComplete: ((Int) -> Unit)? = null)
```

---

### 10. Download Listener Interface

Implement `DownloadListener` to wire download actions to UI:

```kotlin
interface DownloadListener {
    fun pauseDownload(vdcId: String)
    fun resumeDownload(vdcId: String, url: String, fileName: String, downloadableName: String,
                       onError: ((String) -> Unit)? = null, onSuccess: (() -> Unit)? = null)
    fun startDownload(vdcId: String, url: String, fileName: String, downloadableName: String,
                      onError: ((String) -> Unit)? = null, onSuccess: (() -> Unit)? = null)
    fun cancelDownload(vdcId: String)
    fun deleteDownload(vdcId: String)
}
```
`MainActivity` in the demo implements this interface.

---

### 11. Download Status Constants

```kotlin
DownloadStatus.DOWNLOADING  // "downloading"
DownloadStatus.DOWNLOADED   // "downloaded"
DownloadStatus.PAUSED       // "paused"
DownloadStatus.RESUMED      // "resumed"
DownloadStatus.CANCELLED    // "cancelled"
DownloadStatus.FAILED       // "failed"
DownloadStatus.DELETED      // "deleted"
```

---

### 12. DRM Configuration

```kotlin
// Override the default DRM license URL (call before initializeDrmPlayback)
educryptMedia.setDrmLicenceUrl("https://your-license-server/validateLicense")
```

DRM uses `pallycon-customdata-v2` token, sent as a request header to the license URL. Token is returned by the VideoCrypt API in `VideoPlayback.data.link.token`.

---

## DRM Architecture

- **Provider**: PallyCon (token-based) over Widevine (`C.WIDEVINE_UUID`)
- **License URL**: `https://license.videocrypt.com/validateLicense` (default, client-overridable via `setDrmLicenceUrl()`)
- **Auth**: PallyCon token passed via `pallycon-customdata-v2` HTTP header to license server
- **Offline DRM**: NOT supported — only online license validation
- **Non-DRM downloads**: Supported (files AES-encrypted on disk using per-video key from `AES.kt`)
- **Live stream**: Widevine/PallyCon DRM also applies to live streams; `isLive` flag set by SDK automatically

---

## ABR Architecture (Session D — OTT-Grade Hybrid BBA-2 + dash.js DYNAMIC)

The SDK manages its own internal `ExoPlayer` instance (created lazily on first `MediaLoaderBuilder.load()` call — **not** at SDK init time).
Clients may obtain it via `educryptMedia.getPlayer()` or continue using `getMediaSource()` / `getMediaItem()` with their own player (legacy pattern — both are supported).

- **Buffer**: `EducryptLoadControl` — 15 s min / 50 s max / 3 s start / 5 s rebuffer
- **Stall detection**: `StallRecoveryManager` — 8 s threshold, 2 s poll interval
- **ABR strategy** (Hybrid BBA-2 + dash.js DYNAMIC — two-phase):
  - **Phase 1 (startup, buffer < 10 s)**: Throughput-based. EWMA bandwidth × 0.7 safety factor → select highest `QualityTier` whose bitrate fits. Upshift requires `UPSHIFT_HOLD_MS=3s` guard.
  - **Phase 2 (steady state, buffer ≥ 10 s)**: Buffer-zone-based with bandwidth ceiling.
    - CRITICAL (< 2 s) → drop 2 tiers immediately (no switch-interval guard)
    - LOW (< 4 s) → drop 1 tier immediately
    - STABLE (4 s – 15 s) → hold
    - HEALTHY (15 s – 25 s) → upshift one tier if bandwidth supports + 3 s upshift guard
    - EXCESS (≥ 25 s) → upshift freely (upshift guard waived; bandwidth still the ceiling)
  - **Live streams**: all buffer thresholds halved (`factor = 0.5`)
- **Bandwidth EWMA**: α = 0.3; `smoothedBandwidth` seeded on first probe; safety factor 0.7 applied before tier comparisons; emitted as `BandwidthEstimated` on every probe
- **`QualityTier` data class**: `(index, height, bitrate)` — bitrate from `Format.bitrate` when set; else `height² × 2.5` bps fallback when `Format.NO_VALUE`
- **On stall**: drop 2 tiers + reset EWMA to 50 % + clear upshift timer
- **Safe mode**: 3 stalls in 60 s → lock to tier 0; named `safeModeExitRunnable` (fixes prior anonymous-lambda bug); exit after 5 min stable
- **Safe mode exit**: step up 1 tier + enter cautious re-entry (probe interval 8 s for 60 s), then return to normal 5 s probe interval
- **Switch guard**: `MIN_SWITCH_INTERVAL_MS=2s` — minimum time between any two quality switches (drops bypass this)
- **Quality forcing**: `TrackSelectionOverride` — forces the exact track, bypasses ExoPlayer's internal ABR. Fallback to `setMaxVideoSize` cap when track not yet resolved. Auto mode restored via `clearOverridesOfType(C.TRACK_TYPE_VIDEO)`.
- **Event flow**: `StallDetected` → `EducryptAbrController.onStallDetected` → `QualityChanged` (emitted from `applyQuality()` only — `fromHeight`/`toHeight`/`reason` always populated)
- **Track selector**: explicit `DefaultTrackSelector` passed to `ExoPlayer.Builder` and to `EducryptAbrController`
- **Bandwidth meter**: explicit `DefaultBandwidthMeter` passed to both builder and controller
- **Error policy**: `EducryptLoadErrorPolicy` attached to `DefaultMediaSourceFactory` in `initPlayer()` — covers all media paths (DRM DASH, progressive, non-DRM)

---

## Download Architecture

- **Library**: Custom `VideoDownloadWorker extends CoroutineWorker` (NOT ExoPlayer DownloadManager)
- **File storage**: `context.getExternalFilesDir(null)/<filename>.mp4`
- **File encryption**: AES-128 via `AES.generateLibkeyAPI()` / `generateLibVectorAPI()` — key derived from `vdcId.split("_")[2]`
- **Metadata storage**: Realm Kotlin 3.0.0, schema version 3, entities `DownloadMeta` + `ChunkMeta`
- **Resume support (single-connection)**: HTTP Range requests (`Range: bytes=N-`); falls back to full restart if server returns 200 instead of 206
- **Parallel download (4 chunks)**: `probeFile()` HEAD request checks `Accept-Ranges: bytes` and total size. If supported: file pre-allocated, 4 `ChunkMeta` records created (or restored), 4 async coroutines on `Dispatchers.IO` write non-overlapping byte ranges via `RandomAccessFile.seek()`. Progress aggregated via `AtomicLong`. Falls back to single-connection if HEAD fails or Range not supported.
- **Chunk resume**: If `ChunkMeta` records exist (size == 4) and file exists → `resumeFrom = startByte + downloadedBytes` per chunk; skips completed chunks.
- **Chunk cleanup**: `ChunkMeta` records deleted on success, failure, and pause paths via `awaitChunkDelete()`.
- **Chunk progress writes**: Every 512 KB per chunk (`CHUNK_PROGRESS_WRITE_INTERVAL`) to limit Realm transaction rate.
- **Max concurrent**: 3 (default); configurable via `EducryptMedia.setMaxConcurrentDownloads(max: Int)`
- **States**: `downloading`, `downloaded`, `paused`, `failed`, `resumed`, `cancelled`, `deleted`
- **Progress broadcast**: Dual mechanism — `DownloadProgressManager` (LiveData/StateFlow) + `LocalBroadcastManager` (legacy, kept for backward compatibility)

---

## Quick File Finder

**SDK Core:**
- Main entry point: `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/playback/EducryptMedia.kt`
- Player settings UI: `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/playback/PlayerSettingsBottomSheetDialog.kt`
- Network: `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/NetworkManager.kt`
- Encryption: `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/utils/AES.kt`

**SDK Player (internal — player/):**
- Buffer config: `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/player/EducryptLoadControl.kt`
- Stall watchdog: `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/player/StallRecoveryManager.kt`
- ABR controller: `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/player/EducryptAbrController.kt`
- Network recovery: `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/player/NetworkRecoveryManager.kt`
- Snapshot builder: `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/player/MetaSnapshotBuilder.kt` (INTERNAL — builds PlayerMetaSnapshot + NetworkMetaSnapshot)

**SDK Event Bus (internal — logger/):**
- All event types: `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/logger/EducryptEvent.kt` (PUBLIC sealed class — 25 subtypes)
- Event bus: `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/logger/EducryptEventBus.kt`
- Player listener: `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/logger/EducryptPlayerListener.kt`

**SDK Error Classification (internal — error/):**
- Error types: `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/error/EducryptError.kt` (PUBLIC)
- ExoPlayer mapper: `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/error/EducryptExoPlayerErrorMapper.kt`
- Load error policy: `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/error/EducryptLoadErrorPolicy.kt`

**SDK Guards & State (internal — core/):**
- Usage guard: `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/core/EducryptGuard.kt`
- SDK state: `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/core/EducryptSdkState.kt`

**SDK Lifecycle (internal — lifecycle/):**
- Lifecycle manager: `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/lifecycle/EducryptLifecycleManager.kt`

**SDK Public Contracts:**
- `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/downloads/DownloadListener.kt`
- `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/downloads/DownloadProgressManager.kt` (contains `DownloadProgress` data class)
- `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/utils/DownloadStatus.kt`

**SDK Public Models:**
- `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/models/VideoPlayback.kt` (VideoPlayback, Data, PlayUrls, Downloads, DownloadsData, DownloadableUrl)
- `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/models/ResolutionModel.kt`
- `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/models/SpeedModel.kt`
- `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/realm/entity/DownloadMeta.kt`

**SDK Downloads:**
- `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/downloads/VideoDownloadWorker.kt`
- `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/downloads/DownloadProgressManager.kt`
- `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/downloads/DownloadListener.kt`

**SDK Realm:**
- `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/realm/entity/DownloadMeta.kt`
- `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/realm/entity/ChunkMeta.kt` (schema v3 — parallel chunk state)
- `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/realm/dao/DownloadMetaDao.kt`
- `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/realm/dao/ChunkMetaDao.kt`
- `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/realm/impl/DownloadMetaImpl.kt`
- `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/realm/impl/ChunkMetaImpl.kt`
- `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/module/RealmManager.kt`

**SDK Internal API:**
- `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/interfaces/Apis.kt` (hardcoded endpoint URLs)
- `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/interfaces/ApiInterface.kt`

**Demo App:**
| File | SDK Feature Demonstrated |
|------|--------------------------|
| `app/.../BaseApp.kt` | SDK init + notification channel + stale cleanup + full event logging |
| `app/.../MainActivity.kt` | Implements `DownloadListener` — wires all download actions |
| `app/.../PlayerActivity.kt` | `MediaLoaderBuilder` + DRM/non-DRM/offline playback + `PlayerSettingsBottomSheetDialog` + event collect |
| `app/.../HomeFragment.kt` | `MediaDownloadBuilder` — fetch download URLs |
| `app/.../DownloadFragment.kt` | `LocalBroadcastManager` download progress + play offline |
| `app/.../Const.kt` | Test credentials (ACCESS_KEY, SECRET_KEY, USER_ID, ACCOUNT_ID) |
| `app/.../Extension.kt` | Shared view/UI extension functions |
| `app/.../adapter/DownloadsAdapter.kt` | RecyclerView adapter for download list |
| `app/.../adapter/ListItemAdapter.kt` | RecyclerView adapter for video list |
| `app/.../fragments/ListBottomSheetDialogFragment.kt` | Bottom sheet for video selection |
| `app/.../utils/SharedPreference.kt` | Demo-only EncryptedSharedPreferences wrapper (single download state) |
| `app/.../utils/FragmentStateAdapterDemo.kt` | ViewPager2 adapter for demo tabs |

---

## AAR Build & Distribution

```bash
# Build release AAR
./gradlew :EducryptMediaSdk:assembleRelease

# Build debug AAR
./gradlew :EducryptMediaSdk:assembleDebug

# Test SDK via demo app
./gradlew :app:assembleDebug

# Clean
./gradlew clean
```

**Output:** `EducryptMediaSdk/build/outputs/aar/EducryptMediaSdk-release.aar`

**ProGuard / consumer-rules.pro:**
- `EducryptMediaSdk/consumer-rules.pro` — bundled INTO the AAR for consumers
- Realm entities kept: ✅ (`com.appsquadz.educryptmedia.realm.entity.**`)
- Models kept: ✅ (`com.appsquadz.educryptmedia.models.**`)
- Media3 kept: ✅
- Retrofit/OkHttp kept: ✅
- **`EducryptMedia` + inner classes kept: ✅** (fixed 2026-03-19)
- **`PlayerSettingsBottomSheetDialog` + inner classes kept: ✅** (fixed 2026-03-19)
- **`DownloadProgressManager` / `DownloadProgress` kept: ✅** (fixed 2026-03-19)
- **`DownloadListener` kept: ✅** (fixed 2026-03-19)
- **`DownloadStatus` kept: ✅** (fixed 2026-03-19)
- **`VideoDownloadWorker` kept: ✅** (fixed 2026-03-19)

---

## Gotchas & Known Constraints

### consumer-rules.pro — new public class rule (ongoing requirement)
Every time a new public class or interface is added to the SDK, its keep rule must be added to `consumer-rules.pro` in the same commit. Use the `$*` wildcard to cover inner classes.
- ❌ WRONG: Add a new public class and forget consumer-rules.pro — clients get `ClassNotFoundException`
- ✅ RIGHT: Add class, then immediately add `-keep class com.appsquadz.educryptmedia.X { *; }` and `-keep class com.appsquadz.educryptmedia.X$* { *; }`

### POST endpoints must remain idempotent for retry safety
The retry interceptor in `NetworkManager` retries ALL HTTP methods including POST (up to 3 attempts with exponential backoff). This is safe because all current POST endpoints are read-only lookups.
- ❌ WRONG: Add a new POST endpoint that creates/modifies server state — it will be retried up to 3× on failure, potentially duplicating server-side actions
- ✅ RIGHT: If a non-idempotent POST is ever needed, add a URL-pattern exclusion in `isRetriableMethod()` before shipping. Comment next to it: why this endpoint is excluded.

### DownloadStatus — always use constants, never raw strings
`DownloadStatus.DOWNLOADING = "downloading"` (lowercase). Any raw string like `"Downloading"` (capital D) will silently fail status comparisons.
- ❌ WRONG: `status == "Downloading"` or `status == "downloaded"`
- ✅ RIGHT: `status == DownloadStatus.DOWNLOADING`, `status == DownloadStatus.DOWNLOADED`

### ⚠️ SDK Public API changes = breaking changes for clients
Any change to public methods/interfaces/models affects external codebases you don't control.
- ❌ WRONG: Remove or rename a public function without deprecation
- ✅ RIGHT: Deprecate first (`@Deprecated`), keep old working, add new function

### ⚠️ Realm schema version 3 — next field change requires schemaVersion(4)
Current schema: `DownloadMeta` (7 fields: vdcId, fileName, url, percentage, status, totalBytes, downloadedBytes) + `ChunkMeta` (7 fields: id, vdcId, chunkIndex, startByte, endByte, downloadedBytes, completed). Both entities registered in `RealmManager`.
- ❌ WRONG: Add/remove Realm fields without bumping `schemaVersion`
- ✅ RIGHT: Bump `schemaVersion` in `RealmManager` and provide an `AutomaticSchemaMigration` block

### ⚠️ ChunkMeta records must be cleaned up on all terminal paths
`deleteChunksForVdcId(vdcId)` must be called in four places:
1. `downloadParallel()` — on successful completion, failure ✅ (done in Session C)
2. `deleteDownload()` — permanent removal ✅ (done in micro-fix)
3. `cancelDownload()` — permanent removal ✅ (done in micro-fix)
4. `cleanupStaleDownloads()` — stale sweep + orphan sweep on app start ✅ (done in micro-fix)

**Pause does NOT delete chunks** — they are preserved for resume by `downloadParallel()`.

If chunk records are left behind (e.g., crash), the next `downloadParallel()` call sees `existingChunks.size == NUM_CHUNKS` and tries to resume — but if the file was also deleted, `file.exists()` will be false and a fresh start is initiated (stale chunks deleted).
- The `file.exists()` guard in `downloadParallel()` prevents corrupt resume: `if (existingChunks.size == NUM_CHUNKS && file.exists())` — both conditions must be true for resume.

### ⚠️ DRM is online-only — no offline license caching
Downloaded video files are AES-encrypted (non-DRM encryption), not Widevine DRM. If a client expects true offline DRM (Widevine offline license), it's not supported.

### ⚠️ Hardcoded DRM license URL
Default `https://license.videocrypt.com/validateLicense` is hardcoded. Client must call `setDrmLicenceUrl()` to override for non-VideoCrypt deployments. This is an SDK-specific backend coupling.

### ⚠️ AES key material hardcoded in AES.kt
`strArrayKeyLib` and `strArrayvectorLib` are static strings used to derive per-file AES keys. These are baked into the AAR — any client who decompiles gets the key derivation algorithm. The actual per-file key also depends on `vdcId.split("_")[2]`.

### !! operator fix pattern — use checkNotNull() with a message
When removing `!!` from SDK code, classify first:
- **Category A** (value assigned in-function just above use — a programming contract): replace with `val local = checkNotNull(field) { "field is null in functionName — internal SDK error" }` then use `local`. This gives a readable `IllegalStateException` instead of a cryptic NPE, and avoids lambdas closing over mutable vars.
- **Category B** (value may legitimately be null at runtime): replace with null-safe early return + invoke existing error callback.
- ❌ WRONG: `someField!!` anywhere in SDK — crashes client app with no recoverable state
- ✅ RIGHT: `val local = checkNotNull(someField) { "descriptive message" }` (Category A) or null-safe return + error callback (Category B)

`initializeDrmPlayback()` is now fully `!!`-free (0 unsafe operators as of 2026-03-19).

Remaining `!!` elsewhere: `EducryptMedia.kt` (rest of file — not yet scanned); `PlayerActivity.kt` (demo, lower risk, not blocking).

### ⚠️ Network recovery is automatic — no client action required
After a `NETWORK_UNAVAILABLE` / `NETWORK_TIMEOUT` / `SOURCE_UNAVAILABLE` fatal error, the SDK registers a `ConnectivityManager.NetworkCallback` and waits for a validated internet connection. When the network returns:
1. `EducryptEvent.NetworkRestored` is emitted (collect to show "reconnecting…" UI if desired)
2. SDK calls `player.prepare()` + `playWhenReady = true` automatically
- Recovery uses the same `mediaSource` / `mediaItem` already set — no re-authentication needed
- Watch is cancelled automatically on: new playback start, `stop()`, `shutdown()`
- `ACCESS_NETWORK_STATE` permission is bundled in the SDK AAR manifest — no client manifest change needed
- DRM, decoder, and auth errors do NOT trigger recovery — they would re-error immediately

### ⚠️ `NetworkRecoveryManager` uses `onCapabilitiesChanged()`, NOT `onAvailable()`
`onAvailable()` fires the moment a network interface connects — before Android has probed and validated internet access. `NET_CAPABILITY_VALIDATED` is added asynchronously and only appears in subsequent `onCapabilitiesChanged()` calls.
- ❌ WRONG: Check `NET_CAPABILITY_VALIDATED` inside `onAvailable()` — it is never set at that point; `isUsable` is always false; recovery never triggers
- ✅ RIGHT: Override `onCapabilitiesChanged()` and check both `NET_CAPABILITY_INTERNET` and `NET_CAPABILITY_VALIDATED` there

### ⚠️ Capture callback in local var before calling `stopWatching()`
`stopWatching()` sets `onNetworkRestored = null`. If the callback is invoked via the field after `stopWatching()` returns, it is always a no-op.
- ❌ WRONG: `stopWatching(); onNetworkRestored?.invoke()` — field is null by the time invoke() runs
- ✅ RIGHT: `val pending = onNetworkRestored; stopWatching(); pending?.invoke()` — local var holds the reference independent of the field

### ⚠️ Player lifecycle — ExoPlayer is fully SDK-managed
ExoPlayer is created lazily inside the SDK. Clients obtain it via `getPlayer()` and bind it to `PlayerView`. Never create ExoPlayer directly in the client — all Phase 3/4 infrastructure (ABR, stall recovery, tuned buffer) is wired to the SDK player only. Using a client-created ExoPlayer bypasses all reliability improvements.

- `EducryptMedia.init(context)` → SDK ready, **no ExoPlayer created**
- `MediaLoaderBuilder.load()` → `releasePlayer()` + `initPlayer()` called on main thread, `getPlayer()` returns the player from here
- `EducryptMedia.prepareForPlayback()` → for offline-only playback (no `load()` call), use this to create the SDK player before calling `initializeNonDrmDownloadPlayback()`
- `educryptMedia.stop()` → releases SDK player; call from `onDestroy()` of the player screen
- `EducryptMedia.shutdown()` → full SDK teardown (also releases player); call only when your app is done with the SDK entirely
- `getPlayer()` returns null until `load()` or `prepareForPlayback()` has been called — this is correct
- **Never call `player.release()` directly** — use `stop()` to keep SDK state consistent
- `getTrackSelector()` — returns the SDK-managed `DefaultTrackSelector` for use with `PlayerSettingsBottomSheetDialog`

### ⚠️ ABR uses constraint-based selection — NEVER TrackSelectionOverride in applyQuality()
`applyQuality()` in `EducryptAbrController` uses `setMaxVideoSize` + `setMinVideoSize` with `clearOverridesOfType(C.TRACK_TYPE_VIDEO)`. This keeps `AdaptiveTrackSelection` active so transitions happen at segment boundaries without flushing the buffer — no loading spinner on quality switches.
- **Downshift**: `setMaxVideoSize(∞, targetHeight)` + `setMinVideoSize(0, 0)` — ceiling only; already-buffered segments at the old quality keep playing.
- **Upshift**: `setMaxVideoSize(∞, targetHeight)` + `setMinVideoSize(0, targetHeight)` — floor + ceiling pins the tier without a hard flush.
- `restoreAutoSelection()` calls `clearOverridesOfType` + unconstrained `setMaxVideoSize`/`setMinVideoSize` — full ExoPlayer ABR control restored.
- `TrackSelectionOverride` is ONLY valid for user-manual quality selection via `PlayerSettingsBottomSheetDialog`. Never use it in `applyQuality()`.
- ❌ WRONG: `setOverrideForType(TrackSelectionOverride(...))` in ABR code — replaces `AdaptiveTrackSelection`, flushes buffer, triggers loading spinner on every switch.
- ✅ RIGHT: `clearOverridesOfType` + `setMaxVideoSize` + `setMinVideoSize` — constraint-based, smooth, invisible to user.

### ⚠️ Progress bar / spinner must NEVER react to STATE_BUFFERING immediately
ExoPlayer emits a transient `STATE_READY → STATE_BUFFERING → STATE_READY` cycle when `trackSelector.parameters` changes, even with 20+ seconds of buffered content. Reacting immediately causes a visible flash on every ABR quality switch.
- ❌ WRONG: `progressBar.isVisible = true` directly in `STATE_BUFFERING` handler — spinner flashes on every ABR transition
- ✅ RIGHT: Launch a 500ms coroutine before showing; cancel it on `STATE_READY`. If BUFFERING resolves within 500ms (ABR switch transient), spinner is never shown. If it persists (real network buffering), spinner appears after barely perceptible delay.
- `bufferingJob?.cancel()` must also be called on `STATE_READY`, `STATE_ENDED`, and `onDestroy()`.

### ⚠️ SDK stall events are for analytics — NOT for direct spinner control
`EducryptEvent.StallDetected` / `StallRecovered` should drive logging and analytics only. The loading spinner must have a **single owner**: the debounced `STATE_BUFFERING` handler. Having two systems toggle the same view creates race conditions.
- ❌ WRONG: `is StallDetected -> progressBar.isVisible = true` in events collector
- ✅ RIGHT: `is StallDetected -> Log.d(...)` — analytics only; spinner controlled by debounced player state

### ⚠️ `PlayerMetaSnapshot` track info is 0 at LOADING / READY / DRM_READY triggers
At these early triggers, ExoPlayer has not yet selected tracks — `currentResolutionHeight`, `currentResolutionWidth`, `currentBitrateBps`, and `mimeType` will all be 0 / empty. This is expected and correct. Track info is always populated at ERROR / STALL_RECOVERY / NETWORK_RECOVERY triggers because the player has been running.
- ❌ WRONG: Assume track info is populated immediately after `load()` fires LOADING
- ✅ RIGHT: Use track info from ERROR / STALL_RECOVERY / NETWORK_RECOVERY snapshots for diagnostics

### ⚠️ `videoUrl` in `PlayerMetaSnapshot` is empty at ERROR / STALL_RECOVERY / NETWORK_RECOVERY
The URL is available at LOADING / DRM_READY / READY (passed as parameter to init functions). At later triggers it is not re-passed to keep the callback API simple — use `videoId` to correlate with the session instead.

### ⚠️ `currentVideoId` and `isDrmPlayback` are reset in `releasePlayer()`, not `setValuesToDefault()`
`setValuesToDefault()` is called at the start of `initializeDrmPlayback()` and `initializeNonDrmPlayback()`. Resetting session metadata there would clear `currentVideoId` before the snapshot inside those functions fires. These fields are instead reset in `releasePlayer()` and set in `load()` after `releasePlayer()` completes.

### ⚠️ QualityChanged is emitted from `applyQuality()` only — never from `EducryptPlayerListener`
`EducryptPlayerListener.onTracksChanged` previously emitted `QualityChanged` from the selected track. During quality transitions ExoPlayer briefly reports `format.height = 0`, producing `0p→0p` events. Removed. `QualityChanged` is now emitted exclusively inside `EducryptAbrController.applyQuality()` where `fromHeight` and `toHeight` are both unambiguous.

### ⚠️ `tracksInitialized` only becomes true when heights are non-empty
`onTracksChanged` fires during intermediate states before tracks are resolved. `onTracksAvailable()` retries (does not set `tracksInitialized = true`) until `getAvailableVideoHeights()` returns non-empty data.

### ⚠️ Buffer tuning values — do not change without device testing
`EducryptLoadControl` (player/ package) replaces ExoPlayer defaults:
- `minBufferMs = 15_000` (ExoPlayer default: 50 000)
- `maxBufferMs = 50_000` (unchanged)
- `bufferForPlaybackMs = 3_000` (ExoPlayer default: 2 500)
- `bufferForRebufferMs = 5_000` (unchanged)
Constraints: `bufferForPlaybackMs < minBufferMs < maxBufferMs` must always hold or ExoPlayer throws at runtime.
Do NOT change these values without testing on mid-range/low-end devices (1–2 GB RAM, HSPA / 3G).

### ⚠️ Stall thresholds + ABR parameters — tuned starting points, not production-final values
`StallRecoveryManager` (player/ package) declares a stall after 8 s continuous buffering.
Safe mode is triggered after 3 stalls within 60 s.
`EducryptAbrController` bandwidth thresholds: 500 K / 1 M / 2.5 M / 5 M bps → quality tiers.
Safe mode exit: 5 min of stable playback at the lowest quality tier.
All values are starting points — adjust based on real `StallDetected`/`BandwidthEstimated` event data from production.

### ⚠️ Dual error emission in onPlayerError() — DO NOT merge or remove PlaybackError
`EducryptPlayerListener.onPlayerError()` emits **two events** for every player error:
- `EducryptEvent.PlaybackError` — original Phase 1 event, kept for **backward compatibility**
- `EducryptEvent.ErrorOccurred` — new classified event with `EducryptError.code` for structured handling

**DO NOT remove `PlaybackError`** — it is a public API that existing clients may be collecting and pattern-matching on.
**DO NOT merge these into one event** — they serve different client generations.
This dual-emission is intentional and permanent until a future major version deprecation cycle.

### ~~POST calls are not retried~~ — ✅ Fixed 2026-03-19
POST was added to `isRetriableMethod()` in Session 3. All current POST endpoints are read-only lookups — retrying is safe. See the "POST endpoints must remain idempotent" gotcha above for ongoing constraints on future endpoints.

### ⚠️ DrmReady ≠ license acquired — do not use it to confirm DRM playback is active
`DrmReady` fires when DRM infrastructure objects (`DefaultDrmSessionManager`, `DashMediaSource`) are assembled — before any network call to the license server. The actual Widevine license HTTP POST fires when `player.prepare()` is called. Use `DrmLicenseAcquired` to confirm the license was obtained and decryption is ready.
- ❌ WRONG: Treat `DrmReady` as confirmation that the license server responded successfully
- ✅ RIGHT: Collect `DrmLicenseAcquired` to confirm the license HTTP POST succeeded and ExoPlayer has loaded the DRM keys

### ⚠️ ErrorOccurred.isRetrying is structurally always false — use RetryAttempted instead
`ErrorOccurred` is emitted by `EducryptPlayerListener.onPlayerError()`, which is only invoked after `EducryptLoadErrorPolicy` has exhausted all retries. The `isRetrying` field is hardcoded to `false` at that call site. It is a misleading field — not a bug in the SDK, but a design gap. To observe retry state, collect `RetryAttempted` events which fire before each delay.
- ❌ WRONG: `if (event.isRetrying) { /* SDK is mid-retry */ }` — this block never executes
- ✅ RIGHT: Collect `RetryAttempted` (has `attemptNumber`, `reason`, `delayMs`, `failedUrl`, `dataType`) to track retry progress; `ErrorOccurred` fires only on final failure

### ⚠️ DRM network recovery requires full player reinit — bare prepare() is unsafe
After a network loss long enough to expire the Widevine session, calling `prepare()` on the existing player causes `exoCode=6004` (DRM_SYSTEM_ERROR) — ExoPlayer reuses the corrupted DRM session. `attemptPlaybackRecovery()` detects `isDrmPlayback` and runs a full cycle instead.
- ❌ WRONG: Call `prepare()` on existing player after network recovery for DRM content — triggers `DRM_LICENSE_FAILED, exoCode=6004` if session expired
- ✅ RIGHT: `attemptPlaybackRecovery()` already handles this: `releasePlayer()→initPlayer()→initializeDrmPlayback(currentVideoUrl, currentDrmToken)→setMediaSource→prepare`. Non-DRM path (prepare() only) is unchanged.
- Guard: if `currentDrmToken` is empty on recovery, falls back to `prepare()` with a warning log — monitor for `exoCode=6004` recurrence on very short outages where this branch is taken.

### ⚠️ `downloadableName` parameter ignored in EducryptMedia.resumeDownload()
`DownloadListener.resumeDownload()` accepts `downloadableName` but `EducryptMedia.resumeDownload()` does not have this parameter. The parameter is silently dropped. API inconsistency.

### ~~`observeAllDownloads()` dead code~~ — ✅ Deleted 2026-03-20 (Session A)
`EducryptMedia.observeAllDownloads()` was deleted entirely — used `observeForever` with no lifecycle owner (leak risk) and duplicated `DownloadProgressManager`. No replacement needed; `DownloadProgressManager.allDownloadsLiveData` is the correct alternative.

### ~~`liveEdgeJob == null` bug in PlayerActivity.onDestroy()~~ — ✅ Fixed 2026-03-20 (Session 13)
Changed from `liveEdgeJob == null` (comparison) to `liveEdgeJob = null` (assignment). Demo-only.

### ⚠️ Realm is a transitive `api()` dependency
`io.realm.kotlin:library-base` is declared as `api()` in the SDK, meaning it's exposed to AAR consumers. Clients will see Realm in their dependency graph whether they want it or not.

### ⚠️ AesDataSource uses RandomAccessFile directly — do NOT wrap in CipherInputStream
`AesDataSource` performs block-aligned `cipher.update()` calls on raw bytes read from a `RandomAccessFile`. This is required for O(1) CBC seek: the cipher must be re-initialised with a mid-file IV derived from the preceding ciphertext block, which is impossible with a `CipherInputStream` wrapping a forward-only stream.
- ❌ WRONG: Wrap the file in `CipherInputStream` or `BufferedInputStream` — breaks seeking and CBC block alignment
- ✅ RIGHT: Read raw blocks from `RandomAccessFile`, call `cipher.update(rawBlock, 0, bytesRead)` directly
- The `forceSkip` extension on `CipherInputStream` has been deleted — do not re-add it
- Seek algorithm: `blockIndex = P/16`; IV = raw ciphertext bytes at `(blockIndex-1)*16`; `raf.seek(blockIndex*16)` then `cipher.init()`

### ⚠️ Realm schema is v3 — any new field requires all three of: entity change + schemaVersion bump + migration block
Adding a field to `DownloadMeta` or `ChunkMeta` (or any Realm entity) requires all three to land in the same build:
1. The new field on the entity class with a non-null default value
2. `schemaVersion` bumped by 1 in `RealmManager`
3. A migration block (`AutomaticSchemaMigration`) handling `oldRealm.schemaVersion() < N`

Missing any one of these causes `RealmMigrationNeededException` on first launch for all existing users.
- ❌ WRONG: Add a field, bump version, forget migration — or add field without bumping version
- ✅ RIGHT: All three in one commit. Test with existing data (Test B) before shipping.

Current schema: v3. Next change will use `schemaVersion(4)`.

### ⚠️ Download buffer: `BufferedInputStream` and write `ByteArray` must always be the same size
`getBufferSize()` in `VideoDownloadWorker` returns 128 KB on WiFi (`NET_CAPABILITY_NOT_METERED`) and 32 KB on cellular/metered. Both `BufferedInputStream` and the write `ByteArray(bufferSize)` must use the same variable — never set one without the other.
- ❌ WRONG: `BufferedInputStream(stream, BUFFER_SIZE_WIFI)` with `ByteArray(BUFFER_SIZE_CELLULAR)`
- ✅ RIGHT: `val bufferSize = getBufferSize(); BufferedInputStream(stream, bufferSize); ByteArray(bufferSize)`

### ⚠️ `pendingDownloadQueue` is internal — never expose publicly
`EducryptMedia.pendingDownloadQueue` holds queued downloads when the concurrent limit is reached. It drains automatically via `drainQueue()` when a slot opens (on DOWNLOADED/FAILED/CANCELLED). Clients call `startDownload()` as always — queuing is transparent. Never expose the queue publicly.

### ⚠️ All SDK logging must use `EducryptLogger` — raw Log.* and println() are banned
`EducryptLogger` (`internal object` in `com.appsquadz.educryptmedia.util`) wraps every log call behind `if (BuildConfig.DEBUG)`. Raw `Log.*`, `println()`, and `System.out` must NOT appear anywhere in `EducryptMediaSdk/src/`.
- ❌ WRONG: `Log.d(MEDIA_TAG, message)` or `println(...)` anywhere in SDK source
- ✅ RIGHT: `EducryptLogger.d(message)` / `.i()` / `.w()` / `.e(message, throwable?)` / `.v()`
- `EducryptLogger` is `internal` — do NOT add it to `consumer-rules.pro`
- To audit: `grep -rn "Log\.\|println(" EducryptMediaSdk/src/main/java/` → must return 0 results

### ⚠️ `stop()` is the single clear point for session-lifetime state
Fields set once per Activity session that must survive internal player reinit — such as `onPlayerRecreated` callback and `last*` credential fields — must be cleared in `stop()` only. `releasePlayer()` is called internally during DRM recovery reinit and fires before these fields are needed. Clearing them there silently breaks any subsequent recovery cycle.
- ❌ WRONG: Clear `onPlayerRecreated` or `last*` fields in `releasePlayer()` finally block
- ✅ RIGHT: Clear them in `stop()` before calling `releasePlayer()` — they survive all internal reinit cycles and are only wiped when the client explicitly destroys the player in `onDestroy()`

### ⚠️ DRM recovery always re-fetches a fresh token from the API
`attemptPlaybackRecovery()` calls the VideoCrypt API on every recovery rather than reusing `currentDrmToken`. PallyCon tokens are single-use and time-limited — the cached token is always expired or consumed by the time network recovery fires. Re-fetch incurs ~350ms API round-trip but is the only reliable approach.
- ❌ WRONG: Reuse `currentDrmToken` (or any cached token) for recovery — it will be rejected by the license server
- ✅ RIGHT: Use the stored `last*` credential fields to call `getContentPlayBack()` and obtain a fresh token before rebuilding the player

### ⚠️ PlayerView must be rebound after DRM recovery
DRM recovery calls `releasePlayer()` + `initPlayer()` internally, creating a new `ExoPlayer` instance. The old `playerView.player` reference is stale. Clients must register `setOnPlayerRecreatedListener` and rebind `playerView.player` + re-add their `Player.Listener` inside the callback. Without this, the view renders nothing after recovery.
- ❌ WRONG: Assume the existing `playerView.player` binding survives DRM recovery
- ✅ RIGHT: `educryptMedia.setOnPlayerRecreatedListener { val p = educryptMedia.getPlayer() ?: return; playerView.player = p; p.addListener(listener) }` — register once in `onCreate()`, fires automatically after each DRM reinit

---

## Out of Scope

**DO NOT touch without explicit instruction:**
- `AES.kt` — hardcoded key material, backend-coupled
- `EncryptionData.kt` — API request body, backend-coupled
- `realm/entity/DownloadMeta.kt` — schema changes break client upgrades (current schema v3)
- `interfaces/Apis.kt` — hardcoded API/DRM URLs, backend-coupled
- `consumer-rules.pro` — ProGuard for AAR consumers (touch ONLY when adding new public class)
- `EducryptMediaSdk/build.gradle.kts` — SDK build config

---

## Public API Change Protocol

Before modifying ANY public-facing file:
1. Ask: "Is this a breaking change?" (removes/renames existing public method or model field)
2. If YES → deprecate the old method (`@Deprecated`), add new one, keep both working
3. If NO (additive change) → proceed, but if new public class: add keep rule to `consumer-rules.pro`
4. After any public API change → update TASKS.md "Decisions Made"

---

## Rules

- NEVER change a public SDK method signature without deprecating the old one first
- NEVER modify Realm entity fields without bumping `schemaVersion` in `RealmManager` + providing migration
- NEVER add a new public class without adding its keep rule to `consumer-rules.pro`
- ALWAYS test SDK changes via the app module before building AAR
- ALWAYS build and verify AAR after any SDK change: `./gradlew :EducryptMediaSdk:assembleRelease`
- NEVER add sensitive values (keys, URLs) in SDK source — use client-provided params
- ASK before touching any file in the Out of Scope list
- DO NOT pass Realm objects across threads — use `copyFromRealm()` or query fresh in each context

---

## SDK Reference

> Auto-merged — do not edit this section manually. Source: SDK_REFERENCE.md (merged 2026-03-20)

### On every session start — run this first

```bash
ls EducryptMediaSdk/ 2>/dev/null && echo "CONTEXT: MODULE" || echo "CONTEXT: AAR"
```

If `EducryptMediaSdk/` directory exists → you are on **main** (SDK as module).
If it does not exist → you are on **client** (SDK as AAR).

Apply the matching rules below before doing anything else.

---

### CONTEXT: MODULE (main branch)

SDK source is fully editable. Both modules are in scope.

**You may touch:**
- `EducryptMediaSdk/` — all source files
- `app/` — demo app

**Rules:**
- Always build both after any SDK change: `./gradlew :EducryptMediaSdk:assembleRelease :app:assembleDebug`
- Any new public class must have a `-keep` rule in `EducryptMediaSdk/consumer-rules.pro`
- Any new `EducryptEvent` subtype must be handled in `app/` BaseApp events collector
- Never use raw strings for DownloadStatus — always use constants
- Realm schema changes require a version bump + migration in `RealmManager`
- Never change a public method signature without deprecating the old one first
- Test every SDK change via the demo app before building AAR
- When shipping to client branch: build AAR → copy to `app/libs/` on client → manually sync any `app/` file changes → run `./gradlew :app:assembleDebug` on client to verify

---

### CONTEXT: AAR (client branch)

SDK is a pre-built AAR. Source is not available and cannot be changed.

**You may touch:**
- `app/` only

**You may NOT touch:**
- The AAR file
- Any SDK internals — they do not exist here

**Rules:**
- If you find a bug that originates in the SDK, note it in SCRATCHPAD.md and stop
- Never change the dependency versions listed below — they must match the AAR exactly
- The AAR already bundles consumer Proguard rules — do not add duplicate keep rules
- If a DRM recovery issue is reported: check whether the API re-fetch credentials (last* fields) are being cleared too early — this is a known SDK-side pattern, note in SCRATCHPAD.md and flag for main branch fix

---

### SDK Public API (quick reference)

#### Singleton access

```kotlin
EducryptMedia.init(context)                          // Call in Application.onCreate()
val sdk = EducryptMedia.getInstance(context)         // Returns the singleton
EducryptMedia.shutdown()                             // Full teardown — call only if done with SDK
```

#### Playback — via API (recommended)

```kotlin
sdk.MediaLoaderBuilder()
    .setVideoId(videoId)
    .setAccessKey(accessKey).setSecretKey(secretKey)
    .setUserId(userId).setDeviceType("1").setDeviceId(deviceId)
    .setVersion("2").setDeviceName(deviceName).setAccountId(accountId)
    .onDrm { /* player ready */ }
    .onNonDrm { /* player ready */ }
    .onError { message -> }
    .load()
```

#### Playback — direct (client already has URL/token)

```kotlin
sdk.initializeDrmPlayback(videoUrl: String, token: String)
sdk.initializeNonDrmPlayback(videoUrl: String)
sdk.setDrmLicenceUrl(url: String)   // Override default DRM URL before initializeDrmPlayback
```

#### Playback — offline (downloaded file)

```kotlin
EducryptMedia.prepareForPlayback()  // Required before initializeNonDrmDownloadPlayback when no load()
sdk.initializeNonDrmDownloadPlayback(
    videoId: String,
    videoUrl: String,               // local file:// URI
    onReady: (() -> Unit)? = null,
    onError: ((String) -> Unit)? = null
)
```

#### Player access

```kotlin
sdk.getPlayer(): ExoPlayer?               // null until load() or prepareForPlayback()
sdk.getMediaSource(): MediaSource?
sdk.getMediaItem(): MediaItem?
sdk.getTrackSelector(): DefaultTrackSelector?
sdk.stop()                                // Release player — call from onDestroy()
sdk.isLive: Boolean                       // true for live streams; check after onDrm/onNonDrm fires
```

#### Downloads — fetch available URLs

```kotlin
sdk.MediaDownloadBuilder()
    .setVideoId(videoId).setAccessKey(key).setSecretKey(secret)
    .onDownload { downloads: Downloads -> }
    .onError { message -> }
    .execute()
```

#### Downloads — lifecycle

```kotlin
sdk.setNotificationVisibility(true)
sdk.setDownloadableName("Lecture 01")
sdk.setMaxConcurrentDownloads(3)           // Companion method; default 3, range 1–10

sdk.startDownload(vdcId, url, fileName, onError, onSuccess)
sdk.pauseDownload(vdcId)
sdk.resumeDownload(vdcId, url, fileName, onError, onSuccess)
sdk.cancelDownload(vdcId)
sdk.deleteDownload(vdcId)
sdk.deleteAllDownloads()
```

#### Downloads — query

```kotlin
sdk.getVideoStatusByVdcId(vdcId): String?
sdk.getVideoPercentageByVdcId(vdcId): Int?
sdk.getVideoFileNameByVdcId(vdcId): String?
sdk.getVideoUrlByVdcId(vdcId): String?
sdk.getVideoByVdcId(vdcId): DownloadMeta?
sdk.getAllDownloadedVideo(): List<DownloadMeta?>?
sdk.isDownloadValid(vdcId): Boolean        // Checks Realm AND file on disk
sdk.cleanupStaleDownloads(onComplete: ((Int) -> Unit)? = null)
```

#### Events

```kotlin
// Process-lifetime (Application)
EducryptMedia.events: SharedFlow<EducryptEvent>

// Screen-lifetime (Activity/Fragment)
lifecycleScope.launch { EducryptMedia.events.collect { event -> when(event) { ... } } }

// Recent events buffer (last N, survives screen lifecycle)
EducryptMedia.recentEvents(count: Int = 50): List<EducryptEvent>
```

---

### EducryptEvent — All subtypes (25 total)

#### Playback lifecycle

| Event | Fields | When |
|---|---|---|
| `PlaybackStarted` | `videoUrl: String`, `isDrm: Boolean` | Playback initialised (DRM and non-DRM) |
| `DrmReady` | `videoUrl: String`, `licenseUrl: String = ""` | DRM infrastructure assembled (before license HTTP request) |
| `DrmLicenseAcquired` | `videoId: String`, `licenseUrl: String` | Widevine license HTTP POST succeeded — keys loaded, decryption ready |
| `PlaybackBuffering` | `isBuffering: Boolean` | ExoPlayer `isLoading` changes |

#### Stalls

| Event | Fields | When |
|---|---|---|
| `StallDetected` | `positionMs: Long`, `stallCount: Int` | Buffering > 8 s threshold |
| `StallRecovered` | `positionMs: Long`, `stallDurationMs: Long` | Buffering ends |

#### ABR / Quality

| Event | Fields | When |
|---|---|---|
| `QualityChanged` | `qualityLabel: String`, `fromHeight: Int`, `toHeight: Int`, `reason: String` | Track switch |
| `BandwidthEstimated` | `bandwidthBps: Long` | Bandwidth probe tick |
| `SafeModeEntered` | `reason: String` | 3 stalls in 60 s |
| `SafeModeExited` | `stablePlaybackMs: Long` | 5 min stable at lowest quality |

#### Errors & retries

| Event | Fields | When |
|---|---|---|
| `PlaybackError` | `message: String`, `cause: Throwable?` | Any player error (backward compat) |
| `ErrorOccurred` | `code: String`, `message: String`, `isFatal: Boolean`, `isRetrying: Boolean`, `exoPlayerErrorCode: Int = -1`, `httpStatusCode: Int = -1`, `cause: Throwable? = null` | Classified error |
| `NetworkRestored` | _(object, no fields)_ | Network reconnected after fatal error |
| `RetryAttempted` | `attemptNumber: Int`, `reason: String`, `delayMs: Long`, `failedUrl: String = ""`, `dataType: String = ""` | Before each retry (max 3). `dataType`: MEDIA / MANIFEST / DRM_LICENSE / AD / TIME_SYNC / UNKNOWN |

#### Downloads

| Event | Fields | When |
|---|---|---|
| `DownloadStarted` | `vdcId: String` | Worker enqueued |
| `DownloadProgressChanged` | `vdcId: String`, `progress: Int`, `status: String` | 25 / 50 / 75% milestones |
| `DownloadCompleted` | `vdcId: String` | Status → DOWNLOADED |
| `DownloadFailed` | `vdcId: String`, `message: String` | Status → FAILED |
| `DownloadPaused` | `vdcId: String` | pauseDownload() called |
| `DownloadCancelled` | `vdcId: String` | cancelDownload() called |
| `DownloadDeleted` | `vdcId: String` | deleteDownload() called |

#### Snapshots

| Event | Key fields | When |
|---|---|---|
| `PlayerMetaSnapshot` | `videoId`, `videoUrl`, `isDrm`, `isLive`, `currentResolutionHeight`, `currentResolutionWidth`, `currentBitrateBps`, `mimeType`, `playbackTrigger` | See triggers below |
| `NetworkMetaSnapshot` | `transportType`, `operatorName`, `networkGeneration`, `isMetered`, `isRoaming`, `downstreamBandwidthKbps`, `upstreamBandwidthKbps`, `estimatedBandwidthBps`, `signalStrength` | Paired with PlayerMetaSnapshot |

**PlayerMetaSnapshot fires at:** `LOADING` · `DRM_READY` · `READY` · `ERROR` · `STALL_RECOVERY` · `NETWORK_RECOVERY`

Track info (`currentResolutionHeight`, `currentBitrateBps`, `mimeType`) is **0 / empty** at `LOADING`, `DRM_READY`, `READY` — ExoPlayer has not yet selected tracks. Always populated at `ERROR`, `STALL_RECOVERY`, `NETWORK_RECOVERY`.

`videoUrl` is **empty** at `ERROR`, `STALL_RECOVERY`, `NETWORK_RECOVERY` — use `videoId` to correlate.

#### SDK / Custom

| Event | Fields | When |
|---|---|---|
| `Custom` | `name: String`, `params: Map<String, String>` | Client-emitted via `logEvent()` |
| `SdkError` | `code: String`, `message: String` | Incorrect SDK usage (not a playback error) |

**SdkError codes:** `SDK_NOT_INITIALISED` · `SDK_SHUT_DOWN` · `INVALID_INPUT` · `WRONG_THREAD` · `API_ERROR` · `NETWORK_UNAVAILABLE` · `NETWORK_ERROR`

---

### EducryptError — Error codes (13 total)

| Code | Category | Fatal | Cause |
|---|---|---|---|
| `SOURCE_UNAVAILABLE` | Network | Yes | 4xx/5xx or unreachable URL |
| `NETWORK_TIMEOUT` | Network | Yes | Read/connection timeout during playback |
| `NETWORK_UNAVAILABLE` | Network | Yes | No active network — triggers auto-recovery |
| `DRM_LICENSE_FAILED` | DRM | Yes | License server rejected request |
| `DRM_LICENSE_EXPIRED` | DRM | Yes | License expired, renewal failed |
| `DRM_NOT_SUPPORTED` | DRM | Yes | Widevine L1/L3 not available on device |
| `AUTH_EXPIRED` | Auth | Yes | PallyCon/VideoCrypt token expired |
| `AUTH_INVALID` | Auth | Yes | Credentials malformed or not recognised |
| `UNSUPPORTED_FORMAT` | Format | Yes | Container/codec not supported on device |
| `DECODER_ERROR` | Format | Yes | Hardware or software decoder failure |
| `DOWNLOAD_FAILED` | Download | No | I/O error, server error, or worker cancellation |
| `STORAGE_INSUFFICIENT` | Download | No | Not enough disk space |
| `UNKNOWN` | Fallback | Yes | No matching category |

**Auto-recovery applies only to:** `NETWORK_UNAVAILABLE`, `NETWORK_TIMEOUT`, `SOURCE_UNAVAILABLE`. DRM, decoder, and auth errors do not trigger recovery — they would re-error immediately.

---

### DownloadStatus constants (7 total)

| Constant | String value | Notes |
|---|---|---|
| `DownloadStatus.DOWNLOADING` | `"downloading"` | Worker actively writing bytes |
| `DownloadStatus.DOWNLOADED` | `"downloaded"` | Complete, file on disk |
| `DownloadStatus.PAUSED` | `"paused"` | Worker cancelled, Realm updated |
| `DownloadStatus.RESUMED` | `"resumed"` | Resume enqueued (transitions to DOWNLOADING) |
| `DownloadStatus.CANCELLED` | `"cancelled"` | File and Realm record removed |
| `DownloadStatus.FAILED` | `"failed"` | Download failed; Realm record kept |
| `DownloadStatus.DELETED` | `"deleted"` | deleteDownload() called |

**Never use raw strings.** All comparisons and Realm writes must use the constants above.
Grep to audit: `grep -rn '"Paused"\|"Downloading"\|"downloaded"\|"Cancelled"\|"Failed"' EducryptMediaSdk/src/`

---

### Required dependency versions (must match AAR exactly)

| Dependency | Version | Notes |
|---|---|---|
| `io.realm.kotlin:library-base` | **3.0.0** | Version-coupled code generation — must match exactly |
| `androidx.media3:*` | **1.4.1** | All media3 artifacts must use the same version |
| `androidx.work:work-runtime-ktx` | **2.9.0** | WorkManager for download workers |

Clients do NOT need Retrofit or OkHttp — bundled inside the AAR as `implementation()`.
Realm is exposed as `api()` — it will appear in the client's dependency graph.

---

### consumer-rules.pro — covered classes

| Class / package | Rule type |
|---|---|
| `com.appsquadz.educryptmedia.playback.EducryptMedia` | explicit + `$*` inner classes |
| `com.appsquadz.educryptmedia.playback.PlayerSettingsBottomSheetDialog` | explicit + `$*` |
| `com.appsquadz.educryptmedia.logger.EducryptEvent` | explicit + `$*` (all 24 subtypes) |
| `com.appsquadz.educryptmedia.error.EducryptError` | explicit + `$*` (all 13 subtypes) |
| `com.appsquadz.educryptmedia.downloads.DownloadProgressManager` | explicit |
| `com.appsquadz.educryptmedia.downloads.DownloadProgress` | explicit |
| `com.appsquadz.educryptmedia.downloads.DownloadListener` | explicit |
| `com.appsquadz.educryptmedia.utils.DownloadStatus` | explicit |
| `com.appsquadz.educryptmedia.downloads.VideoDownloadWorker` | explicit |
| `com.appsquadz.educryptmedia.models.**` | wildcard (all model classes) |
| `com.appsquadz.educryptmedia.realm.entity.**` | wildcard (DownloadMeta + ChunkMeta — added Session C) |
| `io.realm.**` | wildcard |
| `androidx.media3.**` | wildcard |
| `retrofit2.**`, `okhttp3.**`, `com.google.gson.**` | wildcard |

---

### SDK behaviour — what it manages automatically

- ExoPlayer lifecycle (created on `load()` or `prepareForPlayback()`, released on `stop()`)
- DRM licence acquisition, retry, and refresh (PallyCon token via `pallycon-customdata-v2` header)
- Buffer configuration: 15 s min / 50 s max / 3 s start / 5 s rebuffer (`EducryptLoadControl`)
- Stall watchdog: 8 s threshold, 2 s poll, 3 stalls in 60 s → safe mode (`StallRecoveryManager`)
- ABR: conservative start (mid tier), bandwidth probe every 5 s, stall → drop, safe mode → lowest quality for 5 min (`EducryptAbrController`)
- Weak signal throttling: when signal=WEAK, retry count increases to 5 (vs 3), upshift buffer threshold raises to 12s (vs 8s), starting quality capped to 360p, media segment read timeout increases to 20s (vs 10s)
- Network recovery: `ConnectivityManager.NetworkCallback` on `NET_CAPABILITY_VALIDATED` (not `onAvailable`) → auto `prepare()` with position restore
- Download scheduling via WorkManager `CoroutineWorker` with HTTP Range resume support
- All coroutine scopes anchored to `ProcessLifecycleOwner` (never leak Activity/Fragment)

---

### SDK Reference — Gotchas (quick-ref subset)

**DownloadStatus — always use constants.** `DownloadStatus.PAUSED = "paused"` (lowercase). `pauseDownload()` previously wrote `"Paused"` (capital P) — fixed in Session 22. Any new status write must use the constant, never a raw string.

**PlaybackError + ErrorOccurred — both fire on every player error.** `PlaybackError` is kept for backward compatibility. DO NOT remove it. DO NOT merge them.

**getPlayer() — never call player.release() directly.** Calling `player.release()` desynchronises SDK state. Always use `sdk.stop()`.

**NetworkRecoveryManager uses onCapabilitiesChanged, NOT onAvailable.** `onAvailable()` fires before `NET_CAPABILITY_VALIDATED` is set. Do not move recovery check to `onAvailable()`.

**Callback must be captured before stopWatching().** `val pending = onNetworkRestored; stopWatching(); pending?.invoke()`.

**DownloadProgressManager must be updated on pause.** `pauseDownload()` calls `DownloadProgressManager.updateProgress(vdcId, currentProgress.copy(status = DownloadStatus.PAUSED))` after `cancelWorkerForVdcId()`. Without this, `isDownloadActive()` returns true for paused downloads and blocks resume. Fixed in Session 22.

**Realm schema version 1 — first migration is pending.** Any `DownloadMeta` field change requires schemaVersion bump + migration in `RealmManager`.

**consumerProguardFiles must be set in build.gradle.kts.** `consumerProguardFiles("consumer-rules.pro")` is in `defaultConfig` — bundles rules into AAR automatically.

**AES key material is hardcoded in AES.kt.** Changing these values breaks playback of all existing downloaded files.

**SSL pinning is disabled.** `createCertificatePinner()` is implemented but commented out in `NetworkManager`. Enable for production builds.

**DRM is online-only.** Downloaded files use AES-128 (SDK-managed), not Widevine offline licences.

**isLive is a public var.** Set by SDK after API response — do not set manually.

**currentSignalStrength — only update on non-UNKNOWN transport.**
❌ WRONG: Update `currentSignalStrength` on every `NetworkMetaSnapshot` including UNKNOWN transport.
✅ RIGHT: Only update `currentSignalStrength` when `transportType != "UNKNOWN"`. Network drop snapshots report UNKNOWN — overwriting the last good signal disables all weak signal throttling during drops. Preserve the last real reading.

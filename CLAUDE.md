# EducryptMedia SDK — Android

EducryptMedia is an Android SDK (distributed as an AAR) that provides DRM-protected and non-DRM video playback (VOD and LIVE) plus background video downloading with AES encryption. It targets content platforms that use the VideoCrypt DRM infrastructure (Widevine + PallyCon licensing).

SDK Package:  `com.appsquadz.educryptmedia`
Demo Package: `com.drm.videocrypt`
Last Updated: 2026-03-20 (Session 20)

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
│   ├── EducryptAbrController            ← ABR controller (TrackSelectionOverride-based)
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
│   ├── entity/DownloadMeta              ← Realm entity (schema v1) — returned to clients
│   ├── dao/DownloadMetaDao              ← Internal repository interface
│   └── impl/DownloadMetaImpl            ← Internal Realm implementation
├── utils/
│   ├── DownloadStatus                   ← PUBLIC status constants object
│   ├── AES                              ← Internal AES key derivation (hardcoded key material!)
│   ├── AesDataSource                    ← Internal custom Media3 DataSource
│   ├── NetworkUtils.kt                  ← Internal extensions (hitApi, getCipher, isDownloadExistForVdcId)
│   └── forceSkip.kt                     ← Internal CipherInputStream extension
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

## ABR Architecture (Phase 4)

The SDK manages its own internal `ExoPlayer` instance (created lazily on first `MediaLoaderBuilder.load()` call — **not** at SDK init time).
Clients may obtain it via `educryptMedia.getPlayer()` or continue using `getMediaSource()` / `getMediaItem()` with their own player (legacy pattern — both are supported).

- **Buffer**: `EducryptLoadControl` — 15 s min / 50 s max / 3 s start / 5 s rebuffer
- **Stall detection**: `StallRecoveryManager` — 8 s threshold, 2 s poll interval
- **ABR strategy** (mirrors Netflix / Prime Video):
  1. Start at mid quality tier (conservative)
  2. Bandwidth probe every **5 s** of stable playback → step up one tier if bandwidth allows
  3. On stall → drop one tier immediately
  4. After 3 stalls in 60 s → lock to lowest quality (safe mode)
  5. After 5 min stable in safe mode → step up one tier + resume bandwidth probe
- **Quality forcing**: `TrackSelectionOverride` — forces the exact track, bypasses ExoPlayer's internal ABR. Fallback to `setMaxVideoSize` cap when track not yet resolved. Auto mode restored via `clearOverridesOfType(C.TRACK_TYPE_VIDEO)`.
- **Bandwidth thresholds**: < 500 Kbps → safe, < 1 Mbps → low, < 2.5 Mbps → mid, < 5 Mbps → high, ≥ 5 Mbps → best
- **Event flow**: `StallDetected` → `EducryptAbrController.onStallDetected` → `QualityChanged` (emitted from `applyQuality()` only — `fromHeight`/`toHeight`/`reason` always populated)
- **Track selector**: explicit `DefaultTrackSelector` passed to `ExoPlayer.Builder` and to `EducryptAbrController`
- **Bandwidth meter**: explicit `DefaultBandwidthMeter` passed to both builder and controller
- **Error policy**: `EducryptLoadErrorPolicy` attached to `DefaultMediaSourceFactory` in `initPlayer()` — covers all media paths (DRM DASH, progressive, non-DRM)

---

## Download Architecture

- **Library**: Custom `VideoDownloadWorker extends CoroutineWorker` (NOT ExoPlayer DownloadManager)
- **File storage**: `context.getExternalFilesDir(null)/<filename>.mp4`
- **File encryption**: AES-128 via `AES.generateLibkeyAPI()` / `generateLibVectorAPI()` — key derived from `vdcId.split("_")[2]`
- **Metadata storage**: Realm Kotlin 3.0.0, schema version 1, entity `DownloadMeta`
- **Resume support**: HTTP Range requests (`Range: bytes=N-`); falls back to full restart if server returns 200 instead of 206
- **Max concurrent**: 3 (default); configurable via `EducryptMedia.setMaxConcurrentDownloads(max: Int)`
- **States**: `downloading`, `downloaded`, `Paused` (note: capital P — see Gotchas), `failed`, `paused`, `resumed`, `cancelled`, `deleted`
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
- `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/realm/dao/DownloadMetaDao.kt`
- `EducryptMediaSdk/src/main/java/com/appsquadz/educryptmedia/realm/impl/DownloadMetaImpl.kt`
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

### ⚠️ Realm schema version 1 — first migration is coming
Current schema: `DownloadMeta` with `vdcId`, `fileName`, `url`, `percentage`, `status`.
- ❌ WRONG: Add/remove Realm fields without bumping `schemaVersion`
- ✅ RIGHT: Bump `schemaVersion` in `RealmManager` and provide a `RealmMigration`

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

### ⚠️ ABR uses TrackSelectionOverride — NOT setMaxVideoSize
`applyQuality()` in `EducryptAbrController` uses `TrackSelectionOverride(group.mediaTrackGroup, trackIndex)` to force a specific video track. `setMaxVideoSize` only sets a ceiling — ExoPlayer's internal ABR continues running within it and may stay below the cap. Only `TrackSelectionOverride` actually forces the quality tier.
- `restoreAutoSelection()` calls `clearOverridesOfType(C.TRACK_TYPE_VIDEO)` to return full control to ExoPlayer's ABR.
- Fallback to `setMaxVideoSize` only when no exact height match exists in current tracks.

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

### ⚠️ POST calls are not retried by NetworkManager
`retryInterceptor` only retries GET/PUT/DELETE. The VideoCrypt API exclusively uses POST. Network blips on API calls are NOT retried despite the retry interceptor being present.

### ⚠️ `downloadableName` parameter ignored in EducryptMedia.resumeDownload()
`DownloadListener.resumeDownload()` accepts `downloadableName` but `EducryptMedia.resumeDownload()` does not have this parameter. The parameter is silently dropped. API inconsistency.

### ⚠️ `observeAllDownloads()` is private and dead code
`EducryptMedia.observeAllDownloads()` is defined but never called. Should either be exposed publicly or deleted.

### ⚠️ `liveEdgeJob == null` bug in PlayerActivity.onDestroy()
Line 334: `liveEdgeJob == null` (comparison, not assignment). Should be `liveEdgeJob = null`. Demo-only bug but worth noting.

### ⚠️ Realm is a transitive `api()` dependency
`io.realm.kotlin:library-base` is declared as `api()` in the SDK, meaning it's exposed to AAR consumers. Clients will see Realm in their dependency graph whether they want it or not.

---

## Out of Scope

**DO NOT touch without explicit instruction:**
- `AES.kt` — hardcoded key material, backend-coupled
- `EncryptionData.kt` — API request body, backend-coupled
- `realm/entity/DownloadMeta.kt` — schema changes break client upgrades (schema v1)
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

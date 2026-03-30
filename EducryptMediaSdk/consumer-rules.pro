# Realm Kotlin ProGuard Rules
-keep class io.realm.kotlin.** { *; }
-keep class io.realm.** { *; }
-dontwarn io.realm.**

# Keep Realm model classes
-keep class com.appsquadz.educryptmedia.realm.entity.** { *; }
# ChunkMeta — parallel download chunk state (schema v3); also covered by wildcard above.
-keep class com.appsquadz.educryptmedia.realm.entity.ChunkMeta { *; }
-keep class com.appsquadz.educryptmedia.realm.entity.ChunkMeta$* { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Retrofit
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions

# Gson
-keep class com.google.gson.** { *; }
-keep class com.appsquadz.educryptmedia.models.** { *; }

# Media3/ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# EducryptMedia SDK — Public API classes
# These classes are called directly by AAR consumers and must not be obfuscated.
-keep class com.appsquadz.educryptmedia.playback.EducryptMedia { *; }
-keep class com.appsquadz.educryptmedia.playback.EducryptMedia$* { *; }
-keep class com.appsquadz.educryptmedia.playback.PlayerSettingsBottomSheetDialog { *; }
-keep class com.appsquadz.educryptmedia.playback.PlayerSettingsBottomSheetDialog$* { *; }
-keep class com.appsquadz.educryptmedia.downloads.DownloadProgressManager { *; }
-keep class com.appsquadz.educryptmedia.downloads.DownloadProgress { *; }
-keep interface com.appsquadz.educryptmedia.downloads.DownloadListener { *; }
-keep class com.appsquadz.educryptmedia.utils.DownloadStatus { *; }

# VideoDownloadWorker — constants are referenced by clients via LocalBroadcastManager
-keep class com.appsquadz.educryptmedia.downloads.VideoDownloadWorker { *; }

# EducryptError — typed error classification (public sealed class; clients pattern-match on code strings)
-keep class com.appsquadz.educryptmedia.error.EducryptError { *; }
-keep class com.appsquadz.educryptmedia.error.EducryptError$* { *; }

# EducryptLogger — event sealed class (all subtypes including SdkError must survive minification)
-keep class com.appsquadz.educryptmedia.logger.EducryptEvent { *; }
-keep class com.appsquadz.educryptmedia.logger.EducryptEvent$* { *; }

# SessionStatus — public enum referenced by SessionStatusChanged and getSessionStatus()
-keep class com.appsquadz.educryptmedia.logger.SessionStatus { *; }
-keep class com.appsquadz.educryptmedia.logger.SessionStatus$* { *; }

# EducryptMedia public event stream + lifecycle methods
-keepclassmembers class com.appsquadz.educryptmedia.playback.EducryptMedia {
    public static kotlinx.coroutines.flow.SharedFlow getEvents();
    public static kotlinx.coroutines.flow.SharedFlow events;
    public static kotlinx.coroutines.flow.SharedFlow getIndexedEvents();
    public static kotlinx.coroutines.flow.SharedFlow indexedEvents;
    public static java.util.List recentEvents(int);
    public static java.util.List recentIndexedEvents(int);
    public static void logEvent(java.lang.String, java.util.Map);
    public static void init(android.content.Context);
    public static void shutdown();
}

# SharedFlow and IndexedValue — needed for the events stream
-keep class kotlinx.coroutines.flow.SharedFlow { *; }
-keepclassmembers class kotlin.collections.IndexedValue { *; }

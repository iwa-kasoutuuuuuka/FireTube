# ProGuard rules for FireTube (Fire OS TV Client)

# NewPipe Extractor
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**

# AndroidX Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Glide
-keep public class * extends com.github.bumptech.glide.module.AppGlideModule
-keep class com.firetube.tv.util.FireTubeGlideModule { *; }
-keepclassmembers class * {
    @com.github.bumptech.glide.annotation.GlideOption public *;
    @com.github.bumptech.glide.annotation.GlideType public *;
}

# Room Database
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.**

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class com.firetube.tv.data.model.** { *; }

# OkHttp & Coroutines
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

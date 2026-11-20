# ---- DhakaFlix TV R8 rules ----
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, SourceFile, LineNumberTable

# LibVLC / FFmpeg use JNI callbacks by name
-keep class org.videolan.** { *; }
-dontwarn org.videolan.**
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keep class androidx.media3.decoder.** { *; }

# Jsoup
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# kotlinx.serialization for our models
-keepclassmembers class com.israk.dhakaflix.** {
    *** Companion;
}
-keepclasseswithmembers class com.israk.dhakaflix.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.israk.dhakaflix.**$$serializer { *; }
-keep @kotlinx.serialization.Serializable class com.israk.dhakaflix.** { *; }

# Room entities/DAOs are referenced from generated code
-keep class com.israk.dhakaflix.data.db.** { *; }

-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.re2j.**

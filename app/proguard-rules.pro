# kotlinx-serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.music115.tv.**$$serializer { *; }
-keepclassmembers class com.music115.tv.** {
    *** Companion;
}
-keepclasseswithmembers class com.music115.tv.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Media3
-keep class androidx.media3.** { *; }

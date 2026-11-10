# Retrofit + kotlinx.serialization
-keepattributes Signature
-keepattributes *Annotation*
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.smartinvest.**$$serializer { *; }
-keepclassmembers class com.smartinvest.** {
    *** Companion;
}
-keepclasseswithmembers class com.smartinvest.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }

# Hilt / WorkManager
-keep class androidx.work.impl.WorkManagerImpl { *; }
-dontwarn org.jsoup.**

# PDFBox Android: optional JPEG2000 decoder that is not bundled.
-dontwarn com.gemalto.jp2.**

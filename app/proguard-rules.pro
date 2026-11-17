# Keep kotlinx.serialization generated serializers for the API/auth models.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.gothwad.mail.** {
    *** Companion;
}
-keepclasseswithmembers class com.gothwad.mail.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# OkHttp platform hints.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# kotlinx.serialization — keep generated serializers for the config models
-keepclassmembers class com.astrion.remote.config.** {
    *** Companion;
}
-keepclasseswithmembers class com.astrion.remote.config.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.astrion.remote.**$$serializer { *; }

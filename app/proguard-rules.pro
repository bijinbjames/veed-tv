# Optimise, but keep class and method names so logcat stack traces stay readable.
-dontobfuscate

# lineup.json is decoded with kotlinx.serialization.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class home.veed.tv.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class home.veed.tv.** {
    kotlinx.serialization.KSerializer serializer(...);
}

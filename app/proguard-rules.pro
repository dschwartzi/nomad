# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keep,includedescriptorclasses class ai.nomad.**$$serializer { *; }
-keepclassmembers class ai.nomad.** {
    *** Companion;
}
-keepclasseswithmembers class ai.nomad.** {
    kotlinx.serialization.KSerializer serializer(...);
}

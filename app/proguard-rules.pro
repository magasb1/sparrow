# srtdroid calls back into Kotlin classes from JNI.
-keep class io.github.thibaultbee.srtdroid.** { *; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class app.sparrow.core.config.** { *** Companion; }
-keepclasseswithmembers class app.sparrow.core.config.** { kotlinx.serialization.KSerializer serializer(...); }

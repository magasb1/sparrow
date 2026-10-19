# srtdroid calls back into Kotlin classes from JNI.
-keep class io.github.thibaultbee.srtdroid.** { *; }
# protobuf-lite messages are read and written through reflection on their fields.
-keep class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class app.sparrow.core.config.** { *** Companion; }
-keepclasseswithmembers class app.sparrow.core.config.** { kotlinx.serialization.KSerializer serializer(...); }

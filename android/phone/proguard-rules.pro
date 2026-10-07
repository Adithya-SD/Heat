# kotlinx.serialization: generated serializers are found reflectively.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class app.heat.** { *** Companion; }
-keepclasseswithmembers class app.heat.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class app.heat.**$$serializer { *; }

# The Wearable Data Layer service is bound by Play Services by name.
-keep class app.heat.platform.HeatDataLayerService { *; }

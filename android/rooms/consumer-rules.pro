# libwebrtc reaches its Java side from native code by name, through its own binding layer (org.jni_zero), and the
# binding looks classes and methods up reflectively at library load. Shrinking or renaming any of it crashes the
# process the first time a room opens.
-keep class org.webrtc.** { *; }
-keep class org.jni_zero.** { *; }
-keep class org.chromium.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-dontwarn org.webrtc.**
-dontwarn org.jni_zero.**
-dontwarn org.chromium.**

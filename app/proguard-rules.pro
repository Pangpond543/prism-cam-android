# Prism Cam — R8 rules. Compose / AndroidX / CameraX ship their own consumer rules.

# MediaPipe tasks read their protobuf messages by field name (reflection). Obfuscating them broke the AI on the
# tablet: "Field platform_ for mn1 not found" (2026-11-24). Keep MediaPipe + protobuf-lite intact.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; <methods>; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-dontwarn com.google.auto.value.**
-dontwarn javax.annotation.**
# MediaPipe logs through Flogger, which finds its caller by walking the stack: renamed Flogger classes made
# Graph.<clinit> fail with "no caller found on the stack" (2026-11-24).
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.common.flogger.**

# JNI bridge to libopus: method names must match the C symbols.
-keepclasseswithmembernames class * { native <methods>; }
-keep class ai.paai.link.audio.OpusNative { *; }

# Bouncy Castle lightweight API (no reflection used), silence optional-dependency warnings.
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

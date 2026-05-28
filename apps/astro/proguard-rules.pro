# JNA + UniFFI (FFI surface for the Rust core).
-keep class com.sun.jna.** { *; }
-keep interface com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.* { *; }
-keepclassmembers class * implements com.sun.jna.Library { *; }
-keepclassmembers class * implements com.sun.jna.Callback { *; }
-dontwarn java.awt.**
-keep class uniffi.** { *; }
-keepclassmembers class uniffi.** { *; }

# OkHttp / Okio (R8-friendly already; silence platform-specific warnings).
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

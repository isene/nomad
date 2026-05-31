# ---- JNA ----
# The generated UniFFI bindings call into libfe2o3_mobile_core.so through
# JNA, which binds methods reflectively at runtime. R8 must not rename or
# remove any of it.
-keep class com.sun.jna.** { *; }
-keep interface com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.* { *; }
-keepclassmembers class * implements com.sun.jna.Library { *; }
-keepclassmembers class * implements com.sun.jna.Callback { *; }
-dontwarn java.awt.**

# ---- UniFFI ----
-keep class uniffi.** { *; }
-keepclassmembers class uniffi.** { *; }

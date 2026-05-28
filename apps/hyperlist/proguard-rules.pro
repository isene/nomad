# ---- JNA ----
# Generated UniFFI bindings bind native methods reflectively through JNA;
# R8 must not rename or strip any of it.
-keep class com.sun.jna.** { *; }
-keep interface com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.* { *; }
-keepclassmembers class * implements com.sun.jna.Library { *; }
-keepclassmembers class * implements com.sun.jna.Callback { *; }
-dontwarn java.awt.**

# ---- UniFFI ----
# The bindgen output (Library interface, FfiConverters, records, enums) is
# the contract with libfe2o3_mobile_core.so. Keep all of it, names included.
-keep class uniffi.** { *; }
-keepclassmembers class uniffi.** { *; }

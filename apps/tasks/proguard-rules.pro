# JNA — used by the generated UniFFI Kotlin bindings. R8 must not
# rename or remove JNA internals, or the runtime FFI calls fail with
# UnsatisfiedLinkError.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Callback { *; }
-keepclassmembers class * extends com.sun.jna.Structure { *; }

# Everything the UniFFI bindgen emits lives under uniffi.fe2o3_mobile_core.
# Keep both the public Kotlin surface and the internal FFI helpers
# untouched; they are the contract with the .so.
-keep class uniffi.** { *; }

# ---- JNA ----
# The generated UniFFI bindings call into libfe2o3_mobile_core.so through
# JNA, which binds methods reflectively at runtime. R8 must not rename or
# remove any of it, or the FFI fails with UnsatisfiedLinkError /
# NoSuchMethodError at first call.
-keep class com.sun.jna.** { *; }
-keep interface com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.* { *; }
-keepclassmembers class * implements com.sun.jna.Library { *; }
-keepclassmembers class * implements com.sun.jna.Callback { *; }
# JNA references java.awt on the desktop; absent on Android. Silence the
# warning rather than fail the build.
-dontwarn java.awt.**

# ---- UniFFI ----
# Everything the bindgen emits lives under uniffi.fe2o3_mobile_core. It is
# the contract with the .so: the Library interface, the FfiConverters, the
# data classes, the callback interfaces. Keep all of it, names included.
-keep class uniffi.** { *; }
-keepclassmembers class uniffi.** { *; }

# ---- Glance widget ----
# The widget receiver is instantiated by the system from the manifest;
# AGP keeps manifest classes, but keep the Glance subclasses explicitly so
# the RemoteViews session classes survive shrinking.
-keep class com.isene.tasks.widget.** { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }

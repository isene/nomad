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

# Jakarta Mail. The providers are found by reflection through
# META-INF/javamail.*, so R8 cannot see the reference and would strip
# the IMAP provider out from under us.
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keepclassmembers class * extends javax.mail.Provider { *; }
-dontwarn com.sun.mail.**
-dontwarn javax.mail.**
-dontwarn javax.activation.**
-dontwarn java.beans.**
-dontwarn javax.security.sasl.**

# ---- Glance widget ----
# The receiver is instantiated by the system from the manifest; AGP keeps
# manifest classes, but keep the Glance subclasses explicitly so their
# members survive too.
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }

# ---- WorkManager ----
# Workers are instantiated reflectively by class name.
-keep class * extends androidx.work.ListenableWorker { *; }

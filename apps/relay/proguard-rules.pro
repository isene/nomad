# Keep the NotificationListenerService subclass — the system instantiates it
# by name from the manifest; R8 must not rename or strip it.
-keep class com.isene.relay.service.** { *; }
-keep class * extends android.service.notification.NotificationListenerService { *; }

# Keep Media3's session/service entry points (they are resolved reflectively by
# the platform, so R8 cannot see the references).
-keep class androidx.media3.session.** { *; }
-keep class * extends androidx.media3.session.MediaSessionService
-keep class * extends android.app.Service
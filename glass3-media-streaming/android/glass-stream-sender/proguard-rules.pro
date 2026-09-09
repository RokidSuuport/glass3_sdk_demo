# The release test build is intentionally not minified. Keep rules live here so
# enabling minification later does not strip Binder and WebRTC callback types.
-keep class com.rokid.security.** { *; }
-keep class livekit.org.webrtc.** { *; }
# Rokid Open SDK callbacks are delivered through Binder and WebRTC reaches
# several media classes from native code.
-keep class com.rokid.security.glass3.** { *; }
-keep class livekit.org.webrtc.** { *; }

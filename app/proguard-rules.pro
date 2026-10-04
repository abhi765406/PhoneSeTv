# WebRTC and OkHttp both do some reflection internally - keep things conservative.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

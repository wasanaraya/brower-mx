# MyMXBrowser (Advanced Auto-Find)
WebView browser เล็กๆ ที่พยายามจับลิงก์วิดีโอ (.mp4/.m3u8) เองจาก
- Navigation (shouldOverrideUrlLoading)
- Resource load (shouldInterceptRequest)
- JS hook (fetch/XHR + scan <video>/<source>)

เมื่อเจอลิงก์ .mp4 จะเติม .m3u8 ต่อท้ายให้ แล้วกด Copy/Open MX ได้จากปุ่มลอย

## Build
เปิดโฟลเดอร์นี้ใน Android Studio แล้ว Run/Build APK (ต้องมี JDK 17)

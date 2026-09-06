# libUVCCamera registers methods against this literal class name and looks up
# its control-range/native-handle fields by name from JNI.
-keep class com.serenegiant.usb.UVCCamera { *; }

# Native callbacks look up interface method names on their implementations.
-keep interface com.serenegiant.usb.IFrameCallback { *; }
-keep interface com.serenegiant.usb.IStatusCallback { *; }
-keep interface com.serenegiant.usb.IButtonCallback { *; }
-keepclassmembers class * implements com.serenegiant.usb.IFrameCallback {
    public void onFrame(java.nio.ByteBuffer);
}
-keepclassmembers class * implements com.serenegiant.usb.IStatusCallback {
    public void onStatus(int, int, int, int, java.nio.ByteBuffer);
}
-keepclassmembers class * implements com.serenegiant.usb.IButtonCallback {
    public void onButton(int, int);
}

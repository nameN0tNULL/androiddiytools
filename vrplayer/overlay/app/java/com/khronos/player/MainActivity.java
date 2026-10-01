package com.khronos.player;

public class MainActivity extends android.app.NativeActivity {
    static {
        System.loadLibrary("openxr_loader");
        System.loadLibrary("avutil");
        System.loadLibrary("swresample");
        System.loadLibrary("swscale");
        System.loadLibrary("avcodec");
        System.loadLibrary("avformat");
        System.loadLibrary("player");
    }
}

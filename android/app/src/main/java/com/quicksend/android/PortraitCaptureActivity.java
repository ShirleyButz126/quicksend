package com.quicksend.android;

import com.journeyapps.barcodescanner.CaptureActivity;

/**
 * 竖屏扫码界面：固定 portrait，避免扫码时被旋转成横屏全屏。
 * 实际方向由 AndroidManifest 里的 android:screenOrientation="portrait" 保证。
 */
public class PortraitCaptureActivity extends CaptureActivity {
}

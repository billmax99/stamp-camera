package com.stampcamera.app;

import android.Manifest;
import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    // WebView 的 getUserMedia 依赖应用已持有相机运行时权限,启动即申请
    requestPermissions(new String[]{Manifest.permission.CAMERA}, 1001);
  }
}

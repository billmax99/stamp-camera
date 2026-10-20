package com.stampcamera.app;

import android.Manifest;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.DownloadListener;
import android.widget.Toast;
import com.getcapacitor.BridgeActivity;
import java.io.OutputStream;

public class MainActivity extends BridgeActivity {
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    // WebView 的 getUserMedia 依赖应用已持有相机运行时权限,启动即申请
    requestPermissions(new String[]{Manifest.permission.CAMERA}, 1001);
    // WebView 对 <a download> 无系统下载 UI(点了没反应):接住 data:URL 解码存进系统相册
    this.bridge.getWebView().setDownloadListener(new DownloadListener() {
      @Override
      public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
        if (!url.startsWith("data:")) {
          Toast.makeText(MainActivity.this, "仅支持保存图片", Toast.LENGTH_SHORT).show();
          return;
        }
        try {
          byte[] bin = Base64.decode(url.substring(url.indexOf(',') + 1), Base64.DEFAULT);
          Bitmap bm = BitmapFactory.decodeByteArray(bin, 0, bin.length);
          if (bm == null) throw new IllegalStateException("图片解码失败");
          ContentValues v = new ContentValues();
          v.put(MediaStore.Images.Media.DISPLAY_NAME, "stamp_" + System.currentTimeMillis() + ".png");
          v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
          if (Build.VERSION.SDK_INT >= 29) {
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/邮票相机");
          }
          Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
          OutputStream os = getContentResolver().openOutputStream(uri);
          bm.compress(Bitmap.CompressFormat.PNG, 100, os);
          os.close();
          Toast.makeText(MainActivity.this, "已保存到相册 · Pictures/邮票相机", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
          Toast.makeText(MainActivity.this, "保存失败:" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
      }
    });
  }
}

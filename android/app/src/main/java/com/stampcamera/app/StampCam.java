package com.stampcamera.app;

import android.annotation.SuppressLint;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Size;
import android.view.Surface;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * 原生 Camera2 相机引擎:WebView getUserMedia 在红米/vivo 上 single-shot 对焦是假动作
 * (回 ok 镜头不动),此插件把对焦交还 HAL——点按 = AF/AE 区域 + AF_TRIGGER,由相机
 * 自己的对比度/相位检测引擎找峰。预览不再直接用 getUserMedia:相机帧经 MJPEG 裸流
 * (127.0.0.1 随机端口,WebView 对环回地址有 mixed-content 豁免)送到 JS,由页面
 * canvas.captureStream 回喂 <video>,美颜/人脸/裁切管线零改动。
 * 借鉴墨影相机 CameraEngine 的两个实证教训:点按请求与预览请求的控制项保持一致
 * (帧率档缺失会让 HAL 切裁切路径,表现为焦段突变);拍摄不必等 afState 收敛
 * (continuous-picture 取景阶段早已收敛)。
 * ponytail: 单连接单客户端;帧流 ~30fps 960 档 JPEG 质量 75,再高的预览帧率收益递减
 */
@CapacitorPlugin(name = "StampCam")
public class StampCam extends Plugin {
    private CameraDevice device;
    private CameraCharacteristics ch;
    private CameraCaptureSession session;
    private CaptureRequest.Builder base;      // 预览基础请求,torch/点按都从它复制
    private ImageReader frameReader, stillReader;
    private HandlerThread camThread;
    private Handler cam;
    private String facing = "back";
    private volatile boolean openPending = false;

    // MJPEG 服务
    private ServerSocket server;
    private volatile Socket client;
    private volatile OutputStream clientOut;
    private Thread serverThread;
    private volatile long lastSent = 0;
    private static final byte[] BOUNDARY = "--stampframe\r\n".getBytes();

    @PluginMethod
    public void open(PluginCall call) {
        String want = call.getString("facing", "back");
        synchronized (this) {
            closeInternal();
            openPending = true;
        }
        final PluginCall c = call;
        camPost(() -> openCamera(want, c));
    }

    @PluginMethod
    public void close(PluginCall call) {
        synchronized (this) {
            closeInternal();
        }
        call.resolve();
    }

    /** 点按对焦:x/y 为取景内容(已旋转竖直、已去镜像)的归一化坐标 [0,1]。
     * 本机(onyx)实测 AF_TRIGGER+区域是假动作(镜头 2s 纹丝不动),唯一听管的是
     * manual LENS_FOCUS_DISTANCE——把扫描下沉到原生层近→远扫档选峰(与网页版 v4
     * 同算法,但没有 WebView 约束协商开销),扫完停 2.5s 回连续对焦 */
    @PluginMethod
    public void tapFocus(PluginCall call) {
        Double x = call.getDouble("x"), y = call.getDouble("y");
        if (x == null || y == null) { call.reject("需要 x/y"); return; }
        if (session == null || device == null || frameReader == null) { call.reject("相机未开"); return; }
        int orient = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
        double sx, sy;
        if (orient == 90) { sx = y; sy = 1.0 - x; }
        else if (orient == 270) { sx = 1.0 - y; sy = x; }
        else if (orient == 180) { sx = 1.0 - x; sy = 1.0 - y; }
        else { sx = x; sy = y; }
        int fw = frameReader.getWidth(), fh = frameReader.getHeight();
        int cxPx = (int) Math.round(sx * fw), cyPx = (int) Math.round(sy * fh);
        int half = Math.max(8, Math.min(fw, fh) * 12 / 100); // 与网页版 ±12% 区域一致
        Rect region = new Rect(
            Math.max(0, cxPx - half), Math.max(0, cyPx - half),
            Math.min(fw, cxPx + half), Math.min(fh, cyPx + half));

        final int gen = ++sweepGen;
        sweepLast = "rect" + region.toString() + " fw" + fw + " fh" + fh;
        Thread t = new Thread(() -> runSweep(region, gen), "stampcam-sweep");
        t.setDaemon(true);
        t.start();
        call.resolve();
    }

    // ---------------- 对焦扫描(独立线程:onFrame 在相机线程更新分数,这里只等待) ----------------
    private int sweepGen = 0;
    private volatile Rect sweepRect = null;  // 非空 = onFrame 要给这个区域打分
    private volatile double sweepScore = 0;  // 最近一帧的区域清晰度
    private volatile double sweepScoreTs = 0;
    private volatile boolean torchOn = false;
    private volatile String sweepLast = ""; // 诊断:最近一次扫描的档位分数与定档结果
    private volatile float parkedDiopter = -1; // ≥0 = 当前停在手动距离
    // 屈光度档,近→远(约合米 0.125/0.28/0.63/1.33/3.0/9)
    private static final double[] STOPS = {8.0, 3.6, 1.6, 0.75, 0.33, 0.11};

    private void runSweep(Rect region, int gen) {
        try {
            double[] scores = new double[STOPS.length];
            sweepRect = region;
            for (int i = 0; i < STOPS.length && gen == sweepGen; i++) {
                applyDistance(STOPS[i]);
                scores[i] = settleScore(i == 0 ? 650 : 500);
                double max = 0;
                for (int j = 0; j <= i; j++) max = Math.max(max, scores[j]);
                if (i >= 1 && scores[i] < max * 0.65) break; // 峰在身后
            }
            if (gen != sweepGen) return;
            int bi = 0;
            for (int i = 1; i < scores.length; i++) if (scores[i] > scores[bi]) bi = i;
            double second = 0;
            for (int i = 0; i < scores.length; i++) if (i != bi && scores[i] > second) second = scores[i];
            if (bi == scores.length - 1 || scores[bi] < second * 1.1) {
                // 峰不可辨或峰在末档:停最远档不折腾,交回连续
                sweepRect = null;
                restoreContinuous();
                return;
            }
            double best = STOPS[bi], bs = scores[bi];
            java.util.List<String> dbg = new java.util.ArrayList<>();
            for (int i = 0; i < scores.length; i++) dbg.add((int) STOPS[i] + ":" + String.format("%.4f", scores[i]));
            if (bi == 0) { // 峰在首档:真峰可能更靠前(首档与次档之间),补测对数中点
                double mid = Math.sqrt(STOPS[0] * STOPS[1]);
                applyDistance(mid);
                double s2 = settleScore(500);
                dbg.add("mid" + String.format("%.2f", mid) + ":" + String.format("%.4f", s2));
                if (s2 > bs) { bs = s2; best = mid; }
            }
            if (bi >= 1) { // 对数空间抛物线顶点;偏离>12% 才真动一次验证
                double vd = parabola(STOPS[bi - 1], STOPS[bi], STOPS[bi + 1], scores[bi - 1], scores[bi], scores[bi + 1]);
                if (vd > 0 && Math.abs(Math.log(vd / best)) > Math.log(1.12)) {
                    applyDistance(vd);
                    double s2 = settleScore(500);
                    dbg.add("vtx" + String.format("%.2f", vd) + ":" + String.format("%.4f", s2));
                    if (s2 > bs) { bs = s2; best = vd; }
                } else if (vd > 0) dbg.add("vtxSkip" + String.format("%.2f", vd));
            }
            applyDistance(best);
            dbg.add("best" + String.format("%.2f", best));
            sweepLast = String.join(" ", dbg);
            sweepRect = null;
            Thread.sleep(2500); // 停 2.5s 回连续保取景常锐;再点按会刷新代际
            if (gen == sweepGen) restoreContinuous();
        } catch (Exception ignore) {
            sweepRect = null;
        }
    }

    /** 等镜头到位并取稳定真值:连 3 帧波动<5% 且满 220ms(镜头机械行程 170~350ms,
     * 窗口太短会读到行程中值、峰位滞后一档),超时取尾均值 */
    private double settleScore(long budget) throws InterruptedException {
        long t0 = System.currentTimeMillis();
        double s1 = 0, s2 = 0, s3 = 0;
        while (System.currentTimeMillis() - t0 < budget) {
            Thread.sleep(20);
            if (sweepScoreTs < t0) continue;          // 还没有本档新帧
            s1 = s2; s2 = s3; s3 = sweepScore;
            long el = System.currentTimeMillis() - t0;
            boolean stable = el > 220 && s1 > 0
                && Math.abs(s2 - s1) < 0.05 * s1 && Math.abs(s3 - s2) < 0.05 * s2;
            if (stable) return (s1 + s2 + s3) / 3;
        }
        return (s2 + s3) / 2;
    }

    private void applyDistance(double diopter) {
        try {
            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(frameReader.getSurface());
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, (float) diopter);
            parkedDiopter = (float) diopter;
            applyFlash(b);
            session.setRepeatingRequest(b.build(), resultCb, cam);
        } catch (Exception ignore) {}
    }

    private void restoreContinuous() {
        try {
            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(frameReader.getSurface());
            baseControls(b);
            parkedDiopter = -1;
            applyFlash(b);
            session.setRepeatingRequest(b.build(), resultCb, cam);
        } catch (Exception ignore) {}
    }

    private void applyFlash(CaptureRequest.Builder b) {
        if (torchOn) b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
    }

    /** 对数空间抛物线顶点(0=不凹/出界);与网页版 afParabola 同式 */
    private static double parabola(double dLo, double dB, double dHi, double sLo, double sB, double sHi) {
        double x0 = Math.log(dLo), x1 = Math.log(dB), x2 = Math.log(dHi);
        double A = ((sHi - sLo) / (x2 - x0) - (sB - sLo) / (x1 - x0)) / (x2 - x1);
        if (!(A < 0)) return 0;
        double B = (sB - sLo) / (x1 - x0) - A * (x0 + x1);
        double xv = -B / (2 * A);
        return xv > x0 && xv < x2 ? Math.exp(xv) : 0;
    }

    /** 全幅拍照:返回 {b64, w, h},JPEG 已按传感器方向转正 */
    @PluginMethod
    public void capture(PluginCall call) {
        CameraCaptureSession s = session;
        CameraDevice d = device;
        if (s == null || d == null || stillReader == null) { call.reject("相机未开"); return; }
        final PluginCall c = call;
        stillReader.setOnImageAvailableListener(reader -> {
            reader.setOnImageAvailableListener(null, null);
            try (Image img = reader.acquireLatestImage()) {
                if (img == null) { c.reject("空帧"); return; }
                ByteBuffer bb = img.getPlanes()[0].getBuffer();
                byte[] jpeg = new byte[bb.remaining()];
                bb.get(jpeg);
                JSObject r = new JSObject();
                r.put("b64", Base64.encodeToString(jpeg, Base64.NO_WRAP));
                r.put("w", img.getWidth());
                r.put("h", img.getHeight());
                c.resolve(r);
            } catch (Exception e) {
                c.reject("capture: " + e.getMessage());
            }
        }, cam);
        camPost(() -> {
            try {
                CaptureRequest.Builder b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                b.addTarget(stillReader.getSurface());
                if (parkedDiopter >= 0) { // 停泊手动档:拍照同距离,不跑焦
                    b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                    b.set(CaptureRequest.LENS_FOCUS_DISTANCE, parkedDiopter);
                } else {
                    baseControls(b);
                }
                Integer orient = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
                b.set(CaptureRequest.JPEG_ORIENTATION, (orient == null ? 90 : orient + 360) % 360);
                s.capture(b.build(), null, cam);
            } catch (Exception e) {
                c.reject("capture: " + e.getMessage());
            }
        });
    }

    // 诊断与测试通道:读 HAL 真值(afState/实际镜头屈光度),手动定距离
    private volatile int lastAfState = -1;
    private volatile float lastDiopter = -1;

    @PluginMethod
    public void getState(PluginCall call) {
        JSObject r = new JSObject();
        r.put("afState", lastAfState);
        r.put("diopter", lastDiopter);
        r.put("sessionAlive", session != null);
        r.put("maxAf", ch == null ? null : ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF));
        r.put("orient", ch == null ? null : ch.get(CameraCharacteristics.SENSOR_ORIENTATION));
        r.put("sweep", sweepLast);
        call.resolve(r);
    }

    /** diopter>=0 = 手动定距(0=无穷远);<0 = 回连续对焦 */
    @PluginMethod
    public void setManual(PluginCall call) {
        Double d = call.getDouble("diopter", -1.0);
        CameraCaptureSession s = session;
        CameraDevice dev = device;
        if (s == null || dev == null) { call.reject("相机未开"); return; }
        camPost(() -> {
            try {
                CaptureRequest.Builder b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                b.addTarget(frameReader.getSurface());
                if (d >= 0) {
                    b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                    b.set(CaptureRequest.LENS_FOCUS_DISTANCE, d.floatValue());
                } else {
                    b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                }
                s.setRepeatingRequest(b.build(), resultCb, cam);
                call.resolve();
            } catch (Exception e) {
                call.reject("setManual: " + e.getMessage());
            }
        });
    }

    private final CameraCaptureSession.CaptureCallback resultCb = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession ses, CaptureRequest req, TotalCaptureResult res) {
            Integer af = res.get(CaptureResult.CONTROL_AF_STATE);
            if (af != null) lastAfState = af;
            Float d = res.get(CaptureResult.LENS_FOCUS_DISTANCE);
            if (d != null) lastDiopter = d;
        }
    };

    @PluginMethod
    public void setTorch(PluginCall call) {
        Boolean on = call.getBoolean("on", false);
        torchOn = on;
        CameraCaptureSession s = session;
        CameraDevice d = device;
        if (s == null || d == null) { call.reject("相机未开"); return; }
        Boolean has = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        if (has == null || !has) { call.reject("无闪光灯"); return; }
        camPost(() -> {
            try {
                CaptureRequest.Builder b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                b.addTarget(frameReader.getSurface());
                baseControls(b);
                b.set(CaptureRequest.FLASH_MODE, on ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
                s.setRepeatingRequest(b.build(), resultCb, cam);
                call.resolve();
            } catch (Exception e) {
                call.reject("setTorch: " + e.getMessage());
            }
        });
    }

    // ---------------- 内部:相机生命周期 ----------------

    private void camPost(Runnable r) {
        if (camThread == null) {
            camThread = new HandlerThread("stampcam");
            camThread.start();
            cam = new Handler(camThread.getLooper());
        }
        cam.post(r);
    }

    @SuppressLint("MissingPermission")
    private void openCamera(String want, PluginCall call) {
        try {
            CameraManager mgr = getContext().getSystemService(CameraManager.class);
            if (mgr == null) { fail(call, "无相机服务"); return; }
            int lens = "front".equals(want) ? CameraMetadata.LENS_FACING_FRONT : CameraMetadata.LENS_FACING_BACK;
            String id = null;
            for (String cand : mgr.getCameraIdList()) {
                CameraCharacteristics cc = mgr.getCameraCharacteristics(cand);
                Integer f = cc.get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == lens) { id = cand; ch = cc; break; }
            }
            if (id == null) { fail(call, "没有这颗镜头"); return; }
            facing = want;
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size frame = pickSize(map.getOutputSizes(ImageFormat.YUV_420_888), 1280, 960);
            Size still = largest(map.getOutputSizes(ImageFormat.JPEG));
            frameReader = ImageReader.newInstance(frame.getWidth(), frame.getHeight(), ImageFormat.YUV_420_888, 3);
            stillReader = ImageReader.newInstance(still.getWidth(), still.getHeight(), ImageFormat.JPEG, 2);
            frameReader.setOnImageAvailableListener(this::onFrame, cam);
            mgr.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice dev) {
                    device = dev;
                    try {
                        dev.createCaptureSession(Arrays.asList(frameReader.getSurface(), stillReader.getSurface()),
                            new CameraCaptureSession.StateCallback() {
                                @Override public void onConfigured(CameraCaptureSession ses) {
                                    session = ses;
                                    try {
                                        base = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                        base.addTarget(frameReader.getSurface());
                                        baseControls(base);
                                        session.setRepeatingRequest(base.build(), resultCb, cam);
                                        startServer();
                                        JSObject r = new JSObject();
                                        r.put("streamUrl", "http://127.0.0.1:" + server.getLocalPort() + "/mjpeg");
                                        r.put("w", frame.getWidth());
                                        r.put("h", frame.getHeight());
                                        call.resolve(r);
                                    } catch (Exception e) { fail(call, "session: " + e.getMessage()); }
                                }
                                @Override public void onConfigureFailed(CameraCaptureSession ses) { fail(call, "会话配置失败"); }
                            }, cam);
                    } catch (Exception e) { fail(call, e.getMessage()); }
                }
                @Override public void onDisconnected(CameraDevice dev) { dev.close(); }
                @Override public void onError(CameraDevice dev, int err) { fail(call, "相机错误 " + err); }
            }, cam);
        } catch (Exception e) {
            fail(call, e.getMessage());
        }
    }

    private void fail(PluginCall call, String msg) {
        openPending = false;
        call.reject(msg);
    }

    /** 预览/点按/拍照共用的控制组:全部只声明 AF 连续 + AE 自动,谁都不多带参数,
     *  避免 HAL 因控制面差异切流(墨影实证:帧率档不一致会导致焦段突变) */
    private void baseControls(CaptureRequest.Builder b) {
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
    }

    private void onFrame(ImageReader reader) {
        try (Image img = reader.acquireLatestImage()) {
            if (img == null) return;
            Rect sr = sweepRect;
            if (sr != null) { // 对焦扫描中:先给点击区打分(便宜,只在区域内采样)
                sweepScore = yScore(img.getPlanes()[0], sr);
                sweepScoreTs = System.currentTimeMillis();
            }
            long now = System.currentTimeMillis();
            if (clientOut == null || now - lastSent < 33) return; // 帧流限速 ~30fps
            lastSent = now;
            byte[] jpeg = yuvToJpeg(img);
            if (jpeg == null) return;
            OutputStream os = clientOut;
            if (os == null) return;
            synchronized (os) {
                os.write(BOUNDARY);
                os.write(("Content-Type: image/jpeg\r\nContent-Length: " + jpeg.length + "\r\n\r\n").getBytes());
                os.write(jpeg);
                os.write("\r\n".getBytes());
                os.flush();
            }
        } catch (Exception ignore) {
            // 客户端断开等:丢帧继续
        }
    }

    /** Y 平面区域清晰度:梯度/亮度归一(与网页版 regionSharpness 同判据),~64×64 采样 */
    private static double yScore(Image.Plane yp, Rect r) {
        ByteBuffer b = yp.getBuffer();
        int rs = yp.getRowStride();
        int step = Math.max(1, Math.min(r.width(), r.height()) / 64);
        double grad = 0, lum = 0;
        long n = 0;
        for (int y = r.top; y + step < r.bottom; y += step) {
            int base = y * rs;
            int baseD = (y + step) * rs;
            for (int x = r.left; x + step < r.right; x += step) {
                int v = b.get(base + x) & 0xff;
                lum += v;
                grad += Math.abs(v - (b.get(base + x + step) & 0xff)) + Math.abs(v - (b.get(baseD + x) & 0xff));
                n++;
            }
        }
        return n == 0 ? 0 : grad / (lum + 1);
    }

    private byte[] yuvToJpeg(Image img) {
        try {
            int w = img.getWidth(), h = img.getHeight();
            byte[] nv21 = new byte[w * h * 3 / 2];
            Image.Plane[] pl = img.getPlanes();
            copyY(pl[0], nv21, w, h);
            interleaveUV(pl[2], pl[1], nv21, w, h); // planes[2]=V 在前 = NV21
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            new android.graphics.YuvImage(nv21, ImageFormat.NV21, w, h, null)
                .compressToJpeg(new Rect(0, 0, w, h), 75, bos);
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** Y 平面逐行拷:兼容 rowStride≠宽度(HAL 对齐填充)与 pixelStride≠1(极少数) */
    private static void copyY(Image.Plane p, byte[] dst, int w, int h) {
        ByteBuffer b = p.getBuffer();
        int rs = p.getRowStride(), ps = p.getPixelStride();
        int pos = 0;
        for (int row = 0; row < h; row++) {
            int base = row * rs;
            for (int col = 0; col < w; col++) {
                int i = base + col * ps;
                dst[pos++] = i < b.limit() ? b.get(i) : (byte) 16;
            }
        }
    }

    /** U/V 平面交织成 NV21 尾段;越界补中性色,不让单帧异常毁掉整条流 */
    private static void interleaveUV(Image.Plane v, Image.Plane u, byte[] dst, int w, int h) {
        ByteBuffer vb = v.getBuffer(), ub = u.getBuffer();
        int vrs = v.getRowStride(), vps = v.getPixelStride();
        int urs = u.getRowStride(), ups = u.getPixelStride();
        int pos = w * h;
        for (int row = 0; row < h / 2; row++) {
            for (int col = 0; col < w / 2; col++) {
                int vi = row * vrs + col * vps;
                int ui = row * urs + col * ups;
                boolean ok = vi < vb.limit() && ui < ub.limit();
                dst[pos++] = ok ? vb.get(vi) : (byte) 128;
                dst[pos++] = ok ? ub.get(ui) : (byte) 128;
            }
        }
    }

    // ---------------- 内部:MJPEG 服务(127.0.0.1,单客户端) ----------------

    private void startServer() {
        stopServer();
        try {
            server = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
            serverThread = new Thread(() -> {
                try {
                    Socket s = server.accept();
                    s.setTcpNoDelay(true);
                    OutputStream os = s.getOutputStream();
                    os.write("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=stampframe\r\nAccess-Control-Allow-Origin: *\r\n\r\n".getBytes());
                    os.flush();
                    client = s;
                    clientOut = os;
                    // 读端不消费也不退出;断开由写失败感知
                    byte[] sink = new byte[4096];
                    while (s.getInputStream().read(sink) >= 0) { }
                } catch (Exception ignore) {
                } finally {
                    dropClient();
                }
            }, "stampcam-mjpeg");
            serverThread.start();
        } catch (Exception ignore) {
        }
    }

    private void dropClient() {
        try { if (clientOut != null) clientOut.close(); } catch (Exception ignore) {}
        try { if (client != null) client.close(); } catch (Exception ignore) {}
        clientOut = null;
        client = null;
    }

    private void stopServer() {
        dropClient();
        try { if (server != null) server.close(); } catch (Exception ignore) {}
        try { if (serverThread != null) serverThread.interrupt(); } catch (Exception ignore) {}
        server = null;
        serverThread = null;
    }

    private void closeInternal() {
        stopServer();
        try { if (session != null) session.close(); } catch (Exception ignore) {}
        try { if (device != null) device.close(); } catch (Exception ignore) {}
        try { if (frameReader != null) frameReader.close(); } catch (Exception ignore) {}
        try { if (stillReader != null) stillReader.close(); } catch (Exception ignore) {}
        session = null;
        device = null;
        frameReader = null;
        stillReader = null;
        base = null;
        openPending = false;
    }

    @Override
    protected void handleOnDestroy() {
        synchronized (this) { closeInternal(); }
        if (camThread != null) camThread.quitSafely();
        super.handleOnDestroy();
    }

    // ---------------- 工具 ----------------

    private static Size pickSize(Size[] sizes, int tw, int th) {
        Size best = null;
        long bestScore = Long.MAX_VALUE;
        for (Size s : sizes) {
            if (s.getWidth() > 1920) continue; // 帧流够用就好,大图浪费 CPU
            long d = Math.abs((long) s.getWidth() * th - (long) s.getHeight() * tw);
            long score = d * 1000 + Math.abs(s.getWidth() - tw);
            if (score < bestScore) { bestScore = score; best = s; }
        }
        return best != null ? best : sizes[0];
    }

    private static Size largest(Size[] sizes) {
        Size best = sizes[0];
        for (Size s : sizes) if (s.getWidth() * s.getHeight() > best.getWidth() * best.getHeight()) best = s;
        return best;
    }
}

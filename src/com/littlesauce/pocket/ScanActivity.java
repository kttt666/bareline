package com.littlesauce.pocket;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.hardware.Camera;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 扫码页。
 *
 * 两条路：
 * 1) 系统 Camera API 实时取 NV21 帧 + ZXing 解二维码；
 * 2) 右下角「相册」按钮导入图片 —— 先把二维码截图存进相册，再从相册选图识别，
 *    这样扫电脑屏幕上的连接码时不用举着手机对屏幕。
 *
 * 只认二维码（QR_CODE）—— 主要用途是扫 dsh-pocket 的连接码。
 */
public class ScanActivity extends Activity implements SurfaceHolder.Callback {

    public static final String EXTRA_RESULT = "result";

    private static final String TAG = "PocketScan";
    private static final int REQ_CAMERA = 2001;
    private static final int REQ_ALBUM = 2002;

    /** 导入图片时的首轮最大边长：先在这个尺寸上试，不行再往上放大重试。 */
    private static final int IMPORT_SIDE_LADDER[] = {1280, 2048, 4096};

    private SurfaceView preview;
    private TextView tip;
    private Camera camera;
    private Camera.Parameters params;

    /** 预览专用 reader —— 不能和相册解码共用，decodeWithState 不是线程安全的。 */
    private final MultiFormatReader previewReader = new MultiFormatReader();
    private final AtomicBoolean decoding = new AtomicBoolean(false);
    /** 相册解码进行中时暂停预览解码，避免抢 CPU 和干扰。 */
    private volatile boolean importing = false;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private boolean torchOn = false;
    private boolean found = false;
    private boolean permissionAsked = false;
    private int previewW = 0;
    private int previewH = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setupWindow();

        setContentView(R.layout.activity_scan);

        hideBars();

        preview = findViewById(R.id.preview);
        tip = findViewById(R.id.tip);

        preview.getHolder().addCallback(this);

        ImageButton close = findViewById(R.id.btnClose);
        close.setOnClickListener(v -> finish());

        ImageButton torch = findViewById(R.id.btnTorch);
        torch.setOnClickListener(v -> toggleTorch());

        ImageButton album = findViewById(R.id.btnAlbum);
        album.setOnClickListener(v -> pickFromAlbum());

        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            // 没有摄像头也能用相册导入，所以不直接退出
            Toast.makeText(this, "没有摄像头，可用右下角相册导入", Toast.LENGTH_LONG).show();
        }

        setupReader();
        ensureCameraPermission();
    }

    /** 相机权限：Android 6+ 必须运行时申请。 */
    private void ensureCameraPermission() {
        if (!hasCamera() || hasCameraPermission() || permissionAsked) return;
        permissionAsked = true;
        requestPermissions(new String[]{android.Manifest.permission.CAMERA}, REQ_CAMERA);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req != REQ_CAMERA) return;
        boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (ok) {
            SurfaceHolder h = preview.getHolder();
            if (h.getSurface().isValid() && camera == null) {
                openCamera(h);
            }
        } else {
            // 拒绝相机权限也还能用相册导入
            Toast.makeText(this, "没有相机权限，可用右下角相册导入", Toast.LENGTH_LONG).show();
        }
    }

    private boolean hasCamera() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
    }

    private void setupReader() {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        List<BarcodeFormat> formats = new ArrayList<>();
        formats.add(BarcodeFormat.QR_CODE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, formats);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        previewReader.setHints(hints);
    }

    /** 每个解码器都要独立 hints，不能共享 reader 实例。 */
    private MultiFormatReader newReader() {
        MultiFormatReader r = new MultiFormatReader();
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        List<BarcodeFormat> formats = new ArrayList<>();
        formats.add(BarcodeFormat.QR_CODE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, formats);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        r.setHints(hints);
        return r;
    }

    private void toggleTorch() {
        if (camera == null || params == null) return;
        try {
            torchOn = !torchOn;
            params.setFlashMode(torchOn ? Camera.Parameters.FLASH_MODE_TORCH
                    : Camera.Parameters.FLASH_MODE_OFF);
            camera.setParameters(params);
        } catch (Exception e) {
            torchOn = false;
            Toast.makeText(this, "这个摄像头不支持手电筒", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------ 沉浸式

    /**
     * 窗口属性，必须在 setContentView 之前设。
     * 这里不能碰 getInsetsController()，DecorView 还没建，会 NPE。
     */
    private void setupWindow() {
        Window w = getWindow();
        w.setStatusBarColor(0x00000000);
        w.setNavigationBarColor(0x00000000);

        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }

        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        }
    }

    /** 把栏收起来。必须在 setContentView 之后调。 */
    private void hideBars() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            }
        } else {
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) return;
        hideBars();
    }

    // ------------------------------------------------------------ 相机

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (hasCameraPermission()) {
            openCamera(holder);
        } else {
            ensureCameraPermission();
        }
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(android.Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (camera == null) return;
        try {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(0, info);
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            int degrees = 0;
            switch (rotation) {
                case Surface.ROTATION_0: degrees = 0; break;
                case Surface.ROTATION_90: degrees = 90; break;
                case Surface.ROTATION_180: degrees = 180; break;
                case Surface.ROTATION_270: degrees = 270; break;
            }
            int result;
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                result = (info.orientation + degrees) % 360;
                result = (360 - result) % 360;
            } else {
                result = (info.orientation - degrees + 360) % 360;
            }
            camera.setDisplayOrientation(result);
            camera.setParameters(params);
            camera.startPreview();
        } catch (Exception e) {
            Log.w(TAG, "surfaceChanged: " + e.getMessage());
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        releaseCamera();
    }

    private void openCamera(SurfaceHolder holder) {
        try {
            camera = Camera.open(0);
        } catch (Exception e) {
            Log.w(TAG, "Camera.open failed: " + e.getMessage());
            return;
        }

        try {
            params = camera.getParameters();
            params.setPreviewFormat(ImageFormat.NV21);

            Camera.Size best = null;
            for (Camera.Size s : params.getSupportedPreviewSizes()) {
                if (s.width * s.height <= 1280 * 720) {
                    if (best == null || s.width * s.height > best.width * best.height) best = s;
                }
            }
            if (best == null) {
                List<Camera.Size> all = params.getSupportedPreviewSizes();
                best = all.get(all.size() / 2);
            }
            params.setPreviewSize(best.width, best.height);
            previewW = best.width;
            previewH = best.height;

            List<String> focusModes = params.getSupportedFocusModes();
            if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }

            camera.setParameters(params);
            camera.setPreviewDisplay(holder);

            int bufSize = previewW * previewH * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;
            for (int i = 0; i < 3; i++) {
                camera.addCallbackBuffer(new byte[bufSize]);
            }
            camera.setPreviewCallbackWithBuffer((data, cam) -> onFrame(data, cam));

            camera.startPreview();
        } catch (Exception e) {
            Log.w(TAG, "openCamera config failed: " + e.getMessage());
            releaseCamera();
        }
    }

    private void releaseCamera() {
        if (camera != null) {
            try {
                camera.setPreviewCallbackWithBuffer(null);
                camera.stopPreview();
                camera.release();
            } catch (Exception ignored) {
            }
            camera = null;
        }
    }

    // ------------------------------------------------------------ 实时解码

    private void onFrame(byte[] data, Camera cam) {
        if (data == null || cam == null) return;

        if (!found && !importing && decoding.compareAndSet(false, true)) {
            try {
                PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                        data, previewW, previewH,
                        0, 0, previewW, previewH, false);
                BinaryBitmap bmp = new BinaryBitmap(new HybridBinarizer(source));
                Result r = previewReader.decodeWithState(bmp);
                if (r != null && r.getText() != null) {
                    found = true;
                    final String text = r.getText().trim();
                    ui.post(() -> deliver(text));
                }
            } catch (Exception ignored) {
                // NotFoundException 是常态，继续下一帧
            } finally {
                decoding.set(false);
            }
        }

        try {
            cam.addCallbackBuffer(data);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------ 相册导入

    private void pickFromAlbum() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "选择二维码截图"), REQ_ALBUM);
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的相册应用", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_ALBUM || res != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;

        importing = true;
        tip.setText("正在识别图片…");
        new Thread(() -> {
            String text = decodeImage(uri);
            importing = false;
            ui.post(() -> {
                if (text != null && !text.isEmpty()) {
                    deliver(text);
                } else {
                    tip.setText("这张图里没找到二维码，换一张试试");
                    Toast.makeText(this, "没识别到二维码", Toast.LENGTH_SHORT).show();
                }
            });
        }, "album-decode").start();
    }

    /**
     * 相册图片解码。
     *
     * 按 IMPORT_SIDE_LADDER 逐级放大重试：截图里的二维码往往只占屏幕一小块，
     * 一次性缩到固定尺寸很容易把模块糊掉，所以从大到小分别试一遍。
     *
     * 注意：系统图片选择器返回的 content:// URI 只能稳定打开一次，
     * 所以先把整个文件读成 byte[]，后续解码 / 读 EXIF 都走内存。
     */
    private String decodeImage(Uri uri) {
        Bitmap base = null;
        try {
            byte[] raw = readAll(uri);
            if (raw == null || raw.length == 0) {
                Log.w(TAG, "decodeImage: empty stream");
                return null;
            }
            Log.i(TAG, "decodeImage: bytes=" + raw.length + " uri=" + uri);
            base = decodeBytes(raw);
            if (base == null) {
                Log.w(TAG, "decodeImage: bitmap null");
                return null;
            }
            Log.i(TAG, "decodeImage: bitmap " + base.getWidth() + "x" + base.getHeight());
            base = applyExifRotation(raw, base);

            MultiFormatReader r = newReader();
            String text;

            // 1) 原尺寸 + 放大到阶梯尺寸，各试一遍
            text = decodeAt(r, base, 0);
            if (text != null) return text;

            for (int side : IMPORT_SIDE_LADDER) {
                text = decodeAt(r, base, side);
                if (text != null) return text;
            }

            // 2) 反色（深色主题截图）再走一遍最小的两档
            text = decodeInverted(r, base, 0);
            if (text != null) return text;
            text = decodeInverted(r, base, IMPORT_SIDE_LADDER[0]);
            if (text != null) return text;

            return null;
        } catch (Exception e) {
            Log.w(TAG, "decodeImage failed: " + e.getMessage());
            return null;
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "decodeImage OOM");
            return null;
        } finally {
            if (base != null && !base.isRecycled()) base.recycle();
        }
    }

    /** 一次性读完整个图片文件；超过 64MB 直接放弃，避免把内存撑爆。 */
    private byte[] readAll(Uri uri) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) return null;
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 64 * 1024 * 1024) return null;
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 从内存字节解码；超大图先按 2 的幂采样降到 4096 以内。 */
    private Bitmap decodeBytes(byte[] raw) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / sample > 4096) {
            sample *= 2;
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
    }

    /** side<=0 表示不缩放直接用原图。 */
    private String decodeAt(MultiFormatReader r, Bitmap src, int side) {
        Bitmap work = src;
        boolean scaled = false;
        try {
            if (side > 0) {
                int w = src.getWidth();
                int h = src.getHeight();
                int longest = Math.max(w, h);
                if (longest != side) {
                    float k = (float) side / longest;
                    int nw = Math.max(1, Math.round(w * k));
                    int nh = Math.max(1, Math.round(h * k));
                    work = Bitmap.createScaledBitmap(src, nw, nh, true);
                    scaled = true;
                }
            }
            return decodeBitmap(r, work);
        } catch (Exception e) {
            return null;
        } finally {
            if (scaled && work != null && !work.isRecycled()) work.recycle();
        }
    }

    private String decodeInverted(MultiFormatReader r, Bitmap src, int side) {
        Bitmap work = src;
        boolean scaled = false;
        Bitmap inv = null;
        try {
            if (side > 0) {
                int longest = Math.max(src.getWidth(), src.getHeight());
                if (longest != side) {
                    float k = (float) side / longest;
                    work = Bitmap.createScaledBitmap(src,
                            Math.max(1, Math.round(src.getWidth() * k)),
                            Math.max(1, Math.round(src.getHeight() * k)), true);
                    scaled = true;
                }
            }
            int w = work.getWidth();
            int h = work.getHeight();
            int[] px = new int[w * h];
            work.getPixels(px, 0, w, 0, 0, w, h);
            for (int i = 0; i < px.length; i++) {
                int p = px[i];
                int rr = 255 - ((p >> 16) & 0xFF);
                int gg = 255 - ((p >> 8) & 0xFF);
                int bb = 255 - (p & 0xFF);
                px[i] = 0xFF000000 | (rr << 16) | (gg << 8) | bb;
            }
            inv = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            inv.setPixels(px, 0, w, 0, 0, w, h);
            return decodeBitmap(r, inv);
        } catch (Exception e) {
            return null;
        } finally {
            if (inv != null && !inv.isRecycled()) inv.recycle();
            if (scaled && work != null && !work.isRecycled()) work.recycle();
        }
    }

    private String decodeBitmap(MultiFormatReader r, Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);

        RGBLuminanceSource source = new RGBLuminanceSource(w, h, pixels);

        // 混合二值化
        try {
            Result res = r.decodeWithState(new BinaryBitmap(new HybridBinarizer(source)));
            if (res != null && res.getText() != null) return res.getText();
        } catch (Exception ignored) {
        }
        // 全局直方图二值化，对均匀背景的截图更稳
        try {
            Result res = r.decodeWithState(new BinaryBitmap(new GlobalHistogramBinarizer(source)));
            if (res != null && res.getText() != null) return res.getText();
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 用内存里的字节读 EXIF 方向，不重新打开 URI。 */
    private Bitmap applyExifRotation(byte[] raw, Bitmap src) {
        try {
            ExifInterface exif = new ExifInterface(new java.io.ByteArrayInputStream(raw));
            int orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int deg;
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90: deg = 90; break;
                case ExifInterface.ORIENTATION_ROTATE_180: deg = 180; break;
                case ExifInterface.ORIENTATION_ROTATE_270: deg = 270; break;
                default: return src;
            }
            Matrix m = new Matrix();
            m.postRotate(deg);
            Bitmap out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
            if (out != src) src.recycle();
            return out;
        } catch (Exception e) {
            return src;
        }
    }

    // ------------------------------------------------------------ 结果

    private void deliver(String text) {
        tip.setText("已识别");
        try {
            if (camera != null) camera.stopPreview();
        } catch (Exception ignored) {
        }
        Intent out = new Intent();
        out.putExtra(EXTRA_RESULT, text);
        setResult(RESULT_OK, out);
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        releaseCamera();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasCameraPermission()
                && preview != null
                && preview.getHolder().getSurface().isValid()
                && camera == null) {
            openCamera(preview.getHolder());
        }
    }

    @Override
    protected void onDestroy() {
        releaseCamera();
        super.onDestroy();
    }
}

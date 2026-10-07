package com.example.superzoom;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Super Zoom: native Camera2 viewfinder with every back lens selectable and zoom up to 200x.
 *
 * Zoom is split in two. The camera hardware crops the sensor as far as it allows (real detail),
 * and the GPU scales the preview texture for the rest. The preview matrix is computed from the
 * crop region the camera reports it actually applied, so the image never jumps while zooming.
 */
public class MainActivity extends Activity {

    private static final int REQ_CAMERA = 1;
    private static final float MAX_ZOOM = 200f;
    private static final int[] RES_MP = {2, 5, 12};

    /** One selectable lens. Physical lenses hidden behind a logical camera have a parentId. */
    private static class Lens {
        String id;
        String parentId;          // null when the camera can be opened directly
        CameraCharacteristics ch; // sizes, orientation, sensor info for this lens
        CameraCharacteristics reqCh; // characteristics of the camera we actually open
        String label;
        float eqFocal = 999f;

        boolean direct() { return parentId == null; }
        String openId() { return parentId != null ? parentId : id; }
        int orientation() {
            Integer o = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            return o == null ? 0 : o;
        }
    }

    /** TextureView that keeps the camera's aspect ratio. */
    private static class AutoFitTextureView extends TextureView {
        private int rw, rh;

        AutoFitTextureView(Context c) { super(c); }

        void setAspectRatio(int w, int h) {
            rw = w;
            rh = h;
            requestLayout();
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws);
            int h = MeasureSpec.getSize(hs);
            if (rw == 0 || rh == 0) {
                setMeasuredDimension(w, h);
            } else if (w * rh < h * rw) {
                setMeasuredDimension(w, w * rh / rw);
            } else {
                setMeasuredDimension(h * rw / rh, h);
            }
        }
    }

    // ---- UI ----
    private final Handler ui = new Handler(Looper.getMainLooper());
    private AutoFitTextureView tv;
    private TextView hud;
    private TextView zoomLabel;
    private Spinner lensSpinner;
    private SeekBar zoomBar;
    private Button resBtn;
    private Button sharpBtn;
    private ScaleGestureDetector scaleDetector;
    private float lastX = Float.NaN, lastY = Float.NaN;

    // ---- state shared between UI and camera threads ----
    private volatile SurfaceTexture surfaceTexture;
    private volatile Lens lens;
    private volatile int generation = 0;
    private volatile float zoom = 1f;
    private volatile float cx = 0.5f, cy = 0.5f; // window centre, display-normalised 0..1
    // crop the camera reports it applied, display-normalised
    private volatile float ax0 = 0f, ay0 = 0f, ax1 = 1f, ay1 = 1f;
    private volatile boolean sharpen = false;
    private volatile Size previewSize;
    private int resIndex = 1;
    private float curG = 1f, curOx = 0f, curOy = 0f;
    private String lastHud = "";

    // ---- camera (camera thread only) ----
    private CameraManager manager;
    private HandlerThread camThread;
    private Handler camHandler;
    private Executor camExecutor;
    private CameraDevice camDevice;
    private CameraCaptureSession session;
    private CaptureRequest.Builder builder;
    private Surface previewSurface;

    private final List<Lens> lenses = new ArrayList<>();
    private final Runnable updateRunnable = new Runnable() {
        @Override
        public void run() { updateRequest(); }
    };
    private final Runnable matrixRunnable = new Runnable() {
        @Override
        public void run() { updateMatrix(); }
    };

    // =====================================================================================
    // lifecycle
    // =====================================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        camThread = new HandlerThread("camera");
        camThread.start();
        camHandler = new Handler(camThread.getLooper());
        camExecutor = camHandler::post;

        buildUi();

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            initCameras();
        } else {
            hud.setText("Allow camera access to start.");
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_CAMERA) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initCameras();
        } else {
            hud.setText("Camera permission denied. Enable it in Settings > Apps > Super Zoom > Permissions.");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (lens != null && tv != null && tv.isAvailable()) restartCamera();
    }

    @Override
    protected void onPause() {
        generation++;
        camHandler.post(new Runnable() {
            @Override
            public void run() { closeInternal(); }
        });
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        camThread.quitSafely();
        super.onDestroy();
    }

    // =====================================================================================
    // UI construction
    // =====================================================================================

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14f);
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        return b;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        FrameLayout stage = new FrameLayout(this);
        root.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        tv = new AutoFitTextureView(this);
        stage.addView(tv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        hud = new TextView(this);
        hud.setTextColor(Color.WHITE);
        hud.setTextSize(12f);
        hud.setBackgroundColor(0x99000000);
        hud.setPadding(dp(8), dp(4), dp(8), dp(4));
        FrameLayout.LayoutParams hudLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        hudLp.setMargins(dp(8), dp(8), dp(8), 0);
        stage.addView(hud, hudLp);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setBackgroundColor(0xFF181818);
        controls.setPadding(dp(12), dp(8), dp(12), dp(10));
        root.addView(controls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // lens picker
        lensSpinner = new Spinner(this);
        controls.addView(lensSpinner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // zoom slider + label
        LinearLayout zoomRow = new LinearLayout(this);
        zoomRow.setOrientation(LinearLayout.HORIZONTAL);
        zoomRow.setGravity(Gravity.CENTER_VERTICAL);
        zoomBar = new SeekBar(this);
        zoomBar.setMax(1000);
        zoomRow.addView(zoomBar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        zoomLabel = new TextView(this);
        zoomLabel.setTextColor(Color.WHITE);
        zoomLabel.setTextSize(16f);
        zoomLabel.setGravity(Gravity.END);
        zoomLabel.setText("1.0x");
        zoomRow.addView(zoomLabel, new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));
        controls.addView(zoomRow);

        // presets
        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        final float[] values = {1f, 5f, 20f, 50f, 100f, 200f};
        for (final float v : values) {
            Button b = makeButton(((int) v) + "x");
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) { setZoom(v); }
            });
            presets.addView(b, new LinearLayout.LayoutParams(0, dp(44), 1f));
        }
        controls.addView(presets);

        // actions
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        resBtn = makeButton("Res: " + RES_MP[resIndex] + "MP");
        sharpBtn = makeButton("Sharpen: off");
        Button capBtn = makeButton("Capture");
        actions.addView(resBtn, new LinearLayout.LayoutParams(0, dp(48), 1f));
        actions.addView(sharpBtn, new LinearLayout.LayoutParams(0, dp(48), 1f));
        actions.addView(capBtn, new LinearLayout.LayoutParams(0, dp(48), 1f));
        controls.addView(actions);

        setContentView(root);

        // surface
        tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                surfaceTexture = st;
                if (lens != null) restartCamera();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                updateMatrix();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                surfaceTexture = null;
                generation++;
                camHandler.post(new Runnable() {
                    @Override
                    public void run() { closeInternal(); }
                });
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });

        // zoom slider
        zoomBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser) setZoom((float) Math.pow(MAX_ZOOM, p / 1000.0));
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) { }

            @Override
            public void onStopTrackingTouch(SeekBar sb) { }
        });

        // pinch + pan
        scaleDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector d) {
                        setZoom(zoom * d.getScaleFactor());
                        return true;
                    }
                });
        tv.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                scaleDetector.onTouchEvent(e);
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    lastX = e.getX();
                    lastY = e.getY();
                } else if (a == MotionEvent.ACTION_MOVE) {
                    if (!scaleDetector.isInProgress() && e.getPointerCount() == 1) {
                        if (!Float.isNaN(lastX)) panBy(e.getX() - lastX, e.getY() - lastY);
                        lastX = e.getX();
                        lastY = e.getY();
                    } else {
                        lastX = Float.NaN;
                        lastY = Float.NaN;
                    }
                } else if (a == MotionEvent.ACTION_POINTER_DOWN
                        || a == MotionEvent.ACTION_POINTER_UP
                        || a == MotionEvent.ACTION_UP
                        || a == MotionEvent.ACTION_CANCEL) {
                    lastX = Float.NaN;
                    lastY = Float.NaN;
                }
                return true;
            }
        });

        resBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                resIndex = (resIndex + 1) % RES_MP.length;
                resBtn.setText("Res: " + RES_MP[resIndex] + "MP");
                if (lens != null) restartCamera();
            }
        });
        sharpBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sharpen = !sharpen;
                sharpBtn.setText(sharpen ? "Sharpen: on" : "Sharpen: off");
                requestCamUpdate();
            }
        });
        capBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) { capture(); }
        });
    }

    // =====================================================================================
    // lens discovery
    // =====================================================================================

    private static boolean contains(int[] arr, int v) {
        if (arr == null) return false;
        for (int x : arr) if (x == v) return true;
        return false;
    }

    private static boolean isColorCamera(CameraCharacteristics c) {
        int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (caps == null) return true;
        return contains(caps, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE);
    }

    private Lens makeLens(String id, String parentId, CameraCharacteristics ch, CameraCharacteristics reqCh,
                          boolean detailsKnown) {
        Lens l = new Lens();
        l.id = id;
        l.parentId = parentId;
        l.ch = ch;
        l.reqCh = reqCh;
        String via = parentId == null ? "" : " via " + parentId;
        float[] fl = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        SizeF ps = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        if (detailsKnown && fl != null && fl.length > 0 && ps != null) {
            double diag = Math.hypot(ps.getWidth(), ps.getHeight());
            if (diag > 0) {
                l.eqFocal = (float) (fl[0] * 43.27 / diag);
                int eq = Math.round(l.eqFocal);
                String kind = eq < 20 ? "Ultra-wide" : (eq <= 40 ? "Main" : "Telephoto");
                l.label = kind + " " + eq + "mm (id " + id + via + ")";
                return l;
            }
        }
        l.label = "Camera id " + id + via;
        return l;
    }

    private void buildLensList() {
        lenses.clear();
        try {
            String[] ids = manager.getCameraIdList();
            Set<String> listed = new HashSet<>(Arrays.asList(ids));
            for (String id : ids) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != CameraCharacteristics.LENS_FACING_BACK) continue;
                if (!isColorCamera(c)) continue;
                lenses.add(makeLens(id, null, c, c, true));
                for (String pid : c.getPhysicalCameraIds()) {
                    if (listed.contains(pid)) continue;
                    try {
                        CameraCharacteristics pc = manager.getCameraCharacteristics(pid);
                        if (!isColorCamera(pc)) continue;
                        lenses.add(makeLens(pid, id, pc, c, true));
                    } catch (Exception e) {
                        // details for this hidden lens are not exposed; still offer it
                        lenses.add(makeLens(pid, id, c, c, false));
                    }
                }
            }
        } catch (CameraAccessException e) {
            toast("Could not list cameras: " + e.getMessage());
        }
        Collections.sort(lenses, (a, b) -> Float.compare(a.eqFocal, b.eqFocal));
    }

    private void initCameras() {
        manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        buildLensList();
        if (lenses.isEmpty()) {
            hud.setText("No back cameras found.");
            return;
        }
        List<String> labels = new ArrayList<>();
        int def = 0;
        boolean found = false;
        for (int i = 0; i < lenses.size(); i++) {
            Lens l = lenses.get(i);
            labels.add(l.label);
            if (!found && l.direct() && l.eqFocal >= 20f && l.eqFocal <= 40f) {
                def = i;
                found = true;
            }
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        lensSpinner.setAdapter(adapter);
        lensSpinner.setSelection(def, false);
        lensSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                Lens l = lenses.get(pos);
                if (l != lens) selectLens(l);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        selectLens(lenses.get(def));
    }

    private void selectLens(Lens l) {
        lens = l;
        zoom = 1f;
        cx = 0.5f;
        cy = 0.5f;
        ax0 = 0f;
        ay0 = 0f;
        ax1 = 1f;
        ay1 = 1f;
        zoomBar.setProgress(0);
        zoomLabel.setText("1.0x");
        restartCamera();
    }

    // =====================================================================================
    // zoom + gestures (UI thread)
    // =====================================================================================

    private static float clampF(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void clampCenter() {
        float s = 1f / zoom;
        cx = clampF(cx, s / 2f, 1f - s / 2f);
        cy = clampF(cy, s / 2f, 1f - s / 2f);
    }

    private void setZoom(float z) {
        zoom = clampF(z, 1f, MAX_ZOOM);
        clampCenter();
        zoomBar.setProgress(Math.round((float) (1000.0 * Math.log(zoom) / Math.log(MAX_ZOOM))));
        zoomLabel.setText(String.format(Locale.US, zoom < 10f ? "%.1fx" : "%.0fx", zoom));
        updateMatrix();
        requestCamUpdate();
    }

    private void panBy(float dxPx, float dyPx) {
        int vw = tv.getWidth();
        int vh = tv.getHeight();
        if (vw == 0 || vh == 0) return;
        cx -= dxPx / (vw * zoom);
        cy -= dyPx / (vh * zoom);
        clampCenter();
        updateMatrix();
        requestCamUpdate();
    }

    private void requestCamUpdate() {
        camHandler.removeCallbacks(updateRunnable);
        camHandler.post(updateRunnable);
    }

    /** Scales the preview texture to cover whatever the camera crop did not already provide. */
    private void updateMatrix() {
        int vw = tv.getWidth();
        int vh = tv.getHeight();
        if (vw == 0 || vh == 0) return;
        float s = 1f / zoom;
        float aw = ax1 - ax0;
        float ah = ay1 - ay0;
        if (aw <= 0f || ah <= 0f) {
            aw = 1f;
            ah = 1f;
            ax0 = 0f;
            ay0 = 0f;
            ax1 = 1f;
            ay1 = 1f;
        }
        float g = Math.max(1f, aw / s);
        float acx = (ax0 + ax1) / 2f;
        float acy = (ay0 + ay1) / 2f;
        float lim = (1f - 1f / g) / 2f;
        float ox = clampF((cx - acx) / aw, -lim, lim);
        float oy = clampF((cy - acy) / ah, -lim, lim);
        Matrix m = new Matrix();
        m.postScale(g, g, vw / 2f, vh / 2f);
        m.postTranslate(-ox * vw * g, -oy * vh * g);
        tv.setTransform(m);
        curG = g;
        curOx = ox;
        curOy = oy;

        Lens l = lens;
        Size ps = previewSize;
        String text = String.format(Locale.US, "%.1fx  =  camera %.1fx + digital %.1fx\n%s%s",
                zoom, 1f / aw, g,
                l == null ? "" : l.label,
                ps == null ? "" : "\npreview " + ps.getWidth() + "x" + ps.getHeight());
        if (!text.equals(lastHud)) {
            lastHud = text;
            hud.setText(text);
        }
    }

    // =====================================================================================
    // camera control
    // =====================================================================================

    private void toast(final String msg) {
        ui.post(new Runnable() {
            @Override
            public void run() { Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show(); }
        });
    }

    private static float[] sensorToDisplay(float x, float y, int o) {
        switch (o) {
            case 90:
                return new float[]{1f - y, x};
            case 180:
                return new float[]{1f - x, 1f - y};
            case 270:
                return new float[]{y, 1f - x};
            default:
                return new float[]{x, y};
        }
    }

    private static float[] displayToSensor(float x, float y, int o) {
        switch (o) {
            case 90:
                return new float[]{y, 1f - x};
            case 180:
                return new float[]{1f - x, 1f - y};
            case 270:
                return new float[]{1f - y, x};
            default:
                return new float[]{x, y};
        }
    }

    private Size pickSize(CameraCharacteristics c, int targetMp) {
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map == null ? null : map.getOutputSizes(SurfaceTexture.class);
        if (sizes == null || sizes.length == 0) return new Size(1280, 720);
        Rect active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        double want = active != null ? (double) active.width() / active.height() : 4.0 / 3.0;
        long cap = targetMp * 1000000L;
        Size best = null;
        for (Size s : sizes) {
            double diff = Math.abs((double) s.getWidth() / s.getHeight() - want);
            long px = (long) s.getWidth() * s.getHeight();
            if (diff < 0.03 && px <= cap) {
                if (best == null || px > (long) best.getWidth() * best.getHeight()) best = s;
            }
        }
        if (best != null) return best;
        // nothing matched under the cap: take the smallest size with the right shape
        for (Size s : sizes) {
            double diff = Math.abs((double) s.getWidth() / s.getHeight() - want);
            long px = (long) s.getWidth() * s.getHeight();
            if (diff < 0.03) {
                if (best == null || px < (long) best.getWidth() * best.getHeight()) best = s;
            }
        }
        if (best != null) return best;
        return sizes[sizes.length - 1];
    }

    /** UI thread. (Re)opens the selected lens at the chosen preview resolution. */
    private void restartCamera() {
        final Lens l = lens;
        if (l == null || surfaceTexture == null || manager == null) return;
        final int gen = ++generation;
        final Size size = pickSize(l.ch, RES_MP[resIndex]);
        previewSize = size;
        int o = l.orientation();
        if (o % 180 == 90) {
            tv.setAspectRatio(size.getHeight(), size.getWidth());
        } else {
            tv.setAspectRatio(size.getWidth(), size.getHeight());
        }
        ax0 = 0f;
        ay0 = 0f;
        ax1 = 1f;
        ay1 = 1f;
        camHandler.post(new Runnable() {
            @Override
            public void run() {
                closeInternal();
                camHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() { openInternal(l, size, gen); }
                }, 150);
            }
        });
    }

    private void closeInternal() {
        try {
            if (session != null) session.close();
        } catch (Exception ignored) {
        }
        session = null;
        builder = null;
        try {
            if (camDevice != null) camDevice.close();
        } catch (Exception ignored) {
        }
        camDevice = null;
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
    }

    private void openInternal(final Lens l, final Size size, final int gen) {
        if (gen != generation || surfaceTexture == null) return;
        try {
            manager.openCamera(l.openId(), new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    if (gen != generation) {
                        device.close();
                        return;
                    }
                    camDevice = device;
                    startSession(l, size, gen);
                }

                @Override
                public void onDisconnected(CameraDevice device) {
                    device.close();
                    if (camDevice == device) camDevice = null;
                }

                @Override
                public void onError(CameraDevice device, int error) {
                    device.close();
                    if (camDevice == device) camDevice = null;
                    toast("Camera error " + error + " opening " + l.label);
                }
            }, camHandler);
        } catch (Exception e) {
            toast("Could not open camera: " + e.getMessage());
        }
    }

    private void startSession(final Lens l, final Size size, final int gen) {
        try {
            SurfaceTexture st = surfaceTexture;
            if (st == null || camDevice == null) return;
            st.setDefaultBufferSize(size.getWidth(), size.getHeight());
            previewSurface = new Surface(st);
            OutputConfiguration oc = new OutputConfiguration(previewSurface);
            if (!l.direct()) oc.setPhysicalCameraId(l.id);
            SessionConfiguration sc = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    Collections.singletonList(oc),
                    camExecutor,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            if (gen != generation || camDevice == null) {
                                s.close();
                                return;
                            }
                            session = s;
                            try {
                                builder = camDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                builder.addTarget(previewSurface);
                                applyStaticSettings(l);
                                updateRequest();
                            } catch (Exception e) {
                                toast("Could not start preview: " + e.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (gen != generation) return;
                                    if (resIndex > 0) {
                                        resIndex--;
                                        resBtn.setText("Res: " + RES_MP[resIndex] + "MP");
                                        toast("That resolution didn't work for this lens. Trying a lower one.");
                                        restartCamera();
                                    } else {
                                        toast("This lens could not be started.");
                                    }
                                }
                            });
                        }
                    });
            camDevice.createCaptureSession(sc);
        } catch (Exception e) {
            toast("Could not create camera session: " + e.getMessage());
        }
    }

    /** Settings that do not change while previewing: focus mode and a steady frame rate. */
    private void applyStaticSettings(Lens l) {
        CameraCharacteristics c = l.reqCh;
        int[] afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (contains(afModes, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        }
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        Range<Integer>[] ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges != null) {
            Range<Integer> best = null;
            for (Range<Integer> r : ranges) {
                int hi = r.getUpper();
                if (hi > 30) continue;
                if (best == null
                        || hi > best.getUpper()
                        || (hi == best.getUpper() && r.getLower() > best.getLower())) {
                    best = r;
                }
            }
            if (best != null) builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, best);
        }
    }

    /** Builds and sends the repeating request with the current zoom, pan and sharpen settings. */
    private void updateRequest() {
        final Lens l = lens;
        if (session == null || builder == null || l == null) return;
        try {
            CameraCharacteristics rc = l.reqCh;
            int edgeMode = sharpen ? CaptureRequest.EDGE_MODE_HIGH_QUALITY : CaptureRequest.EDGE_MODE_FAST;
            if (contains(rc.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES), edgeMode)) {
                builder.set(CaptureRequest.EDGE_MODE, edgeMode);
            }
            int nrMode = sharpen ? CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                    : CaptureRequest.NOISE_REDUCTION_MODE_FAST;
            if (contains(rc.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES), nrMode)) {
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, nrMode);
            }

            if (l.direct()) {
                // hardware crop as far as the camera allows; the GPU does the rest
                Rect active = l.ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                Float md = l.ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
                if (active != null) {
                    float maxDz = md == null ? 1f : md;
                    float hwZ = Math.max(1f, Math.min(zoom, maxDz));
                    float hs = 1f / hwZ;
                    float hcx = clampF(cx, hs / 2f, 1f - hs / 2f);
                    float hcy = clampF(cy, hs / 2f, 1f - hs / 2f);
                    int o = l.orientation();
                    float[] p0 = displayToSensor(hcx - hs / 2f, hcy - hs / 2f, o);
                    float[] p1 = displayToSensor(hcx + hs / 2f, hcy + hs / 2f, o);
                    float sx0 = Math.min(p0[0], p1[0]);
                    float sx1 = Math.max(p0[0], p1[0]);
                    float sy0 = Math.min(p0[1], p1[1]);
                    float sy1 = Math.max(p0[1], p1[1]);
                    int aw = active.width();
                    int ah = active.height();
                    Rect r = new Rect(
                            active.left + Math.round(sx0 * aw),
                            active.top + Math.round(sy0 * ah),
                            active.left + Math.round(sx1 * aw),
                            active.top + Math.round(sy1 * ah));
                    builder.set(CaptureRequest.SCALER_CROP_REGION, r);
                }
            }
            session.setRepeatingRequest(builder.build(), captureCallback, camHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            // session is closing or being replaced; ignore
        }
    }

    private final CameraCaptureSession.CaptureCallback captureCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request,
                                               TotalCaptureResult result) {
                    Lens l = lens;
                    if (l == null || !l.direct()) return;
                    Rect cr = result.get(CaptureResult.SCALER_CROP_REGION);
                    Rect active = l.ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                    if (cr == null || active == null || active.width() == 0 || active.height() == 0) return;
                    float nx0 = (cr.left - active.left) / (float) active.width();
                    float nx1 = (cr.right - active.left) / (float) active.width();
                    float ny0 = (cr.top - active.top) / (float) active.height();
                    float ny1 = (cr.bottom - active.top) / (float) active.height();
                    int o = l.orientation();
                    float[] p0 = sensorToDisplay(nx0, ny0, o);
                    float[] p1 = sensorToDisplay(nx1, ny1, o);
                    float nax0 = Math.min(p0[0], p1[0]);
                    float nax1 = Math.max(p0[0], p1[0]);
                    float nay0 = Math.min(p0[1], p1[1]);
                    float nay1 = Math.max(p0[1], p1[1]);
                    if (Math.abs(nax0 - ax0) > 0.0005f || Math.abs(nax1 - ax1) > 0.0005f
                            || Math.abs(nay0 - ay0) > 0.0005f || Math.abs(nay1 - ay1) > 0.0005f) {
                        ax0 = nax0;
                        ax1 = nax1;
                        ay0 = nay0;
                        ay1 = nay1;
                        ui.removeCallbacks(matrixRunnable);
                        ui.post(matrixRunnable);
                    }
                }
            };

    // =====================================================================================
    // capture
    // =====================================================================================

    /** Saves exactly what is on screen, using the sharpest frame the preview stream offers. */
    private void capture() {
        Lens l = lens;
        Size ps = previewSize;
        if (l == null || ps == null || tv == null || !tv.isAvailable()) {
            toast("Camera is not ready yet.");
            return;
        }
        final float g = curG;
        final float ox = curOx;
        final float oy = curOy;
        int w = l.orientation() % 180 == 90 ? ps.getHeight() : ps.getWidth();
        int h = l.orientation() % 180 == 90 ? ps.getWidth() : ps.getHeight();
        float k = Math.min(1f, 3200f / Math.max(w, h));
        int bw = Math.max(2, Math.round(w * k));
        int bh = Math.max(2, Math.round(h * k));

        // read the untransformed frame so the crop below is exact, then restore the zoom matrix
        Matrix saved = new Matrix();
        tv.getTransform(saved);
        tv.setTransform(new Matrix());
        Bitmap full;
        try {
            full = tv.getBitmap(bw, bh);
        } finally {
            tv.setTransform(saved);
        }
        if (full == null) {
            toast("Capture failed.");
            return;
        }
        final Bitmap frame = full;
        new Thread(new Runnable() {
            @Override
            public void run() { saveCrop(frame, g, ox, oy); }
        }).start();
    }

    private void saveCrop(Bitmap full, float g, float ox, float oy) {
        try {
            int fw = full.getWidth();
            int fh = full.getHeight();
            int cw = Math.max(1, Math.min(fw, Math.round(fw / g)));
            int ch = Math.max(1, Math.min(fh, Math.round(fh / g)));
            int left = Math.max(0, Math.min(fw - cw, Math.round(fw * (0.5f + ox) - cw / 2f)));
            int top = Math.max(0, Math.min(fh - ch, Math.round(fh * (0.5f + oy) - ch / 2f)));
            Bitmap crop = Bitmap.createBitmap(full, left, top, cw, ch);
            float up = Math.max(1f, 1080f / cw);
            Bitmap out = up > 1.01f
                    ? Bitmap.createScaledBitmap(crop, Math.round(cw * up), Math.round(ch * up), true)
                    : crop;

            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, "SuperZoom-" + System.currentTimeMillis() + ".jpg");
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SuperZoom");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IllegalStateException("could not create file");
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new IllegalStateException("could not open file");
                out.compress(Bitmap.CompressFormat.JPEG, 95, os);
            }
            toast("Saved to Pictures/SuperZoom");
        } catch (Exception e) {
            toast("Save failed: " + e.getMessage());
        }
    }
}

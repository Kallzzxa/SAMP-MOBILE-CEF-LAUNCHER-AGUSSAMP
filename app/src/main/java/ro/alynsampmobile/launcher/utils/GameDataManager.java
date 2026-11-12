package ro.alynsampmobile.launcher.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import ro.alynsampmobile.launcher.R;


import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.WeakHashMap;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * AgusSamp: in-game data downloader (ported verbatim from ALYN-SA-MP-FIX
 * GameDataManager v17.24, then adapted: the offer dialog auto-downloads
 * the GPU-matching package instead of asking the user to pick ADRENO /
 * MALI / POWERVR — manual pick survives as "Pilih manual").
 *
 * ALYN-SA-MP-FIX: in-game data downloader (ported from SA-MP Mobile Launcher pattern).
 *
 * Downloads ONE complete data package (files.zip via MediaFire, URL built in)
 * and extracts it to the game directory. Completion is version-pinned
 * via "data_version" + SAMP/.data_ready marker + sentinel file.
 */
public final class GameDataManager {
    private static final String TAG = "GameDataManager";
    private static final String FALLBACK_DATA_PAGE = "https://www.mediafire.com/file/rhkel93l3frrgtn/files.zip/file";
    private static final String FALLBACK_DATA_VERSION = "1";

    // ALYN-SA-MP-FIX v17.17: GPU-specific data packages. The texture format
    // MUST match the GPU (Adreno decodes DXT in hardware, Mali uses ETC,
    // PowerVR uses PVR); a mismatched package forces slow software decoding
    // or broken textures, which also costs FPS.
    private static final String DATA_PAGE_DXT = "https://www.mediafire.com/file/x3jwmyoaf46o2ju/Data_Alyn_-_Adreno.zip/file";
    private static final String DATA_PAGE_ETC = "https://www.mediafire.com/file/2tg0ukkncro50c2/Data_Alyn_-_Mali.zip/file";
    private static final String DATA_PAGE_PVR = "https://www.mediafire.com/file/i6v15yacx7ql4vh/Data_Alyn_P.VR.zip/file";

    // Launcher blue on dark (matches md_theme_dark_primary + @color/grey).
    private static final int BLUE = Color.parseColor("#9ECAFF");

    private static final String PREFS = "alyn_game_data";
    private static final String KEY_DATA_VERSION = "data_version";
    private static final String MARKER = "SAMP/.data_ready";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final WeakHashMap<Activity, Boolean> ACTIVE_FLOWS = new WeakHashMap<>();

    public interface ReadyCallback { void onReady(); }

    private static final class DataInfo {
        String url;
        String version;
        long size;
        String sentinel;
        String gpu;
        String format;
    }

    private GameDataManager() {}

    public static void checkAndPrepare(Activity activity, ReadyCallback callback) {
        synchronized (ACTIVE_FLOWS) {
            if (ACTIVE_FLOWS.containsKey(activity)) return;
            ACTIVE_FLOWS.put(activity, Boolean.TRUE);
        }

        IO.execute(() -> {
            try {
                // ALYN-SA-MP-FIX v17.17: pick the data package for this GPU.
                // Version is per-GPU, so existing installs are offered the
                // matching package exactly once (and a GPU change later
                // re-downloads the right one).
                Utils.GPUType gpuType = detectGpuType();
                if (gpuType == null) gpuType = Utils.GPUType.NONE;
                DataInfo info = new DataInfo();
                switch (gpuType) {
                    case DXT:
                        info.url = DATA_PAGE_DXT;
                        info.version = "2-dxt";
                        info.gpu = "Adreno";
                        info.format = "DXT";
                        break;
                    case PVR:
                        info.url = DATA_PAGE_PVR;
                        info.version = "2-pvr";
                        info.gpu = "PowerVR";
                        info.format = "PVR";
                        break;
                    case ETC:
                        info.url = DATA_PAGE_ETC;
                        info.version = "2-etc";
                        info.gpu = "Mali";
                        info.format = "ETC";
                        break;
                    default:
                        info.url = FALLBACK_DATA_PAGE;
                        info.version = "2-generic";
                        info.gpu = "Umum";
                        info.format = "Umum";
                        break;
                }
                info.size = 0;
                info.sentinel = "SAMP/SAMP.ide";

                String installed = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                        .getString(KEY_DATA_VERSION, "");
                if (info.version.equals(installed) && isInstalled(activity, info)) {
                    MAIN.post(() -> {
                        endFlow(activity);
                        if (!activity.isFinishing()) callback.onReady();
                    });
                    return;
                }
                final DataInfo fi = info;
                MAIN.post(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        endFlow(activity);
                        return;
                    }
                    showDownloadOffer(activity, fi, callback);
                });
            } catch (Exception e) {
                Log.e(TAG, "Data check failed", e);
                MAIN.post(() -> {
                    endFlow(activity);
                    showNetworkError(activity, e.getMessage(), callback);
                });
            }
        });
    }

    private static void endFlow(Activity activity) {
        synchronized (ACTIVE_FLOWS) {
            ACTIVE_FLOWS.remove(activity);
        }
    }

    // Detect the GPU texture format, reusing the same extension logic as
    // SplashActivity (PVRTC -> PVR, DXT/S3TC/ATC -> DXT, else ETC). If
    // SplashActivity already detected it, reuse that; otherwise detect here
    // with a 1x1 EGL pbuffer (no UI needed) so the downloader never depends
    // on splash ordering. Never returns null (NONE on failure -> generic).
    private static Utils.GPUType detectGpuType() {
        try {
            Utils.GPUType existing = Utils.GPU_TYPE;
            if (existing != null && existing != Utils.GPUType.NONE) return existing;
        } catch (Exception ignored) {}
        Utils.GPUType type = Utils.GPUType.ETC;
        EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (display == EGL14.EGL_NO_DISPLAY) return Utils.GPUType.NONE;
            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return Utils.GPUType.NONE;
            int[] configAttribs = {
                    EGL14.EGL_RENDERABLE_TYPE, 4,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)
                    || numConfigs[0] <= 0) return Utils.GPUType.NONE;
            int[] surfaceAttribs = { EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE };
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfaceAttribs, 0);
            if (surface == EGL14.EGL_NO_SURFACE) return Utils.GPUType.NONE;
            int[] contextAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
            if (context == EGL14.EGL_NO_CONTEXT) return Utils.GPUType.NONE;
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return Utils.GPUType.NONE;
            String extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS);
            if (extensions == null) extensions = "";
            if (extensions.contains("GL_IMG_texture_compression_pvrtc")) {
                type = Utils.GPUType.PVR;
            } else if (extensions.contains("GL_EXT_texture_compression_dxt1")
                    || extensions.contains("GL_EXT_texture_compression_s3tc")
                    || extensions.contains("GL_AMD_compressed_ATC_texture")) {
                type = Utils.GPUType.DXT;
            } else {
                type = Utils.GPUType.ETC;
            }
            try { Utils.GPU_TYPE = type; } catch (Exception ignored) {}
            return type;
        } catch (Exception e) {
            Log.w(TAG, "GPU detection failed, using generic package", e);
            return Utils.GPUType.NONE;
        } finally {
            try {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
                    EGL14.eglTerminate(display);
                }
            } catch (Exception ignored) {}
        }
    }

    private static boolean isInstalled(Activity a, DataInfo info) {
        try {
            File root = a.getExternalFilesDir(null);
            if (root == null) return false;
            // Sentinel is the source of truth: if game data files exist, data is installed
            // (marker may be missing if data came from old installer or manual copy).
            boolean sentinelOk = true;
            if (info.sentinel != null && !info.sentinel.isEmpty()) {
                sentinelOk = new File(root, info.sentinel).exists();
            } else {
                String[] list = root.list();
                sentinelOk = list != null && list.length > 0;
            }
            if (sentinelOk) {
                // adopt: write marker so future checks are fast
                markInstalled(a, info);
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static void markInstalled(Activity a, DataInfo info) {
        try {
            File root = a.getExternalFilesDir(null);
            if (root == null) return;
            File marker = new File(root, MARKER);
            File parent = marker.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (OutputStream os = new FileOutputStream(marker)) {
                os.write(info.version.getBytes("UTF-8"));
            }
            a.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                    .edit().putString(KEY_DATA_VERSION, info.version).apply();
        } catch (Exception e) {
            Log.e(TAG, "markInstalled failed", e);
        }
    }


    private static String getText(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    private static String resolveDownloadUrl(String url) throws Exception {
        if (url.contains("mediafire.com/file/")) {
            String html = getText(url);
            Matcher m = Pattern.compile("https://download\\d*\\.mediafire\\.com/[^\\s\"'<>]+").matcher(html);
            if (m.find()) return m.group(0);
            throw new Exception("Tidak bisa menemukan link download langsung dari MediaFire.");
        }
        return url;
    }

    // ---- UI (v17.17: modern blue on dark, matching the launcher theme) ----

    private static int dp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }




    // Tint dialog buttons blue to match the launcher's Material dark primary.
    // Must be called AFTER show() (buttons only exist then).
    private static void styleDialogButtons(AlertDialog d) {
        try {
            if (d.getButton(AlertDialog.BUTTON_POSITIVE) != null)
                d.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(BLUE);
            if (d.getButton(AlertDialog.BUTTON_NEGATIVE) != null)
                d.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(BLUE);
            if (d.getButton(AlertDialog.BUTTON_NEUTRAL) != null)
                d.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(BLUE);
        } catch (Exception ignored) {}
    }



    // v17.18: live status inside the checking dialog (e.g. show the
    // detected GPU as soon as detection finishes).

    // Rounded blue chip (e.g. "Mali  •  ETC") — reads as a modern badge,
    // unlike plain colored text.

    private static void showNetworkError(Activity a, String detail, ReadyCallback cb) {
        if (a.isFinishing()) return;
        AlertDialog d = new AlertDialog.Builder(a).setTitle("Gagal memuat data game")
                .setMessage("Tidak bisa memeriksa data game. Periksa internet lalu coba lagi.\n\n" + (detail == null ? "" : detail))
                .setCancelable(false)
                .setPositiveButton("COBA LAGI", (x, w) -> checkAndPrepare(a, cb))
                .setNegativeButton("KELUAR", (x, w) -> a.finishAffinity())
                .show();
        styleDialogButtons(d);
    }

    // AgusSamp: AUTO-download sesuai GPU — user tidak perlu pilih ADRENO /
    // MALI / POWERVR lagi. GPU sudah terdeteksi di SplashActivity (GLSurfaceView,
    // fallback EGL pbuffer di detectGpuType() di atas), jadi dialog ini cuma
    // minta konfirmasi satu tombol DOWNLOAD untuk paket yang sudah cocok.
    // "Pilih manual" tetap ada sebagai jalan keluar kalau deteksi salah.
    private static void showDownloadOffer(Activity a, DataInfo info, ReadyCallback cb) {
        if (a.isFinishing() || a.isDestroyed()) {
            endFlow(a);
            return;
        }

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(a, 20), dp(a, 18), dp(a, 20), dp(a, 18));

        // Background Dialog Biru (Sesuai foto)
        GradientDrawable dialogBg = new GradientDrawable();
        dialogBg.setColor(Color.parseColor("#1C3068")); // Warna biru container dialog
        dialogBg.setCornerRadius(dp(a, 12));
        root.setBackground(dialogBg);

        // Judul Utama "Game Files Selection"
        TextView title = new TextView(a);
        title.setText("Game Files Selection");
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.WHITE);
        title.setGravity(android.view.Gravity.CENTER);

        // Garis Pembatas Biru Terang
        View divider = new View(a);
        divider.setBackgroundColor(Color.parseColor("#4B6EB5"));
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                dp(a, 170), dp(a, 2));
        divParams.topMargin = dp(a, 6);
        divParams.bottomMargin = dp(a, 10);
        divParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;

        // Teks Deteksi GPU Otomatis
        TextView gpuInfoText = new TextView(a);
        gpuInfoText.setText("GPU kamu saat ini: " + info.gpu + " (" + info.format + ")");
        gpuInfoText.setTextSize(12);
        gpuInfoText.setTypeface(null, Typeface.BOLD);
        gpuInfoText.setTextColor(Color.parseColor("#90CAF9")); // Warna biru terang
        gpuInfoText.setGravity(android.view.Gravity.CENTER);

        LinearLayout.LayoutParams gpuTextParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gpuTextParams.bottomMargin = dp(a, 8);

        // Penjelasan auto-download
        TextView noteText = new TextView(a);
        noteText.setText("Data game belum terpasang.\nPaket " + info.format + " akan di-download otomatis sesuai GPU kamu.");
        noteText.setTextSize(12);
        noteText.setTextColor(Color.WHITE);
        noteText.setGravity(android.view.Gravity.CENTER);

        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        noteParams.bottomMargin = dp(a, 16);

        AlertDialog[] dialogHolder = new AlertDialog[1];

        // Tombol utama: DOWNLOAD OTOMATIS (satu tombol, full width)
        TextView downloadBtn = new TextView(a);
        downloadBtn.setText("DOWNLOAD OTOMATIS");
        downloadBtn.setTextSize(13);
        downloadBtn.setTypeface(null, Typeface.BOLD);
        downloadBtn.setGravity(android.view.Gravity.CENTER);
        downloadBtn.setPadding(dp(a, 4), dp(a, 12), dp(a, 4), dp(a, 12));
        GradientDrawable downloadBg = new GradientDrawable();
        downloadBg.setCornerRadius(dp(a, 20));
        downloadBg.setColor(Color.parseColor("#90CAF9"));
        downloadBtn.setTextColor(Color.parseColor("#0A192F"));
        downloadBtn.setBackground(downloadBg);
        downloadBtn.setOnClickListener(v -> {
            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
            startInstall(a, info, cb);
        });

        LinearLayout.LayoutParams downloadParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        downloadParams.bottomMargin = dp(a, 10);

        // Baris kecil: Pilih manual • Keluar
        LinearLayout secondaryRow = new LinearLayout(a);
        secondaryRow.setOrientation(LinearLayout.HORIZONTAL);
        secondaryRow.setGravity(android.view.Gravity.CENTER);

        TextView manualBtn = new TextView(a);
        manualBtn.setText("Pilih manual");
        manualBtn.setTextSize(11);
        manualBtn.setTextColor(Color.parseColor("#90CAF9"));
        manualBtn.setPadding(dp(a, 8), dp(a, 4), dp(a, 8), dp(a, 4));
        manualBtn.setOnClickListener(v -> {
            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
            showManualOffer(a, info, cb);
        });

        TextView sep = new TextView(a);
        sep.setText("\u2022");
        sep.setTextSize(11);
        sep.setTextColor(Color.parseColor("#4B6EB5"));
        sep.setPadding(dp(a, 4), dp(a, 4), dp(a, 4), dp(a, 4));

        TextView exitBtn = new TextView(a);
        exitBtn.setText("Keluar");
        exitBtn.setTextSize(11);
        exitBtn.setTextColor(Color.parseColor("#90CAF9"));
        exitBtn.setPadding(dp(a, 8), dp(a, 4), dp(a, 8), dp(a, 4));
        exitBtn.setOnClickListener(v -> {
            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
            endFlow(a);
            a.finishAffinity();
        });

        secondaryRow.addView(manualBtn);
        secondaryRow.addView(sep);
        secondaryRow.addView(exitBtn);

        root.addView(title);
        root.addView(divider, divParams);
        root.addView(gpuInfoText, gpuTextParams);
        root.addView(noteText, noteParams);
        root.addView(downloadBtn, downloadParams);
        root.addView(secondaryRow);

        AlertDialog d = new AlertDialog.Builder(a)
                .setView(root)
                .setCancelable(false)
                .create();

        dialogHolder[0] = d;

        // Buat background window bawaan dialog transparan agar sudut rounded terlihat rapi
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        d.show();
    }

    // Pilihan manual (dulu dialog utama v17.17-v17.24): tiga tombol GPU.
    // Hanya dipanggil dari "Pilih manual" di dialog auto di atas.
    private static void showManualOffer(Activity a, DataInfo info, ReadyCallback cb) {
        if (a.isFinishing() || a.isDestroyed()) {
            endFlow(a);
            return;
        }

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(a, 20), dp(a, 18), dp(a, 20), dp(a, 18));

        // Background Dialog Biru (Sesuai foto)
        GradientDrawable dialogBg = new GradientDrawable();
        dialogBg.setColor(Color.parseColor("#1C3068")); // Warna biru container dialog
        dialogBg.setCornerRadius(dp(a, 12));
        root.setBackground(dialogBg);

        // Judul Utama "Game Files Selection"
        TextView title = new TextView(a);
        title.setText("Game Files Selection");
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.WHITE);
        title.setGravity(android.view.Gravity.CENTER);

        // Garis Pembatas Biru Terang
        View divider = new View(a);
        divider.setBackgroundColor(Color.parseColor("#4B6EB5"));
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                dp(a, 170), dp(a, 2));
        divParams.topMargin = dp(a, 6);
        divParams.bottomMargin = dp(a, 10);
        divParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;

        // Teks Deteksi GPU Otomatis
        TextView gpuInfoText = new TextView(a);
        gpuInfoText.setText("GPU kamu saat ini: " + info.gpu + " (" + info.format + ")");
        gpuInfoText.setTextSize(12);
        gpuInfoText.setTypeface(null, Typeface.BOLD);
        gpuInfoText.setTextColor(Color.parseColor("#90CAF9")); // Warna biru terang
        gpuInfoText.setGravity(android.view.Gravity.CENTER);

        LinearLayout.LayoutParams gpuTextParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gpuTextParams.bottomMargin = dp(a, 16);

        // Container Tombol Pilihan GPU (Horizontal)
        LinearLayout buttonsRow = new LinearLayout(a);
        buttonsRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonsRow.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

        AlertDialog[] dialogHolder = new AlertDialog[1];

        Utils.GPUType[] types = { Utils.GPUType.DXT, Utils.GPUType.ETC, Utils.GPUType.PVR };
        String[] labels = { "ADRENO", "MALI", "POWERVR" };

        for (int i = 0; i < types.length; i++) {
            final Utils.GPUType type = types[i];
            final DataInfo choiceInfo = createDataInfo(type);

            TextView btn = new TextView(a);
            btn.setText(labels[i]);
            btn.setTextSize(11);
            btn.setTypeface(null, Typeface.BOLD);
            btn.setGravity(android.view.Gravity.CENTER);
            btn.setPadding(dp(a, 4), dp(a, 10), dp(a, 4), dp(a, 10));

            // Style Tombol Rounded Putih/Biru Muda
            GradientDrawable btnBg = new GradientDrawable();
            btnBg.setCornerRadius(dp(a, 20));
            btnBg.setColor(Color.parseColor("#90CAF9"));
            btn.setTextColor(Color.parseColor("#0A192F"));
            btn.setBackground(btnBg);

            btn.setOnClickListener(v -> {
                if (dialogHolder[0] != null) dialogHolder[0].dismiss();
                startInstall(a, choiceInfo, cb);
            });

            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
            if (i > 0) btnParams.leftMargin = dp(a, 8);
            buttonsRow.addView(btn, btnParams);
        }

        root.addView(title);
        root.addView(divider, divParams);
        root.addView(gpuInfoText, gpuTextParams);
        root.addView(buttonsRow, rowParams);

        AlertDialog d = new AlertDialog.Builder(a)
                .setView(root)
                .setCancelable(false)
                .create();

        dialogHolder[0] = d;

        // Buat background window bawaan dialog transparan agar sudut rounded terlihat rapi
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        d.show();
    }

    // v17.24: build the data package for a manually picked GPU (the offer
    // dialog lets the user override the auto-detected GPU). Same mapping
    // as checkAndPrepare.
    private static DataInfo createDataInfo(Utils.GPUType type) {
        DataInfo info = new DataInfo();
        switch (type) {
            case DXT:
                info.url = DATA_PAGE_DXT;
                info.version = "2-dxt";
                info.gpu = "Adreno";
                info.format = "DXT";
                break;
            case PVR:
                info.url = DATA_PAGE_PVR;
                info.version = "2-pvr";
                info.gpu = "PowerVR";
                info.format = "PVR";
                break;
            case ETC:
                info.url = DATA_PAGE_ETC;
                info.version = "2-etc";
                info.gpu = "Mali";
                info.format = "ETC";
                break;
            default:
                info.url = FALLBACK_DATA_PAGE;
                info.version = "2-generic";
                info.gpu = "Umum";
                info.format = "Umum";
                break;
        }
        info.size = 0;
        info.sentinel = "SAMP/SAMP.ide";
        return info;
    }

    private static final class ProgressUi {
        Activity activity;
        TextView phase, detail, bigPct;
        ProgressBar bar;
    }

    // Use activity_update.xml as the activity content so the splash layout
    // is replaced instead of remaining visible underneath a progress dialog.
    private static ProgressUi showProgress(Activity a, DataInfo info) {
        View root = LayoutInflater.from(a).inflate(R.layout.activity_update, null);
        TextView phase = root.findViewById(R.id.update_state);
        TextView detail = root.findViewById(R.id.size);
        TextView bigPct = root.findViewById(R.id.progress);
        TextView filename = root.findViewById(R.id.filename);
        ProgressBar bar = root.findViewById(R.id.progressBar);
        phase.setText("Menyiapkan...");
        bigPct.setText("0%");
        filename.setText("");
        detail.setText("");
        bar.setIndeterminate(false);
        bar.setMax(1000);
        bar.setProgress(0);
        a.setContentView(root);
        ProgressUi ui = new ProgressUi();
        ui.activity = a; ui.phase = phase; ui.detail = detail; ui.bar = bar; ui.bigPct = bigPct;
        return ui;
    }

    private static void postPhase(ProgressUi ui, String phase, String detail) {
        MAIN.post(() -> {
            if (!ui.activity.isFinishing() && !ui.activity.isDestroyed()) {
                ui.phase.setText(phase);
                ui.detail.setText(detail);
            }
        });
    }

    private static void startInstall(Activity a, DataInfo info, ReadyCallback cb) {
        if (a.isFinishing() || a.isDestroyed()) {
            endFlow(a);
            return;
        }
        ProgressUi ui = showProgress(a, info);
        IO.execute(() -> {
            File zipFile = null;
            try {
                postPhase(ui, "Mencari link download...", "");
                String directUrl = resolveDownloadUrl(info.url);
                Log.i(TAG, "direct url resolved");

                File cacheDir = a.getExternalCacheDir();
                if (cacheDir == null) cacheDir = a.getCacheDir();
                zipFile = new File(cacheDir, "game_data_" + info.version + ".zip");
                if (zipFile.exists()) zipFile.delete();

                downloadWithProgress(directUrl, zipFile, info.size, ui);
                if (info.size > 0 && zipFile.length() != info.size)
                    throw new Exception("Ukuran file tidak sesuai, download mungkin corrupt. Coba lagi.");

                postPhase(ui, "Mengekstrak data...", "");
                extractWithProgress(a, zipFile, ui);

                File root = a.getExternalFilesDir(null);
                if (root == null) throw new Exception("Penyimpanan tidak tersedia.");
                if (info.sentinel != null && !info.sentinel.isEmpty()
                        && !new File(root, info.sentinel).exists())
                    throw new Exception("Data tidak lengkap setelah ekstrak (sentinel hilang).");

                markInstalled(a, info);
                // v17.18: remove the mis-extracted nesting left by v17.17
                // (data landed under ro.alyn_sampmobile.game/files/ instead
                // of the game root). Only deleted when it matches that
                // exact pattern, so no legitimate file is ever touched.
                try {
                    File nested = new File(root, "ro.alyn_sampmobile.game");
                    if (new File(nested, "files/SAMP/SAMP.ide").exists()) {
                        deleteRecursive(nested);
                        Log.i(TAG, "removed mis-extracted nested dir");
                    }
                } catch (Exception ignored) {}
                try { if (zipFile.exists()) zipFile.delete(); } catch (Exception ignored) {}

                MAIN.post(() -> {
                    endFlow(a);
                    if (ui.activity.isFinishing() || ui.activity.isDestroyed()) return;
                    Toast.makeText(a, "Game data siap dimainkan", Toast.LENGTH_SHORT).show();
                    if (!a.isFinishing()) cb.onReady();
                });
            } catch (Exception e) {
                Log.e(TAG, "Install failed", e);
                final File zf = zipFile;
                MAIN.post(() -> {
                    if (a.isFinishing()) return;
                    AlertDialog d = new AlertDialog.Builder(a).setTitle("Download gagal")
                            .setMessage(e.getMessage() == null ? "Silakan coba lagi." : e.getMessage())
                            .setCancelable(false)
                            .setPositiveButton("COBA LAGI", (x, w) -> startInstall(a, info, cb))
                            .setNegativeButton("KELUAR", (x, w) -> a.finishAffinity())
                            .show();
                    styleDialogButtons(d);
                    try { if (zf != null && zf.exists()) zf.delete(); } catch (Exception ignored) {}
                });
            }
        });
    }

    private static void downloadWithProgress(String url, File out, long expectedSize, ProgressUi ui) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "ALYN-SA-MP-FIX");
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        if (code / 100 != 2) throw new Exception("Server menolak download (HTTP " + code + ").");
        long total = c.getContentLengthLong();
        if (total <= 0) total = expectedSize;

        final long totalF = total;
        postPhase(ui, "Mengunduh game data...", totalF > 0 ? "0 / " + fmtBytes(totalF) : "");
        long lastUi = 0;
        long startNs = System.nanoTime();
        byte[] buf = new byte[128 * 1024];
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             OutputStream fos = new BufferedOutputStream(new FileOutputStream(out))) {
            long done = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                fos.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (now - lastUi > 300) {
                    lastUi = now;
                    long elapsed = Math.max(1, (System.nanoTime() - startNs) / 1_000_000_000L);
                    long speed = done / elapsed;
                    final long d = done;
                    final int permille = totalF > 0 ? (int) Math.min(1000, (d * 1000) / totalF) : 0;
                    MAIN.post(() -> {
                        if (ui.activity.isFinishing() || ui.activity.isDestroyed()) return;
                        ui.bar.setProgress(permille);
                        ui.bigPct.setText((permille / 10) + "%");
                        String eta = "";
                        if (speed > 0 && totalF > 0 && d < totalF)
                            eta = "  \u2022  sisa ~" + fmtDuration((totalF - d) / speed);
                        ui.detail.setText(fmtBytes(d) + (totalF > 0 ? " / " + fmtBytes(totalF) : "")
                                + "  " + fmtBytes(speed) + "/dtk" + eta);
                    });
                }
            }
        } finally {
            c.disconnect();
        }
        MAIN.post(() -> {
            if (!ui.activity.isFinishing() && !ui.activity.isDestroyed()) {
                ui.bar.setProgress(1000);
                ui.bigPct.setText("100%");
                ui.detail.setText("Download selesai");
            }
        });
    }

    private static void extractWithProgress(Activity a, File zipFile, ProgressUi ui) throws Exception {
        File root = a.getExternalFilesDir(null);
        if (root == null) throw new Exception("Penyimpanan tidak tersedia.");
        final String rootCanon = root.getCanonicalPath() + File.separator;
        try (ZipFile zip = new ZipFile(zipFile)) {
            int totalEntries = zip.size();
            if (totalEntries <= 0) throw new Exception("File zip kosong / rusak.");
            ArrayList<String> names = new ArrayList<>(totalEntries);
            Enumeration<? extends ZipEntry> scan = zip.entries();
            while (scan.hasMoreElements()) names.add(scan.nextElement().getName());
            String stripPrefix = detectGameRoot(names, "SAMP/SAMP.ide");
            if (stripPrefix == null) stripPrefix = detectStripPrefix(names);
            if (!stripPrefix.isEmpty()) Log.i(TAG, "stripping zip prefix: " + stripPrefix);

            postPhase(ui, "Mengekstrak data...", "0 / " + totalEntries + " file");
            Enumeration<? extends ZipEntry> en = zip.entries();
            int done = 0;
            long lastUi = 0;
            byte[] buf = new byte[64 * 1024];
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (!stripPrefix.isEmpty()) {
                    // Entries outside the detected game root (e.g. stray
                    // files at the zip top level) are skipped, not dumped
                    // into the game dir with their full nested path.
                    if (!name.startsWith(stripPrefix)) { done++; continue; }
                    name = name.substring(stripPrefix.length());
                }
                if (name.isEmpty()) { done++; continue; }
                File out = new File(root, name);
                // zip-slip protection
                if (!out.getCanonicalPath().startsWith(rootCanon))
                    throw new Exception("Zip entry tidak aman: " + e.getName());
                if (e.isDirectory()) {
                    out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (InputStream in = new BufferedInputStream(zip.getInputStream(e));
                         OutputStream fos = new BufferedOutputStream(new FileOutputStream(out))) {
                        int n;
                        while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
                    }
                }
                done++;
                long now = System.currentTimeMillis();
                if (now - lastUi > 500) {
                    lastUi = now;
                    final int d = done;
                    final int permille = (int) Math.min(1000, (d * 1000L) / totalEntries);
                    MAIN.post(() -> {
                        if (ui.activity.isFinishing() || ui.activity.isDestroyed()) return;
                        ui.bar.setProgress(permille);
                        ui.bigPct.setText((permille / 10) + "%");
                        ui.detail.setText(d + " / " + totalEntries + " file");
                    });
                }
            }
        }
    }

    // v17.18: derive the extraction root from the sentinel's own path
    // in the zip. GPU packages are backups of someone's files dir wrapped
    // in extra folders (e.g. "Data Alyn - Mali/ro.alyn_sampmobile.game/
    // files/SAMP/SAMP.ide"): stripping only the single common prefix left
    // the data nested and the sentinel check failed even though the data
    // was inside the zip. The sentinel's parent IS the game root by
    // definition, so strip that whole prefix. Shallowest match wins (a
    // backup copy of the sentinel deeper in the tree must not redirect
    // the root). Returns null when the zip has no sentinel (caller falls
    // back to the old common-prefix rule).
    private static String detectGameRoot(ArrayList<String> names, String sentinel) {
        if (sentinel == null || sentinel.isEmpty()) return null;
        String best = null;
        int bestDepth = Integer.MAX_VALUE;
        for (String n : names) {
            if (n.length() < sentinel.length()) continue;
            int at = n.length() - sentinel.length();
            // Match must sit at a path boundary (root, or right after '/').
            if (at > 0 && n.charAt(at - 1) != '/') continue;
            if (!n.regionMatches(true, at, sentinel, 0, sentinel.length())) continue;
            String root = n.substring(0, at);
            int depth = 0;
            for (int i = 0; i < root.length(); i++) if (root.charAt(i) == '/') depth++;
            if (depth < bestDepth) { bestDepth = depth; best = root; }
        }
        return best;
    }

    private static String detectStripPrefix(ArrayList<String> names) {
        if (names.isEmpty()) return "";
        String first = names.get(0);
        int slash = first.indexOf('/');
        if (slash <= 0) return "";
        String prefix = first.substring(0, slash + 1);
        for (String n : names) {
            if (!n.startsWith(prefix)) return "";
        }
        return prefix;
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }

    private static String fmtDuration(long seconds) {
        if (seconds < 60) return seconds + " dtk";
        long m = seconds / 60, s = seconds % 60;
        if (m < 60) return m + " mnt " + s + " dtk";
        return (m / 60) + " jam " + (m % 60) + " mnt";
    }

    private static String fmtBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format("%.1f MB", b / (1024.0 * 1024));
        return String.format("%.2f GB", b / (1024.0 * 1024 * 1024));
    }
}

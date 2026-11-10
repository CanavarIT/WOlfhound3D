package com.wolfhound.canavar;

import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * WOlfhound3D main activity.
 *
 * Game data (vswap, maphead, gamemaps, audiohed, audiot, vgagraph, vgahead,
 * vgadict with the extension .wl6 / .sod / .wl3 / .wl1 / .sdm) can come from
 * two places; the first one that contains vswap.* wins:
 *
 *   1. Android/data/com.wolfhound.canavar/files/   - copied there by the user
 *   2. assets/ inside the APK - copied by the app into its private storage
 *      (getFilesDir()) on start, so no manual copying is needed.
 *
 * The native side (cpp/main.cpp) uses exactly the same rule to pick the folder.
 */
public class MainActivity extends WolfhoundSDLActivity {

    /** Implemented natively (see cpp/main.cpp): presses/releases an engine key. */
    public static native void nativeKey(int keysym, boolean down);

    /** Right-side swipe: horizontal turn amount (positive = turn right). */
    public static native void nativeLook(int dx);

    /** Stick left/right while playing: -1 strafe left, 0 none, +1 strafe right. */
    public static native void nativeStrafe(int dir);

    /** True while a level is being played (false in menus / between levels). */
    public static native boolean nativeInGameplay();

    private static final String TAG = "WOlfhound3D";
    private static final String[] DATA_EXTS = { "wl6", "sod", "wl3", "wl1", "sdm" };
    private static final String STAMP_FILE = ".assets_stamp";

    /** Folder with the game data, or null if none was found. */
    private File activeDataDir;

    private TouchControls controls;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Must happen BEFORE the engine starts (super.onCreate starts SDL).
        prepareGameData();

        super.onCreate(savedInstanceState);

        hideSystemBars();

        if (activeDataDir == null) {
            showMissingDataDialog();
        } else {
            // On-screen controls on top of the SDL surface.
            controls = new TouchControls(this, new TouchControls.KeySink() {
                @Override public void key(int keysym, boolean down) {
                    try { nativeKey(keysym, down); } catch (UnsatisfiedLinkError e) { warnNative(e); }
                }
                @Override public void look(int dx) {
                    try { nativeLook(dx); } catch (UnsatisfiedLinkError e) { warnNative(e); }
                }
                @Override public void strafe(int dir) {
                    try { nativeStrafe(dir); } catch (UnsatisfiedLinkError e) { warnNative(e); }
                }
                @Override public boolean inGameplay() {
                    try { return nativeInGameplay(); } catch (UnsatisfiedLinkError e) { warnNative(e); return false; }
                }
            });
            addContentView(controls, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    // ------------------------------------------------------------------
    // Status bar: hidden during the game, shown only while the user
    // swipes down from the top edge (transient, hides again by itself).
    // ------------------------------------------------------------------

    private boolean nativeWarned;

    /** The native library is older than the Java code: log once instead of crashing. */
    private void warnNative(UnsatisfiedLinkError e) {
        if (nativeWarned) return;
        nativeWarned = true;
        Log.e(TAG, "Native library is out of date - rebuild it (Clean Project)", e);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        try {
            Window w = getWindow();
            if (w == null) return;

            // draw under the camera cutout as well
            if (Build.VERSION.SDK_INT >= 28) {
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                w.setAttributes(lp);
            }

            if (Build.VERSION.SDK_INT >= 30) {
                w.setDecorFitsSystemWindows(false);
                android.view.WindowInsetsController c = w.getInsetsController();
                if (c != null) {
                    c.hide(android.view.WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            }
        } catch (Exception e) {
            Log.w(TAG, "hideSystemBars failed", e);
        }
    }

    @Override
    protected void onPause() {
        // never leave a key "stuck" when the app goes to background
        if (controls != null) controls.releaseAll();
        super.onPause();
    }

    // ------------------------------------------------------------------
    // Game data lookup / copying
    // ------------------------------------------------------------------

    /** Android/data/<package>/files (created by this call); may be null. */
    private File externalDir() {
        try {
            return getExternalFilesDir(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Private app storage: /data/data/<package>/files */
    private File internalDir() {
        return getFilesDir();
    }

    private void prepareGameData() {
        activeDataDir = null;

        // Variant 1: files placed by the user into Android/data/.../files
        File ext = externalDir();
        if (ext != null && hasVswap(ext)) {
            normalizeNames(ext);
            activeDataDir = ext;
            return;
        }

        // Variant 2: data bundled in assets/ -> copy into private storage
        File in = internalDir();
        copyAssetsTo(in);
        if (hasVswap(in)) {
            activeDataDir = in;
        }
    }

    private static boolean isGameExt(String lowerName) {
        int dot = lowerName.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = lowerName.substring(dot + 1);
        for (String e : DATA_EXTS) {
            if (e.equals(ext)) return true;
        }
        return false;
    }

    /**
     * Name the engine opens the file under. Most files are lower case
     * (vswap.wl1), but the engine hard-codes HELPART., ENDART1. and DEMO0.
     * in upper case; the extension is always lower case.
     */
    private static String engineName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0) return lower;
        String stem = lower.substring(0, dot);
        String ext = lower.substring(dot + 1);
        if (stem.startsWith("helpart") || stem.startsWith("endart") || stem.startsWith("demo")) {
            stem = stem.toUpperCase(Locale.ROOT);
        }
        return stem + "." + ext;
    }

    private static boolean hasVswap(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (f.isFile() && n.startsWith("vswap.") && isGameExt(n)) {
                return true;
            }
        }
        return false;
    }

    /** Renames VSWAP.WL1 -> vswap.wl1 etc., because the engine opens lower-case names. */
    private static void normalizeNames(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            if (!f.isFile() || !isGameExt(name.toLowerCase(Locale.ROOT))) continue;
            String target = engineName(name);
            if (!target.equals(name)) {
                if (!f.renameTo(new File(dir, target))) {
                    Log.w(TAG, "Could not rename " + name + " to " + target);
                }
            }
        }
    }

    /** Changes with every install/update of the APK, so new assets get re-copied. */
    private String installStamp() {
        try {
            return String.valueOf(
                getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime);
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    private static String readText(File f) {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[256];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeText(File f, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes("UTF-8"));
        }
    }

    /**
     * Copies game files (*.wl1, *.wl6, *.sod, ...) from assets/ into dir.
     * Unrelated files (e.g. ecwolf.pk3) are ignored. Files that are already
     * there are kept unless the app was updated since the last copy.
     */
    private void copyAssetsTo(File dir) {
        try {
            AssetManager am = getAssets();
            String[] names = am.list("");
            if (names == null) return;

            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "Cannot create " + dir);
                return;
            }

            File stampFile = new File(dir, STAMP_FILE);
            String stamp = installStamp();
            boolean upToDate = stamp.equals(readText(stampFile));

            for (String name : names) {
                if (!isGameExt(name.toLowerCase(Locale.ROOT))) continue;

                File out = new File(dir, engineName(name));
                if (upToDate && out.isFile() && out.length() > 0) continue;

                // Write to a temp file first so an interrupted copy never
                // leaves a truncated data file behind.
                File tmp = new File(dir, out.getName() + ".tmp");
                try (InputStream in = am.open(name);
                     FileOutputStream fos = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                    }
                }
                if (out.exists() && !out.delete()) {
                    Log.w(TAG, "Cannot replace " + out);
                }
                if (!tmp.renameTo(out)) {
                    tmp.delete();
                    throw new IOException("Cannot rename " + tmp + " to " + out);
                }
            }

            writeText(stampFile, stamp);
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy game data from assets", e);
        }
    }

    private void showMissingDataDialog() {
        File ext = externalDir();
        String extPath = ext != null ? ext.getAbsolutePath() : "Android/data/" + getPackageName() + "/files";
        runOnUiThread(() -> new AlertDialog.Builder(this)
            .setTitle("WOlfhound3D")
            .setMessage("Game data files not found!\n\n"
                + "Option 1: put the game data files (vswap, maphead, gamemaps, "
                + "audiohed, audiot, vgagraph, vgahead, vgadict with the extension "
                + ".wl6, .sod, .wl3, .wl1 or .sdm) into:\n\n"
                + extPath + "\n\n"
                + "Option 2: put them into the app's assets folder "
                + "(app/src/main/assets) and rebuild the app.\n\n"
                + "Then start the game again.")
            .setCancelable(false)
            .setPositiveButton("Exit", (d, w) -> finish())
            .show());
    }
}

package com.dc1.amber;

import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Mirrors the stock DC-1 amber frontlight behavior into a GSI, rootlessly.
 *
 * The source of truth is the system setting {@code screen_brightness_amber_rate}
 * (0..SETTING_MAX=1023). This service registers a SettingsObserver on it and
 * applies the value whenever it changes, and on boot. "Applying" is a
 * <em>crossfade</em>, because amber is an additive second LED channel: driving
 * it alone would only add light (brighter), not shift the colour temperature.
 * So warmth w = warmth_setting / SETTING_MAX drives both halves:
 *
 *   amber LED  = w * node_max                       (kernel LED node)
 *   white      = max(floor, round(base * (1 - w)))  (screen_brightness setting)
 *
 * where {@code base} is the user's own white level, captured the moment warmth
 * leaves 0 and restored when it returns to 0. At w=1 white sits at the floor
 * and the panel is lit by amber alone — stock Daylight's candlelight look.
 *
 * The white side goes through the official brightness pipeline
 * ({@code Settings.System.screen_brightness}); there is no writable white
 * sysfs node on this device. The amber kernel node is found by:
 *   1. ro.dc1.amber.node               (Rom prop override; empty on stock config)
 *   2. persisted choice                (SharedPreferences)
 *   3. auto-discovery on first boot    (see {@link #discoverNode()})
 *
 * The app is a platform-signed priv-app (system_ext) so it has WRITE_SETTINGS
 * and runs in the system_app domain; SElinux write access to the LED node is
 * granted by sepolicy/dc1amber.te.
 */
public final class AmberService extends Service {
    private static final String TAG = "AmberControl";

    /** Stock setting key; 0..1023 authoritative scale. */
    static final String SETTING = "screen_brightness_amber_rate";

    /** White backlight: the official pipeline, 0..255. */
    static final String WHITE_SETTING = Settings.System.SCREEN_BRIGHTNESS;

    private static final String PROP_OVERRIDE = "ro.dc1.amber.node";
    private static final String PROP_MAX = "ro.dc1.amber.max";
    private static final String PROP_DEFAULT = "ro.dc1.amber.default";
    private static final String PROP_WHITE_FLOOR = "ro.dc1.amber.white_floor";
    private static final String PREF = "amber";
    private static final String PREF_NODE = "node";
    /** The user's own white level, held while the crossfade is engaged. */
    private static final String PREF_WHITE_BASE = "white_base";
    /** Last white value this app wrote — lets us ignore our own echo. */
    private static final String PREF_WHITE_LAST = "white_last";

    /** Daylight's app shows 0..255; "255" = full = this constant. */
    static final int DEFAULT_AMBER = propInt(PROP_DEFAULT, 1023);
    static final int SETTING_MAX = propInt(PROP_MAX, 1023);

    static final int WHITE_MAX = 255;
    /** Never write 0: a black panel with a dead amber node is unrecoverable. */
    static final int WHITE_MIN = 1;
    /** Where white lands at full warmth. */
    static final int WHITE_FLOOR = propInt(PROP_WHITE_FLOOR, 10);

    private ContentObserver mObserver;
    private ContentObserver mWhiteObserver;
    private final Handler mHandler = new Handler();

    @Override
    public void onCreate() {
        super.onCreate();
        mObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                mirrorSetting();
            }
        };
        ContentResolver cr = getContentResolver();
        cr.registerContentObserver(
                Settings.System.getUriFor(SETTING), false, mObserver);

        // The user may reach for the brightness slider while warmth is engaged;
        // that value is their new "full white" intent, so re-derive the base.
        mWhiteObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                rebaseWhite(AmberService.this);
            }
        };
        cr.registerContentObserver(
                Settings.System.getUriFor(WHITE_SETTING), false, mWhiteObserver);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureDefaultValue(this);
        mirrorSetting();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        getContentResolver().unregisterContentObserver(mObserver);
        getContentResolver().unregisterContentObserver(mWhiteObserver);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** On first boot of a GSI the setting does not exist; seed the default. */
    static void ensureDefaultValue(Context context) {
        String cur = Settings.System.getString(
                context.getContentResolver(), SETTING);
        if (cur == null) {
            Settings.System.putInt(context.getContentResolver(), SETTING, DEFAULT_AMBER);
        }
    }

    /**
     * Apply the warmth setting: amber LED up, white backlight down. Every
     * writer of the warmth setting (slider, QS tile, boot, future automation)
     * funnels through here, so the crossfade is never bypassed.
     */
    static void mirrorSetting(Context context) {
        int value = readWarmth(context);
        AmberLed led = AmberLed.forContext(context);
        final int v = value;
        new Thread(() -> led.apply(v), "amber-write").start();
        applyWhite(context, v);
    }

    private void mirrorSetting() {
        mirrorSetting(this);
    }

    // ── the white half of the crossfade ─────────────────────────────────

    /**
     * Drive {@code screen_brightness} for a given warmth.
     *
     * warmth 0  → restore the user's captured base and forget it (and, when
     *             nothing was captured, do not touch brightness at all: that is
     *             what keeps boot from clobbering the user's level).
     * warmth >0 → capture the base on the way in, then hold white at
     *             max(floor, round(base * (1 - w))), never above the base.
     */
    static void applyWhite(Context context, int warmth) {
        SharedPreferences p = prefs(context);
        int base = p.getInt(PREF_WHITE_BASE, -1);

        if (warmth <= 0) {
            if (base >= 0) {
                writeWhite(context, base);
                p.edit().remove(PREF_WHITE_BASE).remove(PREF_WHITE_LAST).apply();
            }
            return;
        }

        if (base < 0) {
            int cur = readWhite(context);
            if (cur < 0) {
                // Brightness unreadable: leave the white side alone entirely
                // rather than guess a base we would later "restore" to.
                Log.w(TAG, "screen_brightness unreadable; amber only");
                return;
            }
            base = clampWhite(cur);
            p.edit().putInt(PREF_WHITE_BASE, base).apply();
            Log.d(TAG, "crossfade engaged, white base=" + base);
        }
        writeWhite(context, crossfadeWhite(base, warmth));
    }

    /** white = max(floor, round(base * (1 - w))), clamped to never exceed base. */
    static int crossfadeWhite(int base, int warmth) {
        double w = (double) clampWarmth(warmth) / SETTING_MAX;
        int scaled = (int) Math.round(base * (1.0d - w));
        // A base dimmer than the floor must not be brightened by going warm.
        int floor = Math.min(WHITE_FLOOR, base);
        return Math.max(floor, Math.min(base, scaled));
    }

    /**
     * The user moved the brightness slider while the crossfade was engaged.
     * Their value is the *crossfaded* white they want, so the base they mean is
     * observed / (1 - w); store that and re-derive so the two stay consistent.
     */
    static void rebaseWhite(Context context) {
        SharedPreferences p = prefs(context);
        int base = p.getInt(PREF_WHITE_BASE, -1);
        if (base < 0) {
            return;                 // not engaged: a plain brightness change
        }
        int observed = readWhite(context);
        if (observed < 0) {
            return;
        }
        if (observed == p.getInt(PREF_WHITE_LAST, -1)) {
            return;                 // our own write echoing back
        }
        int warmth = readWarmth(context);
        if (warmth <= 0) {
            return;                 // disengaging; applyWhite owns that path
        }
        double remaining = 1.0d - (double) warmth / SETTING_MAX;
        if (remaining <= 0.0d || observed <= Math.min(WHITE_FLOOR, base)) {
            // Fully warm, or pinned at the floor: the observed value carries no
            // recoverable base, so keep the one we have and let them be.
            return;
        }
        int newBase = clampWhite((int) Math.round(observed / remaining));
        p.edit().putInt(PREF_WHITE_BASE, newBase).apply();
        Log.d(TAG, "white rebased: observed=" + observed + " -> base=" + newBase);
        writeWhite(context, crossfadeWhite(newBase, warmth));
    }

    // ── small helpers ───────────────────────────────────────────────────

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** @return the warmth setting, clamped; 0 when unreadable. */
    static int readWarmth(Context context) {
        try {
            return clampWarmth(Settings.System.getInt(
                    context.getContentResolver(), SETTING, 0));
        } catch (Exception e) {
            return 0;
        }
    }

    /** @return current white 0..255, or -1 when unreadable. */
    private static int readWhite(Context context) {
        try {
            return Settings.System.getInt(
                    context.getContentResolver(), WHITE_SETTING, -1);
        } catch (Exception e) {
            return -1;
        }
    }

    private static void writeWhite(Context context, int value) {
        int v = clampWhite(value);
        // Record before writing: the observer callback may land immediately and
        // must recognise this value as ours, not as the user's.
        prefs(context).edit().putInt(PREF_WHITE_LAST, v).apply();
        try {
            Settings.System.putInt(context.getContentResolver(), WHITE_SETTING, v);
        } catch (Exception e) {
            Log.w(TAG, "white write failed: " + e.getMessage());
        }
    }

    private static int clampWarmth(int value) {
        if (value < 0) {
            return 0;
        }
        return value > SETTING_MAX ? SETTING_MAX : value;
    }

    private static int clampWhite(int value) {
        if (value < WHITE_MIN) {
            return WHITE_MIN;
        }
        return value > WHITE_MAX ? WHITE_MAX : value;
    }

    private static int propInt(String key, int def) {
        try {
            return Integer.parseInt(SystemProperties.get(key).trim());
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * Wraps the amber kernel LED node: resolution (override → persisted →
     * discovery) and value scaling (setting 0..1023 → node 0..max_brightness).
     */
    static final class AmberLed {
        final Context context;
        private String node;
        private int nodeMax;

        private AmberLed(Context context) {
            this.context = context.getApplicationContext();
        }

        static AmberLed forContext(Context context) {
            return new AmberLed(context);
        }

        synchronized void apply(int settingValue) {
            if (!resolve()) {
                Log.d(TAG, "no amber node yet, skipping write");
                return;
            }
            long scaled = (long) settingValue * nodeMax / SETTING_MAX;
            if (scaled < 0) {
                scaled = 0;
            }
            if (scaled > nodeMax) {
                scaled = nodeMax;
            }
            if (writeFile(node, String.valueOf(scaled))) {
                Log.d(TAG, "amber=" + settingValue + " -> " + node + "=" + scaled);
            } else {
                Log.w(TAG, "write failed for " + node + " (SELinux? node moved?)");
            }
        }

        /** @return the amber node path this Led resolves to, or null. */
        synchronized String resolvedNode() {
            return resolve() ? node : null;
        }

        synchronized int resolvedMax() {
            return resolve() ? nodeMax : -1;
        }

        private boolean resolve() {
            if (node != null) {
                return true;
            }
            String p = SystemProperties.get(PROP_OVERRIDE);
            if (p != null && !p.isEmpty() && probe(p)) {
                node = p;
                return true;
            }
            p = prefs().getString(PREF_NODE, null);
            if (p != null && probe(p)) {
                node = p;
                return true;
            }
            String found = discoverNode();
            if (found != null) {
                node = found;
                prefs().edit().putString(PREF_NODE, node).apply();
                return true;
            }
            return false;
        }

        private android.content.SharedPreferences prefs() {
            return context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        }

        private boolean probe(String path) {
            int max = readMax(path);
            if (max > 0) {
                nodeMax = max;
                return true;
            }
            return false;
        }

        /**
         * Scan the sysfs LED/backlight class dirs and choose the amber node.
         * The white backlight is excluded (class {@code backlight/} or the
         * MediaTek standard name {@code lcd-backlight}); amber is the
         * remaining writable channel. Name hints (amber|warm|frontlight)
         * break ties when several channels exist.
         */
        private String discoverNode() {
            List<File> candidates = new ArrayList<>();
            for (String base : new String[] {"/sys/class/leds", "/sys/class/backlight"}) {
                File dir = new File(base);
                File[] subs = dir.listFiles();
                if (subs == null) {
                    continue;
                }
                for (File sub : subs) {
                    if (new File(sub, "brightness").isFile()) {
                        candidates.add(sub);
                    }
                }
            }
            if (candidates.isEmpty()) {
                return null;
            }
            List<File> nonWhite = new ArrayList<>();
            for (File c : candidates) {
                String name = c.getName();
                boolean white = name.startsWith("lcd-backlight")
                        || name.contains("backlight")
                        || c.getParentFile().getName().equals("backlight");
                if (!white) {
                    nonWhite.add(c);
                }
            }
            List<File> pool = nonWhite.isEmpty() ? candidates : nonWhite;
            List<File> hinted = new ArrayList<>();
            for (File c : pool) {
                String n = c.getName().toLowerCase();
                if (n.contains("amber") || n.contains("warm") || n.contains("frontlight")) {
                    hinted.add(c);
                }
            }
            List<File> pick = hinted.isEmpty() ? pool : hinted;
            Collections.sort(pick, Comparator.comparing(File::getName));
            for (File f : pick) {
                if (probe(f + "/brightness")) {
                    Log.i(TAG, "amber node discovered: " + f + "/brightness (max "
                            + nodeMax + ")");
                    return f + "/brightness";
                }
            }
            return null;
        }

        private static int readMax(String path) {
            String s = readFile(new File(path).getParent() + "/max_brightness");
            if (s == null) {
                return 0;
            }
            try {
                return Integer.parseInt(s.trim());
            } catch (Exception e) {
                return 0;
            }
        }

        private static String readFile(String path) {
            try {
                File f = new File(path);
                if (!f.canRead()) {
                    return null;
                }
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                byte[] buf = new byte[32];
                int n = in.read(buf);
                in.close();
                if (n <= 0) {
                    return null;
                }
                return new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                return null;
            }
        }

        private static boolean writeFile(String path, String value) {
            try {
                java.io.FileOutputStream out =
                        new java.io.FileOutputStream(new File(path));
                out.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.close();
                return true;
            } catch (Exception e) {
                Log.w(TAG, "write " + path + ": " + e.getMessage(), e);
                return false;
            }
        }
    }
}

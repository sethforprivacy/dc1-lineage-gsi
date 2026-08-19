package com.dc1.amber;

import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
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
 * writes the (scaled) value to the kernel LED node whenever it changes, and on
 * boot. The kernel node is found by:
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

    private static final String PROP_OVERRIDE = "ro.dc1.amber.node";
    private static final String PROP_MAX = "ro.dc1.amber.max";
    private static final String PROP_DEFAULT = "ro.dc1.amber.default";
    private static final String PREF = "amber";
    private static final String PREF_NODE = "node";

    /** Daylight's app shows 0..255; "255" = full = this constant. */
    static final int DEFAULT_AMBER = propInt(PROP_DEFAULT, 1023);
    static final int SETTING_MAX = propInt(PROP_MAX, 1023);

    private ContentObserver mObserver;
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
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureDefaultValue();
        mirrorSetting();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        getContentResolver().unregisterContentObserver(mObserver);
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

    /** Read the setting and push it to the LED (async on the IO thread). */
    static void mirrorSetting(Context context) {
        int value;
        try {
            value = Settings.System.getInt(context.getContentResolver(), SETTING, 0);
        } catch (Exception e) {
            value = 0;
        }
        if (value < 0) {
            value = 0;
        }
        if (value > SETTING_MAX) {
            value = SETTING_MAX;
        }
        AmberLed led = AmberLed.forContext(context);
        final int v = value;
        new Thread(() -> led.apply(v), "amber-write").start();
    }

    private void mirrorSetting() {
        mirrorSetting(this);
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

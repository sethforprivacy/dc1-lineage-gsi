package com.dc1.amber;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import java.util.Locale;

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
 *   amber LED  = round((B / 255) * w * amber_node_max)   (kernel LED node)
 *   white      = max(floor, round(B * (1 - w)))          (see white modes)
 *
 * where B is the lamp's total output: the user's own white level, captured the
 * moment warmth leaves 0, restored when it returns to 0, and re-derived when
 * they move the brightness slider mid-fade. Warmth is a mix <em>rate</em> (the
 * stock key is screen_brightness_amber_<em>rate</em>), so it sets the hue while
 * B sets how much light comes out — scaling amber by B is what keeps a dim warm
 * setting dim instead of blasting the amber channel at full tilt. At w=1 white
 * sits at the floor and the panel is lit by amber alone, at the chosen
 * brightness — stock Daylight's candlelight look.
 *
 * <h3>Channel plumbing (verified on hardware, 2026-08-24)</h3>
 *
 * The DC-1 has two Richtek RT4539 frontlight chips (kernel driver
 * leds-rt4539): the amber string at i2c 2-003c exposes
 * /sys/class/leds/lcd-backlight-amber, the white string at i2c 5-003c exposes
 * /sys/class/leds/lcd-backlight. The framework's brightness pipeline goes
 * through the vendor lights HAL, which rewrites <em>both</em> nodes on every
 * brightness change with its own split (white pinned at sysfs 1 — below the
 * driver's on-threshold, so white never lights — and amber at B-1). The HAL's
 * ratio input is not public and not framework-visible, so the mix is driven
 * <em>directly</em>: both LED nodes are written by this app, and
 * {@code screen_brightness} is only the lamp total (B).
 *
 * Because the HAL keeps re-asserting its split whenever the framework applies
 * brightness (slider ramps, screen wake, boot), the mix is re-asserted after
 * each such event: once immediately, and once more after the ~2s ramp ends
 * ({@link #scheduleRemirror()}), plus on ACTION_SCREEN_ON.
 *
 * The plumbing stays live-configurable from {@code Settings.System} for
 * diagnosis, re-read on <em>every</em> mirror pass:
 *
 * <ul>
 *   <li>{@code dc1_amber_node} — absolute /sys path overriding the amber
 *       node. Unset keeps the normal resolution order below.</li>
 *   <li>{@code dc1_white_mode} — how the white half is driven:
 *       {@code node} (default; the DC-1's white LED node directly, resolved
 *       from {@code ro.dc1.white.node} or /sys/class/leds/lcd-backlight),
 *       {@code node:<path>} (an explicit node), {@code brightness} (the
 *       framework pipeline — HAL split applies, white will not light), or
 *       {@code off} (do not drive the white half at all).</li>
 * </ul>
 *
 * Every mirror logs one line with the resolved configuration and both computed
 * channel values ({@code mix: ...}); unparseable values fall back to the
 * defaults with a warning. See docs/amber.md.
 *
 * The amber kernel node is found by:
 *   1. Settings.System dc1_amber_node   (live experiment override)
 *   2. ro.dc1.amber.node               (Rom prop override; empty on stock config)
 *   3. persisted choice                (SharedPreferences)
 *   4. auto-discovery on first boot    (see {@link AmberLed#discoverNode()})
 *
 * The app is a platform-signed priv-app (system_ext) so it has WRITE_SETTINGS
 * and runs in the platform_app domain; SElinux write access to the LED node is
 * granted by sepolicy/dc1amber.te and the DAC mode by dc1-amber.rc.
 */
public final class AmberService extends Service {
    private static final String TAG = "AmberControl";

    /** Stock setting key; 0..1023 authoritative scale. */
    static final String SETTING = "screen_brightness_amber_rate";

    /** White backlight: the official pipeline, 0..255. */
    static final String WHITE_SETTING = Settings.System.SCREEN_BRIGHTNESS;

    /** Live override for the amber node path (diagnostics); see {@link Config}. */
    static final String SETTING_AMBER_NODE = "dc1_amber_node";
    /** Live selector for how the white half is driven; see {@link Config}. */
    static final String SETTING_WHITE_MODE = "dc1_white_mode";

    static final String WHITE_MODE_BRIGHTNESS = "brightness";
    static final String WHITE_MODE_OFF = "off";
    /** Bare "node": resolve the white node like the amber one (prop → standard name). */
    static final String WHITE_MODE_NODE = "node";
    static final String WHITE_MODE_NODE_PREFIX = "node:";

    private static final String PROP_OVERRIDE = "ro.dc1.amber.node";
    /** White node override, same pattern as the amber one. */
    private static final String PROP_WHITE_NODE = "ro.dc1.white.node";
    private static final String PROP_MAX = "ro.dc1.amber.max";
    private static final String PROP_DEFAULT = "ro.dc1.amber.default";
    private static final String PROP_WHITE_FLOOR = "ro.dc1.amber.white_floor";
    /** The DC-1's white string (MediaTek's standard backlight LED name). */
    private static final String WHITE_NODE_FALLBACK =
            "/sys/class/leds/lcd-backlight/brightness";
    private static final String PREF = "amber";
    private static final String PREF_NODE = "node";
    /** The user's own white level, held while the crossfade is engaged. */
    private static final String PREF_WHITE_BASE = "white_base";
    /** Last white value this app wrote — lets us ignore our own echo. */
    private static final String PREF_WHITE_LAST = "white_last";

    /**
     * How long after the last brightness change to re-assert the mix. The
     * framework's brightness ramp (DisplayPowerController → lights HAL) takes
     * roughly two seconds and overwrites both LED nodes along the way with its
     * own white/amber split; our write must land after the ramp ends, or the
     * panel flips to the HAL's idea of the mix until the next warmth change.
     */
    private static final long REMIRROR_DELAY_MS = 2500;

    /** Daylight's app shows 0..255; "255" = full = this constant. */
    static final int DEFAULT_AMBER = propInt(PROP_DEFAULT, 1023);
    static final int SETTING_MAX = propInt(PROP_MAX, 1023);

    static final int WHITE_MAX = 255;
    /** Never write 0: a black panel with a dead amber node is unrecoverable. */
    static final int WHITE_MIN = 1;
    /** Where white lands at full warmth in brightness mode. */
    static final int WHITE_FLOOR = propInt(PROP_WHITE_FLOOR, 10);

    private ContentObserver mObserver;
    private ContentObserver mWhiteObserver;
    private ContentObserver mConfigObserver;
    private BroadcastReceiver mScreenOnReceiver;
    private final Handler mHandler = new Handler();
    /** The pending post-ramp re-mirror, if any. */
    private final Runnable mRemirror = new Runnable() {
        @Override
        public void run() {
            Log.i(TAG, "re-asserting mix after brightness ramp");
            mirrorSetting();
        }
    };
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
        // In the node/off white modes we never write screen_brightness, so a
        // change there is simply a new lamp total: re-mirror both channels.
        mWhiteObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                if (Config.resolve(AmberService.this).whiteMode
                        == Config.MODE_BRIGHTNESS) {
                    rebaseWhite(AmberService.this);
                } else if (!isOwnWhiteEcho(AmberService.this)) {
                    mirrorSetting();
                    // The framework's brightness ramp rewrites both LED
                    // nodes for ~2s after this; re-assert once it settles.
                    scheduleRemirror();
                }
            }
        };
        cr.registerContentObserver(
                Settings.System.getUriFor(WHITE_SETTING), false, mWhiteObserver);

        // Channel plumbing is re-read per mirror anyway; observing the keys
        // makes a live experiment a single `settings put` rather than a put
        // plus a nudge of the warmth setting to force a mirror.
        mConfigObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                Log.i(TAG, "channel config changed (" + uri + "), re-mirroring");
                mirrorSetting();
                scheduleRemirror();
            }
        };
        cr.registerContentObserver(
                Settings.System.getUriFor(SETTING_AMBER_NODE), false, mConfigObserver);
        cr.registerContentObserver(
                Settings.System.getUriFor(SETTING_WHITE_MODE), false, mConfigObserver);

        // Waking the display makes the framework re-apply its brightness to
        // the lights HAL, which rewrites both LED nodes with its own
        // white/amber split. Re-assert the mix when that happens: immediately
        // (the wake apply itself) and after the ramp (a policy-restored
        // brightness also ramps).
        mScreenOnReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.i(TAG, "screen on; re-asserting mix");
                mirrorSetting();
                scheduleRemirror();
            }
        };
        registerReceiver(mScreenOnReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureDefaultValue(this);
        mirrorSetting();
        // The display usually finishes coming up around boot-complete, after
        // this first mirror; the HAL's boot-time apply would then own the
        // nodes until the next setting change. Re-assert once it has settled.
        scheduleRemirror();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mHandler.removeCallbacks(mRemirror);
        unregisterReceiver(mScreenOnReceiver);
        ContentResolver cr = getContentResolver();
        cr.unregisterContentObserver(mObserver);
        cr.unregisterContentObserver(mWhiteObserver);
        cr.unregisterContentObserver(mConfigObserver);
        super.onDestroy();
    }

    /**
     * (Re)schedule the post-ramp re-mirror. Every call pushes the deadline
     * out, so a dragged brightness slider — which fires the observer for each
     * ramp step — still ends with exactly one re-assert, after the last ramp.
     */
    private void scheduleRemirror() {
        mHandler.removeCallbacks(mRemirror);
        mHandler.postDelayed(mRemirror, REMIRROR_DELAY_MS);
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

    // ── live channel plumbing ───────────────────────────────────────────

    /**
     * The resolved channel plumbing for one mirror pass.
     *
     * Built, used and dropped per pass — deliberately never cached, so a
     * {@code settings put system dc1_white_mode ...} takes effect on the next
     * mirror with no restart and no reflash. Bad values are logged and replaced
     * by the defaults (normal amber resolution, white via the brightness
     * pipeline) rather than disabling the frontlight.
     */
    static final class Config {
        /** White driven through {@code Settings.System.screen_brightness}. */
        static final int MODE_BRIGHTNESS = 0;
        /** White driven by writing {@link #whiteNode} directly. */
        static final int MODE_NODE = 1;
        /** White not driven at all. */
        static final int MODE_OFF = 2;

        /** Explicit amber node path, or null for the normal resolution order. */
        final String amberNode;
        final int whiteMode;
        /** Non-null iff {@link #whiteMode} == {@link #MODE_NODE}. */
        final String whiteNode;

        private Config(String amberNode, int whiteMode, String whiteNode) {
            this.amberNode = amberNode;
            this.whiteMode = whiteMode;
            this.whiteNode = whiteNode;
        }

        static Config resolve(Context context) {
            String amber = sanitizeNode(
                    readSetting(context, SETTING_AMBER_NODE), SETTING_AMBER_NODE);
            String mode = readSetting(context, SETTING_WHITE_MODE);
            if (mode != null) {
                mode = mode.trim();
            }
            if (mode == null || mode.isEmpty() || WHITE_MODE_NODE.equals(mode)) {
                // Default (and bare "node"): drive the white string directly.
                // Unset only when no white node exists, in which case the
                // brightness pipeline is the safe fallback.
                String node = defaultWhiteNode();
                if (node != null) {
                    return new Config(amber, MODE_NODE, node);
                }
                if (mode != null && !mode.isEmpty()) {
                    Log.w(TAG, "no white LED node found; falling back to "
                            + WHITE_MODE_BRIGHTNESS);
                }
                return new Config(amber, MODE_BRIGHTNESS, null);
            }
            if (WHITE_MODE_BRIGHTNESS.equals(mode)) {
                return new Config(amber, MODE_BRIGHTNESS, null);
            }
            if (WHITE_MODE_OFF.equals(mode)) {
                return new Config(amber, MODE_OFF, null);
            }
            if (mode.startsWith(WHITE_MODE_NODE_PREFIX)) {
                String raw = mode.substring(WHITE_MODE_NODE_PREFIX.length()).trim();
                if (raw.isEmpty()) {
                    Log.w(TAG, SETTING_WHITE_MODE + "='" + mode + "' has no path"
                            + " after '" + WHITE_MODE_NODE_PREFIX + "'; using "
                            + WHITE_MODE_NODE);
                    return resolveNodeOrBrightness(context, amber);
                }
                String path = sanitizeNode(raw, SETTING_WHITE_MODE);
                if (path != null) {
                    return new Config(amber, MODE_NODE, path);
                }
                // sanitizeNode already logged why; fall back to the default.
                return resolveNodeOrBrightness(context, amber);
            }
            Log.w(TAG, SETTING_WHITE_MODE + "='" + mode + "' not understood"
                    + " (want node | node:<path> | brightness | off); using "
                    + WHITE_MODE_NODE);
            return resolveNodeOrBrightness(context, amber);
        }

        private static Config resolveNodeOrBrightness(Context context, String amber) {
            String node = defaultWhiteNode();
            return node != null
                    ? new Config(amber, MODE_NODE, node)
                    : new Config(amber, MODE_BRIGHTNESS, null);
        }

        /**
         * The white node for bare {@code node} mode: maintainer prop first,
         * then the DC-1's standard MediaTek backlight LED name.
         *
         * @return an existing node path, or null when neither resolves (the
         *         caller then falls back to the brightness pipeline).
         */
        private static String defaultWhiteNode() {
            String p = SystemProperties.get(PROP_WHITE_NODE);
            if (!p.isEmpty() && new File(p).isFile()) {
                return p;
            }
            if (new File(WHITE_NODE_FALLBACK).isFile()) {
                return WHITE_NODE_FALLBACK;
            }
            return null;
        }

        /** The mode as it would be written to the setting; for the mix log. */
        String describe() {
            switch (whiteMode) {
                case MODE_NODE:
                    return WHITE_MODE_NODE_PREFIX + whiteNode;
                case MODE_OFF:
                    return WHITE_MODE_OFF;
                default:
                    return WHITE_MODE_BRIGHTNESS;
            }
        }
    }

    /** @return the raw setting string, or null when unset/unreadable. */
    private static String readSetting(Context context, String key) {
        try {
            return Settings.System.getString(context.getContentResolver(), key);
        } catch (Exception e) {
            Log.w(TAG, "read " + key + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Validate a node path from a setting. Unset is the (silent) default; a
     * relative path or one that does not exist is a typo, so warn and return
     * null, which puts the caller back on its default.
     *
     * A path outside /sys/ is only warned about, not rejected: the setting is
     * writable by WRITE_SETTINGS holders alone (the shell, system apps), and
     * what a write may actually touch is decided by SELinux and DAC, not here.
     * But every real channel on this device lives under /sys/, so an unusual
     * path is worth saying out loud in the log.
     */
    private static String sanitizeNode(String path, String key) {
        if (path == null) {
            return null;
        }
        String p = path.trim();
        if (p.isEmpty()) {
            return null;
        }
        if (!p.startsWith("/")) {
            Log.w(TAG, key + "='" + p
                    + "' rejected: not an absolute path; using default");
            return null;
        }
        if (!new File(p).isFile()) {
            Log.w(TAG, key + "='" + p + "' rejected: no such file; using default");
            return null;
        }
        if (!p.startsWith("/sys/")) {
            Log.w(TAG, key + "='" + p + "' is outside /sys/ — unusual for a"
                    + " channel node, continuing anyway");
        }
        return p;
    }

    /**
     * Apply the warmth setting: amber LED up, white backlight down. Every
     * writer of the warmth setting (slider, QS tile, boot, future automation)
     * funnels through here, so the crossfade is never bypassed.
     *
     * Warmth is a mix <em>rate</em> (the stock key is literally
     * screen_brightness_amber_<em>rate</em>), not an absolute amber level: the
     * brightness base is the lamp's total output and warmth only decides how
     * that output is split between the white and amber channels. So both sides
     * are scaled by the base, and the hue depends on warmth alone.
     *
     * Both channels are resolved and computed here before anything is written,
     * so the pass can emit a single line describing the whole mix — the log the
     * on-hardware channel-mapping experiments read.
     */
    static void mirrorSetting(Context context) {
        Config cfg = Config.resolve(context);
        if (cfg.whiteMode != Config.MODE_BRIGHTNESS) {
            // These modes never write screen_brightness, so a base captured by
            // an earlier brightness-mode pass would strand the user's slider
            // at a crossfaded value. Hand it back before reading the lamp total.
            releaseBrightnessBase(context);
        }

        int warmth = readWarmth(context);
        int base = lampBase(context, cfg);

        final AmberLed led = AmberLed.forContext(context, cfg.amberNode);
        // Node first: an unresolvable node would otherwise be re-resolved (and
        // re-warned about) by the second accessor.
        String amberNode = led.resolvedNode();
        int amberMax = amberNode != null ? led.resolvedMax() : -1;
        final int amberValue = amberMax > 0
                ? amberNodeValue(warmth, base, amberMax) : -1;

        String white = applyWhite(context, warmth, base, cfg);

        Log.i(TAG, "mix: amber_node=" + (amberNode == null ? "<none>" : amberNode)
                + " white_mode=" + cfg.describe()
                + " B=" + base
                + " w=" + rate(warmth)
                + " -> amber=" + (amberValue < 0
                        ? "<none>" : amberValue + "/" + amberMax)
                + " white=" + white);

        if (amberValue >= 0) {
            new Thread(() -> led.write(amberValue), "amber-write").start();
        }
    }

    private void mirrorSetting() {
        mirrorSetting(this);
    }

    // ── the white half of the crossfade ─────────────────────────────────

    /**
     * Drive the white half for a given warmth, per the configured mode.
     *
     * @param lampTotal B, the lamp's total output (1..255)
     * @return a short description of what was written, for the mix log
     */
    static String applyWhite(Context context, int warmth, int lampTotal, Config cfg) {
        switch (cfg.whiteMode) {
            case Config.MODE_OFF:
                return "<off>";
            case Config.MODE_NODE:
                return applyWhiteNode(context, warmth, lampTotal, cfg.whiteNode);
            default:
                return applyWhiteBrightness(context, warmth);
        }
    }

    /**
     * Default mode: drive {@code screen_brightness}, i.e. the official
     * framework/HWC pipeline.
     *
     * warmth 0  → restore the user's captured base and forget it (and, when
     *             nothing was captured, do not touch brightness at all: that is
     *             what keeps boot from clobbering the user's level).
     * warmth >0 → capture the base on the way in, then hold white at
     *             max(floor, round(base * (1 - w))), never above the base.
     */
    static String applyWhiteBrightness(Context context, int warmth) {
        SharedPreferences p = prefs(context);
        int base = p.getInt(PREF_WHITE_BASE, -1);

        if (warmth <= 0) {
            if (base >= 0) {
                writeWhite(context, base);
                p.edit().remove(PREF_WHITE_BASE).remove(PREF_WHITE_LAST).apply();
                return String.valueOf(clampWhite(base)) + " (restored)";
            }
            return "<untouched>";
        }

        if (base < 0) {
            int cur = readWhite(context);
            if (cur < 0) {
                // Brightness unreadable: leave the white side alone entirely
                // rather than guess a base we would later "restore" to.
                Log.w(TAG, "screen_brightness unreadable; amber only");
                return "<unreadable>";
            }
            base = clampWhite(cur);
            p.edit().putInt(PREF_WHITE_BASE, base).apply();
            Log.d(TAG, "crossfade engaged, white base=" + base);
        }
        int value = crossfadeWhite(base, warmth);
        writeWhite(context, value);
        return String.valueOf(value);
    }

    /**
     * White half driven by writing a sysfs node directly (the v9 default).
     *
     * The rate model is unchanged — the white share of the lamp is still
     * {@code round(B * (1 - w))} on the framework's own 0..255 scale — and
     * only the final step differs: that share is rescaled to the node's own
     * {@code max_brightness}, and {@code screen_brightness} is left entirely
     * alone (so B keeps reading back as the user's lamp total).
     *
     * The floor is 0 here (not {@link #WHITE_FLOOR}): both strings are under
     * our direct control, so at full warmth the panel is lit purely by amber
     * — the stock candlelight look, verified live on hardware.
     */
    private static String applyWhiteNode(
            Context context, int warmth, int lampTotal, final String node) {
        final int max = AmberLed.readMax(node);
        if (max <= 0) {
            Log.w(TAG, "white node " + node
                    + " has no usable max_brightness; white left alone");
            return "<no-max>";
        }
        int share = crossfadeWhite(clampWhite(lampTotal), warmth, 0);
        int scaled = (int) Math.round((double) share / WHITE_MAX * max);
        final int value = Math.max(0, Math.min(max, scaled));
        new Thread(() -> {
            if (!AmberLed.writeFile(node, String.valueOf(value))) {
                Log.w(TAG, "white node write failed: " + node
                        + " (SELinux? DAC? node moved?)");
            }
        }, "white-write").start();
        return value + "/" + max;
    }

    /**
     * Give the user their own brightness back and forget the captured base.
     * Called when the white half stops going through {@code screen_brightness},
     * so switching modes live never leaves the slider pinned at a crossfaded
     * value with nothing left to restore it.
     */
    private static void releaseBrightnessBase(Context context) {
        SharedPreferences p = prefs(context);
        int base = p.getInt(PREF_WHITE_BASE, -1);
        if (base < 0) {
            return;
        }
        Log.i(TAG, "white no longer on the brightness pipeline; restoring base="
                + base);
        writeWhite(context, base);
        // Keep PREF_WHITE_LAST: this restore is about to echo back through the
        // brightness observer, and in the node/off modes that echo must be
        // recognised as ours or it re-enters mirrorSetting and doubles the pass.
        p.edit().remove(PREF_WHITE_BASE).apply();
    }

    /**
     * @return true when {@code screen_brightness} currently holds the value
     *         this app last wrote, i.e. an observer callback is our own echo
     *         rather than the user moving their brightness slider.
     */
    private static boolean isOwnWhiteEcho(Context context) {
        int last = prefs(context).getInt(PREF_WHITE_LAST, -1);
        return last >= 0 && last == readWhite(context);
    }

    /**
     * Total lamp output for the mix: the captured white base while a
     * brightness-mode crossfade is engaged, otherwise the user's current
     * brightness. This is the B in both channel formulas.
     *
     * In the node/off white modes {@code screen_brightness} is never written,
     * so it always reads back as the lamp total directly and there is no base
     * to consult.
     */
    static int lampBase(Context context, Config cfg) {
        if (cfg.whiteMode == Config.MODE_BRIGHTNESS) {
            int stored = prefs(context).getInt(PREF_WHITE_BASE, -1);
            if (stored >= 0) {
                return clampWhite(stored);
            }
        }
        int cur = readWhite(context);
        if (cur < 0) {
            // Brightness unreadable (fresh /data, before the display service
            // seeds it): assume a full lamp so the frontlight still lights up.
            return WHITE_MAX;
        }
        return clampWhite(cur);
    }

    /**
     * The amber half of the mix: {@code round((B / 255) * w * node_max)}.
     *
     * The single place the amber scale is computed. Rounding is to nearest so
     * the mix tracks the slider symmetrically with the white side, with one
     * deliberate exception: past the halfway point of the fade the lamp is
     * mostly amber, so a base dim enough to round the amber channel to 0 is
     * floored at 1 instead — at minimum brightness and full warmth the panel
     * must still glow rather than go dark.
     */
    static int amberNodeValue(int warmth, int base, int nodeMax) {
        int w10 = clampWarmth(warmth);
        if (w10 <= 0 || nodeMax <= 0) {
            return 0;
        }
        int b = clampWhite(base);
        double w = (double) w10 / SETTING_MAX;
        double lamp = (double) b / WHITE_MAX;
        int value = (int) Math.round(lamp * w * nodeMax);
        if (value < 1 && w > 0.5d) {
            value = 1;
        }
        return Math.max(0, Math.min(nodeMax, value));
    }

    /**
     * white = max(floor, round(base * (1 - w))), clamped to never exceed base.
     *
     * @param floor the minimum white share: {@link #WHITE_FLOOR} in brightness
     *              mode (where a 0 write means a dark panel if amber is dead),
     *              0 in node mode (where both strings are driven directly and
     *              full warmth should be pure amber — the owner-verified look).
     */
    static int crossfadeWhite(int base, int warmth, int floor) {
        double w = (double) clampWarmth(warmth) / SETTING_MAX;
        int scaled = (int) Math.round(base * (1.0d - w));
        // A base dimmer than the floor must not be brightened by going warm.
        int f = Math.min(floor, base);
        return Math.max(f, Math.min(base, scaled));
    }

    /** Brightness-mode crossfade keeps the recovery floor. */
    static int crossfadeWhite(int base, int warmth) {
        return crossfadeWhite(base, warmth, WHITE_FLOOR);
    }

    /**
     * The user moved the brightness slider while a brightness-mode crossfade
     * was engaged. Brightness is the whole lamp's output now, so their value
     * re-derives the base and <em>both</em> channels are rewritten from it —
     * same warmth, same hue, more or less light.
     *
     * Mid-fade their value is the crossfaded white they picked, so the base
     * they mean is observed / (1 - w). At full warmth white is pinned at the
     * floor and carries no scale information, so the value they picked is read
     * as the lamp total directly; white then returns to the floor and the extra
     * light they asked for comes out of the amber channel, which is the whole
     * point of a warmth mix.
     *
     * Once the new base is stored the ordinary mirror pass does the rest — it
     * reads the base back and rewrites both channels (and logs the mix).
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
        int newBase;
        if (remaining <= 0.0d) {
            // Fully warm: white is decoupled, so read their pick as lamp total.
            newBase = clampWhite(observed);
        } else if (observed <= Math.min(WHITE_FLOOR, base)) {
            // Pinned at the floor mid-fade: nothing recoverable, leave them be.
            return;
        } else {
            newBase = clampWhite((int) Math.round(observed / remaining));
        }
        p.edit().putInt(PREF_WHITE_BASE, newBase).apply();
        Log.d(TAG, "lamp rebased: observed=" + observed + " -> base=" + newBase);
        // Brightness scales the whole lamp, so both channels move together.
        mirrorSetting(context);
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

    /** The mix rate as logged: fraction plus the raw setting it came from. */
    private static String rate(int warmth) {
        int w = clampWarmth(warmth);
        return String.format(Locale.US, "%.3f(%d/%d)",
                (double) w / SETTING_MAX, w, SETTING_MAX);
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
     * Wraps the amber kernel LED node: resolution (live setting → property →
     * persisted → discovery) and the node write. The value itself comes from
     * {@link AmberService#amberNodeValue}, which is the only place the amber
     * scale is computed.
     *
     * One instance per mirror pass, so a changed {@code dc1_amber_node} is
     * picked up on the very next pass.
     */
    static final class AmberLed {
        final Context context;
        /** Live path override from {@code dc1_amber_node}, already validated. */
        private final String override;
        private String node;
        private int nodeMax;

        private AmberLed(Context context, String override) {
            this.context = context.getApplicationContext();
            this.override = override;
        }

        /** Resolve the amber node honouring the live setting override. */
        static AmberLed forContext(Context context) {
            return new AmberLed(context, sanitizeNode(
                    readSetting(context, SETTING_AMBER_NODE), SETTING_AMBER_NODE));
        }

        static AmberLed forContext(Context context, String override) {
            return new AmberLed(context, override);
        }

        /** Write an already-computed value to the resolved node. */
        synchronized void write(int value) {
            if (!resolve()) {
                Log.d(TAG, "no amber node yet, skipping write");
                return;
            }
            if (!writeFile(node, String.valueOf(value))) {
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
            // The live experiment override wins: it is set precisely to try a
            // node the normal order would not pick, and it is never persisted.
            if (override != null && probe(override)) {
                node = override;
                return true;
            }
            if (override != null) {
                Log.w(TAG, SETTING_AMBER_NODE + "='" + override
                        + "' has no usable max_brightness; falling back");
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

        /** @return the node's max_brightness, or 0 when unreadable. */
        static int readMax(String path) {
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

        static boolean writeFile(String path, String value) {
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

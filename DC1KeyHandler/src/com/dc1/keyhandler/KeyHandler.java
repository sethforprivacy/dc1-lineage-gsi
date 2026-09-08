/*
 * Copyright (C) 2026 The DC-1 LineageOS GSI contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dc1.keyhandler;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;
import android.view.KeyEvent;

import com.android.internal.os.DeviceKeyHandler;

/**
 * Restores the DC-1's two physical buttons on a GSI.
 *
 * Both buttons emit plain function keys on mtk-kpd (/dev/input/event1):
 * scancodes 87/88 are mapped to KEY_F11 / KEY_F12 by
 * /vendor/usr/keylayout/mtk-kpd.kl. Neither keycode has a default action in
 * AOSP or LineageOS — on stock they were handled by
 * com.daylightcomputer.systemrunner's KeyHandler, which a GSI replaces, so
 * both buttons are inert from the flash onwards.
 *
 * What stock did (recovered from the stock OTA's SystemRunner.apk,
 * KeyHandler.handleKeyEvent, resolved against bytecode offsets — the branch
 * targets invert the obvious reading order, so the disassembly listing alone
 * attributes the wrong action to each key):
 *
 *   F11 (orange, side) -> a toast, "Walkie-Talkie assistant is coming soon!",
 *                         a stub for a feature Daylight never shipped.
 *   F12 (top)          -> handleTopButton(): launch com.fluidtouch.noteshelf2,
 *                         else noteshelf3, else "No note-taking app found".
 *
 * F11 therefore has no real behaviour to preserve and is bound here to the
 * amber frontlight toggle — a physical control findable in the dark beats the
 * QS tile on a bedside reader. It drives AmberControl's own source of truth
 * (the Settings.System key), so there is no second mechanism to keep in sync.
 *
 * F12 generalises what stock hardcoded: an explicit override if set, else
 * Android's Notes role (whatever the user's default notes app is), else the
 * two Noteshelf packages for stock parity.
 */
public class KeyHandler implements DeviceKeyHandler {

    private static final String TAG = "DC1KeyHandler";

    /** Orange side button. */
    private static final int KEY_AMBER_TOGGLE = KeyEvent.KEYCODE_F11;
    /** Top button. */
    private static final int KEY_NOTES = KeyEvent.KEYCODE_F12;

    /** Remembers the warmth to come back to when toggling amber back on. */
    private static final String SETTING_LAST_RATE = "dc1_amber_last_rate";
    /** Optional explicit target for the top button; unset = Notes role. */
    private static final String SETTING_NOTES_PACKAGE = "dc1_notes_package";

    private static final String[] STOCK_NOTES_PACKAGES = {
        "com.fluidtouch.noteshelf2",
        "com.fluidtouch.noteshelf3",
    };

    private final Context mContext;
    private final PowerManager mPowerManager;
    private final Handler mHandler;

    private final String mAmberSetting;
    private final int mAmberMax;

    public KeyHandler(Context context) {
        mContext = context;
        mPowerManager = context.getSystemService(PowerManager.class);

        // Same knobs the product fragment configures AmberControl with, so the
        // button can never disagree with the app about scale or key name.
        mAmberSetting = SystemProperties.get("ro.dc1.amber.setting",
                "screen_brightness_amber_rate");
        mAmberMax = SystemProperties.getInt("ro.dc1.amber.max", 1023);

        HandlerThread thread = new HandlerThread("DC1KeyHandler");
        thread.start();
        mHandler = new Handler(thread.getLooper());
    }

    @Override
    public KeyEvent handleKeyEvent(KeyEvent event) {
        final int keyCode = event.getKeyCode();
        if (keyCode != KEY_AMBER_TOGGLE && keyCode != KEY_NOTES) {
            return event;
        }

        // Consume both halves of the press so the keycodes never reach apps,
        // but act once, on the initial down.
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                && mPowerManager.isInteractive()) {
            // Never do work on the input thread.
            mHandler.post(keyCode == KEY_AMBER_TOGGLE ? this::toggleAmber : this::openNotes);
        }
        return null;
    }

    /**
     * Toggle the frontlight between off and the last warmth used.
     *
     * AmberControl owns the mix; this only moves its input, so auto-discovery,
     * the channel model and the watchdog all keep applying.
     */
    private void toggleAmber() {
        final int current = getSetting(mAmberSetting, mAmberMax);
        final int next;
        if (current > 0) {
            putSetting(SETTING_LAST_RATE, current);
            next = 0;
        } else {
            final int remembered = getSetting(SETTING_LAST_RATE, mAmberMax);
            next = remembered > 0 ? remembered : mAmberMax;
        }
        putSetting(mAmberSetting, next);
        Slog.i(TAG, "orange button -> " + mAmberSetting + "=" + next);
    }

    /** Open the user's notes app, mirroring what the top button did on stock. */
    private void openNotes() {
        final String override = Settings.System.getStringForUser(
                mContext.getContentResolver(), SETTING_NOTES_PACKAGE,
                UserHandle.USER_CURRENT);
        if (override != null && !override.isEmpty() && launchPackage(override)) {
            return;
        }

        // The Notes role: routes to whatever the user set as their notes app.
        final Intent createNote = new Intent(Intent.ACTION_CREATE_NOTE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (startActivity(createNote, "CREATE_NOTE (notes role)")) {
            return;
        }

        for (String pkg : STOCK_NOTES_PACKAGES) {
            if (launchPackage(pkg)) {
                return;
            }
        }
        Slog.i(TAG, "top button -> no note-taking app found");
    }

    private boolean launchPackage(String packageName) {
        final Intent intent = mContext.getPackageManager()
                .getLaunchIntentForPackage(packageName);
        if (intent == null) {
            return false;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return startActivity(intent, packageName);
    }

    private boolean startActivity(Intent intent, String what) {
        try {
            mContext.startActivityAsUser(intent, UserHandle.CURRENT);
            Slog.i(TAG, "top button -> " + what);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        } catch (Exception e) {
            Slog.w(TAG, "top button -> " + what + " failed", e);
            return false;
        }
    }

    private int getSetting(String key, int def) {
        return Settings.System.getIntForUser(mContext.getContentResolver(), key, def,
                UserHandle.USER_CURRENT);
    }

    private void putSetting(String key, int value) {
        Settings.System.putIntForUser(mContext.getContentResolver(), key, value,
                UserHandle.USER_CURRENT);
    }
}

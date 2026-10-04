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

import android.app.ActivityManager;
import android.app.SearchManager;
import android.app.role.RoleManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

import com.android.internal.display.BrightnessSynchronizer;
import com.android.internal.os.DeviceKeyHandler;

import java.util.List;

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
 * Each button has a short-press and a long-press action, picked in
 * ButtonsSettingsActivity. The defaults:
 *
 *   F11 (side)         -> short press: open the user's digital assistant,
 *                         the same way a long-press on home does.
 *                         long press: turn the frontlight off, or back on at
 *                         the brightness it had.
 *   F12 (top)          -> short press: open the app that holds the Notes role.
 */
public class KeyHandler implements DeviceKeyHandler {

    private static final String TAG = "DC1KeyHandler";

    /** Side orange button. */
    private static final int KEY_SIDE = KeyEvent.KEYCODE_F11;
    /** Top orange button. */
    private static final int KEY_TOP = KeyEvent.KEYCODE_F12;

    /** Brightness to come back to when the frontlight is turned on again. */
    private static final String SETTING_SAVED_BRIGHTNESS = "dc1_frontlight_saved_brightness";
    /** The lights HAL disables both LED drivers at this level. */
    private static final int BRIGHTNESS_OFF = 1;

    private final Context mContext;
    private final ContentResolver mResolver;
    private final PowerManager mPowerManager;
    private final Handler mHandler;
    private final Runnable mSideLongPress = () -> run(ButtonConfig.SIDE_LONG);
    private final Runnable mTopLongPress = () -> run(ButtonConfig.TOP_LONG);

    // Last key down, passed on to the assistant. Handler thread only.
    private int mDownDeviceId;
    private long mDownTime;

    public KeyHandler(Context context) {
        mContext = context;
        mResolver = context.getContentResolver();
        mPowerManager = context.getSystemService(PowerManager.class);

        HandlerThread thread = new HandlerThread("DC1KeyHandler");
        thread.start();
        mHandler = new Handler(thread.getLooper());

        mResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        forgetSavedBrightnessIfLit();
                    }
                }, UserHandle.USER_ALL);
    }

    @Override
    public KeyEvent handleKeyEvent(KeyEvent event) {
        final int keyCode = event.getKeyCode();
        if (keyCode != KEY_SIDE && keyCode != KEY_TOP) {
            return event;
        }

        // Consume every event of both keys so the keycodes never reach apps.
        // The work runs on the handler thread, never on the input thread.
        final boolean side = keyCode == KEY_SIDE;
        final int action = event.getAction();
        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                && mPowerManager.isInteractive()) {
            final int deviceId = event.getDeviceId();
            final long eventTime = event.getEventTime();
            mHandler.post(() -> onDown(side, deviceId, eventTime));
        } else if (action == KeyEvent.ACTION_UP) {
            final boolean canceled = event.isCanceled();
            mHandler.post(() -> onUp(side, canceled));
        }
        return null;
    }

    private void onDown(boolean side, int deviceId, long eventTime) {
        mDownDeviceId = deviceId;
        mDownTime = eventTime;
        final int shortSlot = side ? ButtonConfig.SIDE_SHORT : ButtonConfig.TOP_SHORT;
        final int longSlot = side ? ButtonConfig.SIDE_LONG : ButtonConfig.TOP_LONG;
        if (ButtonConfig.NONE.equals(action(longSlot))) {
            // No long press to wait for: act on the press, not on the release.
            run(shortSlot);
        } else {
            mHandler.postDelayed(side ? mSideLongPress : mTopLongPress,
                    ViewConfiguration.getLongPressTimeout());
        }
    }

    private void onUp(boolean side, boolean canceled) {
        final Runnable longPress = side ? mSideLongPress : mTopLongPress;
        if (!mHandler.hasCallbacks(longPress)) {
            return;
        }
        // Released before the long press fired: it was a short press.
        mHandler.removeCallbacks(longPress);
        if (!canceled) {
            run(side ? ButtonConfig.SIDE_SHORT : ButtonConfig.TOP_SHORT);
        }
    }

    private String action(int slot) {
        return ButtonConfig.get(mResolver, slot, ActivityManager.getCurrentUser());
    }

    private void run(int slot) {
        final String action = action(slot);
        Slog.i(TAG, "slot " + slot + " -> " + action);
        if (action.startsWith(ButtonConfig.APP_PREFIX)) {
            openApp(action.substring(ButtonConfig.APP_PREFIX.length()));
            return;
        }
        switch (action) {
            case ButtonConfig.ASSISTANT:
                launchAssistant();
                break;
            case ButtonConfig.FRONTLIGHT:
                toggleFrontlight();
                break;
            case ButtonConfig.NOTES:
                openNotes();
                break;
            default:
                break;
        }
    }

    /** On or off is read from the brightness itself, there is no separate flag. */
    private void toggleFrontlight() {
        final int current = getSetting(Settings.System.SCREEN_BRIGHTNESS, BRIGHTNESS_OFF);
        final int next;
        if (current > BRIGHTNESS_OFF) {
            putSetting(SETTING_SAVED_BRIGHTNESS, current);
            next = BRIGHTNESS_OFF;
        } else {
            final int saved = getSetting(SETTING_SAVED_BRIGHTNESS, 0);
            next = saved > BRIGHTNESS_OFF ? saved : defaultBrightness();
            clearSavedBrightness();
        }
        putSetting(Settings.System.SCREEN_BRIGHTNESS, next);
        Slog.i(TAG, "frontlight -> screen_brightness=" + next);
    }

    /** The user moved the brightness slider while the light was off. */
    private void forgetSavedBrightnessIfLit() {
        if (getSetting(Settings.System.SCREEN_BRIGHTNESS, BRIGHTNESS_OFF) > BRIGHTNESS_OFF
                && getSetting(SETTING_SAVED_BRIGHTNESS, 0) > 0) {
            clearSavedBrightness();
        }
    }

    private int defaultBrightness() {
        final int def = BrightnessSynchronizer.brightnessFloatToInt(
                mPowerManager.getBrightnessConstraint(
                        PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_DEFAULT));
        return Math.max(def, BRIGHTNESS_OFF + 1);
    }

    /** Same path as PhoneWindowManager.launchAssistAction: the assistant role decides. */
    private void launchAssistant() {
        if (Settings.Secure.getIntForUser(mResolver, Settings.Secure.USER_SETUP_COMPLETE, 0,
                UserHandle.USER_CURRENT) == 0) {
            return;
        }
        final SearchManager searchManager = mContext.getSystemService(SearchManager.class);
        if (searchManager == null) {
            Slog.w(TAG, "assistant: no SearchManager");
            return;
        }
        final Bundle args = new Bundle();
        args.putInt(Intent.EXTRA_ASSIST_INPUT_DEVICE_ID, mDownDeviceId);
        args.putLong(Intent.EXTRA_TIME, mDownTime);
        try {
            searchManager.launchAssist(args);
        } catch (RuntimeException e) {
            Slog.w(TAG, "assistant failed", e);
        }
    }

    /** Does nothing when no app holds the Notes role. */
    private void openNotes() {
        final UserHandle user = UserHandle.of(ActivityManager.getCurrentUser());
        try {
            final List<String> holders = mContext.getSystemService(RoleManager.class)
                    .getRoleHoldersAsUser(RoleManager.ROLE_NOTES, user);
            if (holders.isEmpty()) {
                Slog.i(TAG, "notes: no notes app is set");
                return;
            }
            final Intent intent = new Intent(Intent.ACTION_CREATE_NOTE)
                    .setPackage(holders.get(0))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivityAsUser(intent, user);
        } catch (RuntimeException e) {
            Slog.w(TAG, "notes failed", e);
        }
    }

    private void openApp(String packageName) {
        final UserHandle user = UserHandle.of(ActivityManager.getCurrentUser());
        try {
            final Intent launch = mContext.createContextAsUser(user, 0)
                    .getPackageManager().getLaunchIntentForPackage(packageName);
            if (launch == null) {
                Slog.i(TAG, "app: " + packageName + " has no launcher activity");
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivityAsUser(launch, user);
        } catch (RuntimeException e) {
            Slog.w(TAG, "app: " + packageName + " failed", e);
        }
    }

    private int getSetting(String key, int def) {
        return Settings.System.getIntForUser(mResolver, key, def, UserHandle.USER_CURRENT);
    }

    private void putSetting(String key, int value) {
        Settings.System.putIntForUser(mResolver, key, value, UserHandle.USER_CURRENT);
    }

    private void clearSavedBrightness() {
        Settings.System.putStringForUser(mResolver, SETTING_SAVED_BRIGHTNESS, null,
                UserHandle.USER_CURRENT);
    }
}

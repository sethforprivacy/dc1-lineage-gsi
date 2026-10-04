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

import android.content.ContentResolver;
import android.provider.Settings;
import android.text.TextUtils;

/** The action of each button press, kept in Settings.Secure. */
final class ButtonConfig {

    static final int SIDE_SHORT = 0;
    static final int SIDE_LONG = 1;
    static final int TOP_SHORT = 2;
    static final int TOP_LONG = 3;

    static final String NONE = "none";
    static final String ASSISTANT = "assistant";
    static final String FRONTLIGHT = "frontlight";
    static final String NOTES = "notes";
    /** Followed by a package name. */
    static final String APP_PREFIX = "app:";

    private static final String[] KEYS = {
        "dc1_button_side_short",
        "dc1_button_side_long",
        "dc1_button_top_short",
        "dc1_button_top_long",
    };

    private static final String[] DEFAULTS = {ASSISTANT, FRONTLIGHT, NOTES, NONE};

    private ButtonConfig() {
    }

    static String get(ContentResolver resolver, int slot, int userId) {
        final String action = Settings.Secure.getStringForUser(resolver, KEYS[slot], userId);
        return TextUtils.isEmpty(action) ? DEFAULTS[slot] : action;
    }

    static void put(ContentResolver resolver, int slot, String action) {
        Settings.Secure.putString(resolver, KEYS[slot], action);
    }
}

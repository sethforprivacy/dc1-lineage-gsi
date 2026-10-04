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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.UserHandle;
import android.widget.ListView;
import android.widget.SimpleAdapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Picks the action of a short and a long press of each button. */
public class ButtonsSettingsActivity extends Activity {

    private static final String TITLE = "title";
    private static final String SUMMARY = "summary";

    private static final int[] SLOT_TITLES = {
        R.string.side_short,
        R.string.side_long,
        R.string.top_short,
        R.string.top_long,
    };

    private final List<Map<String, String>> mRows = new ArrayList<>();
    private SimpleAdapter mAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mAdapter = new SimpleAdapter(this, mRows, android.R.layout.simple_list_item_2,
                new String[] {TITLE, SUMMARY},
                new int[] {android.R.id.text1, android.R.id.text2});

        final ListView list = new ListView(this);
        list.setFitsSystemWindows(true);
        list.setAdapter(mAdapter);
        list.setOnItemClickListener((parent, view, position, id) -> pickAction(position));
        setContentView(list);
        refresh();
    }

    private void refresh() {
        mRows.clear();
        for (int slot = 0; slot < SLOT_TITLES.length; slot++) {
            final Map<String, String> row = new HashMap<>();
            row.put(TITLE, getString(SLOT_TITLES[slot]));
            row.put(SUMMARY, label(ButtonConfig.get(getContentResolver(), slot,
                    UserHandle.myUserId())));
            mRows.add(row);
        }
        mAdapter.notifyDataSetChanged();
    }

    private String label(String action) {
        if (action.startsWith(ButtonConfig.APP_PREFIX)) {
            final String pkg = action.substring(ButtonConfig.APP_PREFIX.length());
            final PackageManager pm = getPackageManager();
            try {
                return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (PackageManager.NameNotFoundException e) {
                return pkg;
            }
        }
        switch (action) {
            case ButtonConfig.ASSISTANT:
                return getString(R.string.action_assistant);
            case ButtonConfig.FRONTLIGHT:
                return getString(R.string.action_frontlight);
            case ButtonConfig.NOTES:
                return getString(R.string.action_notes);
            default:
                return getString(R.string.action_none);
        }
    }

    private void pickAction(int slot) {
        final String[] actions = {
            ButtonConfig.NONE,
            ButtonConfig.ASSISTANT,
            ButtonConfig.FRONTLIGHT,
            ButtonConfig.NOTES,
        };
        final CharSequence[] labels = new CharSequence[actions.length + 1];
        for (int i = 0; i < actions.length; i++) {
            labels[i] = label(actions[i]);
        }
        labels[actions.length] = getString(R.string.action_app);

        new AlertDialog.Builder(this)
                .setTitle(SLOT_TITLES[slot])
                .setItems(labels, (dialog, which) -> {
                    if (which == actions.length) {
                        pickApp(slot);
                    } else {
                        save(slot, actions[which]);
                    }
                })
                .show();
    }

    private void pickApp(int slot) {
        final PackageManager pm = getPackageManager();
        final Intent launcher = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER);
        final Map<String, String> packageByLabel = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (ResolveInfo info : pm.queryIntentActivities(launcher, 0)) {
            final String pkg = info.activityInfo.packageName;
            if (!packageByLabel.containsValue(pkg)) {
                packageByLabel.put(info.loadLabel(pm) + " (" + pkg + ")", pkg);
            }
        }
        final String[] labels = packageByLabel.keySet().toArray(new String[0]);
        final String[] packages = packageByLabel.values().toArray(new String[0]);

        new AlertDialog.Builder(this)
                .setTitle(SLOT_TITLES[slot])
                .setItems(labels, (dialog, which) ->
                        save(slot, ButtonConfig.APP_PREFIX + packages[which]))
                .show();
    }

    private void save(int slot, String action) {
        ButtonConfig.put(getContentResolver(), slot, action);
        refresh();
    }
}

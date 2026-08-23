package com.dc1.amber;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemProperties;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The amber frontlight surface: one continuous cool↔warm slider over the
 * authoritative 0..1023 setting, a friendly 0-100% readout, and saveable
 * presets.
 *
 * Write path is unchanged from the first version: {@code Settings.System}
 * plus an immediate {@link AmberService#mirrorSetting(Context)} so the LED
 * follows the finger even if the observer is momentarily not running.
 *
 * Presets live in their own SharedPreferences file as a CSV of raw setting
 * values ("0,512,1023"), sorted ascending and de-duplicated. On first run the
 * list is seeded with the two obvious ends (off / full); they are ordinary
 * presets afterwards and can be deleted like any other.
 */
public final class AmberSettingsActivity extends Activity {

    private static final String PREF_UI = "amber_ui";
    private static final String KEY_PRESETS = "presets";

    /** Values seeded on first run: fully cool and fully warm. */
    private static final int[] DEFAULT_PRESETS = {0, AmberService.SETTING_MAX};

    private SeekBar mSlider;
    private TextView mReadout;
    private TextView mStatus;
    private LinearLayout mPresetRow;

    /** Raw setting values, ascending. */
    private final List<Integer> mPresets = new ArrayList<>();
    /** Chip views, index-aligned with {@link #mPresets}. */
    private final List<TextView> mChips = new ArrayList<>();

    /** Last values pushed to the views, so drag updates stay cheap. */
    private int mShownPercent = -1;
    private int mActiveChip = -2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_amber);

        mReadout = findViewById(R.id.readout);
        mStatus = findViewById(R.id.status);
        mPresetRow = findViewById(R.id.preset_row);
        mSlider = findViewById(R.id.slider);

        mSlider.setMax(AmberService.SETTING_MAX);
        mSlider.setProgress(current());
        mSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    // Live: every drag position is applied immediately.
                    set(progress);
                } else {
                    render(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        loadPresets();
        buildPresetRow();
        render(current());
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The QS tile (or anything else writing the setting) may have moved it.
        int value = current();
        mSlider.setProgress(value);
        render(value);
        refreshStatus();
    }

    // ── value plumbing ──────────────────────────────────────────────────

    private int current() {
        return Settings.System.getInt(
                getContentResolver(), AmberService.SETTING, 0);
    }

    private void set(int value) {
        value = clamp(value);
        Settings.System.putInt(getContentResolver(), AmberService.SETTING, value);
        AmberService.mirrorSetting(this);
        render(value);
    }

    /** Apply a preset: move the slider (which does not re-enter set()) and write. */
    private void apply(int value) {
        mSlider.setProgress(clamp(value));
        set(value);
    }

    private static int clamp(int value) {
        if (value < 0) {
            return 0;
        }
        if (value > AmberService.SETTING_MAX) {
            return AmberService.SETTING_MAX;
        }
        return value;
    }

    /** 0..SETTING_MAX → 0..100, rounded to nearest. */
    private static int percentOf(int value) {
        return (clamp(value) * 100 + AmberService.SETTING_MAX / 2)
                / AmberService.SETTING_MAX;
    }

    /**
     * Readout + preset-chip selection for a value. Called for every drag
     * position, so it only touches views that actually change.
     */
    private void render(int value) {
        int v = clamp(value);
        int percent = percentOf(v);
        if (percent != mShownPercent) {
            mShownPercent = percent;
            mReadout.setText(getString(R.string.readout_percent, percent));
        }
        int active = mPresets.indexOf(Integer.valueOf(v));
        if (active != mActiveChip) {
            styleChip(mActiveChip, false);
            styleChip(active, true);
            mActiveChip = active;
        }
    }

    private void styleChip(int index, boolean active) {
        if (index < 0 || index >= mChips.size()) {
            return;
        }
        TextView chip = mChips.get(index);
        chip.setBackgroundResource(active ? R.drawable.chip_filled
                : R.drawable.chip_outline);
        chip.setTextColor(getColor(active ? R.color.paper : R.color.ink));
        chip.setSelected(active);
    }

    // ── presets ─────────────────────────────────────────────────────────

    private SharedPreferences prefs() {
        return getSharedPreferences(PREF_UI, Context.MODE_PRIVATE);
    }

    private void loadPresets() {
        mPresets.clear();
        String raw = prefs().getString(KEY_PRESETS, null);
        if (raw == null) {
            for (int v : DEFAULT_PRESETS) {
                mPresets.add(v);
            }
            savePresets();
            return;
        }
        for (String part : raw.split(",")) {
            try {
                int v = clamp(Integer.parseInt(part.trim()));
                if (!mPresets.contains(v)) {
                    mPresets.add(v);
                }
            } catch (NumberFormatException ignored) {
                // Skip junk; an empty string means "user deleted everything".
            }
        }
        Collections.sort(mPresets);
    }

    private void savePresets() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mPresets.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(mPresets.get(i).intValue());
        }
        prefs().edit().putString(KEY_PRESETS, sb.toString()).apply();
    }

    /** Off / Full at the ends, plain percentages in between. */
    private String nameOf(int value) {
        if (value <= 0) {
            return getString(R.string.preset_off);
        }
        if (value >= AmberService.SETTING_MAX) {
            return getString(R.string.preset_full);
        }
        return getString(R.string.preset_percent, percentOf(value));
    }

    private void buildPresetRow() {
        mPresetRow.removeAllViews();
        mChips.clear();
        // Chips start in their XML (unselected) state; force render() to
        // re-apply the active styling to whichever chip matches now.
        mActiveChip = -2;
        LayoutInflater inflater = getLayoutInflater();

        for (int i = 0; i < mPresets.size(); i++) {
            final int value = mPresets.get(i);
            TextView chip = (TextView) inflater.inflate(
                    R.layout.preset_chip, mPresetRow, false);
            chip.setText(nameOf(value));
            chip.setContentDescription(nameOf(value));
            chip.setOnClickListener(v -> apply(value));
            chip.setOnLongClickListener(v -> {
                confirmDelete(value);
                return true;
            });
            mPresetRow.addView(chip);
            mChips.add(chip);
        }

        TextView add = (TextView) inflater.inflate(
                R.layout.preset_chip, mPresetRow, false);
        add.setText(R.string.preset_add);
        add.setContentDescription(getString(R.string.preset_add_desc));
        add.setBackgroundResource(R.drawable.chip_add);
        add.setTextSize(28);
        add.setOnClickListener(v -> saveCurrent());
        mPresetRow.addView(add);
    }

    private void saveCurrent() {
        int value = current();
        if (mPresets.contains(value)) {
            toast(getString(R.string.preset_exists, nameOf(value)));
            return;
        }
        mPresets.add(value);
        Collections.sort(mPresets);
        savePresets();
        buildPresetRow();
        render(value);
        toast(getString(R.string.preset_saved, nameOf(value)));
    }

    private void confirmDelete(final int value) {
        // Uses android:alertDialogTheme from AmberTheme (ink, not accent teal).
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.preset_delete_title, nameOf(value)))
                .setNegativeButton(R.string.preset_delete_cancel, null)
                .setPositiveButton(R.string.preset_delete_confirm, (d, which) -> {
                    mPresets.remove(Integer.valueOf(value));
                    savePresets();
                    buildPresetRow();
                    render(current());
                    toast(getString(R.string.preset_removed, nameOf(value)));
                })
                .show();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    // ── diagnostics footer ──────────────────────────────────────────────

    private void refreshStatus() {
        String node = SystemProperties.get("ro.dc1.amber.node");
        if (node == null || node.isEmpty()) {
            AmberService.AmberLed led = AmberService.AmberLed.forContext(this);
            node = led.resolvedNode();
            int max = led.resolvedMax();
            if (node != null) {
                mStatus.setText(getString(R.string.status_node, node, max));
                return;
            }
        }
        if (node != null && !node.isEmpty()) {
            mStatus.setText(getString(R.string.status_node, node, -1));
        } else {
            mStatus.setText(R.string.status_no_node);
        }
    }
}

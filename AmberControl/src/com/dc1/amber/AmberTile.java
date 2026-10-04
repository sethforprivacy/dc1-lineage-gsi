package com.dc1.amber;

import android.app.Dialog;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.view.Window;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Quick-Settings tile: a tap opens a small dialog over the shade with the
 * warmth slider and the two ends (Off / Full); long-press opens the slider
 * activity. The subtitle shows the same 0-100% warmth the app's readout shows.
 *
 * The tile only writes the warmth setting and calls the mirror; the cool↔warm
 * crossfade (amber up / white down, and restoring white on the way back to 0)
 * lives in {@link AmberService#mirrorSetting}, so the dialog behaves exactly
 * like the slider in the app.
 */
public final class AmberTile extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        show(current());
    }

    @Override
    public void onClick() {
        showDialog(buildDialog());
    }

    private Dialog buildDialog() {
        Dialog dialog = new Dialog(this, R.style.AmberDialog);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_amber);

        final TextView readout = dialog.findViewById(R.id.readout);
        final SeekBar slider = dialog.findViewById(R.id.slider);
        TextView off = dialog.findViewById(R.id.preset_off);
        TextView full = dialog.findViewById(R.id.preset_full);

        int value = current();
        slider.setMax(AmberService.SETTING_MAX);
        slider.setProgress(value);
        readout.setText(getString(R.string.readout_percent, percent(value)));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    apply(progress);
                }
                readout.setText(getString(R.string.readout_percent, percent(progress)));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        off.setText(R.string.preset_off);
        off.setOnClickListener(v -> {
            slider.setProgress(0);
            apply(0);
        });
        full.setText(R.string.preset_full);
        full.setOnClickListener(v -> {
            slider.setProgress(AmberService.SETTING_MAX);
            apply(AmberService.SETTING_MAX);
        });
        return dialog;
    }

    private void apply(int value) {
        Settings.System.putInt(getContentResolver(), AmberService.SETTING, value);
        // The service mirrors the setting to the LED; also push directly so the
        // response is instant even if the observer is momentarily not running.
        AmberService.mirrorSetting(this);
        show(value);
    }

    /** State plus the same friendly percentage the app's readout shows. */
    private void show(int value) {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        tile.setState(value > 0 ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setSubtitle(value > 0
                ? getString(R.string.readout_percent, percent(value))
                : getString(R.string.tile_off));
        tile.updateTile();
    }

    private static int percent(int value) {
        if (value <= 0) {
            return 0;
        }
        if (value >= AmberService.SETTING_MAX) {
            return 100;
        }
        return (value * 100 + AmberService.SETTING_MAX / 2) / AmberService.SETTING_MAX;
    }

    private int current() {
        return Settings.System.getInt(
                getContentResolver(), AmberService.SETTING, 0);
    }
}

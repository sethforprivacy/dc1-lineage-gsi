package com.dc1.amber;

import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Quick-Settings tile: one tap toggles the amber frontlight between off and
 * the configured default (ro.dc1.amber.default, full by default); long-press
 * opens the slider activity (lockscreen-aware). The subtitle shows the same
 * 0-100% warmth the app's readout shows.
 *
 * The tile only writes the warmth setting and calls the mirror; the cool↔warm
 * crossfade (amber up / white down, and restoring white on the way back to 0)
 * lives in {@link AmberService#mirrorSetting}, so toggling from here behaves
 * exactly like dragging the slider.
 */
public final class AmberTile extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        show(current());
    }

    @Override
    public void onClick() {
        int cur = current();
        int next = cur > 0 ? 0 : AmberService.DEFAULT_AMBER;
        Settings.System.putInt(getContentResolver(), AmberService.SETTING, next);
        // The service mirrors the setting to the LED; also push directly so the
        // response is instant even if the observer is momentarily not running.
        AmberService.mirrorSetting(this);
        show(next);
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

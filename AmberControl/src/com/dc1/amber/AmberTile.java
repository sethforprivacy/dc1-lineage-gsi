package com.dc1.amber;

import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

/**
 * Quick-Settings tile: one tap toggles the amber frontlight between off and
 * the default (full "Amber: 255"); long-press opens the slider activity
 * (lockscreen-aware).
 */
public final class AmberTile extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        getQsTile().setState(current() > 0 ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        getQsTile().updateTile();
    }

    @Override
    public void onClick() {
        int cur = current();
        if (cur > 0) {
            Settings.System.putInt(getContentResolver(), AmberService.SETTING, 0);
        } else {
            Settings.System.putInt(
                    getContentResolver(), AmberService.SETTING, AmberService.DEFAULT_AMBER);
        }
        // The service mirrors the setting to the LED; also push directly so the
        // response is instant even if the observer is momentarily not running.
        AmberService.mirrorSetting(this);
        getQsTile().setState(cur > 0 ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
        getQsTile().updateTile();
    }

    @Override
    public void onStartListeningAfterLockedChange() {
        onStartListening();
    }

    @Override
    public void onStopListening() {
        super.onStopListening();
    }

    private int current() {
        return Settings.System.getInt(
                getContentResolver(), AmberService.SETTING, 0);
    }
}

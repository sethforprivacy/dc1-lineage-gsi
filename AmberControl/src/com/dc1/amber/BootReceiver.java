package com.dc1.amber;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * On boot: seed the amber default if unset, start the mirror service, and
 * push once so the frontlight is correct before the user touches anything.
 *
 * The push is the full crossfade. When warmth is 0 that deliberately leaves
 * {@code screen_brightness} untouched (see {@link AmberService#applyWhite}), so
 * booting never clobbers the user's own brightness; when warmth is non-zero,
 * re-applying the dimmed white is exactly what restores the warm look.
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            AmberService.ensureDefaultValue(context);
            AmberService.mirrorSetting(context);
            context.startService(new Intent(context, AmberService.class));
        }
    }
}

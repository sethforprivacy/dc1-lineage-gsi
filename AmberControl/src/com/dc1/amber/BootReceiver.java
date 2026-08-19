package com.dc1.amber;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * On boot: seed the amber default if unset, start the mirror service, and
 * push once so the frontlight is correct before the user touches anything.
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

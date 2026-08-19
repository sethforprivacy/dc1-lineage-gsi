package com.dc1.papermode

import android.content.Context
import android.provider.Settings

/**
 * Thin wrapper over the standard AOSP display settings that create the "paper"
 * experience. None of this is DC-1-specific — it works on stock and any GSI.
 *
 * - Grayscale uses the accessibility color matrix (Monochromacy). Lives in the
 *   Secure namespace, so the app needs WRITE_SECURE_SETTINGS (grant via adb).
 * - Refresh caps live in the System namespace, so they need WRITE_SETTINGS
 *   (user grants "Modify system settings", or adb grants WRITE_SECURE_SETTINGS).
 * - Amber rate is the DC-1 hardware channel; writing it only lights the LED on a
 *   rooted GSI where the amber daemon is running (or on stock).
 */
object DisplayController {

    private const val DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled"
    private const val DALTONIZER = "accessibility_display_daltonizer"
    private const val MONOCHROMACY = 0   // AccessibilityManager.DALTONIZER_MODE monochrome

    private const val MIN_RR = "min_refresh_rate"
    private const val PEAK_RR = "peak_refresh_rate"
    private const val AMBER = "screen_brightness_amber_rate"

    fun canWriteSecure(ctx: Context): Boolean = try {
        // A harmless read+write round-trip to confirm the permission is granted.
        Settings.Secure.getInt(ctx.contentResolver, DALTONIZER_ENABLED, 0)
        Settings.Secure.putInt(ctx.contentResolver, DALTONIZER_ENABLED,
            Settings.Secure.getInt(ctx.contentResolver, DALTONIZER_ENABLED, 0))
        true
    } catch (e: SecurityException) {
        false
    }

    fun canWriteSystem(ctx: Context): Boolean = Settings.System.canWrite(ctx)

    fun isGrayscale(ctx: Context): Boolean =
        Settings.Secure.getInt(ctx.contentResolver, DALTONIZER_ENABLED, 0) == 1

    fun setGrayscale(ctx: Context, on: Boolean) {
        if (on) {
            Settings.Secure.putInt(ctx.contentResolver, DALTONIZER, MONOCHROMACY)
            Settings.Secure.putInt(ctx.contentResolver, DALTONIZER_ENABLED, 1)
        } else {
            Settings.Secure.putInt(ctx.contentResolver, DALTONIZER_ENABLED, 0)
        }
    }

    /** Pin the panel to a single refresh rate. fps<=0 clears the cap. */
    fun setRefresh(ctx: Context, fps: Float) {
        if (fps <= 0f) {
            Settings.System.putString(ctx.contentResolver, MIN_RR, null)
            Settings.System.putString(ctx.contentResolver, PEAK_RR, null)
        } else {
            Settings.System.putFloat(ctx.contentResolver, MIN_RR, fps)
            Settings.System.putFloat(ctx.contentResolver, PEAK_RR, fps)
        }
    }

    fun getRefresh(ctx: Context): Float =
        try { Settings.System.getFloat(ctx.contentResolver, PEAK_RR) } catch (e: Exception) { 0f }

    /** 0..1023, same scale as stock. Only lights the LED on a rooted GSI w/ daemon. */
    fun setAmber(ctx: Context, value: Int) {
        Settings.System.putInt(ctx.contentResolver, AMBER, value.coerceIn(0, 1023))
    }

    fun getAmber(ctx: Context): Int =
        Settings.System.getInt(ctx.contentResolver, AMBER, 0)
}

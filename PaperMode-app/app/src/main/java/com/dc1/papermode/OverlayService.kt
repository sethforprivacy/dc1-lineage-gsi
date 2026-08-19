package com.dc1.papermode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Foreground service hosting a full-screen, non-interactive overlay used for:
 *  - a software "warm" blue-light filter (an amber wash at adjustable strength),
 *  - a "ghost-clear" flash (white->black sweep) to reset LCD smearing.
 *
 * This is the no-root fallback for warmth. It does NOT drive the hardware amber
 * LED (that's the root daemon's job); it tints what's on screen instead.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_WARMTH = "warmth"      // EXTRA_LEVEL 0..100
        const val ACTION_FLASH = "flash"
        const val EXTRA_LEVEL = "level"
        private const val CHANNEL = "papermode"
        private const val NOTIF_ID = 42

        // Warm tone applied over the screen; alpha scales with "warmth".
        private const val WARM_RGB = 0xFF7A33   // amber-ish
    }

    private lateinit var wm: WindowManager
    private var warmView: View? = null
    private var flashView: View? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { removeWarm(); stopSelf() }
            ACTION_WARMTH -> setWarmth(intent.getIntExtra(EXTRA_LEVEL, 0))
            ACTION_FLASH -> ghostClear()
            else -> ensureWarmView()
        }
        return START_STICKY
    }

    private fun overlayParams(touchable: Boolean): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY

        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type, flags, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    private fun ensureWarmView() {
        if (warmView != null) return
        warmView = View(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        wm.addView(warmView, overlayParams(touchable = false))
    }

    private fun setWarmth(level: Int) {
        ensureWarmView()
        val a = (level.coerceIn(0, 100) * 255 / 100)
        warmView?.setBackgroundColor((a shl 24) or WARM_RGB)
    }

    private fun removeWarm() {
        warmView?.let { runCatching { wm.removeView(it) } }
        warmView = null
    }

    /** Quick white->black->clear sweep to clear LCD ghosting/smearing. */
    private fun ghostClear() {
        if (flashView != null) return
        val v = View(this)
        flashView = v
        wm.addView(v, overlayParams(touchable = false))
        v.setBackgroundColor(Color.WHITE)
        main.postDelayed({ v.setBackgroundColor(Color.BLACK) }, 120)
        main.postDelayed({
            runCatching { wm.removeView(v) }
            flashView = null
        }, 240)
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Paper Mode", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("Paper Mode active")
            .setSmallIcon(R.drawable.ic_paper)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        removeWarm()
        flashView?.let { runCatching { wm.removeView(it) } }
        super.onDestroy()
    }
}

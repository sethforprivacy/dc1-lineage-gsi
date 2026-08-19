package com.dc1.papermode

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Single-screen control panel. View-based (no Compose) so it builds with just
 * core-ktx. Each control maps to one of the four "paper" mechanisms.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(header("Paper Mode"))
        status = TextView(this).apply { setPadding(0, dp(4), 0, dp(12)) }
        root.addView(status)

        // --- Grayscale (Secure setting; needs WRITE_SECURE_SETTINGS via adb) ---
        root.addView(section("Grayscale (monochrome)"))
        val gray = Switch(this).apply {
            text = "Monochrome rendering"
            isChecked = DisplayController.isGrayscale(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                try { DisplayController.setGrayscale(this@MainActivity, on) }
                catch (e: SecurityException) { needSecureToast() }
                refreshStatus()
            }
        }
        root.addView(gray)

        // --- Refresh presets (System setting; needs WRITE_SETTINGS) ---
        root.addView(section("Refresh pacing"))
        val rr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("60" to 60f, "30" to 30f, "10" to 10f, "6" to 6f, "Auto" to 0f).forEach { (label, fps) ->
            rr.addView(Button(this).apply {
                text = label
                setOnClickListener {
                    if (!DisplayController.canWriteSystem(this@MainActivity)) { needSystemToast(); return@setOnClickListener }
                    DisplayController.setRefresh(this@MainActivity, fps); refreshStatus()
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        root.addView(rr)

        // --- Software warm filter (overlay; no root) ---
        root.addView(section("Warm filter (software blue-light reduction)"))
        root.addView(TextView(this).apply {
            text = "Tints the screen warm. Not the hardware amber LED — that needs the root daemon."
            textSize = 12f
        })
        root.addView(seek(0, 100) { level ->
            if (!Settings.canDrawOverlays(this)) { needOverlayToast(); return@seek }
            startService(Intent(this, OverlayService::class.java)
                .setAction(OverlayService.ACTION_WARMTH)
                .putExtra(OverlayService.EXTRA_LEVEL, level))
        })

        // --- Ghost clear + stop overlay ---
        val ovRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ovRow.addView(Button(this).apply {
            text = "Ghost-clear flash"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) { needOverlayToast(); return@setOnClickListener }
                startService(Intent(this@MainActivity, OverlayService::class.java).setAction(OverlayService.ACTION_FLASH))
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        ovRow.addView(Button(this).apply {
            text = "Stop overlay"
            setOnClickListener {
                startService(Intent(this@MainActivity, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(ovRow)

        // --- Hardware amber (rooted GSI only) ---
        root.addView(section("Hardware amber LED (rooted GSI + daemon)"))
        root.addView(TextView(this).apply {
            text = "Writes screen_brightness_amber_rate (0..1023). The amber daemon lights the real LED."
            textSize = 12f
        })
        root.addView(seek(0, 1023, DisplayController.getAmber(this)) { v ->
            if (!DisplayController.canWriteSystem(this)) { needSystemToast(); return@seek }
            DisplayController.setAmber(this, v)
        })

        // --- Permission helpers ---
        root.addView(section("Permissions"))
        root.addView(Button(this).apply {
            text = "Grant 'Modify system settings'"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            }
        })
        root.addView(Button(this).apply {
            text = "Grant 'Display over other apps'"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })

        setContentView(ScrollView(this).apply { addView(root) })
        refreshStatus()
    }

    override fun onResume() { super.onResume(); refreshStatus() }

    private fun refreshStatus() {
        val secure = DisplayController.canWriteSecure(this)
        val system = DisplayController.canWriteSystem(this)
        val overlay = Settings.canDrawOverlays(this)
        status.text = buildString {
            append("WRITE_SECURE_SETTINGS: ${if (secure) "OK" else "MISSING"}\n")
            if (!secure) append("  → adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS\n")
            append("Modify system settings: ${if (system) "OK" else "MISSING"}\n")
            append("Overlay permission: ${if (overlay) "OK" else "MISSING"}\n")
            append("Grayscale: ${if (DisplayController.isGrayscale(this@MainActivity)) "on" else "off"}")
        }
    }

    private fun needSecureToast() = toast("Needs WRITE_SECURE_SETTINGS — see adb command in status.")
    private fun needSystemToast() = toast("Tap 'Grant Modify system settings' first.")
    private fun needOverlayToast() = toast("Tap 'Grant Display over other apps' first.")
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // --- tiny view helpers ---
    private fun header(s: String) = TextView(this).apply { text = s; textSize = 24f }
    private fun section(s: String) = TextView(this).apply {
        text = s; textSize = 16f; setPadding(0, dp(16), 0, dp(4))
    }
    private fun seek(min: Int, max: Int, start: Int = 0, onChange: (Int) -> Unit): View {
        return SeekBar(this).apply {
            this.max = max - min
            progress = (start - min).coerceIn(0, this.max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange(min + p) }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

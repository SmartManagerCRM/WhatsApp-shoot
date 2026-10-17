package com.smartmanager.whatsappshoot.platform

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.smartmanager.whatsappshoot.R

/**
 * Small capture button drawn as an accessibility overlay window. It needs no "draw over other apps"
 * permission: accessibility services may add TYPE_ACCESSIBILITY_OVERLAY windows. Only shown while
 * automation is running, so it doubles as a visible "automation is active" indicator.
 */
class FloatingControl(private val service: AccessibilityService, private val onTap: () -> Unit, private val onStop: () -> Unit) {
    private val wm = service.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
    private var view: android.widget.LinearLayout? = null
    private var wantVisible = false
    private var hiddenForCapture = false
    private var builtLanguage = ""

    val isShowing: Boolean get() = view?.visibility == View.VISIBLE

    fun setVisible(visible: Boolean) {
        wantVisible = visible
        apply()
    }

    fun hideForCapture() {
        hiddenForCapture = true
        apply()
    }

    fun showAfterCapture() {
        hiddenForCapture = false
        apply()
    }

    fun remove() {
        view?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        view = null
    }

    private fun apply() {
        val show = wantVisible && !hiddenForCapture
        if (view != null && builtLanguage != LocaleHelper.effective(service)) remove() // language changed in Settings: rebuild with the new texts
        if (show && view == null) create()
        view?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun create() {
        val labels = LocaleHelper.wrap(service)
        builtLanguage = LocaleHelper.effective(service)
        val d = service.resources.displayMetrics.density
        fun pill(text: String, color: Long, desc: String, onClick: () -> Unit) = TextView(service).apply {
            this.text = text
            contentDescription = desc
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            background = GradientDrawable().apply { cornerRadius = 24 * d; setColor(color.toInt()) }
            setOnClickListener { onClick() }
        }
        val box = android.widget.LinearLayout(service).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(pill(labels.getString(R.string.floating_label), 0xE6075E54, labels.getString(R.string.floating_description), onTap))
            // Emergency stop: always reachable while automation is active.
            addView(pill(labels.getString(R.string.floating_stop), 0xE6B3261E, labels.getString(R.string.floating_stop_description), onStop),
                android.widget.LinearLayout.LayoutParams(-2, -2).apply { topMargin = (6 * d).toInt() })
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = (8 * d).toInt()
        }
        try {
            wm.addView(box, lp)
            view = box
        } catch (e: Exception) {
            view = null
        }
    }
}

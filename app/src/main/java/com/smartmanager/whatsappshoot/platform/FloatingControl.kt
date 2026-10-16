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
class FloatingControl(private val service: AccessibilityService, private val onTap: () -> Unit) {
    private val wm = service.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
    private var view: TextView? = null
    private var wantVisible = false
    private var hiddenForCapture = false

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
        if (show && view == null) create()
        view?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun create() {
        val d = service.resources.displayMetrics.density
        val tv = TextView(service).apply {
            text = service.getString(R.string.floating_label)
            contentDescription = service.getString(R.string.floating_description)
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 24 * d
                setColor(0xE6075E54.toInt())
            }
            setOnClickListener { onTap() }
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
            wm.addView(tv, lp)
            view = tv
        } catch (e: Exception) {
            view = null
        }
    }
}

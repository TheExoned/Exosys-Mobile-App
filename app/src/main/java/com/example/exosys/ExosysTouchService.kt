package com.example.exosys

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlin.math.hypot

class ExosysTouchService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ExosysTouchService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    fun inyectarGesto(x1: Float, y1: Float, x2: Float, y2: Float, rawDurMs: Long) {
        val dist = hypot((x2 - x1).toDouble(), (y2 - y1).toDouble())
        val path = Path()

        val duration = if (dist < 18.0) {
            path.moveTo(x1, y1)
            if (rawDurMs > 500L) rawDurMs.coerceAtMost(1500L) else 45L
        } else {
            path.moveTo(x1, y1)
            path.lineTo(x2, y2)
            rawDurMs.coerceIn(100L, 600L)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        val builder = GestureDescription.Builder().addStroke(stroke)
        dispatchGesture(builder.build(), null, null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }
}
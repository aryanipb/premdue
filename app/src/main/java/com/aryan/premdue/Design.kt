package com.aryan.premdue

import android.animation.ValueAnimator
import android.content.Context
import android.os.PowerManager
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

object Design {
    const val PEARL: Int = -526345 // #f7f7f7; UI text uses the activity's warmer pearl.

    fun motion(context: Context) =
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) > 0f &&
            ValueAnimator.areAnimatorsEnabled() &&
            !context.getSystemService(PowerManager::class.java).isPowerSaveMode

    // Observe touches without consuming them; native View dispatch retains click/accessibility
    // handling.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    fun press(view: View) {
        view.setOnTouchListener { v, event ->
            if (motion(v.context))
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN ->
                        v.animate().scaleX(.984f).scaleY(.984f).setDuration(110).start()
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL ->
                        v.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(190)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                }
            false
        }
    }
}

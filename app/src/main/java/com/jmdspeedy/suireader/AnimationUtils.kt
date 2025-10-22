package com.jmdspeedy.suireader

import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView

/**
 * Starts the continuous, looping pulse animation on the provided ImageViews.
 */
fun startPulseAnimation(pulse1: ImageView, pulse2: ImageView, pulse3: ImageView) {
    pulse1.visibility = View.VISIBLE
    createPulseAnimator(pulse1).start()
    pulse2.postDelayed({
        pulse2.visibility = View.VISIBLE
        createPulseAnimator(pulse2).start()
    }, 600)
    pulse3.postDelayed({
        pulse3.visibility = View.VISIBLE
        createPulseAnimator(pulse3).start()
    }, 1200)
}

/**
 * Creates a single animator for a wave/pulse view.
 */
private fun createPulseAnimator(pulse: ImageView): ObjectAnimator {
    val animator = ObjectAnimator.ofPropertyValuesHolder(
        pulse,
        PropertyValuesHolder.ofFloat(View.SCALE_X, 0.5f, 3f),
        PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.5f, 3f),
        PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0f)
    )
    animator.duration = 4000
    animator.repeatCount = ObjectAnimator.INFINITE
    animator.repeatMode = ObjectAnimator.RESTART
    animator.interpolator = AccelerateDecelerateInterpolator()
    return animator
}

/**
 * Starts the subtle, periodic tilting animation on the provided View.
 */
fun startIdleTiltAnimation(view: View) {
    val rotationKf = PropertyValuesHolder.ofKeyframe(View.ROTATION,
        Keyframe.ofFloat(0f, 0f),       // Start
        Keyframe.ofFloat(0.1f, -5f),   // Tilt left
        Keyframe.ofFloat(0.2f, 5f),    // Tilt right
        Keyframe.ofFloat(0.3f, -5f),   // Tilt left
        Keyframe.ofFloat(0.4f, 5f),    // Tilt right
        Keyframe.ofFloat(0.5f, 0f),       // Back to center
        Keyframe.ofFloat(1f, 0f)        // Pause at center
    )

    val animator = ObjectAnimator.ofPropertyValuesHolder(
        view,
        rotationKf
    )
    animator.duration = 8000 // A long duration for the 'once in a while' feel
    animator.repeatCount = ObjectAnimator.INFINITE
    animator.repeatMode = ObjectAnimator.RESTART
    animator.interpolator = AccelerateDecelerateInterpolator()
    animator.startDelay = 1900
    animator.start()
}

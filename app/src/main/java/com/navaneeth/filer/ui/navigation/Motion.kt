package com.navaneeth.filer.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * Material motion for navigation:
 * - fade through between top-level tabs (no spatial relationship),
 * - shared X axis for going deeper and coming back (parent-child relationship).
 */
object Motion {
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    private const val DURATION = 350
    private const val FADE_OUT = 90
    private const val FADE_IN_DELAY = 90
    private const val SLIDE_FRACTION = 0.1f

    fun fadeThroughIn(): EnterTransition =
        fadeIn(tween(210, delayMillis = FADE_IN_DELAY, easing = EmphasizedDecelerate)) +
            scaleIn(tween(210, delayMillis = FADE_IN_DELAY, easing = EmphasizedDecelerate), initialScale = 0.94f)

    fun fadeThroughOut(): ExitTransition = fadeOut(tween(FADE_OUT, easing = EmphasizedAccelerate))

    fun sharedAxisXIn(forward: Boolean): EnterTransition =
        slideInHorizontally(tween(DURATION, easing = Emphasized)) { width ->
            val offset = (width * SLIDE_FRACTION).toInt()
            if (forward) offset else -offset
        } + fadeIn(tween(DURATION - FADE_IN_DELAY, delayMillis = FADE_IN_DELAY, easing = EmphasizedDecelerate))

    fun sharedAxisXOut(forward: Boolean): ExitTransition =
        slideOutHorizontally(tween(DURATION, easing = Emphasized)) { width ->
            val offset = (width * SLIDE_FRACTION).toInt()
            if (forward) -offset else offset
        } + fadeOut(tween(FADE_OUT, easing = EmphasizedAccelerate))
}

package com.gazefollower.mobile.experiment

import android.content.Context
import android.widget.FrameLayout
import kotlin.math.min
import kotlin.math.roundToInt

/** A container whose measured bounds are the exact aspect-fit video rectangle. */
class AspectFitFrameLayout(
    context: Context,
    private val contentWidth: Int,
    private val contentHeight: Int,
) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1)
        val scale = min(
            availableWidth.toFloat() / contentWidth.coerceAtLeast(1),
            availableHeight.toFloat() / contentHeight.coerceAtLeast(1),
        )
        val measuredContentWidth = (contentWidth * scale).roundToInt().coerceAtLeast(1)
        val measuredContentHeight = (contentHeight * scale).roundToInt().coerceAtLeast(1)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(measuredContentWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(measuredContentHeight, MeasureSpec.EXACTLY),
        )
    }
}

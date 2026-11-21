/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lunaris.settings.fragments.themes

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import com.android.internal.graphics.AdvancedBlurConfig
import com.android.settings.R

class AdvancedBlurPreviewView(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titleTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val backdropNode = RenderNode("AdvancedPreviewBackdrop")
    private val blurredBackdrop = RenderNode("AdvancedPreviewBlur")
    private val nodes = List(3) { RenderNode("AdvancedBlurPreview$it") }
    private val effects = List(3) { AdvancedBlurConfig.Effect() }
    private val bounds = Array(3) { RectF() }
    private val paths = Array(3) { Path() }
    private val radii = FloatArray(3)
    private val outerPath = Path()
    private var background: LinearGradient? = null
    private var labels = emptyList<String>()
    private var details = emptyList<String>()
    private var density = resources.displayMetrics.density
    private var titleSize = 0f
    private var detailSize = 0f
    private var phase = 0f
    private var nodesRecorded = false
    private var blurRadius = -1f
    private val observer = Runnable { invalidate() }
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 12000
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    init { updateResources() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        AdvancedBlurConfig.addListener(context, observer)
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        AdvancedBlurConfig.removeListener(observer)
        backdropNode.discardDisplayList()
        blurredBackdrop.discardDisplayList()
        nodes.forEach { it.discardDisplayList() }
        nodesRecorded = false
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updateAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateAnimation()
    }

    private fun updateAnimation() {
        if (!isAttachedToWindow) return
        if (windowVisibility == VISIBLE && isShown) {
            if (!animator.isStarted) animator.start() else animator.resume()
        } else {
            animator.pause()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateResources()
        updateGeometry()
        invalidate()
    }

    private fun updateResources() {
        density = resources.displayMetrics.density
        titleSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics)
        detailSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
        labels = listOf(R.string.advanced_blur_preview_connectivity,
            R.string.advanced_blur_preview_music, R.string.advanced_blur_preview_notification).map(context::getString)
        details = listOf(R.string.advanced_blur_preview_connectivity_detail,
            R.string.advanced_blur_preview_music_detail, R.string.advanced_blur_preview_notification_detail).map(context::getString)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGeometry()
    }

    private fun updateGeometry() {
        if (width <= 0 || height <= 0) return
        background = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(Color.rgb(29, 53, 93), Color.rgb(181, 99, 118), Color.rgb(233, 183, 100)),
            null, Shader.TileMode.CLAMP)
        outerPath.reset()
        outerPath.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 24f * density,
            24f * density, Path.Direction.CW)
        val inset = minOf(20f * density, width * 0.1f, height * 0.1f)
        val gap = minOf(12f * density, height * 0.05f)
        val cardHeight = (height - 2f * inset - 2f * gap) / 3f
        for (index in 0..2) {
            val top = inset + index * (cardHeight + gap)
            bounds[index].set(inset, top, width - inset, top + cardHeight)
            radii[index] = minOf(if (index == 0) cardHeight / 2f else 18f * density, cardHeight / 2f)
            paths[index].reset()
            paths[index].addRoundRect(bounds[index], radii[index], radii[index], Path.Direction.CW)
        }
        nodesRecorded = false
    }

    private fun recordNodes() {
        if (nodesRecorded) return
        blurredBackdrop.setPosition(0, 0, width, height)
        blurredBackdrop.beginRecording(width, height).drawRenderNode(backdropNode)
        blurredBackdrop.endRecording()
        nodes.forEach {
            it.setPosition(0, 0, width, height)
            it.beginRecording(width, height).drawRenderNode(blurredBackdrop)
            it.endRecording()
        }
        nodesRecorded = true
    }

    private fun backdrop(canvas: Canvas) {
        paint.shader = background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        paint.color = Color.rgb(98, 186, 202)
        canvas.drawCircle(width * (0.18f + 0.4f * phase), height * 0.32f, width * 0.27f, paint)
        paint.color = Color.rgb(250, 205, 126)
        canvas.drawCircle(width * (0.84f - 0.3f * phase), height * 0.8f, width * 0.36f, paint)
        paint.color = Color.rgb(216, 114, 149)
        canvas.drawRect(width * 0.55f, 0f, width * 0.65f, height.toFloat(), paint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val clip = canvas.save()
        canvas.clipPath(outerPath)
        val preset = AdvancedBlurConfig.current(context)
        val tint = context.getColor(com.android.internal.R.color.surface_effect_0)
        val blur = (preset?.blurDp ?: 34f) * density
        if (canvas.isHardwareAccelerated) {
            backdropNode.setPosition(0, 0, width, height)
            backdrop(backdropNode.beginRecording(width, height))
            backdropNode.endRecording()
            recordNodes()
            if (blurRadius != blur) {
                blurredBackdrop.setRenderEffect(if (blur > 0f)
                    RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP) else null)
                blurRadius = blur
            }
            canvas.drawRenderNode(backdropNode)
        } else {
            backdrop(canvas)
        }
        text.color = preset?.contentColor(context)
            ?: context.getColor(com.android.internal.R.color.materialColorOnSurface)
        for (index in 0..2) {
            val panel = bounds[index]
            val radius = radii[index]
            if (canvas.isHardwareAccelerated) {
                nodes[index].setRenderEffect(effects[index].resolve(context, preset, tint, 0f,
                    panel.left, panel.top, panel.right, panel.bottom, radius, radius, radius, radius))
                val save = canvas.save()
                canvas.clipPath(paths[index])
                canvas.drawRenderNode(nodes[index])
                canvas.restoreToCount(save)
            }
            if (preset == null || !canvas.isHardwareAccelerated) {
                paint.color = tint
                canvas.drawRoundRect(panel, radius, radius, paint)
            }
            text.textSize = minOf(titleSize, panel.height() * 0.28f)
            text.typeface = titleTypeface
            canvas.drawText(labels[index], panel.left + 18f * density, panel.centerY() - 2f * density, text)
            text.textSize = minOf(detailSize, panel.height() * 0.22f)
            text.typeface = Typeface.DEFAULT
            canvas.drawText(details[index], panel.left + 18f * density, panel.centerY() + 17f * density, text)
        }
        canvas.restoreToCount(clip)
    }
}

package br.anotaquestoes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Amostra minimalista da espessura atual (ponto + número). */
class WidthPreview(ctx: Context, private val draw: DrawingView) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dp = ctx.resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val size = draw.widthDp
        val tool = draw.tool
        val cx = w / 2f
        val cy = 13f * dp
        p.reset(); p.isAntiAlias = true
        when (tool) {
            DrawingView.Tool.HIGHLIGHTER -> {
                p.style = Paint.Style.FILL; p.color = draw.hlColor; p.alpha = 150
                val hh = min(size * dp, 16f * dp) / 2f
                c.drawRoundRect(RectF(cx - 10 * dp, cy - hh, cx + 10 * dp, cy + hh), 3 * dp, 3 * dp, p)
            }
            DrawingView.Tool.ERASER, DrawingView.Tool.ERASER_PART -> {
                p.style = Paint.Style.STROKE; p.color = Color.parseColor("#78909C"); p.strokeWidth = 1.5f * dp
                c.drawCircle(cx, cy, min(size * dp / 2f, 9f * dp), p)
            }
            DrawingView.Tool.LASSO -> {}
            else -> {
                p.style = Paint.Style.FILL
                p.color = if (tool == DrawingView.Tool.LASER) Color.parseColor("#FF1744") else draw.penColor
                c.drawCircle(cx, cy, max(1.5f * dp, min(size * dp / 2f, 9f * dp)), p)
            }
        }
        if (tool != DrawingView.Tool.LASSO) {
            p.reset(); p.isAntiAlias = true
            p.color = Color.parseColor("#78909C"); p.textSize = 9f * dp; p.textAlign = Paint.Align.CENTER
            c.drawText(size.toInt().toString(), cx, h - 4f * dp, p)
        }
    }
}

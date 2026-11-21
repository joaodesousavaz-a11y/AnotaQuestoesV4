package br.anotaquestoes

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

enum class Ic {
    PEN, MARKER, LINE, ARROW, RECT, OVAL, LASER, ERASER, ERASER_PART, LASSO,
    UNDO, REDO, MORE, DOCK, CHEV_L, CHEV_R, CHEV_U, CHEV_D, CURSOR, SWATCH, GRIP
}

/** Ícones de linha fina desenhados em uma grade 24x24. */
object Icons {
    private fun poly(c: Canvas, p: Paint, close: Boolean, vararg v: Float) {
        val path = Path()
        path.moveTo(v[0], v[1])
        var i = 2
        while (i < v.size) { path.lineTo(v[i], v[i + 1]); i += 2 }
        if (close) path.close()
        c.drawPath(path, p)
    }

    fun draw(c: Canvas, ic: Ic, p: Paint, fill: Paint) {
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = 1.8f
        fill.style = Paint.Style.FILL
        when (ic) {
            Ic.PEN -> {
                poly(c, p, true, 4f, 20f, 5f, 15f, 16f, 4f, 20f, 8f, 9f, 19f)
                c.drawLine(13f, 7f, 17f, 11f, p)
            }
            Ic.MARKER -> {
                poly(c, p, true, 8f, 12f, 14f, 6f, 19f, 11f, 13f, 17f)
                poly(c, p, true, 8f, 12f, 13f, 17f, 5f, 19f)
            }
            Ic.LINE -> c.drawLine(5f, 19f, 19f, 5f, p)
            Ic.ARROW -> {
                c.drawLine(5f, 19f, 19f, 5f, p)
                poly(c, p, false, 10f, 5f, 19f, 5f, 19f, 14f)
            }
            Ic.RECT -> c.drawRoundRect(RectF(4.5f, 6.5f, 19.5f, 17.5f), 2f, 2f, p)
            Ic.OVAL -> c.drawCircle(12f, 12f, 7.5f, p)
            Ic.LASER -> {
                c.drawCircle(12f, 12f, 2.4f, fill)
                c.drawCircle(12f, 12f, 6.5f, p)
            }
            Ic.ERASER -> {
                poly(c, p, true, 5f, 14f, 13f, 6f, 19f, 12f, 11f, 20f)
                c.drawLine(8f, 17f, 16f, 9f, p)
            }
            Ic.ERASER_PART -> {
                p.pathEffect = DashPathEffect(floatArrayOf(2.6f, 2.6f), 0f)
                c.drawCircle(12f, 12f, 7.5f, p)
                p.pathEffect = null
                c.drawCircle(12f, 12f, 1.5f, fill)
            }
            Ic.LASSO -> {
                p.pathEffect = DashPathEffect(floatArrayOf(2.6f, 2.6f), 0f)
                c.drawOval(RectF(4f, 5f, 20f, 16f), p)
                p.pathEffect = null
                c.drawLine(9f, 16f, 7f, 20f, p)
            }
            Ic.UNDO -> {
                poly(c, p, false, 9f, 7f, 5f, 11f, 9f, 15f)
                val pt = Path()
                pt.moveTo(5f, 11f); pt.lineTo(14f, 11f); pt.quadTo(19f, 11f, 19f, 16f); pt.lineTo(19f, 18f)
                c.drawPath(pt, p)
            }
            Ic.REDO -> {
                c.save(); c.translate(24f, 0f); c.scale(-1f, 1f)
                draw(c, Ic.UNDO, p, fill)
                c.restore()
            }
            Ic.MORE -> {
                c.drawCircle(6f, 12f, 1.7f, fill)
                c.drawCircle(12f, 12f, 1.7f, fill)
                c.drawCircle(18f, 12f, 1.7f, fill)
            }
            Ic.DOCK -> {
                c.drawRoundRect(RectF(4f, 5f, 20f, 19f), 3f, 3f, p)
                c.drawLine(4f, 10f, 20f, 10f, p)
                c.drawLine(10f, 10f, 10f, 19f, p)
            }
            Ic.CHEV_L -> poly(c, p, false, 14.5f, 6f, 8.5f, 12f, 14.5f, 18f)
            Ic.CHEV_R -> poly(c, p, false, 9.5f, 6f, 15.5f, 12f, 9.5f, 18f)
            Ic.CHEV_U -> poly(c, p, false, 6f, 14.5f, 12f, 8.5f, 18f, 14.5f)
            Ic.CHEV_D -> poly(c, p, false, 6f, 9.5f, 12f, 15.5f, 18f, 9.5f)
            Ic.CURSOR -> poly(c, p, true, 7f, 4f, 7f, 18f, 11f, 14.5f, 13.8f, 20.5f, 16f, 19.5f, 13.2f, 13.7f, 18f, 13.2f)
            Ic.SWATCH -> {
                c.drawCircle(12f, 12f, 8f, fill)
                p.strokeWidth = 1.2f
                c.drawCircle(12f, 12f, 8f, p)
            }
            Ic.GRIP -> {
                for (x in listOf(9f, 15f)) for (y in listOf(7f, 12f, 17f)) c.drawCircle(x, y, 1.3f, fill)
            }
        }
    }
}

class IconView(ctx: Context, icon: Ic) : View(ctx) {
    private val dp = ctx.resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)

    var icon: Ic = icon
        set(v) { field = v; invalidate() }
    var active = false
        set(v) { field = v; invalidate() }
    var tint = Color.parseColor("#455A64")
        set(v) { field = v; invalidate() }
    var circleBg: Int? = null
        set(v) { field = v; invalidate() }
    var swatch = Color.BLACK
        set(v) { field = v; invalidate() }
    var activeBg = Color.parseColor("#7C3AED")
    var iconDp = 20f

    init { isClickable = true }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        var fg = tint
        if (active) {
            bg.color = activeBg
            c.drawRoundRect(cx - 15 * dp, cy - 15 * dp, cx + 15 * dp, cy + 15 * dp, 10 * dp, 10 * dp, bg)
            fg = Color.WHITE
        } else if (circleBg != null) {
            bg.color = circleBg!!
            c.drawCircle(cx, cy, 17 * dp, bg)
        } else if (isPressed) {
            bg.color = Color.parseColor("#1A000000")
            c.drawRoundRect(cx - 15 * dp, cy - 15 * dp, cx + 15 * dp, cy + 15 * dp, 10 * dp, 10 * dp, bg)
        }
        val size = iconDp * dp
        c.save()
        c.translate(cx - size / 2f, cy - size / 2f)
        c.scale(size / 24f, size / 24f)
        stroke.color = fg
        fill.color = fg
        if (icon == Ic.SWATCH) { fill.color = swatch; stroke.color = Color.parseColor("#CFD8DC") }
        Icons.draw(c, icon, stroke, fill)
        c.restore()
    }
}

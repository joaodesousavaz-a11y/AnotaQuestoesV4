package br.anotaquestoes

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/** Camada transparente por cima do WebView. Só a caneta (ou o dedo, se ativado) desenha. */
class DrawingView(ctx: Context) : View(ctx) {

    enum class Tool { PEN, HIGHLIGHTER, LINE, ARROW, RECT, OVAL, LASER, ERASER, ERASER_PART, LASSO }
    private enum class Kind { PEN, HL, LINE, ARROW, RECT, OVAL }
    private enum class SelMode { NONE, MOVE, SCALE }

    private class Item(
        val kind: Kind, val color: Int, var width: Float,
        val pts: MutableList<PointF>, val pr: MutableList<Float>? = null
    ) {
        var path: Path? = null
    }

    private class LaserPt(val x: Float, val y: Float, val t: Long)

    // ---------------- configuração (mexida pela barra) ----------------
    var scrollSource: View? = null
    var drawingEnabled = true
    var fingerDraws = false
    var palmRejection = true
    var pressureOn = true
    var ruler = false
    var penColor = Color.parseColor("#E53935")
    var hlColor = Color.parseColor("#FFEB3B")
    var penW = 3f
    var hlW = 16f
    var eraserW = 20f
    var laserW = 6f
    var tool = Tool.PEN
        private set

    var widthDp: Float
        get() = when (tool) {
            Tool.HIGHLIGHTER -> hlW
            Tool.ERASER, Tool.ERASER_PART -> eraserW
            Tool.LASER -> laserW
            else -> penW
        }
        set(v) {
            when (tool) {
                Tool.HIGHLIGHTER -> hlW = v
                Tool.ERASER, Tool.ERASER_PART -> eraserW = v
                Tool.LASER -> laserW = v
                else -> penW = v
            }
        }

    val widthMin: Float
        get() = when (tool) {
            Tool.HIGHLIGHTER -> 6f
            Tool.ERASER, Tool.ERASER_PART -> 8f
            Tool.LASER -> 2f
            else -> 1f
        }
    val widthMax: Float
        get() = when (tool) {
            Tool.HIGHLIGHTER -> 60f
            Tool.ERASER, Tool.ERASER_PART -> 80f
            Tool.LASER -> 20f
            Tool.LASSO -> 1f
            else -> 30f
        }
    val widthStep: Float
        get() = when (tool) {
            Tool.HIGHLIGHTER, Tool.ERASER, Tool.ERASER_PART -> 2f
            else -> 1f
        }

    fun selectTool(t: Tool) {
        tool = t
        if (t == Tool.ERASER || t == Tool.ERASER_PART) eraserTool = t
        if (t != Tool.LASSO) clearSelection()
        invalidate()
    }

    // ---------------- estado ----------------
    private val dp = ctx.resources.displayMetrics.density
    private var eraserTool = Tool.ERASER
    private var items = ArrayList<Item>()
    private val undoStack = ArrayList<List<Item>>()
    private val redoStack = ArrayList<List<Item>>()
    private var current: Item? = null
    private var activeTool = Tool.PEN
    private var rulerActive = false
    private var stylusTouch = false
    private var changed = false
    private var downPx = 0f
    private var downPy = 0f
    private var lastEx = 0f
    private var lastEy = 0f
    private var prSmooth = 0.5f
    private var eraserPos: PointF? = null
    private var penNearUntil = 0L
    private var ignoring = false
    private val lasers = ArrayList<MutableList<LaserPt>>()
    private var laserCur: MutableList<LaserPt>? = null

    // seleção (laço)
    private var sel: List<Item> = emptyList()
    private var selRect: RectF? = null
    private var lasso: MutableList<PointF>? = null
    private var selMode = SelMode.NONE
    private var editStarted = false
    private var startX = 0f
    private var startY = 0f
    private var scaleCorner = 0
    private var basePts: List<List<PointF>> = emptyList()
    private var baseW: List<Float> = emptyList()
    private var baseRect = RectF()
    private val purple = Color.parseColor("#7C3AED")
    private val hDelete = 10
    private val hDup = 11

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dash = DashPathEffect(floatArrayOf(6f * dp, 4f * dp), 0f)

    private val sx get() = (scrollSource?.scrollX ?: 0).toFloat()
    private val sy get() = (scrollSource?.scrollY ?: 0).toFloat()
    private val eraserR get() = eraserW * dp / 2f
    private val selPad get() = 8f * dp

    // ---------------- histórico ----------------
    private fun pushUndo() {
        undoStack.add(ArrayList(items))
        if (undoStack.size > 100) undoStack.removeAt(0)
        redoStack.clear()
    }

    private fun commit(i: Item) { pushUndo(); items.add(i); invalidate() }

    fun undo() {
        if (undoStack.isEmpty()) return
        clearSelection()
        redoStack.add(ArrayList(items))
        items = ArrayList(undoStack.removeAt(undoStack.lastIndex))
        invalidate()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        clearSelection()
        undoStack.add(ArrayList(items))
        items = ArrayList(redoStack.removeAt(redoStack.lastIndex))
        invalidate()
    }

    fun clearAll() {
        if (items.isEmpty()) return
        clearSelection()
        pushUndo(); items = ArrayList(); invalidate()
    }

    // ---------------- toque ----------------
    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        val tt = e.getToolType(0)
        if (tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER)
            penNearUntil = SystemClock.uptimeMillis() + 700
        return super.onGenericMotionEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!drawingEnabled) return false
        if (ignoring) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) ignoring = false
            return true
        }
        val now = SystemClock.uptimeMillis()
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            val tt = e.getToolType(0)
            val stylus = tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER
            if (stylus) {
                penNearUntil = now + 700
            } else {
                if (palmRejection && now < penNearUntil) { ignoring = true; return true }
                if (!fingerDraws) return false
            }
            stylusTouch = stylus
            val eraserPen = tt == MotionEvent.TOOL_TYPE_ERASER ||
                (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
            activeTool = if (eraserPen) eraserTool else tool
            rulerActive = ruler
            changed = false
            downPx = e.x + sx; downPy = e.y + sy
            prSmooth = e.pressure
            begin(downPx, downPy, e.x, e.y, e.pressure)
            return true
        }
        if (stylusTouch) penNearUntil = now + 700
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until e.historySize)
                    move(e.getHistoricalX(h) + sx, e.getHistoricalY(h) + sy,
                        e.getHistoricalX(h), e.getHistoricalY(h), e.getHistoricalPressure(h))
                move(e.x + sx, e.y + sy, e.x, e.y, e.pressure)
            }
            MotionEvent.ACTION_UP -> {
                move(e.x + sx, e.y + sy, e.x, e.y, e.pressure)
                end(false)
            }
            MotionEvent.ACTION_CANCEL -> end(true)
        }
        return true
    }

    private fun begin(px: Float, py: Float, scrX: Float, scrY: Float, p: Float) {
        when (activeTool) {
            Tool.PEN -> current = Item(
                Kind.PEN, penColor, penW * dp, mutableListOf(PointF(px, py)),
                if (pressureOn && stylusTouch) mutableListOf(p) else null
            )
            Tool.HIGHLIGHTER -> current = Item(Kind.HL, hlColor, hlW * dp, mutableListOf(PointF(px, py)))
            Tool.LINE -> current = Item(Kind.LINE, penColor, penW * dp, mutableListOf(PointF(px, py), PointF(px, py)))
            Tool.ARROW -> current = Item(Kind.ARROW, penColor, penW * dp, mutableListOf(PointF(px, py), PointF(px, py)))
            Tool.RECT -> current = Item(Kind.RECT, penColor, penW * dp, mutableListOf(PointF(px, py), PointF(px, py)))
            Tool.OVAL -> current = Item(Kind.OVAL, penColor, penW * dp, mutableListOf(PointF(px, py), PointF(px, py)))
            Tool.LASER -> {
                val s = mutableListOf(LaserPt(scrX, scrY, SystemClock.uptimeMillis()))
                lasers.add(s); laserCur = s
            }
            Tool.ERASER -> { eraserPos = PointF(scrX, scrY); lastEx = px; lastEy = py; eraseAt(px, py) }
            Tool.ERASER_PART -> { eraserPos = PointF(scrX, scrY); lastEx = px; lastEy = py; eraseParts(px, py) }
            Tool.LASSO -> lassoBegin(px, py)
        }
        invalidate()
    }

    private fun move(px: Float, py: Float, scrX: Float, scrY: Float, p: Float) {
        val c = current
        prSmooth = prSmooth * 0.6f + p * 0.4f
        when (activeTool) {
            Tool.PEN -> if (c != null) {
                val l = c.pts.last()
                if (hypot(px - l.x, py - l.y) > 0.6f * dp) {
                    c.pts.add(PointF(px, py)); c.pr?.add(prSmooth); c.path = null
                }
            }
            Tool.HIGHLIGHTER -> if (c != null) {
                if (rulerActive) {
                    if (c.pts.size < 2) c.pts.add(PointF(px, py)) else c.pts[1].set(px, py)
                    c.path = null
                } else {
                    val l = c.pts.last()
                    if (hypot(px - l.x, py - l.y) > 0.6f * dp) { c.pts.add(PointF(px, py)); c.path = null }
                }
            }
            Tool.LINE, Tool.ARROW, Tool.RECT, Tool.OVAL -> if (c != null) { c.pts[1].set(px, py); c.path = null }
            Tool.ERASER -> { eraserPos?.set(scrX, scrY); eraseAlong(px, py, false) }
            Tool.ERASER_PART -> { eraserPos?.set(scrX, scrY); eraseAlong(px, py, true) }
            Tool.LASER -> laserCur?.let { s ->
                if (!lasers.contains(s)) lasers.add(s)
                s.add(LaserPt(scrX, scrY, SystemClock.uptimeMillis()))
            }
            Tool.LASSO -> lassoMove(px, py)
        }
        invalidate()
    }

    private fun end(cancelled: Boolean) {
        val c = current
        if (c != null && !cancelled) {
            val keep = when (c.kind) {
                Kind.LINE, Kind.ARROW, Kind.RECT, Kind.OVAL ->
                    hypot(c.pts[1].x - c.pts[0].x, c.pts[1].y - c.pts[0].y) > 3f * dp
                else -> true
            }
            if (keep) commit(c)
        }
        current = null
        laserCur = null
        eraserPos = null
        if (activeTool == Tool.LASSO) lassoEnd(cancelled)
        invalidate()
    }

    // ---------------- borrachas ----------------
    private fun eraseAlong(px: Float, py: Float, part: Boolean) {
        val dx = px - lastEx; val dy = py - lastEy
        val dist = hypot(dx, dy)
        val stepLen = max(eraserR * 0.5f, 2f * dp)
        val n = max(1, ceil(dist / stepLen).toInt())
        for (s in 1..n) {
            val x = lastEx + dx * s / n; val y = lastEy + dy * s / n
            if (part) eraseParts(x, y) else eraseAt(x, y)
        }
        lastEx = px; lastEy = py
    }

    /** Borracha de traço inteiro. */
    private fun eraseAt(px: Float, py: Float) {
        val r = eraserR
        val del = items.filter { hit(it, px, py, r) }
        if (del.isNotEmpty()) {
            if (!changed) { pushUndo(); changed = true }
            clearSelection()
            items.removeAll(del.toSet())
            invalidate()
        }
    }

    /** Borracha parcial: corta os traços de verdade (não deixa "máscara" para trás). */
    private fun eraseParts(px: Float, py: Float) {
        val rr = eraserR
        val result = ArrayList<Item>(items.size)
        var any = false
        for (itm in items) {
            val pieces = splitItem(itm, px, py, rr + itm.width * 0.25f)
            if (pieces == null) result.add(itm) else { any = true; result.addAll(pieces) }
        }
        if (any) {
            if (!changed) { pushUndo(); changed = true }
            clearSelection()
            items = result
            invalidate()
        }
    }

    private fun polylines(i: Item): List<List<PointF>> = when (i.kind) {
        Kind.PEN, Kind.HL, Kind.LINE -> listOf<List<PointF>>(i.pts)
        Kind.ARROW -> {
            val a = i.pts[0]; val b = i.pts[1]
            val ang = atan2(b.y - a.y, b.x - a.x)
            val len = max(12f * dp, i.width * 4f)
            val t1 = ang + PI.toFloat() + 0.5f
            val t2 = ang + PI.toFloat() - 0.5f
            listOf(
                listOf(a, b),
                listOf(b, PointF(b.x + len * cos(t1), b.y + len * sin(t1))),
                listOf(b, PointF(b.x + len * cos(t2), b.y + len * sin(t2)))
            )
        }
        Kind.RECT -> {
            val a = i.pts[0]; val b = i.pts[1]
            listOf(listOf(a, PointF(b.x, a.y), b, PointF(a.x, b.y), a))
        }
        Kind.OVAL -> listOf(ellipsePts(i.pts[0], i.pts[1], 48))
    }

    private fun ellipsePts(a: PointF, b: PointF, n: Int): List<PointF> {
        val cx = (a.x + b.x) / 2; val cy = (a.y + b.y) / 2
        val rx = abs(b.x - a.x) / 2; val ry = abs(b.y - a.y) / 2
        return (0..n).map { k ->
            val t = (k.toDouble() / n * 2 * PI).toFloat()
            PointF(cx + rx * cos(t), cy + ry * sin(t))
        }
    }

    private fun splitItem(i: Item, ex: Float, ey: Float, rr: Float): List<Item>? {
        val lines = polylines(i)
        if (lines.none { polyHit(it, ex, ey, rr) }) return null
        val kind = if (i.kind == Kind.HL) Kind.HL else Kind.PEN
        val out = ArrayList<Item>()
        for (line in lines) {
            val prs = if (i.pr != null && lines.size == 1 && i.pr.size == line.size) i.pr else null
            splitLine(i, line, prs, ex, ey, rr, kind, out)
        }
        return out
    }

    private fun splitLine(
        i: Item, pts: List<PointF>, prs: List<Float>?, ex: Float, ey: Float, rr: Float,
        kind: Kind, out: MutableList<Item>
    ) {
        val maxStep = 2f * dp
        val dpts = ArrayList<PointF>()
        val dprs: ArrayList<Float>? = if (prs != null) ArrayList() else null
        for (k in pts.indices) {
            if (k == 0) {
                dpts.add(PointF(pts[0].x, pts[0].y))
                if (prs != null && dprs != null) dprs.add(prs[0])
                continue
            }
            val a = pts[k - 1]; val b = pts[k]
            val n = max(1, ceil(hypot(b.x - a.x, b.y - a.y) / maxStep).toInt())
            for (s in 1..n) {
                val t = s.toFloat() / n
                dpts.add(PointF(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
                if (prs != null && dprs != null) dprs.add(prs[k - 1] + (prs[k] - prs[k - 1]) * t)
            }
        }
        var run = ArrayList<PointF>()
        var runPr: ArrayList<Float>? = if (dprs != null) ArrayList() else null
        for (k in dpts.indices) {
            val p = dpts[k]
            if (hypot(p.x - ex, p.y - ey) <= rr) {
                if (run.size >= 2) out.add(Item(kind, i.color, i.width, run, runPr))
                run = ArrayList()
                runPr = if (dprs != null) ArrayList() else null
            } else {
                run.add(p)
                if (dprs != null) runPr?.add(dprs[k])
            }
        }
        if (run.size >= 2) out.add(Item(kind, i.color, i.width, run, runPr))
    }

    // ---------------- laço / seleção ----------------
    fun clearSelection() {
        sel = emptyList(); selRect = null; lasso = null
        selMode = SelMode.NONE; editStarted = false
        invalidate()
    }

    private fun select(list: List<Item>) {
        if (list.isEmpty()) { clearSelection(); return }
        sel = list
        val r = RectF(itemBounds(list[0]))
        for (k in 1 until list.size) r.union(itemBounds(list[k]))
        selRect = r
        invalidate()
    }

    private fun itemBounds(i: Item): RectF {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (p in i.pts) { l = min(l, p.x); t = min(t, p.y); r = max(r, p.x); b = max(b, p.y) }
        val pad = i.width / 2f + (if (i.kind == Kind.ARROW) max(12f * dp, i.width * 4f) else 0f)
        return RectF(l - pad, t - pad, r + pad, b + pad)
    }

    private fun corners(r: RectF): Array<PointF> {
        val g = selPad
        return arrayOf(
            PointF(r.left - g, r.top - g), PointF(r.right + g, r.top - g),
            PointF(r.right + g, r.bottom + g), PointF(r.left - g, r.bottom + g)
        )
    }

    private fun badgeY(r: RectF): Float {
        val above = r.top - selPad - 26f * dp
        return if (above - sy < 14f * dp) r.bottom + selPad + 26f * dp else above
    }

    private fun handleAt(px: Float, py: Float): Int {
        val r = selRect ?: return -1
        val by = badgeY(r)
        val cx = r.centerX()
        if (hypot(px - (cx + 22f * dp), py - by) <= 16f * dp) return hDelete
        if (hypot(px - (cx - 22f * dp), py - by) <= 16f * dp) return hDup
        val cs = corners(r)
        for (k in 0..3) if (hypot(px - cs[k].x, py - cs[k].y) <= 18f * dp) return k
        return -1
    }

    private fun prepEdit(px: Float, py: Float) {
        basePts = sel.map { it.pts.map { p -> PointF(p.x, p.y) } }
        baseW = sel.map { it.width }
        baseRect = RectF(selRect!!)
        startX = px; startY = py
        editStarted = false
    }

    /** Na primeira mudança real: guarda o estado no histórico e passa a editar cópias. */
    private fun startEdit() {
        pushUndo()
        val copies = sel.map { cloneItem(it) }
        for (k in sel.indices) {
            val idx = items.indexOf(sel[k])
            if (idx >= 0) items[idx] = copies[k]
        }
        sel = copies
        editStarted = true
    }

    private fun cloneItem(i: Item) =
        Item(i.kind, i.color, i.width, i.pts.map { PointF(it.x, it.y) }.toMutableList(), i.pr?.toMutableList())

    private fun applyTransform(tx: Float, ty: Float, s: Float, ax: Float, ay: Float) {
        for (k in sel.indices) {
            val it = sel[k]
            for (j in it.pts.indices) {
                val b = basePts[k][j]
                it.pts[j].set(ax + (b.x - ax) * s + tx, ay + (b.y - ay) * s + ty)
            }
            it.width = max(1f, baseW[k] * s)
            it.path = null
        }
        val x1 = ax + (baseRect.left - ax) * s + tx
        val x2 = ax + (baseRect.right - ax) * s + tx
        val y1 = ay + (baseRect.top - ay) * s + ty
        val y2 = ay + (baseRect.bottom - ay) * s + ty
        selRect = RectF(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
    }

    private fun lassoBegin(px: Float, py: Float) {
        selMode = SelMode.NONE; editStarted = false; lasso = null
        val r = selRect
        if (r != null && sel.isNotEmpty()) {
            val h = handleAt(px, py)
            if (h == hDelete) { deleteSelection(); return }
            if (h == hDup) { duplicateSelection(); return }
            if (h in 0..3) { prepEdit(px, py); selMode = SelMode.SCALE; scaleCorner = h; return }
            val inflated = RectF(r)
            inflated.inset(-selPad, -selPad)
            if (inflated.contains(px, py)) { prepEdit(px, py); selMode = SelMode.MOVE; return }
            clearSelection()
        }
        lasso = mutableListOf(PointF(px, py))
    }

    private fun lassoMove(px: Float, py: Float) {
        when (selMode) {
            SelMode.MOVE -> {
                val dx = px - startX; val dy = py - startY
                if (!editStarted && hypot(dx, dy) > 4f * dp) startEdit()
                if (editStarted) applyTransform(dx, dy, 1f, 0f, 0f)
            }
            SelMode.SCALE -> {
                if (!editStarted && hypot(px - startX, py - startY) > 3f * dp) startEdit()
                if (editStarted) {
                    val corner = when (scaleCorner) {
                        0 -> PointF(baseRect.left, baseRect.top)
                        1 -> PointF(baseRect.right, baseRect.top)
                        2 -> PointF(baseRect.right, baseRect.bottom)
                        else -> PointF(baseRect.left, baseRect.bottom)
                    }
                    val anchor = when (scaleCorner) {
                        0 -> PointF(baseRect.right, baseRect.bottom)
                        1 -> PointF(baseRect.left, baseRect.bottom)
                        2 -> PointF(baseRect.left, baseRect.top)
                        else -> PointF(baseRect.right, baseRect.top)
                    }
                    val dxv = corner.x - anchor.x; val dyv = corner.y - anchor.y
                    val denom = (startX - anchor.x) * dxv + (startY - anchor.y) * dyv
                    if (abs(denom) > 1f) {
                        val num = (px - anchor.x) * dxv + (py - anchor.y) * dyv
                        val s = (num / denom).coerceIn(0.1f, 20f)
                        applyTransform(0f, 0f, s, anchor.x, anchor.y)
                    }
                }
            }
            SelMode.NONE -> lasso?.let { l ->
                val last = l.last()
                if (hypot(px - last.x, py - last.y) > 2f * dp) l.add(PointF(px, py))
            }
        }
    }

    private fun lassoEnd(cancelled: Boolean) {
        if (selMode != SelMode.NONE) { selMode = SelMode.NONE; return }
        val poly = lasso
        lasso = null
        if (cancelled || poly == null) return
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (p in poly) { l = min(l, p.x); t = min(t, p.y); r = max(r, p.x); b = max(b, p.y) }
        if (poly.size < 4 || (r - l < 10f * dp && b - t < 10f * dp)) {      // toque simples
            val hitItem = items.lastOrNull { hit(it, downPx, downPy, 8f * dp) }
            if (hitItem != null) select(listOf(hitItem)) else clearSelection()
            return
        }
        val chosen = items.filter { itm ->
            val sp = samplePts(itm)
            val inCount = sp.count { inside(poly, it.x, it.y) }
            inCount >= max(1, ceil(sp.size * 0.5).toInt())
        }
        if (chosen.isEmpty()) clearSelection() else select(chosen)
    }

    private fun deleteSelection() {
        if (sel.isEmpty()) return
        pushUndo()
        items.removeAll(sel.toSet())
        clearSelection()
    }

    private fun duplicateSelection() {
        if (sel.isEmpty()) return
        pushUndo()
        val off = 16f * dp
        val copies = sel.map { s ->
            val c = cloneItem(s)
            for (p in c.pts) { p.x += off; p.y += off }
            c
        }
        items.addAll(copies)
        select(copies)
    }

    private fun inside(poly: List<PointF>, x: Float, y: Float): Boolean {
        var c = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) c = !c
            j = i
        }
        return c
    }

    private fun samplePts(i: Item): List<PointF> = when (i.kind) {
        Kind.PEN, Kind.HL -> {
            val n = i.pts.size
            val st = max(1, n / 30)
            val out = ArrayList<PointF>()
            var k = 0
            while (k < n) { out.add(i.pts[k]); k += st }
            out.add(i.pts.last())
            out
        }
        Kind.LINE, Kind.ARROW -> {
            val a = i.pts[0]; val b = i.pts[1]
            (0..4).map { k -> PointF(a.x + (b.x - a.x) * k / 4f, a.y + (b.y - a.y) * k / 4f) }
        }
        Kind.RECT -> {
            val a = i.pts[0]; val b = i.pts[1]
            val mx = (a.x + b.x) / 2; val my = (a.y + b.y) / 2
            listOf(a, PointF(b.x, a.y), b, PointF(a.x, b.y),
                PointF(mx, a.y), PointF(b.x, my), PointF(mx, b.y), PointF(a.x, my))
        }
        Kind.OVAL -> ellipsePts(i.pts[0], i.pts[1], 8)
    }

    // ---------------- geometria ----------------
    private fun segDist(x: Float, y: Float, a: PointF, b: PointF): Float {
        val dx = b.x - a.x; val dy = b.y - a.y
        val l2 = dx * dx + dy * dy
        val t = if (l2 == 0f) 0f else (((x - a.x) * dx + (y - a.y) * dy) / l2).coerceIn(0f, 1f)
        return hypot(x - (a.x + t * dx), y - (a.y + t * dy))
    }

    private fun polyHit(p: List<PointF>, x: Float, y: Float, tol: Float): Boolean {
        if (p.size == 1) return hypot(x - p[0].x, y - p[0].y) <= tol
        for (k in 1 until p.size) if (segDist(x, y, p[k - 1], p[k]) <= tol) return true
        return false
    }

    private fun hit(i: Item, x: Float, y: Float, r: Float): Boolean {
        val tol = r + i.width / 2f
        return when (i.kind) {
            Kind.PEN, Kind.HL, Kind.LINE, Kind.ARROW -> polyHit(i.pts, x, y, tol)
            Kind.RECT -> {
                val a = i.pts[0]; val b = i.pts[1]
                polyHit(listOf(a, PointF(b.x, a.y), b, PointF(a.x, b.y), a), x, y, tol)
            }
            Kind.OVAL -> polyHit(ellipsePts(i.pts[0], i.pts[1], 48), x, y, tol)
        }
    }

    private fun pathOf(i: Item): Path {
        i.path?.let { return it }
        val p = Path()
        when (i.kind) {
            Kind.PEN, Kind.HL -> {
                val pts = i.pts
                p.moveTo(pts[0].x, pts[0].y)
                if (pts.size == 1) p.lineTo(pts[0].x + 0.01f, pts[0].y)
                else {
                    for (k in 1 until pts.size) {
                        val a = pts[k - 1]; val b = pts[k]
                        p.quadTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2)
                    }
                    p.lineTo(pts.last().x, pts.last().y)
                }
            }
            Kind.LINE -> { p.moveTo(i.pts[0].x, i.pts[0].y); p.lineTo(i.pts[1].x, i.pts[1].y) }
            Kind.ARROW -> {
                val a = i.pts[0]; val b = i.pts[1]
                p.moveTo(a.x, a.y); p.lineTo(b.x, b.y)
                val ang = atan2(b.y - a.y, b.x - a.x)
                val len = max(12f * dp, i.width * 4f)
                for (s in listOf(-1f, 1f)) {
                    val t = ang + PI.toFloat() + s * 0.5f
                    p.moveTo(b.x, b.y)
                    p.lineTo(b.x + len * cos(t), b.y + len * sin(t))
                }
            }
            Kind.RECT -> {
                val a = i.pts[0]; val b = i.pts[1]
                p.addRect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y), Path.Direction.CW)
            }
            Kind.OVAL -> {
                val a = i.pts[0]; val b = i.pts[1]
                p.addOval(RectF(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y)), Path.Direction.CW)
            }
        }
        i.path = p
        return p
    }

    // ---------------- desenho ----------------
    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(-sx, -sy)
        for (i in items) drawItem(canvas, i)
        current?.let { drawItem(canvas, it) }
        drawSelection(canvas)
        canvas.restore()
        drawLasers(canvas)
        drawEraserRing(canvas)
    }

    private fun pf(p: Float) = 0.5f + 0.9f * p.coerceIn(0f, 1f)

    private fun drawItem(c: Canvas, i: Item) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = i.width
        val pr = i.pr
        if (i.kind == Kind.PEN && pr != null && pr.size == i.pts.size) {   // traço sensível à pressão
            paint.color = i.color
            if (i.pts.size == 1) {
                paint.strokeWidth = i.width * pf(pr[0])
                c.drawPoint(i.pts[0].x, i.pts[0].y, paint)
            } else {
                for (k in 1 until i.pts.size) {
                    paint.strokeWidth = i.width * pf((pr[k - 1] + pr[k]) / 2f)
                    c.drawLine(i.pts[k - 1].x, i.pts[k - 1].y, i.pts[k].x, i.pts[k].y, paint)
                }
            }
            return
        }
        if (i.kind == Kind.HL) {
            paint.color = i.color; paint.alpha = 100; paint.strokeCap = Paint.Cap.SQUARE
        } else {
            paint.color = i.color
        }
        c.drawPath(pathOf(i), paint)
    }

    /** Laço em andamento + caixa de seleção com alças. */
    private fun drawSelection(c: Canvas) {
        lasso?.let { l ->
            if (l.size > 1) {
                val path = Path()
                path.moveTo(l[0].x, l[0].y)
                for (k in 1 until l.size) path.lineTo(l[k].x, l[k].y)
                paint.reset(); paint.isAntiAlias = true
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f * dp
                paint.color = purple
                paint.pathEffect = dash
                paint.strokeCap = Paint.Cap.ROUND
                c.drawPath(path, paint)
                paint.alpha = 70
                c.drawLine(l.last().x, l.last().y, l[0].x, l[0].y, paint)
            }
        }
        val r = selRect ?: return
        if (sel.isEmpty()) return
        val g = selPad
        paint.reset(); paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(18, 124, 58, 237)
        c.drawRoundRect(r.left - g, r.top - g, r.right + g, r.bottom + g, 6f * dp, 6f * dp, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f * dp
        paint.color = purple
        paint.pathEffect = dash
        c.drawRoundRect(r.left - g, r.top - g, r.right + g, r.bottom + g, 6f * dp, 6f * dp, paint)
        paint.pathEffect = null
        for (p in corners(r)) {
            paint.style = Paint.Style.FILL; paint.color = Color.WHITE
            c.drawCircle(p.x, p.y, 7f * dp, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f * dp; paint.color = purple
            c.drawCircle(p.x, p.y, 7f * dp, paint)
        }
        val by = badgeY(r)
        val cx = r.centerX()
        // excluir
        val dx = cx + 22f * dp
        paint.style = Paint.Style.FILL; paint.color = Color.parseColor("#E53935")
        c.drawCircle(dx, by, 13f * dp, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f * dp; paint.color = Color.WHITE
        paint.strokeCap = Paint.Cap.ROUND
        c.drawLine(dx - 4.5f * dp, by - 4.5f * dp, dx + 4.5f * dp, by + 4.5f * dp, paint)
        c.drawLine(dx - 4.5f * dp, by + 4.5f * dp, dx + 4.5f * dp, by - 4.5f * dp, paint)
        // duplicar
        val cpx = cx - 22f * dp
        paint.style = Paint.Style.FILL; paint.color = purple
        c.drawCircle(cpx, by, 13f * dp, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.6f * dp; paint.color = Color.WHITE
        c.drawRect(cpx - 6f * dp, by - 6f * dp, cpx + 2f * dp, by + 2f * dp, paint)
        c.drawRect(cpx - 2f * dp, by - 2f * dp, cpx + 6f * dp, by + 6f * dp, paint)
    }

    private fun drawEraserRing(c: Canvas) {
        val pos = eraserPos ?: return
        paint.reset(); paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f * dp
        paint.color = Color.parseColor("#99000000")
        c.drawCircle(pos.x, pos.y, eraserR, paint)
        paint.strokeWidth = 1.2f * dp
        paint.color = Color.WHITE
        c.drawCircle(pos.x, pos.y, eraserR, paint)
    }

    private fun drawLasers(c: Canvas) {
        if (lasers.isEmpty()) return
        val life = 1500L
        val now = SystemClock.uptimeMillis()
        lasers.forEach { s -> s.removeAll { p -> now - p.t > life } }
        lasers.removeAll { it.isEmpty() && it !== laserCur }
        for (s in lasers) {
            for (pass in 0..1) {
                paint.reset(); paint.isAntiAlias = true
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = (if (pass == 0) laserW * 2.6f else laserW) * dp
                if (s.size == 1) {
                    paint.color = Color.argb(alphaFor(now - s[0].t, life, pass), 255, 23, 68)
                    c.drawPoint(s[0].x, s[0].y, paint)
                }
                for (k in 1 until s.size) {
                    paint.color = Color.argb(alphaFor(now - s[k].t, life, pass), 255, 23, 68)
                    c.drawLine(s[k - 1].x, s[k - 1].y, s[k].x, s[k].y, paint)
                }
            }
        }
        if (lasers.isNotEmpty()) postInvalidateOnAnimation()
    }

    private fun alphaFor(age: Long, life: Long, pass: Int): Int {
        val f = (1f - age.toFloat() / life).coerceIn(0f, 1f)
        return (f * (if (pass == 0) 70 else 255)).toInt()
    }
}

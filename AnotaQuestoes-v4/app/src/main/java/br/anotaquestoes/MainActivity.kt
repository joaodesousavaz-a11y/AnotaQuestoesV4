package br.anotaquestoes

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private enum class Dock(val label: String) {
        TOP("Topo"), BOTTOM("Embaixo"), LEFT("Esquerda"), RIGHT("Direita"),
        FLOAT_H("Solto (horizontal)"), FLOAT_V("Solto (vertical)")
    }

    private class ToolBtn(val view: IconView, val tools: List<DrawingView.Tool>, val icons: List<Ic>) {
        var last: DrawingView.Tool = tools[0]
    }

    private lateinit var web: WebView
    private lateinit var draw: DrawingView
    private lateinit var root: FrameLayout
    private lateinit var prefs: SharedPreferences

    private var bar: LinearLayout? = null
    private var toggleView: IconView? = null
    private var swatchBtn: IconView? = null
    private var preview: WidthPreview? = null
    private val toolBtns = ArrayList<ToolBtn>()

    private var lastUrl: String? = null
    private var autoClear = false
    private var dock = Dock.TOP
    private var collapsed = false
    private var reserve = true
    private var barX = -1f
    private var barY = -1f

    private val isVertical get() = dock == Dock.FLOAT_V || dock == Dock.LEFT || dock == Dock.RIGHT
    private val isFloat get() = dock == Dock.FLOAT_H || dock == Dock.FLOAT_V

    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val PURPLE = Color.parseColor("#7C3AED")
    private val INK = Color.parseColor("#455A64")
    private val COLORS = listOf("#111111", "#E53935", "#1E88E5", "#43A047", "#FFEB3B", "#FF9800", "#8E24AA", "#00ACC1")
    private val density by lazy { resources.displayMetrics.density }
    private fun px(v: Int) = (v * density).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        root = FrameLayout(this)

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    if (!isReload && url != lastUrl) {
                        if (autoClear && lastUrl != null) draw.clearAll()
                        lastUrl = url
                    }
                }
            }
        }
        draw = DrawingView(this).apply {
            scrollSource = web
        }
        web.setOnScrollChangeListener { _, _, _, _, _ -> draw.invalidate() }
        loadPrefs()

        val match = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(web, FrameLayout.LayoutParams(match, match))
        root.addView(draw, FrameLayout.LayoutParams(match, match))
        setContentView(root)
        rebuildBar()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })

        web.loadUrl("https://www.tecconcursos.com.br")
    }

    override fun onPause() {
        savePrefs()
        super.onPause()
    }

    // ------------------------------------------------------------ preferências
    private fun loadPrefs() {
        dock = Dock.values().firstOrNull { it.name == prefs.getString("dock", "TOP") } ?: Dock.TOP
        collapsed = prefs.getBoolean("collapsed", false)
        reserve = prefs.getBoolean("reserve", true)
        barX = prefs.getFloat("barX", -1f)
        barY = prefs.getFloat("barY", -1f)
        autoClear = prefs.getBoolean("autoClear", false)
        draw.penColor = prefs.getInt("penColor", draw.penColor)
        draw.hlColor = prefs.getInt("hlColor", draw.hlColor)
        draw.penW = prefs.getFloat("penW", draw.penW)
        draw.hlW = prefs.getFloat("hlW", draw.hlW)
        draw.eraserW = prefs.getFloat("eraserW", draw.eraserW)
        draw.laserW = prefs.getFloat("laserW", draw.laserW)
        draw.pressureOn = prefs.getBoolean("pressure", true)
        draw.palmRejection = prefs.getBoolean("palm", true)
        draw.ruler = prefs.getBoolean("ruler", false)
        draw.fingerDraws = prefs.getBoolean("finger", false)
        val t = DrawingView.Tool.values().firstOrNull { it.name == prefs.getString("toolName", "PEN") } ?: DrawingView.Tool.PEN
        draw.selectTool(t)
    }

    private fun savePrefs() {
        prefs.edit()
            .putString("dock", dock.name).putBoolean("collapsed", collapsed).putBoolean("reserve", reserve)
            .putFloat("barX", barX).putFloat("barY", barY)
            .putBoolean("autoClear", autoClear)
            .putInt("penColor", draw.penColor).putInt("hlColor", draw.hlColor)
            .putFloat("penW", draw.penW).putFloat("hlW", draw.hlW)
            .putFloat("eraserW", draw.eraserW).putFloat("laserW", draw.laserW)
            .putBoolean("pressure", draw.pressureOn).putBoolean("palm", draw.palmRejection)
            .putBoolean("ruler", draw.ruler).putBoolean("finger", draw.fingerDraws)
            .putString("toolName", draw.tool.name)
            .apply()
    }

    // ------------------------------------------------------------ helpers
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun sep(v: Boolean) = View(this).apply {
        setBackgroundColor(Color.parseColor("#E3E6EA"))
        layoutParams = if (v) LinearLayout.LayoutParams(px(18), px(1)).apply { setMargins(0, px(4), 0, px(4)) }
        else LinearLayout.LayoutParams(px(1), px(18)).apply { setMargins(px(4), 0, px(4), 0) }
    }

    private fun iconBtn(ic: Ic, tip: String, size: Int = 36): IconView = IconView(this, ic).apply {
        layoutParams = LinearLayout.LayoutParams(px(size), px(size))
        tooltipText = tip
    }

    /** Botão que repete a ação enquanto está pressionado (para + e −). */
    @SuppressLint("ClickableViewAccessibility")
    private fun repeatable(v: View, action: () -> Unit) {
        val h = Handler(Looper.getMainLooper())
        val r = object : Runnable {
            override fun run() { action(); h.postDelayed(this, 70) }
        }
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { action(); h.postDelayed(r, 380); view.alpha = 0.45f }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { h.removeCallbacks(r); view.alpha = 1f }
            }
            true
        }
    }

    private fun bump(dir: Int) {
        val nv = draw.widthDp + dir * draw.widthStep
        draw.widthDp = nv.coerceIn(draw.widthMin, draw.widthMax)
        preview?.invalidate()
    }

    private fun currentColor() = if (draw.tool == DrawingView.Tool.HIGHLIGHTER) draw.hlColor else draw.penColor

    // ------------------------------------------------------------ barra
    private fun rebuildBar() {
        bar?.let { root.removeView(it) }
        toolBtns.clear()
        val b = buildBar()
        bar = b
        val lp = FrameLayout.LayoutParams(WRAP, WRAP)
        val m = px(8)
        when (dock) {
            Dock.TOP -> { lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; lp.topMargin = m }
            Dock.BOTTOM -> { lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; lp.bottomMargin = m }
            Dock.LEFT -> { lp.gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL; lp.leftMargin = m }
            Dock.RIGHT -> { lp.gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL; lp.rightMargin = m }
            else -> lp.gravity = Gravity.TOP or Gravity.LEFT
        }
        root.addView(b, lp)
        b.post {
            if (isFloat) {
                val mx = max(0f, (root.width - b.width).toFloat())
                val my = max(0f, (root.height - b.height).toFloat())
                b.x = (if (barX >= 0f) barX else px(12).toFloat()).coerceIn(0f, mx)
                b.y = (if (barY >= 0f) barY else px(12).toFloat()).coerceIn(0f, my)
            }
            applyInsets()
        }
        refreshAll()
    }

    /** Em topo/embaixo, empurra a página para a barra não cobrir o conteúdo. */
    private fun applyInsets() {
        val b = bar ?: return
        var top = 0; var bottom = 0
        if (reserve) {
            if (dock == Dock.TOP) top = b.height + px(16)
            if (dock == Dock.BOTTOM) bottom = b.height + px(16)
        }
        for (v in listOf<View>(web, draw)) {
            val lp = v.layoutParams as FrameLayout.LayoutParams
            if (lp.topMargin != top || lp.bottomMargin != bottom) {
                lp.topMargin = top; lp.bottomMargin = bottom
                v.layoutParams = lp
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildBar(): LinearLayout {
        val v = isVertical
        val bar = LinearLayout(this).apply {
            orientation = if (v) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = px(26).toFloat(); setStroke(px(1), Color.parseColor("#E3E6EA"))
            }
            elevation = px(6).toFloat()
            if (v) setPadding(px(4), px(6), px(4), px(6)) else setPadding(px(6), px(4), px(6), px(4))
        }

        // alça (só na barra solta)
        if (isFloat) {
            val grip = IconView(this, Ic.GRIP).apply {
                layoutParams = LinearLayout.LayoutParams(px(if (v) 36 else 22), px(if (v) 22 else 36))
                tint = Color.parseColor("#90A4AE")
            }
            var offX = 0f; var offY = 0f
            grip.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { offX = bar.x - e.rawX; offY = bar.y - e.rawY }
                    MotionEvent.ACTION_MOVE -> {
                        bar.x = (e.rawX + offX).coerceIn(0f, max(0f, (root.width - bar.width).toFloat()))
                        bar.y = (e.rawY + offY).coerceIn(0f, max(0f, (root.height - bar.height).toFloat()))
                        barX = bar.x; barY = bar.y
                    }
                }
                true
            }
            bar.addView(grip)
        }

        // escrever / navegar
        val toggle = IconView(this, Ic.PEN).apply {
            layoutParams = LinearLayout.LayoutParams(px(40), px(40))
            tooltipText = "Escrever / Navegar"
            setOnClickListener { draw.drawingEnabled = !draw.drawingEnabled; refreshAll() }
        }
        toggleView = toggle
        bar.addView(toggle)

        val scroll: ViewGroup = if (v) ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        else HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val content = LinearLayout(this).apply {
            orientation = if (v) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        scroll.addView(content)
        scroll.visibility = if (collapsed) View.GONE else View.VISIBLE
        bar.addView(scroll)

        val sections = ArrayList<List<View>>()

        // 1) ferramentas
        val defs = listOf(
            Triple(listOf(DrawingView.Tool.PEN), listOf(Ic.PEN), "Lápis"),
            Triple(listOf(DrawingView.Tool.HIGHLIGHTER), listOf(Ic.MARKER), "Marcador"),
            Triple(listOf(DrawingView.Tool.LINE, DrawingView.Tool.ARROW), listOf(Ic.LINE, Ic.ARROW), "Linha / seta (toque de novo para alternar)"),
            Triple(listOf(DrawingView.Tool.RECT), listOf(Ic.RECT), "Retângulo"),
            Triple(listOf(DrawingView.Tool.OVAL), listOf(Ic.OVAL), "Círculo"),
            Triple(listOf(DrawingView.Tool.LASER), listOf(Ic.LASER), "Laser"),
            Triple(listOf(DrawingView.Tool.ERASER, DrawingView.Tool.ERASER_PART), listOf(Ic.ERASER, Ic.ERASER_PART),
                "Borracha: traço inteiro / só onde passar (toque de novo para alternar)"),
            Triple(listOf(DrawingView.Tool.LASSO), listOf(Ic.LASSO), "Laço: circule para selecionar, arraste para mover, puxe o canto para redimensionar")
        )
        sections.add(defs.map { (tools, icons, tip) ->
            val iv = iconBtn(icons[0], tip)
            val tb = ToolBtn(iv, tools, icons)
            toolBtns.add(tb)
            iv.setOnClickListener { onToolClick(tb) }
            iv
        })

        // 2) cor + espessura
        val swatch = iconBtn(Ic.SWATCH, "Cor")
        swatch.setOnClickListener { showColors(swatch) }
        swatchBtn = swatch
        sections.add(listOf(swatch, sizeWidget(v)))

        // 3) desfazer / refazer
        sections.add(listOf(
            iconBtn(Ic.UNDO, "Desfazer").apply { setOnClickListener { draw.undo() } },
            iconBtn(Ic.REDO, "Refazer").apply { setOnClickListener { draw.redo() } }
        ))

        // 4) mais opções / posição da barra
        val more = iconBtn(Ic.MORE, "Mais opções")
        more.setOnClickListener { showMore(more) }
        val dockBtn = iconBtn(Ic.DOCK, "Posição da barra")
        dockBtn.setOnClickListener { showDock(dockBtn) }
        sections.add(listOf(more, dockBtn))

        for (views in sections) {
            content.addView(sep(v))
            views.forEach { content.addView(it) }
        }

        // recolher / expandir
        val collapse = iconBtn(chevron(v), "Recolher / expandir", 32).apply {
            tint = Color.parseColor("#90A4AE"); iconDp = 18f
        }
        collapse.setOnClickListener {
            collapsed = !collapsed
            scroll.visibility = if (collapsed) View.GONE else View.VISIBLE
            collapse.icon = chevron(v)
            bar.post { applyInsets() }
        }
        bar.addView(collapse)
        return bar
    }

    private fun chevron(v: Boolean) =
        if (v) (if (collapsed) Ic.CHEV_D else Ic.CHEV_U) else (if (collapsed) Ic.CHEV_R else Ic.CHEV_L)

    private fun sizeWidget(v: Boolean): LinearLayout {
        fun glyph(t: String) = TextView(this).apply {
            text = t; textSize = 20f; gravity = Gravity.CENTER
            setTextColor(INK)
            layoutParams = LinearLayout.LayoutParams(px(if (v) 36 else 28), px(if (v) 26 else 36))
            tooltipText = if (t == "+") "Mais grosso (segure para repetir)" else "Mais fino (segure para repetir)"
        }
        val minus = glyph("−"); val plus = glyph("+")
        repeatable(minus) { bump(-1) }
        repeatable(plus) { bump(1) }
        val pv = WidthPreview(this, draw).apply { layoutParams = LinearLayout.LayoutParams(px(34), px(36)) }
        preview = pv
        return LinearLayout(this).apply {
            orientation = if (v) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            if (v) { addView(plus); addView(pv); addView(minus) } else { addView(minus); addView(pv); addView(plus) }
        }
    }

    // ------------------------------------------------------------ estado da barra
    private fun onToolClick(tb: ToolBtn) {
        if (!draw.drawingEnabled && draw.tool in tb.tools) {
            draw.drawingEnabled = true; refreshAll(); return
        }
        val next = when {
            tb.tools.size == 1 -> tb.tools[0]
            draw.tool in tb.tools -> tb.tools[(tb.tools.indexOf(draw.tool) + 1) % tb.tools.size]
            else -> tb.last
        }
        tb.last = next
        draw.selectTool(next)
        draw.drawingEnabled = true
        refreshAll()
    }

    private fun refreshAll() {
        toggleView?.let { t ->
            if (draw.drawingEnabled) { t.icon = Ic.PEN; t.circleBg = PURPLE; t.tint = Color.WHITE }
            else { t.icon = Ic.CURSOR; t.circleBg = Color.parseColor("#ECEFF1"); t.tint = INK }
            t.invalidate()
        }
        for (tb in toolBtns) {
            if (draw.tool in tb.tools) tb.last = draw.tool
            tb.view.active = draw.tool in tb.tools
            if (tb.tools.size > 1) tb.view.icon = tb.icons[tb.tools.indexOf(tb.last)]
        }
        swatchBtn?.swatch = currentColor()
        preview?.invalidate()
    }

    // ------------------------------------------------------------ popups
    private fun showPopup(anchor: View, content: View): PopupWindow {
        val card = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = px(16).toFloat(); setStroke(px(1), Color.parseColor("#E3E6EA"))
            }
            elevation = px(6).toFloat()
            setPadding(px(6), px(6), px(6), px(6))
            addView(content)
        }
        val outer = FrameLayout(this).apply { setPadding(px(10), px(10), px(10), px(10)); addView(card) }
        val pw = PopupWindow(outer, WRAP, WRAP, true)
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        outer.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = outer.measuredWidth; val h = outer.measuredHeight
        val pad = px(8)
        val b = bar
        val xo: Int; val yo: Int
        if (isVertical) {
            val onRight = when (dock) {
                Dock.LEFT -> true
                Dock.RIGHT -> false
                else -> (b?.x ?: 0f) < root.width / 2f
            }
            xo = if (onRight) anchor.width - pad else -w + pad
            yo = -(anchor.height + h) / 2
        } else {
            val below = when (dock) {
                Dock.TOP -> true
                Dock.BOTTOM -> false
                else -> (b?.y ?: 0f) < root.height / 2f
            }
            xo = (anchor.width - w) / 2
            yo = if (below) -pad else -(anchor.height + h) + pad
        }
        pw.showAsDropDown(anchor, xo, yo)
        return pw
    }

    private fun menuText(t: String) = TextView(this).apply {
        text = t; textSize = 14f
        setTextColor(Color.parseColor("#263238"))
        minWidth = px(230)
        setPadding(px(14), px(10), px(14), px(10))
    }

    private fun menuDivider() = View(this).apply {
        setBackgroundColor(Color.parseColor("#E3E6EA"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1)).apply { setMargins(0, px(4), 0, px(4)) }
    }

    private fun showColors(anchor: View) {
        var pw: PopupWindow? = null
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (rowColors in COLORS.chunked(4)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (hex in rowColors) {
                val color = Color.parseColor(hex)
                val dot = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(px(30), px(30)).apply { setMargins(px(5), px(5), px(5), px(5)) }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(color)
                        if (color == currentColor()) setStroke(px(3), PURPLE) else setStroke(px(1), Color.parseColor("#CFD8DC"))
                    }
                    setOnClickListener {
                        if (draw.tool == DrawingView.Tool.HIGHLIGHTER) draw.hlColor = color else draw.penColor = color
                        refreshAll(); pw?.dismiss()
                    }
                }
                row.addView(dot)
            }
            col.addView(row)
        }
        pw = showPopup(anchor, col)
    }

    private fun showDock(anchor: View) {
        var pw: PopupWindow? = null
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (d in Dock.values()) {
            val tv = menuText((if (d == dock) "●  " else "○  ") + d.label)
            tv.setOnClickListener {
                pw?.dismiss()
                if (d != dock) { dock = d; rebuildBar() }
            }
            col.addView(tv)
        }
        pw = showPopup(anchor, col)
    }

    private fun showMore(anchor: View) {
        var pw: PopupWindow? = null
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun pre(b: Boolean) = if (b) "●  " else "○  "
        fun toggleRow(label: String, get: () -> Boolean, set: (Boolean) -> Unit) {
            val tv = menuText(pre(get()) + label)
            tv.setOnClickListener { set(!get()); tv.text = pre(get()) + label }
            col.addView(tv)
        }
        fun actionRow(label: String, run: () -> Unit) {
            val tv = menuText(label)
            tv.setOnClickListener { pw?.dismiss(); run() }
            col.addView(tv)
        }
        toggleRow("Marcador em linha reta (régua)", { draw.ruler }, { draw.ruler = it })
        toggleRow("Sensibilidade à pressão", { draw.pressureOn }, { draw.pressureOn = it })
        toggleRow("Rejeição de palma", { draw.palmRejection }, { draw.palmRejection = it })
        toggleRow("Dedo também desenha", { draw.fingerDraws }, { draw.fingerDraws = it })
        toggleRow("Limpar ao trocar de página", { autoClear }, { autoClear = it })
        toggleRow("Reservar espaço da barra (topo/embaixo)", { reserve }, { reserve = it; bar?.post { applyInsets() } })
        col.addView(menuDivider())
        actionRow("Salvar captura na galeria") { capture() }
        actionRow("Recarregar página") { web.reload() }
        actionRow("Voltar página") { if (web.canGoBack()) web.goBack() }
        actionRow("Limpar tudo (dá para desfazer)") { draw.clearAll() }
        pw = showPopup(anchor, col)
    }

    // ------------------------------------------------------------ captura
    private fun capture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { toast("Captura precisa do Android 10 ou mais novo"); return }
        val b = bar
        b?.visibility = View.INVISIBLE
        root.postDelayed({
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            val loc = IntArray(2)
            root.getLocationInWindow(loc)
            val rect = Rect(loc[0], loc[1], loc[0] + root.width, loc[1] + root.height)
            try {
                PixelCopy.request(window, rect, bmp, { result ->
                    b?.visibility = View.VISIBLE
                    if (result == PixelCopy.SUCCESS) saveBitmap(bmp) else toast("Não foi possível capturar")
                }, Handler(Looper.getMainLooper()))
            } catch (e: Exception) {
                b?.visibility = View.VISIBLE
                toast("Não foi possível capturar")
            }
        }, 200)
    }

    private fun saveBitmap(bmp: Bitmap) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "questao_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AnotaQuestoes")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) { toast("Falha ao salvar"); return }
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            toast("Salvo na Galeria (pasta AnotaQuestoes)")
        } catch (e: Exception) {
            toast("Falha ao salvar")
        }
    }
}

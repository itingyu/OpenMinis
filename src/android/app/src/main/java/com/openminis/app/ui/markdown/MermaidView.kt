package com.openminis.app.ui.markdown

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.LruCache
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.openminis.app.logging.AppLogger

private const val TAG = "Mermaid"

/**
 * [T-mermaid] Bitmap cache, keyed by (source + theme).
 *
 * Same shape as KaTeXRendererCache: the rendered value is a bitmap whose ink
 * colour is baked in, so the theme has to be part of the key or a diagram keeps
 * its old palette after a theme flip. Bounded by BYTES, not entries — these are
 * native-heap ARGB_8888 buffers.
 */
private object MermaidRendererCache {
    data class Entry(val bitmap: Bitmap, val width: Int, val height: Int)

    private val budgetBytes: Int = run {
        val maxHeap = Runtime.getRuntime().maxMemory()
        (maxHeap / 12).coerceIn(4L * 1024 * 1024, 16L * 1024 * 1024).toInt()
    }

    val cache = object : LruCache<String, Entry>(budgetBytes) {
        override fun sizeOf(key: String, value: Entry): Int =
            value.bitmap.allocationByteCount.coerceAtLeast(1)
    }

    fun key(source: String, isDark: Boolean): String = (if (isDark) "d:" else "l:") + source
}

/**
 * [T-mermaid] Renders a ```mermaid fenced block.
 *
 * Mermaid has no native renderer, so the diagram is drawn by mermaid.min.js in
 * an offscreen WebView (asset, fully offline — the sandbox has no network
 * guarantee) and captured as a bitmap, mirroring [KaTeXRenderView]. While the
 * capture is pending the raw source is shown in monospace, so a diagram never
 * disappears silently if JS fails.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MermaidBlockView(
    source: String,
    modifier: Modifier = Modifier,
) {
    val isDark = com.openminis.app.ui.theme.ChatColors.isDark
    val key = remember(source, isDark) { MermaidRendererCache.key(source, isDark) }
    val cached = remember(key) { MermaidRendererCache.cache.get(key) }

    var bitmap by remember(key) { mutableStateOf<Bitmap?>(null) }
    var cssWidth by remember(key) { mutableStateOf(0) }
    var cssHeight by remember(key) { mutableStateOf(0) }
    var error by remember(key) { mutableStateOf<String?>(null) }

    val shown = cached?.bitmap ?: bitmap
    val shownW = if (cached != null) cached.width else cssWidth
    val shownH = if (cached != null) cached.height else cssHeight

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            // Height still changes when the real bitmap arrives (its aspect
            // ratio is only known then), so animate it instead of snapping.
            .animateContentSize(),
    ) {
        when {
            shown != null && shownW > 0 && shownH > 0 ->
                ZoomableDiagram(bitmap = shown.asImageBitmap(), widthDp = shownW, heightDp = shownH)

            error != null -> MermaidFallback(error = error!!, source = source)

            // [T-mermaid-skeleton] Pending used to show the raw fence, so the
            // bubble was tall code first and a short picture 200 ms later —
            // the page "jumped". A fixed-height skeleton keeps the layout
            // still and reads as work in progress.
            else -> MermaidSkeleton()
        }

        if (shown == null && error == null) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(4000, 4000)
                        // [T-mermaid] Hardware-accelerated WebView drawn onto a
                        // software Canvas via draw() yields an opaque black
                        // bitmap: the GPU layer's framebuffer is unreadable from
                        // software. KaTeX hit this same bug; LAYER_TYPE_SOFTWARE
                        // makes draw() paint the real display list.
                        setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = true
                        settings.textZoom = 100
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)

                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onRendered(width: Int, height: Int, message: String) {
                                // JavaBridge calls land on a WebView worker
                                // thread; every state write below must hop to
                                // main or Compose silently drops it.
                                post {
                                if (message.isNotEmpty()) {
                                    AppLogger.warning(
                                        TAG,
                                        "render failed: $message · ${source.take(80)}",
                                    )
                                    error = message
                                    return@post
                                }
                                if (width <= 0 || height <= 0) {
                                    error = "zero dimensions"
                                    return@post
                                }
                                val scale = ctx.resources.displayMetrics.density
                                val w = (width * scale).toInt().coerceAtMost(4096)
                                val h = (height * scale).toInt().coerceAtMost(4096)
                                post {
                                    layoutParams = ViewGroup.LayoutParams(w, h)
                                    // Explicit measure+layout: draw() reads the
                                    // last laid-out frame, so a requestLayout()
                                    // alone captures an empty (black) canvas.
                                    measure(
                                        android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
                                        android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY),
                                    )
                                    layout(0, 0, w, h)
                                    postDelayed({
                                        val bmp = runCatching {
                                            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                        }.getOrNull() ?: return@postDelayed
                                        val canvas = android.graphics.Canvas(bmp)
                                        canvas.drawColor(android.graphics.Color.TRANSPARENT)
                                        draw(canvas)
                                        MermaidRendererCache.cache.put(
                                            key,
                                            MermaidRendererCache.Entry(bmp, width, height),
                                        )
                                        cssWidth = width
                                        cssHeight = height
                                        bitmap = bmp
                                    }, 120)
                                }
                                }
                            }
                        }, "MermaidBridge")

                        loadDataWithBaseURL(
                            "file:///android_asset/mermaid/",
                            mermaidHtml(source, isDark),
                            "text/html",
                            "utf-8",
                            null,
                        )
                    }
                },
            )
        }
    }
}

/** Raw source shown while rendering, and the final fallback when JS fails. */
@Composable
private fun MermaidFallback(source: String = "", error: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        if (error != null) {
            Text(
                text = "Mermaid render failed: $error",
                style = TextStyle(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = source,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * HTML wrapper. `securityLevel: 'strict'` keeps click handlers and raw HTML in
 * a diagram from reaching the WebView's JS bridge; `useMaxWidth: false` makes
 * mermaid emit a fixed-size SVG so the captured bitmap is the real size.
 */
private fun mermaidHtml(source: String, isDark: Boolean): String {
    val escaped = source
        .replace("\\", "\\\\")
        .replace("`", "\\`")
        .replace("$", "\\$")
    val theme = if (isDark) "dark" else "default"
    return """
<!DOCTYPE html>
<html><head><meta charset="utf-8">
<style>
  html,body{margin:0;padding:0;background:transparent;overflow:hidden}
  #c{display:inline-block;width:max-content}
  .mermaid svg{max-width:none !important;height:auto}
</style>
<script src="mermaid.min.js"></script>
</head><body>
<div id="c"></div>
<script>
  mermaid.initialize({
    startOnLoad: false,
    theme: '$theme',
    securityLevel: 'strict',
    flowchart: { useMaxWidth: false, htmlLabels: false },
    sequence: { useMaxWidth: false },
    gantt: { useMaxWidth: false },
    er: { useMaxWidth: false },
    pie: { useMaxWidth: false }
  });
  var src = `$escaped`;
  mermaid.render('mmd', src).then(function (res) {
    var c = document.getElementById('c');
    c.innerHTML = res.svg;
    var svg = c.querySelector('svg');
    if (!svg) { MermaidBridge.onRendered(0, 0, 'no svg'); return; }
    // width/height attributes are already pixel values with useMaxWidth:false;
    // dropping them made the SVG shrink to the 0-width body instead.
    svg.style.maxWidth = 'none';
    svg.style.width = svg.getAttribute('width') || '';
    svg.style.height = svg.getAttribute('height') || '';
    var r = svg.getBoundingClientRect();
    MermaidBridge.onRendered(Math.ceil(r.width), Math.ceil(r.height), '');
  }).catch(function (e) {
    MermaidBridge.onRendered(0, 0, String(e && e.message ? e.message : e));
  });
</script>
</body></html>
""".trimIndent()
}

/**
 * [T-mermaid-zoom] The captured diagram, pinch-zoomable and draggable.
 *
 * A screenshot is a bitmap, so the old layout had to choose between fitting it
 * (labels unreadable on a phone) and letting it scroll sideways. Pinch zoom
 * plus one-finger pan keeps both: 1x reads like an inline image, 3x is legible.
 *
 * The bitmap itself is never re-rendered — only the layer transform changes, so
 * zooming is free and instant. Offsets are clamped to the scaled bitmap so the
 * diagram cannot be flung off-screen, and double-tap returns to fit.
 */
@Composable
private fun ZoomableDiagram(bitmap: ImageBitmap, widthDp: Int, heightDp: Int) {
    val density = LocalDensity.current
    // [T-mermaid-fit] The capture is in CSS pixels, which used to be handed to
    // Compose as dp — a 1200px-wide diagram laid out 1200dp wide and was simply
    // clipped. Fit it to the bubble and cap its height so one picture cannot
    // fill the screen.
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.38f).dp
    var dispW by remember(bitmap) { mutableStateOf(0.dp) }
    var dispH by remember(bitmap) { mutableStateOf(0.dp) }

    var scale by rememberSaveable(bitmap) { mutableFloatStateOf(1f) }
    var offsetX by rememberSaveable(bitmap) { mutableFloatStateOf(0f) }
    var offsetY by rememberSaveable(bitmap) { mutableFloatStateOf(0f) }

    val reset = {
        scale = 1f; offsetX = 0f; offsetY = 0f
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val availW = maxWidth
        val ratio = if (widthDp > 0) heightDp.toFloat() / widthDp else 1f
        if (dispW == 0.dp) {
            var w = availW
            var h = w * ratio
            if (h > maxH) { h = maxH; w = h / ratio }
            dispW = w
            dispH = h
        }
        val imgW = with(density) { dispW.toPx() }
        val imgH = with(density) { dispH.toPx() }

        Box(
            modifier = Modifier
                .size(dispW, dispH)
                .clipToBounds()
                .pointerInput(bitmap) {
                    // [T-mermaid-scroll] detectTransformGestures swallowed
                    // single-finger drags, so the chat could not be scrolled
                    // past a diagram. Two fingers always mean zoom; one finger
                    // means pan only while zoomed in, otherwise the event is
                    // left untouched for the parent list.
                    awaitEachGesture {
                        var zoomed = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed == 0) break
                            if (pressed >= 2) zoomed = true
                            if (pressed == 1 && !zoomed && scale <= 1.001f) continue
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val next = (scale * zoom).coerceIn(1f, 6f)
                            scale = next
                            val maxX = ((imgW * next - size.width) / 2f).coerceAtLeast(0f)
                            val maxY = ((imgH * next - size.height) / 2f).coerceAtLeast(0f)
                            offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                            offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                            event.changes.forEach { if (it.pressed) it.consume() }
                        }
                    }
                },
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = "Mermaid diagram",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(dispW, dispH)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    },
            )
        }
    }

    // [T-mermaid-zoom-reset] Explicit affordance instead of double-tap: a tap
    // detector competes with the list for the down event.
    if (scale > 1.001f) {
        Text(
            text = "复位",
            style = TextStyle(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = 2.dp, top = 2.dp)
                .clickable { reset() },
        )
    }
}

/** [T-mermaid-skeleton] Placeholder with a stable height while JS renders. */
@Composable
private fun MermaidSkeleton() {
    val base = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(base.copy(alpha = 0.06f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Rendering diagram…",
            style = TextStyle(fontSize = 12.sp),
            color = base.copy(alpha = 0.7f),
        )
    }
}

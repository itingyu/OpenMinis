package com.openminis.app.ui.markdown

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
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
import androidx.compose.ui.graphics.Color
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.openminis.app.logging.AppLogger

private const val TAG = "Mermaid"

/** Long-edge cap for a captured diagram bitmap (see [T-mermaid-mem]). */
private const val CAPTURE_MAX_PX = 2560f

/**
 * [T-mermaid-copy] Grabs the rendered SVG out of the WebView. The clone gets an
 * explicit `xmlns` because `outerHTML` on an HTML-parsed SVG omits it, which
 * makes the copied markup unopenable as a standalone `.svg` file.
 */
private const val SVG_EXTRACT_JS =
    "(function(){var s=document.querySelector('#c svg');if(!s)return '';" +
        "var c=s.cloneNode(true);c.setAttribute('xmlns','http://www.w3.org/2000/svg');" +
        "c.setAttribute('xmlns:xlink','http://www.w3.org/1999/xlink');" +
        "return c.outerHTML;})()"

/** evaluateJavascript hands back a JSON literal, not the raw string. */
private fun parseJsString(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val value = runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull()
    return value?.takeIf { it.isNotBlank() }
}

private fun copySvgToClipboard(ctx: Context, svg: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    // text/plain carries the markup; the HTML entry lets editors that understand
    // it paste the diagram itself instead of its source.
    cm.setPrimaryClip(ClipData.newHtmlText("Mermaid diagram", svg, svg))
    Toast.makeText(ctx, "SVG 源码已复制", Toast.LENGTH_SHORT).show()
}

/**
 * [T-mermaid-svg-on-demand] Renders [source] in a throwaway WebView and returns
 * its SVG markup.
 *
 * Only the copy action needs this, so it runs at tap time instead of after every
 * render: keeping the markup around cost hundreds of KB per diagram in the disk
 * cache for a button most readers never press.
 */
private suspend fun fetchMermaidSvg(
    context: Context,
    source: String,
    isDark: Boolean,
): String? = withContext(Dispatchers.Main) {
    suspendCancellableCoroutine { cont ->
        var settled = false
        val webView = WebView(context)
        fun finish(value: String?) {
            if (settled) return
            settled = true
            runCatching { webView.destroy() }
            if (cont.isActive) cont.resume(value)
        }
        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccess = true
        webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onRendered(width: Int, height: Int, message: String) {
                webView.post {
                    if (message.isNotEmpty() || width <= 0 || height <= 0) {
                        finish(null)
                    } else {
                        webView.evaluateJavascript(SVG_EXTRACT_JS) { raw ->
                            finish(parseJsString(raw))
                        }
                    }
                }
            }
        }, "MermaidBridge")
        cont.invokeOnCancellation { runCatching { webView.destroy() } }
        // A page that never calls back must not leave the tap hanging.
        webView.postDelayed({ finish(null) }, 10_000)
        webView.loadDataWithBaseURL(
            "file:///android_asset/mermaid/",
            mermaidHtml(source, isDark),
            "text/html",
            "utf-8",
            null,
        )
    }
}

/**
 * [T-mermaid-copy] Copies a real image, not markup.
 *
 * A FileProvider URI in the clipboard is unreadable for the pasting app (the
 * provider is not exported and a clip carries no URI grant), so the bitmap goes
 * into MediaStore — every app with media access can read that one. The same
 * slot is reused on every copy so the gallery does not fill up with diagrams.
 *
 * Returns false when the image could not be published.
 */
private fun copyDiagramImage(ctx: Context, bitmap: Bitmap, background: Int): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    val resolver = ctx.contentResolver
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val relDir = Environment.DIRECTORY_PICTURES + "/Minis"
    val name = "mermaid-clipboard.png"
    // Only rows this app owns can be deleted; a failure here is harmless.
    runCatching {
        resolver.delete(
            collection,
            "${MediaStore.Images.Media.DISPLAY_NAME}=? AND ${MediaStore.Images.Media.RELATIVE_PATH}=?",
            arrayOf(name, "$relDir/"),
        )
    }
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, relDir)
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return false
    val written = runCatching {
        // The capture is transparent; a pasted PNG with no alpha channel gets
        // painted black by receivers that ignore transparency, so flatten it
        // onto the chat background first.
        val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(flat).apply {
            drawColor(background)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        resolver.openOutputStream(uri)?.use { out ->
            flat.compress(Bitmap.CompressFormat.PNG, 100, out)
        } ?: false
    }.getOrDefault(false)
    if (!written) {
        runCatching { resolver.delete(uri, null, null) }
        return false
    }
    values.clear()
    values.put(MediaStore.Images.Media.IS_PENDING, 0)
    runCatching { resolver.update(uri, values, null, null) }
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
    cm.setPrimaryClip(ClipData.newUri(resolver, "Mermaid diagram", uri))
    return true
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val key = remember(source, isDark) { (if (isDark) "d:" else "l:") + source }

    // [T-render-disk-cache] Disk is the only cache: the bitmap is read back on
    // composition and dropped with the view, so scroll history costs no heap.
    var entry by remember(key) { mutableStateOf<RenderDiskCache.Entry?>(null) }
    var diskChecked by remember(key) { mutableStateOf(false) }
    var cssWidth by remember(key) { mutableStateOf(0) }
    var cssHeight by remember(key) { mutableStateOf(0) }
    var error by remember(key) { mutableStateOf<String?>(null) }

    LaunchedEffect(key) {
        entry = RenderDiskCache.load(context.applicationContext, "mermaid", key)
        diskChecked = true
    }

    val shown = entry?.bitmap
    val shownW = entry?.cssWidth ?: cssWidth
    val shownH = entry?.cssHeight ?: cssHeight

    var showSource by rememberSaveable(key) { mutableStateOf(false) }
    var fullscreen by rememberSaveable(key) { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            // Height still changes when the real bitmap arrives (its aspect
            // ratio is only known then), so animate it instead of snapping.
            .animateContentSize(),
    ) {
        // [T-mermaid-tabs] Rendered diagram and its Mermaid source share one
        // block; the source stays reachable without leaving the chat.
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabChip("渲染", selected = !showSource) { showSource = false }
            Box(Modifier.size(width = 6.dp, height = 1.dp))
            TabChip("源码", selected = showSource) { showSource = true }
            Box(Modifier.weight(1f))
            // [T-mermaid-fullscreen] The inline block is bounded by the bubble
            // width; this opens the capture at its natural size instead.
            if (!showSource && shown != null) {
                DiagramChip("全屏") { fullscreen = true }
                Box(Modifier.size(width = 6.dp, height = 1.dp))
                CopyDiagramChips(bitmap = shown, source = source, isDark = isDark)
            }
        }

        if (showSource) {
            SourcePane(source)
        } else when {
            shown != null && shownW > 0 && shownH > 0 ->
                ZoomableDiagram(bitmap = shown.asImageBitmap(), widthDp = shownW, heightDp = shownH)

            error != null -> MermaidFallback(error = error!!, source = source)

            // [T-mermaid-skeleton] Pending used to show the raw fence, so the
            // bubble was tall code first and a short picture 200 ms later —
            // the page "jumped". A fixed-height skeleton keeps the layout
            // still and reads as work in progress.
            else -> MermaidSkeleton()
        }

        if (diskChecked && shown == null && error == null) {
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
                                val pxW = (width * scale).toInt().coerceAtMost(4096)
                                val pxH = (height * scale).toInt().coerceAtMost(4096)
                                // [T-mermaid-mem] Capture at most CAPTURE_MAX_PX
                                // on the long edge. A full 3x-density diagram can
                                // be 4000px = 64 MB, which no cache survives;
                                // 1600px still looks sharp on a phone and keeps a
                                // single entry a few MB.
                                val shrink = (CAPTURE_MAX_PX / maxOf(pxW, pxH).toFloat()).coerceAtMost(1f)
                                val w = (pxW * shrink).toInt().coerceAtLeast(1)
                                val h = (pxH * shrink).toInt().coerceAtLeast(1)
                                post {
                                    layoutParams = ViewGroup.LayoutParams(pxW, pxH)
                                    // Explicit measure+layout: draw() reads the
                                    // last laid-out frame, so a requestLayout()
                                    // alone captures an empty (black) canvas.
                                    measure(
                                        android.view.View.MeasureSpec.makeMeasureSpec(pxW, android.view.View.MeasureSpec.EXACTLY),
                                        android.view.View.MeasureSpec.makeMeasureSpec(pxH, android.view.View.MeasureSpec.EXACTLY),
                                    )
                                    layout(0, 0, pxW, pxH)
                                    postDelayed({
                                        val bmp = runCatching {
                                            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                        }.getOrNull() ?: return@postDelayed
                                        val canvas = android.graphics.Canvas(bmp)
                                        canvas.drawColor(android.graphics.Color.TRANSPARENT)
                                        if (shrink < 1f) canvas.scale(shrink, shrink)
                                        draw(canvas)
                                        // [T-mermaid-copy] Harvest the vector
                                        // [T-mermaid-svg-on-demand] Only the
                                        // bitmap is produced here; the SVG
                                        // source is regenerated when the user
                                        // actually asks to copy it.
                                        val rendered = RenderDiskCache.Entry(
                                            bitmap = bmp,
                                            cssWidth = width,
                                            cssHeight = height,
                                        )
                                        entry = rendered
                                        cssWidth = width
                                        cssHeight = height
                                        scope.launch {
                                            RenderDiskCache.save(
                                                ctx.applicationContext,
                                                "mermaid",
                                                key,
                                                rendered,
                                            )
                                        }
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
    // [T-mermaid-fullscreen] Opened from the block header; the dialog shows the
    // capture at its natural CSS width instead of shrinking it to the bubble.
    if (fullscreen) {
        entry?.let { MermaidFullScreen(entry = it, onDismiss = { fullscreen = false }) }
    }
}

/** [T-mermaid-tabs] Small segmented control for 渲染 / 源码. */
@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(
        text = label,
        style = TextStyle(fontSize = 11.sp),
        color = if (selected) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.75f),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) cs.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * [T-mermaid-copy] "复制图片" puts a PNG on the clipboard; "SVG" copies the
 * vector markup for design tools. The image comes first because a paste into a
 * chat app only yields a picture when the clip holds an image URI.
 */
@Composable
private fun CopyDiagramChips(bitmap: Bitmap, source: String, isDark: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically) {
        val bg = MaterialTheme.colorScheme.surface.toArgb()
        DiagramChip("复制图片") {
            val ok = copyDiagramImage(ctx, bitmap, bg)
            Toast.makeText(
                ctx,
                if (ok) "图片已复制" else "复制失败，请重试",
                Toast.LENGTH_SHORT,
            ).show()
        }
        Box(Modifier.size(width = 6.dp, height = 1.dp))
        // [T-mermaid-svg-on-demand] Re-renders this one diagram offscreen and
        // copies its markup. Cheap enough for a tap, and it keeps the render
        // path from holding an SVG string (often hundreds of KB) per diagram
        // for a button most users never press.
        DiagramChip("SVG") {
            scope.launch {
                val svg = fetchMermaidSvg(ctx.applicationContext, source, isDark)
                if (svg == null) {
                    Toast.makeText(ctx, "生成 SVG 失败", Toast.LENGTH_SHORT).show()
                } else {
                    copySvgToClipboard(ctx, svg)
                }
            }
        }
    }
}

@Composable
private fun DiagramChip(label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(
        text = label,
        style = TextStyle(fontSize = 11.sp),
        color = cs.onSurfaceVariant.copy(alpha = 0.85f),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(cs.onSurfaceVariant.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** [T-mermaid-tabs] The diagram's Mermaid source, scrollable in both axes. */
@Composable
private fun SourcePane(source: String) {
    val hs = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.05f))
            .horizontalScroll(hs)
            .padding(10.dp),
    ) {
        Text(
            text = source,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
 * [T-mermaid-fullscreen] Full-screen diagram viewer.
 *
 * The inline block lives inside the message bubble, so it is scaled down to the
 * bubble width and capped at ~38% of the screen height. Zooming there only
 * magnifies an already-shrunken capture. This dialog draws the bitmap at its
 * natural CSS width — or screen width when the diagram is narrower — and pans
 * instead of squeezing, with zoom up to 8x.
 */
@Composable
private fun MermaidFullScreen(entry: RenderDiskCache.Entry, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val conf = LocalConfiguration.current
        val density = LocalDensity.current
        val screenW = conf.screenWidthDp.dp
        val screenH = conf.screenHeightDp.dp

        // A narrow diagram is scaled up to fill the screen; a wide one keeps its
        // own width and is panned. Never shrunk — that was the complaint about
        // zooming into a too-narrow picture.
        val baseScale = remember(entry.cssWidth, conf.screenWidthDp) {
            (conf.screenWidthDp / entry.cssWidth.toFloat()).coerceAtLeast(1f)
        }
        var scale by remember { mutableFloatStateOf(1f) }
        var offX by remember { mutableFloatStateOf(0f) }
        var offY by remember { mutableFloatStateOf(0f) }

        val imgWpx = with(density) { (entry.cssWidth.dp * baseScale).toPx() }
        val imgHpx = with(density) { (entry.cssHeight.dp * baseScale).toPx() }
        val viewW = with(density) { screenW.toPx() }
        val viewH = with(density) { screenH.toPx() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(entry) {
                        awaitEachGesture {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } == 0) break
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val next = (scale * zoom).coerceIn(1f, 8f)
                                scale = next
                                val maxX = ((imgWpx * next - viewW) / 2f).coerceAtLeast(0f)
                                val maxY = ((imgHpx * next - viewH) / 2f).coerceAtLeast(0f)
                                offX = (offX + pan.x).coerceIn(-maxX, maxX)
                                offY = (offY + pan.y).coerceIn(-maxY, maxY)
                                event.changes.forEach { if (it.pressed) it.consume() }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = entry.bitmap.asImageBitmap(),
                    contentDescription = "Mermaid diagram (full screen)",
                    modifier = Modifier
                        .size(entry.cssWidth.dp * baseScale, entry.cssHeight.dp * baseScale)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offX
                            translationY = offY
                        },
                )
            }

            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FsChip("复位") {
                    scale = 1f
                    offX = 0f
                    offY = 0f
                }
                Box(Modifier.size(width = 8.dp, height = 1.dp))
                FsChip("关闭", onDismiss)
            }
        }
    }
}

@Composable
private fun FsChip(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = TextStyle(fontSize = 13.sp),
        color = Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
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

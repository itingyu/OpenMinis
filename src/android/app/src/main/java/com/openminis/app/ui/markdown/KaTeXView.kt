package com.openminis.app.ui.markdown

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.LruCache
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.chat.KatexWebViewPool
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "KaTeXView"

/**
 * [GH#206] Hard ceiling on a captured formula bitmap, in PHYSICAL pixels.
 * Mirrors the constants in KatexWebViewPool — bitmap pixels are NATIVE heap on
 * Android 8+, and these sites previously had no upper bound at all, so one wide
 * display formula could cost several MB. Oversized formulas are scaled DOWN
 * (never clipped), trading sharpness for memory.
 */
private const val MAX_BITMAP_EDGE_PX = 2048
private const val MAX_BITMAP_PIXELS = 4_000_000

/**
 * [T-android-katex-webview-release] Marker set on the offscreen WebView in
 * `AndroidView.onRelease` so a capture runnable that was already queued when
 * the composable left (navigation mid-render) can tell the view is destroyed
 * and skip — drawing a destroyed WebView yields a blank bitmap, and caching
 * that under the formula's key would show an empty formula until eviction.
 */
private const val KATEX_WEBVIEW_RELEASED_TAG = "katex-webview-released"

/**
 * [T-render-disk-cache] Cache key for a formula. `isDark` is part of it because
 * the stored value is a rendered bitmap whose glyphs are baked light-on-dark or
 * the reverse; `displayMode` changes the layout, so it is part of the key too.
 */
internal fun katexCacheKey(
    latex: String,
    displayMode: Boolean,
    isDark: Boolean,
    fontSizePx: Int = KATEX_FONT_SIZE_PX,
): String =
    (if (displayMode) "D:" else "I:") +
        (if (isDark) "k:" else "l:") +
        "$fontSizePx:" +
        latex

/**
 * Composable that renders a display-mode LaTeX math block.
 */
@Composable
fun MathBlockView(
    latex: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        KaTeXRenderView(latex = latex, displayMode = true)
    }
}

/**
 * [T-katex-pool] One shared, warm WebView renders every formula.
 *
 * This used to build a WebView per formula and load katex-render.html each time,
 * so a document with a dozen formulas paid a full page load (KaTeX + fonts,
 * 0.3–0.8 s) per formula, and scrolling the formula out of view and back lost
 * the WebView and started over whenever the bitmap cache missed.
 * [KatexWebViewPool] keeps a single ready page and serialises renders through a
 * mutex, so a warm formula costs one `evaluateJavascript` call.
 */
@Composable
fun KaTeXRenderView(
    latex: String,
    displayMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val isDark = ChatColors.isDark
    // [T-android-inapp-theme-popups #187] `isDark` is part of the key because the
    // cached value is a bitmap whose glyphs are baked light-on-dark or the reverse.
    val cacheKey = remember(latex, displayMode, isDark) {
        katexCacheKey(latex, displayMode, isDark)
    }

    // [T-render-disk-cache] Read-through disk instead of a process-lifetime LRU:
    // a reopened chat reuses the PNG, and the bitmap is released as soon as the
    // formula scrolls out of composition.
    var entry by remember(cacheKey) { mutableStateOf<RenderDiskCache.Entry?>(null) }
    var checked by remember(cacheKey) { mutableStateOf(false) }
    var failed by remember(cacheKey) { mutableStateOf(false) }

    LaunchedEffect(cacheKey) {
        val appContext = context.applicationContext
        val cached = RenderDiskCache.load(appContext, "katex", cacheKey)
        if (cached != null) {
            entry = cached
            checked = true
            return@LaunchedEffect
        }
        val result = runCatching {
            KatexWebViewPool.render(
                context = appContext,
                latex = latex,
                displayMode = displayMode,
                isDark = isDark,
                fontSizePx = KATEX_FONT_SIZE_PX,
            )
        }.getOrNull()
        if (result == null) {
            AppLogger.warning(TAG, "KaTeX render failed · latex=${latex.take(80)}")
            failed = true
        } else {
            // T206: the bitmap is density × CSS px, but the formula is laid out
            // at its CSS size so it matches the surrounding 16sp text.
            val rendered = RenderDiskCache.Entry(
                bitmap = result.bitmap,
                cssWidth = result.size.width,
                cssHeight = result.size.height,
            )
            entry = rendered
            RenderDiskCache.save(appContext, "katex", cacheKey, rendered)
        }
        checked = true
    }

    val shown = entry
    when {
        shown != null -> Image(
            bitmap = shown.bitmap.asImageBitmap(),
            contentDescription = "Math: $latex",
            modifier = modifier.size(shown.cssWidth.dp, shown.cssHeight.dp),
        )

        failed || checked -> Text(
            text = latex,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = modifier,
        )

        // Placeholder while the shared renderer works through the queue. A
        // display-mode box reserves a line of height so surrounding text does
        // not jump when the formula lands.
        else -> Box(
            modifier = modifier.then(
                if (displayMode) {
                    Modifier.fillMaxWidth().height(44.dp)
                } else {
                    Modifier.widthIn(min = 20.dp).height(20.dp)
                },
            ),
        )
    }
}

/** KaTeX body size for every formula — matches the surrounding 16sp text. */
internal const val KATEX_FONT_SIZE_PX = 16

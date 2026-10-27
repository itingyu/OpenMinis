package com.openminis.app.ui.markdown

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * [T-render-disk-cache] On-disk store for rendered markdown media (Mermaid
 * diagrams, KaTeX formulas).
 *
 * Rendered results used to live in process-lifetime LruCaches holding native
 * ARGB bitmaps. Those caches were bounded but never shrank on their own and
 * vanished on process death, so a cold start re-rendered every formula and
 * diagram in the transcript through a WebView.
 *
 * This replaces them: the PNG and its layout metadata go to `cacheDir`, keyed by
 * the render source, so a reopened chat shows diagrams immediately and the
 * resident memory cost is zero once the screen scrolls away (the OS can reclaim
 * the file at any time — it is only a cache).
 *
 * Files are laid out as one directory per namespace, `<sha256-prefix>.png` plus
 * a sidecar `.json` holding the CSS size. `VERSION` is part of the directory name so a renderer
 * upgrade (new mermaid.min.js / KaTeX build) starts from a clean slate instead
 * of serving stale pictures — bump it when the render output changes.
 */
internal object RenderDiskCache {

    /** Bump whenever renderer output changes shape (not on every app build). */
    // [T-mermaid-viewport] Bump when the renderer output changes shape: v1
    // entries were captured with the WebView's 980px default viewport and are
    // missing the right-hand side of wide diagrams.
    private const val VERSION = 2

    /** Total budget across all namespaces; trimmed oldest-first after a write. */
    private const val MAX_BYTES = 96L * 1024 * 1024

    data class Entry(
        val bitmap: Bitmap,
        val cssWidth: Int,
        val cssHeight: Int,
    )

    private fun root(ctx: Context): File =
        File(ctx.cacheDir, "render-cache-v$VERSION").apply { mkdirs() }

    private fun dir(ctx: Context, namespace: String): File =
        File(root(ctx), namespace).apply { mkdirs() }

    /** Stable, filesystem-safe name for a cache key. */
    fun hash(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(40)
    }

    suspend fun load(ctx: Context, namespace: String, key: String): Entry? =
        withContext(Dispatchers.IO) {
            val base = File(dir(ctx, namespace), hash(key))
            val png = File(base.path + ".png")
            if (!png.exists()) return@withContext null
            // Touch so the LRU trim treats a hit as recent use.
            png.setLastModified(System.currentTimeMillis())
            val bitmap = runCatching {
                BitmapFactory.decodeFile(png.path)
            }.getOrNull() ?: run {
                png.delete()
                return@withContext null
            }
            val meta = runCatching {
                JSONObject(File(base.path + ".json").readText())
            }.getOrNull() ?: return@withContext null
            // [T-mermaid-capture-retry] Older builds could cache an unpainted
            // (fully transparent) capture; such an entry would paint a black
            // hole forever, so drop it and let the diagram re-render.
            if (isBlank(bitmap)) {
                bitmap.recycle()
                png.delete()
                return@withContext null
            }
            val cssW = meta.optInt("w", 0)
            val cssH = meta.optInt("h", 0)
            if (cssW <= 0 || cssH <= 0) return@withContext null
            Entry(bitmap = bitmap, cssWidth = cssW, cssHeight = cssH)
        }

    suspend fun save(
        ctx: Context,
        namespace: String,
        key: String,
        entry: Entry,
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val dir = dir(ctx, namespace)
            val base = File(dir, hash(key))
            val png = File(base.path + ".png")
            val tmp = File(base.path + ".png.tmp")
            tmp.outputStream().use { out ->
                entry.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (!tmp.renameTo(png)) {
                tmp.delete()
                return@runCatching
            }
            val meta = JSONObject().apply {
                put("w", entry.cssWidth)
                put("h", entry.cssHeight)
            }
            File(base.path + ".json").writeText(meta.toString())
            trim(dir)
        }.onFailure { Log.w("RenderDiskCache", "save $namespace failed: ${it.message}") }
    }

    /** Delete oldest entries until the directory fits the global budget. */
    private fun trim(dir: File) {
        val total = rootCacheSize(dir)
        if (total <= MAX_BYTES) return
        var remaining = total
        dir.listFiles()
            ?.sortedBy { it.lastModified() }
            ?.forEach { file ->
                if (remaining <= MAX_BYTES) return@forEach
                val size = file.length()
                if (file.delete()) remaining -= size
            }
    }

    private fun rootCacheSize(dir: File): Long {
        val parent = dir.parentFile ?: return 0
        var total = 0L
        parent.listFiles()?.forEach { nsDir ->
            nsDir.listFiles()?.forEach { total += it.length() }
        }
        return total
    }

    /** Drop every namespace, plus directories from older format versions. */
    suspend fun clearAll(ctx: Context) = withContext(Dispatchers.IO) {
        runCatching {
            File(ctx.cacheDir, "render-cache-v$VERSION").deleteRecursively()
        }
    }
}

/** Sampling check for a capture that holds no visible pixel. */
private fun isBlank(bmp: Bitmap): Boolean {
    val stride = 8
    var x = 0
    while (x < bmp.width) {
        var y = 0
        while (y < bmp.height) {
            if (android.graphics.Color.alpha(bmp.getPixel(x, y)) > 8) return false
            y += stride
        }
        x += stride
    }
    return true
}

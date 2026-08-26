package com.ishaan.essentialvoice.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The one picture loader in the app, for the What's new panel.
 *
 * A whole image library would be several hundred kilobytes to draw at most a
 * handful of pictures that are each fetched once and then never change, so this
 * does the three things that are actually needed: fetch once, keep it on disk,
 * and decode no larger than the screen can show.
 *
 * Everything here fails quietly. A changelog picture that will not load is not
 * worth an error message, so a failed load renders as nothing at all and the
 * text around it closes up.
 */
object NetImage {

    /** Roughly a phone's width in pixels. Decoding larger only wastes memory. */
    private const val TARGET_WIDTH = 1200
    private const val MAX_BYTES = 8L * 1024 * 1024
    private const val ASSET_SCHEME = "asset:"

    private fun cacheFile(context: Context, url: String): File {
        val name = MessageDigest.getInstance("SHA-1")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val dir = File(context.cacheDir, "whatsnew").apply { mkdirs() }
        return File(dir, name)
    }

    /**
     * The bytes for [url], from disk if they are already there.
     *
     * Downloaded to a temporary file and renamed, so an interrupted fetch can
     * never leave a half a picture behind that every later load then trusts.
     */
    private fun fetch(context: Context, url: String): File? {
        val target = cacheFile(context, url)
        if (target.exists() && target.length() > 0) return target

        val tmp = File(target.parentFile, target.name + ".part")
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
            }
            try {
                if (conn.responseCode !in 200..299) return null
                // A changelog picture is never megabytes. Anything that big is
                // either a mistake or something worth not downloading.
                val declared = conn.contentLengthLong
                if (declared > MAX_BYTES) return null
                conn.inputStream.use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out) }
                }
            } finally {
                conn.disconnect()
            }
            if (tmp.length() == 0L || tmp.length() > MAX_BYTES) {
                tmp.delete()
                null
            } else {
                tmp.renameTo(target)
                target
            }
        }.getOrElse {
            tmp.delete()
            null
        }
    }

    private fun sampleSizeFor(width: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= TARGET_WIDTH) sample *= 2
        return sample
    }

    /**
     * A picture bundled in the APK, addressed as `asset:name.png`.
     *
     * Only debug builds have any of these — it is how the debug manifest shows
     * a picture without one having been uploaded anywhere. See [Updater].
     */
    private fun loadAsset(context: Context, name: String): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.assets.open(name).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null

        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth) }
        context.assets.open(name).use {
            BitmapFactory.decodeStream(it, null, opts)?.asImageBitmap()
        }
    }.getOrNull()

    /** Decoded at the smallest power-of-two scale that still covers the screen. */
    suspend fun load(context: Context, url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        if (url.startsWith(ASSET_SCHEME)) {
            return@withContext loadAsset(context, url.removePrefix(ASSET_SCHEME))
        }

        val file = fetch(context, url) ?: return@withContext null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) {
            file.delete()
            return@withContext null
        }

        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth) }
        BitmapFactory.decodeFile(file.path, opts)?.asImageBitmap()
    }
}

/**
 * A picture from the web, filling whatever box the caller sizes.
 *
 * Sizing is the caller's job because these sit in a row of cards that all have
 * to be the same height — a picture that sized itself by its own proportions
 * would make every card a different one.
 *
 * While it is loading the box is a flat tile, so the row does not jump when the
 * picture arrives. If it never arrives, nothing is drawn at all: a changelog
 * picture that will not load is not worth an error message.
 */
@Composable
fun NetImageBox(url: String, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        val loaded = NetImage.load(context, url)
        if (loaded == null) failed = true else bitmap = loaded
    }

    if (failed) return

    val shape = RoundedCornerShape(12.dp)
    val image = bitmap

    Box(
        modifier
            .clip(shape)
            // Sunk while there is nothing there yet, so the space reads as
            // waiting rather than as an empty panel.
            .background(if (image == null) EV.SurfaceSunk else EV.Surface),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                // Fit, not Crop: these are screenshots of all shapes, and a
                // crop takes the middle out of a tall one.
                contentScale = ContentScale.Fit,
            )
        }
    }
}

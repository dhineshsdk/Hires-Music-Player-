package com.audiophile.engine.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.audiophile.engine.model.TrackMetadata
import com.audiophile.engine.ui.theme.AppLook
import com.audiophile.engine.ui.theme.LookState
import com.audiophile.engine.ui.theme.palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The cover of the playing track: the MediaStore album thumbnail when the track came from the
 * library, else the picture embedded in the file. Null when the file has no cover; callers then
 * draw a plain label, never invented art.
 */
@Composable
fun rememberTrackArt(track: TrackMetadata): ImageBitmap? {
    val context = LocalContext.current
    val art by produceState<ImageBitmap?>(null, track.albumArtUri, track.mediaUri) {
        value = withContext(Dispatchers.IO) { loadArt(context, track.albumArtUri, track.mediaUri) }
    }
    return art
}

private fun loadArt(context: Context, albumArtUri: String?, mediaUri: String?): ImageBitmap? {
    if (albumArtUri != null) {
        runCatching {
            context.contentResolver.loadThumbnail(Uri.parse(albumArtUri), Size(1024, 1024), null)
        }.getOrNull()?.let { return it.asImageBitmap() }
    }
    if (mediaUri == null) return null
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, Uri.parse(mediaUri))
        val bytes = retriever.embeddedPicture ?: return null
        decodeSampled(bytes, 1024)?.asImageBitmap()
    } catch (e: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private fun decodeSampled(bytes: ByteArray, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxSide && bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

/**
 * Full-bleed backdrop for the active look. Liquid: the cover, heavily blurred and desaturated so it
 * only tints the glass. Afterglow: black with one faint warm-white bloom. Blur needs API 31; older
 * phones get the plain ground.
 */
@Composable
fun LookBackdrop(art: ImageBitmap?, modifier: Modifier = Modifier) {
    val p = palette
    Box(modifier.fillMaxSize().background(p.ground)) {
        if (LookState.look == AppLook.LIQUID) {
            if (art != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val filter = remember {
                    val m = ColorMatrix().apply { setToSaturation(0.3f) }
                    m.timesAssign(ColorMatrix().apply { setToScale(0.55f, 0.55f, 0.55f, 1f) })
                    ColorFilter.colorMatrix(m)
                }
                Image(
                    bitmap = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = filter,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { scaleX = 1.35f; scaleY = 1.35f; alpha = 0.75f }
                        .blur(70.dp, BlurredEdgeTreatment.Unbounded)
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(p.ground.copy(alpha = 0.15f), p.ground.copy(alpha = 0.3f), p.ground.copy(alpha = 0.72f))
                    )
                )
            )
        } else {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color(0x24FFF4E4), Color.Transparent),
                        center = Offset(size.width * 0.5f, size.height * 0.32f),
                        radius = size.width * 0.75f
                    ),
                    radius = size.width * 0.75f,
                    center = Offset(size.width * 0.5f, size.height * 0.32f)
                )
            }
        }
    }
}

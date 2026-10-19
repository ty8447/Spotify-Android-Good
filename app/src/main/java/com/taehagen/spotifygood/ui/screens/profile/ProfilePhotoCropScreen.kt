package com.taehagen.spotifygood.ui.screens.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

/** Spotify-like avatar framing: square image result; circle is only a UI preview mask. */
@Composable
internal fun ProfilePhotoCropScreen(uri: Uri, onCancel: () -> Unit, onUsePhoto: (ByteArray) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var zoom by remember(uri) { mutableFloatStateOf(1f) }
    var angle by remember(uri) { mutableFloatStateOf(0f) }
    var dx by remember(uri) { mutableFloatStateOf(0f) }
    var dy by remember(uri) { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val sideDp = 300.dp
    val sidePx = with(density) { sideDp.toPx() }

    LaunchedEffect(uri) {
        try {
            bitmap = withContext(Dispatchers.IO) {
                val decoded = if (Build.VERSION.SDK_INT >= 28) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                } else {
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                        ?: throw IllegalStateException("Cannot open photo")
                }
                val edge = max(decoded.width, decoded.height)
                if (edge > 2048) Bitmap.createScaledBitmap(decoded,
                    (decoded.width * 2048f / edge).roundToInt().coerceAtLeast(1),
                    (decoded.height * 2048f / edge).roundToInt().coerceAtLeast(1), true)
                else decoded
            }
        } catch (e: Exception) { error = e.message ?: "Could not load image" }
    }
    /**
     * The accepted crop is a disk of radius sidePx / 2, not the whole square.
     * A disk stays a disk when rotated: in image-local axes the image centre
     * must remain within (half scaled image dimension - disk radius).
     * Clamp in those rotated axes, then transform back into screen coordinates.
     * This keeps the crop covered at every zoom and straighten angle while
     * allowing the image outside the circle to remain visible under the mask.
     */
    fun clampOffsets() {
        val b = bitmap ?: return
        zoom = zoom.coerceIn(1f, 10f)
        val base = max(sidePx / b.width, sidePx / b.height)
        val xLimit = ((b.width * base * zoom - sidePx) / 2f).coerceAtLeast(0f)
        val yLimit = ((b.height * base * zoom - sidePx) / 2f).coerceAtLeast(0f)
        val radians = Math.toRadians(angle.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        val localX = (c * dx + s * dy).coerceIn(-xLimit, xLimit)
        val localY = (-s * dx + c * dy).coerceIn(-yLimit, yLimit)
        dx = c * localX - s * localY
        dy = s * localX + c * localY
    }
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel, enabled = !busy) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
            Text("Move and scale", color = Color.White)
            IconButton(onClick = {
                val b = bitmap ?: return@IconButton
                val matrix = Matrix().apply { postRotate(90f) }
                bitmap = Bitmap.createBitmap(b, 0, 0, b.width, b.height, matrix, true)
                zoom = 1f; dx = 0f; dy = 0f
            }, enabled = !busy) { Icon(Icons.Rounded.RotateRight, "Rotate 90°", tint = Color.White) }
        }
        Spacer(Modifier.weight(1f))
        if (bitmap == null) {
            CircularProgressIndicator()
        } else {
            val b = bitmap!!
            // Keep the entire square visible. Only the area *outside* the circular crop
            // is darkened; a transparent centre makes the final selection obvious.
            Box(Modifier.size(sideDp).clipToBounds().background(Color.DarkGray)
                .pointerInput(b, sidePx) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        zoom = (zoom * gestureZoom).coerceIn(1f, 10f)
                        dx += pan.x; dy += pan.y
                        clampOffsets()
                    }
                }, contentAlignment = Alignment.Center) {
                // Draw the *entire* image at its aspect ratio. ContentScale.Crop
                // inside a square would discard image pixels before transforms,
                // causing false edges and incorrect drag limits when rotated.
                val base = max(sidePx / b.width, sidePx / b.height)
                val imageWidth = with(density) { (b.width * base).toDp() }
                val imageHeight = with(density) { (b.height * base).toDp() }
                Image(b.asImageBitmap(), contentDescription = "Photo crop preview",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.wrapContentSize(unbounded = true)
                        .size(imageWidth, imageHeight).graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        rotationZ = angle
                        translationX = dx
                        translationY = dy
                    })
                Canvas(Modifier.fillMaxSize()) {
                    val cutout = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                        addOval(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                    }
                    drawPath(cutout, Color.Black.copy(alpha = 0.30f))
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Pinch to zoom • drag to reposition", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(14.dp))
            Text("Straighten  ${"%.1f".format(java.util.Locale.US, angle)}°", color = Color.White,
                style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(value = angle, onValueChange = { angle = it; clampOffsets() },
                    valueRange = -45f..45f, enabled = !busy, modifier = Modifier.weight(1f))
                TextButton(onClick = { angle = 0f; clampOffsets() }, enabled = !busy) {
                    Text("Reset")
                }
            }
        }
        Spacer(Modifier.weight(1f))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            val b = bitmap ?: return@Button
            busy = true
            scope.launch {
                try {
                    val jpeg = withContext(Dispatchers.Default) {
                        // Render the exact square shown in the editor, including straightening.
                        // Matrix-only rectangular cropping would not preserve arbitrary angles.
                        val outputSize = 1024
                        val output = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
                        val canvas = AndroidCanvas(output)
                        canvas.drawColor(android.graphics.Color.BLACK)
                        val factor = outputSize / sidePx
                        val scale = max(sidePx / b.width, sidePx / b.height) * zoom * factor
                        canvas.translate(outputSize / 2f + dx * factor, outputSize / 2f + dy * factor)
                        canvas.rotate(angle)
                        canvas.scale(scale, scale)
                        canvas.drawBitmap(b, -b.width / 2f, -b.height / 2f,
                            AndroidPaint(AndroidPaint.FILTER_BITMAP_FLAG))
                        ByteArrayOutputStream().use { stream ->
                            check(output.compress(Bitmap.CompressFormat.JPEG, 90, stream))
                            stream.toByteArray()
                        }
                    }
                    onUsePhoto(jpeg)
                } catch (e: Exception) { error = e.message ?: "Could not crop image" }
                finally { busy = false }
            }
        }, enabled = bitmap != null && !busy, modifier = Modifier.padding(bottom = 22.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp)) else Text("Use photo")
        }
    }
}

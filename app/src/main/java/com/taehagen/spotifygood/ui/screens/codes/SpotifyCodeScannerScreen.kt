package com.taehagen.spotifygood.ui.screens.codes

import android.Manifest
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.ImageFormat
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.common.InputImage
import com.taehagen.spotifygood.ui.navigation.LocalAppNavigator
import com.taehagen.spotifygood.ui.components.rememberAppGraph
import com.taehagen.spotifygood.data.rpcArgs
import com.taehagen.spotifygood.data.callOffMain
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import androidx.core.net.toUri

/** Spotify Code live/gallery scanner and QR navigation. */
@Composable
fun SpotifyCodeScannerScreen() {
    val context = LocalContext.current
    val navigator = LocalAppNavigator.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var error by remember { mutableStateOf<String?>(null) }
    var readingPhoto by remember { mutableStateOf(false) }
    val resolved = remember { AtomicBoolean(false) }
    val graph = rememberAppGraph()
    val scope = rememberCoroutineScope()
    var scanDetails by remember { mutableStateOf("") }
    var resolvingWaveform by remember { mutableStateOf(false) }
    // Decode every fresh camera observation even while a previous reference is resolving.
    // Do not retry a rejected reference on every frame (which can starve the camera).
    var attemptedReference by remember { mutableStateOf<String?>(null) }
    var resolverJob by remember { mutableStateOf<Job?>(null) }
    val failedReferenceUntil = remember { mutableMapOf<String, Long>() }
    var cameraPendingReference by remember { mutableStateOf<String?>(null) }
    var cameraPendingAt by remember { mutableLongStateOf(0L) }
    var cameraPendingFrame by remember { mutableLongStateOf(-1L) }
    val onWaveformReading: (SpotifyWaveformProbe.Candidate, Boolean) -> Unit = { candidate, fromCamera ->
        Log.i("SpotifyWaveform", "23 bars orientation=${candidate.orientation} levels=${candidate.levels.joinToString(",")}")
        if (!resolvingWaveform && !resolved.get()) {
            resolvingWaveform = true
            val observed = candidate.levels.toList()
            scope.launch {
                try {
                    val decoded = withContext(Dispatchers.Default) { SpotifyCodeDecoder.decode(observed) }
                    if (decoded == null) {
                        Log.i("SpotifyScannableResolver", "Waveform rejected (CRC / Viterbi): levels=${observed.joinToString(",")}")
                        scanDetails = "23 bars found; decoder could not validate this reading. Try a clearer code."
                    } else {
                        // An isolated CRC-valid camera observation can still be a false positive.
                        // Gallery photos are manually selected and keep their immediate-open behavior.
                        val nowMs = android.os.SystemClock.elapsedRealtime()
                        // A raw reading and its median consensus share one source frame;
                        // neither may confirm the other. A high-res still is independent
                        // and uses observedAtMs=0 until a live frame confirms it.
                        val distinctFrame = candidate.observedAtMs != cameraPendingFrame
                        val confirmed = !fromCamera || (
                            cameraPendingReference == decoded.reference && distinctFrame &&
                                nowMs - cameraPendingAt in 70L..5000L
                        )
                        if (fromCamera && !confirmed) {
                            cameraPendingReference = decoded.reference
                            cameraPendingAt = nowMs
                            cameraPendingFrame = candidate.observedAtMs
                            scanDetails = "Code recognized; hold steady to confirm…"
                            Log.i("SpotifyScannableResolver", "camera provisional reference=${decoded.reference} errors=${decoded.bitErrors}")
                        } else {
                            if (fromCamera) {
                                Log.i("SpotifyScannableResolver", "camera confirmed reference=${decoded.reference} errors=${decoded.bitErrors} confirmMs=${nowMs - cameraPendingAt}")
                            }
                            scanDetails = "Decoded ${decoded.reference} (${decoded.bitErrors} corrected bit errors). Resolving…"
                        }
                        if (confirmed) {
                            val reference = decoded.reference
                            val retryAt = failedReferenceUntil[reference] ?: 0L
                            if (fromCamera && nowMs < retryAt) {
                                // Invalid or unavailable media references must not cause repeated
                                // network requests, nor prevent a different code from scanning.
                                scanDetails = "Code not available; point at another Spotify Code."
                            } else if (reference != attemptedReference) {
                                // A different confirmed code supersedes any unresolved request.
                                resolverJob?.cancel()
                                attemptedReference = reference
                                resolverJob = scope.launch {
                                    try {
                                        val response = withTimeout(4500L) {
                                            graph.rpc.callOffMain<JsonObject>(
                                                "catalog.resolveScannable", rpcArgs { put("mediaReference", reference) }
                                            )
                                        }
                                        // Ignore a response for a code that has already been replaced.
                                        if (attemptedReference == reference && !resolved.get()) {
                                            val uri = response["uri"]?.jsonPrimitive?.content
                                            Log.i("SpotifyScannableResolver", "scan reference=$reference errors=${decoded.bitErrors} uri=$uri")
                                            if (uri != null && spotifyQrUri(uri) != null) {
                                                if (resolved.compareAndSet(false, true)) {
                                                    navigator.back()
                                                    if (!navigator.openUri(uri)) {
                                                        resolved.set(false)
                                                        navigator.showMessage("Spotify Good could not open this URI.")
                                                    }
                                                }
                                            } else {
                                                failedReferenceUntil[reference] = android.os.SystemClock.elapsedRealtime() + 6000L
                                                scanDetails = "Reference $reference is not supported; keep scanning."
                                                Log.w("SpotifyScannableResolver", "scan reference=$reference returned unsupported URI")
                                            }
                                        }
                                    } catch (cancelled: CancellationException) {
                                        // Superseded scans and screen disposal must not show an error.
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        if (attemptedReference == reference && !resolved.get()) {
                                            failedReferenceUntil[reference] = android.os.SystemClock.elapsedRealtime() + 6000L
                                            Log.w("SpotifyScannableResolver", "scan reference=$reference failed: ${failure.javaClass.simpleName}: ${failure.message}", failure)
                                            scanDetails = "Could not resolve that code; keep scanning."
                                        }
                                    } finally {
                                        if (attemptedReference == reference) attemptedReference = null
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    resolvingWaveform = false
                }
            }
        }
    }
    val onDetected: (String) -> Unit = { value ->
        val link = spotifyQrUri(value)
        if (link != null && resolved.compareAndSet(false, true)) {
            // Back and navigation both dispatch on the main thread: leave the camera first.
            navigator.back()
            if (!navigator.openUri(link)) {
                resolved.set(false)
                error = "Spotify could not open this code."
                Log.w("SpotifyCodeScanner", "Unrecognized Spotify URI: $link")
            }
        } else if (link == null && value.startsWith("https://spotify.link/")) {
            error = "Spotify short links need redirect resolution; scan a direct Spotify QR link."
        }
    }
    val onCameraLost: () -> Unit = {
        if (!resolved.get()) {
            cameraPendingReference = null
            cameraPendingAt = 0L
            cameraPendingFrame = -1L
            if (attemptedReference == null && !resolvingWaveform) scanDetails = ""
            Log.d("SpotifyScannableResolver", "camera candidate lost; confirmation reset")
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        granted = allowed
        if (!allowed) error = "Camera access is required for live scanning. You can still select a photo."
    }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            error = null
            scanDetails = ""
            resolverJob?.cancel()
            attemptedReference = null
            cameraPendingReference = null
            cameraPendingFrame = -1L
            readingPhoto = true
            Log.i("SpotifyWaveform", "Gallery scan started")
            scope.launch {
                try {
                    // Decode and search away from the Compose/UI thread. Large camera photos
                    // previously allocated hundreds of MB and blocked rendering.
                    val galleryResult = withContext(Dispatchers.Default) {
                        val resolver = context.contentResolver
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                        var sample = 1
                        while (max(bounds.outWidth, bounds.outHeight) / sample > 1800) sample *= 2
                        val options = BitmapFactory.Options().apply { inSampleSize = sample }
                        resolver.openInputStream(uri)?.use { stream ->
                            BitmapFactory.decodeStream(stream, null, options)?.let { bitmap ->
                                try {
                                    Pair(SpotifyWaveformProbe.fromBitmap(bitmap), "${bitmap.width}x${bitmap.height}")
                                } finally { bitmap.recycle() }
                            }
                        }
                    }
                    if (galleryResult == null) {
                        Log.w("SpotifyWaveform", "Gallery: could not decode bitmap")
                    } else {
                        val (candidate, dimensions) = galleryResult
                        if (candidate != null) onWaveformReading(candidate, false)
                        else Log.i("SpotifyWaveform", "Gallery: no 23-bar candidate ($dimensions)")
                    }
                    // Keep the independent ML Kit QR fallback intact.
                    val image = InputImage.fromFilePath(context, uri)
                    val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
                    scanner.process(image)
                        .addOnSuccessListener { codes ->
                            val target = codes.mapNotNull { it.rawValue }.firstOrNull { spotifyQrUri(it) != null }
                            if (target != null) onDetected(target)
                            else if (error == null && scanDetails.isEmpty() && !resolvingWaveform) error = "No supported QR link or recognizable 23-bar waveform found."
                        }
                        .addOnFailureListener {
                            Log.e("SpotifyCodeScanner", "Gallery image analysis failed", it)
                            error = "Could not read the selected photo."
                        }
                        .addOnCompleteListener { scanner.close(); readingPhoto = false }
                } catch (e: Exception) {
                    readingPhoto = false
                    Log.e("SpotifyWaveform", "Gallery waveform analysis failed", e)
                    error = "Could not open the selected photo."
                }
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            SpotifyCameraPreview(
                onDetected = onDetected,
                onWaveform = { onWaveformReading(it, true) },
                onCameraLost = onCameraLost,
                onError = { error = it },
            )
            Column(
                Modifier.align(Alignment.Center).padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Point your camera at a Spotify Code",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                    Surface(
                        color = Color.Transparent,
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(2.dp, Color.White),
                        modifier = Modifier.fillMaxSize(),
                    ) {}
                }
                Spacer(Modifier.height(24.dp))
                ScannerWaveform()
            }
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 28.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xFF232323))
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ScannerWaveform()
                Spacer(Modifier.height(16.dp))
                Text("Spotify Codes", style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text("Point your camera at a code to open music, playlists, and more. No photo required.",
                    color = Color.LightGray, textAlign = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                Button(onClick = { permission.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.fillMaxWidth()) { Text("Scan") }
                TextButton(onClick = navigator::back) { Text("Dismiss", color = Color.White) }
            }
        }
        IconButton(onClick = navigator::back, modifier = Modifier.align(Alignment.TopStart)
            .statusBarsPadding().padding(12.dp)) {
            Icon(Icons.Rounded.Close, contentDescription = "Close scanner", tint = Color.White)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            error?.let {
                Surface(color = Color(0xDD222222), shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Text(it, color = Color.White, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(12.dp))
                }
            }
            if (scanDetails.isNotEmpty()) {
                Text(scanDetails, color = Color.White, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            if (granted) {
                TextButton(onClick = { photo.launch("image/*") }, enabled = !readingPhoto) {
                    Icon(Icons.Rounded.PhotoLibrary, null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("Select from photos", color = Color.White,
                        style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        if (readingPhoto) {
            Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                Surface(shape = RoundedCornerShape(18.dp), color = Color(0xFF292929)) {
                    Row(Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(14.dp))
                        Text("Getting your code", color = Color.White,
                            style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }

}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
private fun SpotifyCameraPreview(
    onDetected: (String) -> Unit,
    onWaveform: (SpotifyWaveformProbe.Candidate) -> Unit,
    onCameraLost: () -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    // JPEG callbacks must not block the single-thread live ImageAnalysis executor.
    val stillExecutor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var tracked by remember { mutableStateOf<SpotifyWaveformTracker.Box?>(null) }
    var trackedWidth by remember { mutableIntStateOf(0) }
    var trackedHeight by remember { mutableIntStateOf(0) }
    var lastTrackedAt by remember { mutableLongStateOf(0L) }
    DisposableEffect(lifecycleOwner) {
        var active = true
        val future = ProcessCameraProvider.getInstance(context)
        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
        var lastProbe = 0L
        var lastStatus = 0L
        var lastTracking = 0L
        var lastQrProbe = 0L
        var firstCandidateAt = 0L
        var lastCandidateAt = 0L
        var lastLostReportedAt = 0L
        var lastStillCapture = 0L
        val capturingStill = AtomicBoolean(false)
        var checkedFrames = 0
        val waveformConsensus = CameraWaveformConsensus()
        var lastSignature = ""
        var lastSignatureAt = 0L
        var latestTracking: SpotifyWaveformTracker.Box? = null
        Log.i("SpotifyWaveform", "Camera analyzer setup started")
        future.addListener({
            if (!active) return@addListener
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().apply { surfaceProvider = previewView.surfaceProvider }
                // A still photo supplies greater detail only after fast Y-plane detection.
                // Never run continuous high-resolution image analysis.
                val stillCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    // CameraX previously negotiated only 640x480 YUV analysis.
                    // A larger buffer preserves the thin Spotify bars at a distance.
                    .setResolutionSelector(ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(
                            android.util.Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        ))
                        .build())
                    .build()
                Log.i("SpotifyWaveform", "Preferred 1280x720 analysis, fallback favors smaller sizes")
                analysis.setAnalyzer(executor) { proxy ->
                    val image = proxy.image
                    if (image == null) { proxy.close(); return@setAnalyzer }
                    if (checkedFrames == 0 && lastProbe == 0L) {
                        Log.i("SpotifyWaveform", "Actual ImageAnalysis=${proxy.width}x${proxy.height} rotation=${proxy.imageInfo.rotationDegrees}")
                    }
                    val now = android.os.SystemClock.elapsedRealtime()
                    // A 450ms cadence plus 1.8s duplicate suppression made a stable
                    // waveform take seconds to confirm. Analyze at most ~5 times/sec;
                    // KEEP_ONLY_LATEST drops excess frames while processing is busy.
                    if (now - lastProbe >= 180L) {
                        lastProbe = now
                        checkedFrames++
                        // Reacquire promptly after losing a box. A stable box needs
                        // fewer full-frame tracker runs than an unlocalized code.
                        val trackInterval = if (latestTracking == null) 450L else 850L
                        if (now - lastTracking >= trackInterval) {
                            lastTracking = now
                            val tracking = runCatching { SpotifyWaveformTracker.locate(proxy) }
                                .onFailure { Log.w("SpotifyWaveform", "Tracking analysis failed", it) }
                                .getOrNull()
                            latestTracking = tracking
                            val rotated = proxy.imageInfo.rotationDegrees % 180 != 0
                            val frameW = if (rotated) proxy.height else proxy.width
                            val frameH = if (rotated) proxy.width else proxy.height
                            ContextCompat.getMainExecutor(context).execute {
                                if (active) {
                                    tracked = tracking
                                    trackedWidth = frameW
                                    trackedHeight = frameH
                                    lastTrackedAt = now
                                }
                            }
                        }
                        val startedAt = android.os.SystemClock.elapsedRealtime()
                        val candidate = runCatching { SpotifyWaveformProbe.fromFrame(proxy, latestTracking) }
                            .onFailure { Log.w("SpotifyWaveform", "Probe frame failed", it) }
                            .getOrNull()
                        val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                        if (now - lastStatus >= 3000L) {
                            Log.i("SpotifyWaveform", "Camera probe duration=${elapsed}ms tracking=${latestTracking != null} candidate=${candidate != null}")
                        }
                        if (candidate == null && now - lastStatus >= 3000L) {
                            lastStatus = now
                            Log.i("SpotifyWaveform", "Camera analyzing: $checkedFrames sampled frames; no 23-bar candidate yet")
                        }
                        if (candidate == null) {
                            waveformConsensus.expire(now)
                            if (now - lastCandidateAt >= 1100L) {
                                firstCandidateAt = 0L
                                lastSignature = ""
                                if (lastLostReportedAt < lastCandidateAt || lastLostReportedAt == 0L) {
                                    lastLostReportedAt = now
                                    ContextCompat.getMainExecutor(context).execute {
                                        if (active) onCameraLost()
                                    }
                                }
                            }
                        } else {
                            if (firstCandidateAt == 0L || now - lastCandidateAt >= 900L) {
                                firstCandidateAt = now
                            }
                            lastCandidateAt = now
                            // Fast path: give the live decoder time to confirm two actual
                            // frames before paying the cost of a JPEG capture. If the
                            // observed code cannot be resolved, the still remains a fallback.
                            if (now - firstCandidateAt >= 1100L &&
                                now - lastStillCapture >= 7000L &&
                                capturingStill.compareAndSet(false, true)) {
                                lastStillCapture = now
                                ContextCompat.getMainExecutor(context).execute {
                                    if (!active) {
                                        capturingStill.set(false)
                                    } else {
                                        Log.i("SpotifyWaveform", "Camera still capture requested")
                                        stillCapture.takePicture(stillExecutor, object : ImageCapture.OnImageCapturedCallback() {
                                            override fun onCaptureSuccess(photo: ImageProxy) {
                                                try {
                                                    val plane = photo.planes.firstOrNull()
                                                    if (plane != null && photo.format == ImageFormat.JPEG) {
                                                        val buffer = plane.buffer.duplicate()
                                                        val bytes = ByteArray(buffer.remaining())
                                                        buffer.get(bytes)
                                                        // Subsample during JPEG decode; do not allocate full
                                                        // 4000x3000 ARGB + rotated + scaled copies.
                                                        val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                                                        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                                                        if (decoded != null) {
                                                            val rotation = photo.imageInfo.rotationDegrees
                                                            val rotated = if (rotation % 360 != 0) {
                                                                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                                                                android.graphics.Bitmap.createBitmap(
                                                                    decoded, 0, 0, decoded.width, decoded.height, matrix, true
                                                                ).also { decoded.recycle() }
                                                            } else decoded
                                                            try {
                                                                // Bound the bitmap search cost on large JPEG captures.
                                                                val scale = minOf(1f, 1800f / max(rotated.width, rotated.height))
                                                                val search = if (scale < 1f) {
                                                                    android.graphics.Bitmap.createScaledBitmap(
                                                                        rotated, (rotated.width * scale).toInt().coerceAtLeast(1),
                                                                        (rotated.height * scale).toInt().coerceAtLeast(1), true
                                                                    )
                                                                } else rotated
                                                                try {
                                                                    val reading = SpotifyWaveformProbe.fromBitmap(search)
                                                                    Log.i("SpotifyWaveform", "Camera still candidate=${reading != null} size=${search.width}x${search.height}")
                                                                    if (reading != null) {
                                                                        ContextCompat.getMainExecutor(context).execute {
                                                                            if (active) onWaveform(reading)
                                                                        }
                                                                    }
                                                                } finally {
                                                                    if (search !== rotated) search.recycle()
                                                                }
                                                            } finally { rotated.recycle() }
                                                        }
                                                    } else {
                                                        Log.w("SpotifyWaveform", "Unexpected still format=${photo.format}")
                                                    }
                                                } catch (e: Exception) {
                                                    Log.w("SpotifyWaveform", "Camera still processing failed", e)
                                                } finally {
                                                    photo.close()
                                                    capturingStill.set(false)
                                                }
                                            }

                                            override fun onError(exception: ImageCaptureException) {
                                                Log.w("SpotifyWaveform", "Camera still capture failed", exception)
                                                capturingStill.set(false)
                                            }
                                        })
                                    }
                                }
                            }
                            // Send the raw reading first, preserving immediate CRC-valid scans.
                            // Repeated near-identical frames get a median vote to correct
                            // one-level noise without changing the gallery path.
                            // Carry the YUV observation time across raw and fused readings.
                            val attempts = waveformConsensus.offer(candidate.copy(observedAtMs = now), now)
                            for (reading in attempts) {
                                val signature = reading.signature
                                // Dedupe duplicate raw/consensus callbacks from one frame,
                                // but permit the *next* observation to confirm rapidly.
                                if (signature != lastSignature || now - lastSignatureAt >= 180L) {
                                    lastSignature = signature
                                    lastSignatureAt = now
                                    ContextCompat.getMainExecutor(context).execute { onWaveform(reading) }
                                }
                            }
                        }
                    }
                    // ML Kit QR detection previously processed every frame and held
                    // ImageProxy open until its asynchronous completion. Spotify waveform
                    // detection is the primary path, so limit QR work to ~1 Hz.
                    if (now - lastQrProbe < 950L) {
                        proxy.close()
                    } else {
                        lastQrProbe = now
                        try {
                            val input = InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees)
                            scanner.process(input)
                                .addOnSuccessListener { codes ->
                                    if (active) codes.firstNotNullOfOrNull { it.rawValue }?.let(onDetected)
                                }
                                .addOnFailureListener { failure ->
                                    Log.d("SpotifyCodeScanner", "QR frame processing failed", failure)
                                }
                                .addOnCompleteListener { proxy.close() }
                        } catch (failure: Exception) {
                            Log.w("SpotifyCodeScanner", "QR frame could not start", failure)
                            proxy.close()
                        }
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, stillCapture)
                Log.i("SpotifyWaveform", "Camera analyzer bound")
            } catch (e: Exception) {
                Log.e("SpotifyWaveform", "Camera analyzer setup failed", e)
                onError("Camera could not start: ${e.message ?: "unknown error"}")
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            active = false
            if (future.isDone) runCatching { future.get().unbindAll() }
            scanner.close()
            executor.shutdown()
            stillExecutor.shutdown()
        }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        // Match PreviewView.ScaleType.FILL_CENTER (the transformed image is center-cropped).
        Canvas(Modifier.fillMaxSize()) {
            val box = tracked
            if (box != null && trackedWidth > 0 && trackedHeight > 0 &&
                android.os.SystemClock.elapsedRealtime() - lastTrackedAt < 1700L) {
                val scale = max(size.width / trackedWidth, size.height / trackedHeight)
                val imageW = trackedWidth * scale
                val imageH = trackedHeight * scale
                val offsetX = (size.width - imageW) / 2
                val offsetY = (size.height - imageH) / 2
                val x = offsetX + box.left * imageW
                val y = offsetY + box.top * imageH
                val width = (box.right - box.left) * imageW
                val height = (box.bottom - box.top) * imageH
                if (width > 0 && height > 0) {
                    drawRect(Color.LightGray.copy(alpha = 0.22f), Offset(x, y), Size(width, height), style = Fill)
                    drawRect(Color.LightGray.copy(alpha = 0.72f), Offset(x, y), Size(width, height),
                        style = Stroke(width = 2.dp.toPx()))
                }
            }
        }
    }
}

/** Lightweight listening-style movement; visual feedback, not an indicator of a decoded waveform. */
@Composable
private fun ScannerWaveform() {
    val transition = rememberInfiniteTransition(label = "scanner waveform")
    val bars = (0 until 17).map { index ->
        val phase = (index * 83) % 420
        transition.animateFloat(
            initialValue = 7f, targetValue = 22f + (index % 5) * 3f,
            animationSpec = infiniteRepeatable(
                animation = tween(650 + phase, delayMillis = phase, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ), label = "wave $index",
        )
    }
    Row(Modifier.height(42.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        bars.forEach { level ->
            Box(Modifier.width(3.dp).height(level.value.dp)
                .clip(RoundedCornerShape(2.dp)).background(Color.White))
        }
    }
}

/** Normalize supported *direct* QR URLs to the canonical Spotify URI form. */
internal fun spotifyQrUri(raw: String): String? {
    val allowed = setOf("track", "album", "playlist", "artist", "show", "episode", "user")
    if (raw.startsWith("spotify:")) {
        val parts = raw.split(':')
        if (parts.size == 3 && parts[1] in allowed && parts[2].isNotBlank()) return raw
        return null
    }
    val parsed = runCatching { raw.toUri() }.getOrNull() ?: return null
    if (parsed.scheme != "https" || parsed.host !in setOf("open.spotify.com", "play.spotify.com")) return null
    val parts = parsed.pathSegments
    val start = if (parts.firstOrNull()?.startsWith("intl-") == true) 1 else 0
    val kind = parts.getOrNull(start) ?: return null
    val id = parts.getOrNull(start + 1) ?: return null
    if (kind !in allowed || id.isBlank()) return null
    return "spotify:$kind:$id"
}

/** Camera-only temporal filter. Never used for gallery photos.
 * Orientation 180 means reverse the order before voting, not invert heights.
 * Only nearby sequences join a vote so unrelated tracker hits cannot be blended.
 */
internal class CameraWaveformConsensus {
    private val recent = ArrayDeque<List<Int>>()
    private var lastSeen = 0L
    private var anchor: List<Int>? = null

    fun expire(now: Long) {
        if (now - lastSeen > 1700L) {
            recent.clear()
            anchor = null
        }
    }

    fun offer(raw: SpotifyWaveformProbe.Candidate, now: Long): List<SpotifyWaveformProbe.Candidate> {
        expire(now)
        lastSeen = now
        if (raw.levels.size != 23) return listOf(raw)
        val normalized = if (raw.orientation == 180) raw.levels.reversed() else raw.levels
        val prior = anchor
        // A different code (or a badly segmented frame) resets voting immediately.
        if (prior != null) {
            val disagreements = prior.indices.count { kotlin.math.abs(prior[it] - normalized[it]) > 1 }
            if (disagreements > 5) recent.clear()
        }
        recent.addLast(normalized)
        while (recent.size > 5) recent.removeFirst()
        anchor = recent.firstOrNull()
        if (recent.size < 3) return listOf(raw)
        val median = List(23) { i -> recent.map { it[i] }.sorted()[recent.size / 2] }
        val fused = SpotifyWaveformProbe.Candidate(median, raw.contrast, 0, raw.observedAtMs)
        android.util.Log.d("SpotifyWaveform", "Camera consensus frames=${recent.size} disagreements=" +
            raw.levels.indices.count { raw.levels[it] != median[it] } + " levels=" + median.joinToString(","))
        return if (fused.levels == raw.levels) listOf(raw) else listOf(raw, fused)
    }
}

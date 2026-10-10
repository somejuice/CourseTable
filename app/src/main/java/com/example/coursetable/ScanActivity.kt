package com.example.coursetable

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 扫描多色二维码，成功后把解压出的 JSON 通过 RESULT 返回 */
class ScanActivity : ComponentActivity() {
    companion object {
        const val EXTRA_JSON = "json"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val done = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = CourseRepository(applicationContext)
        setContent {
            CourseTableTheme(settings = repo.settings) {
                var hasPermission by remember {
                    mutableStateOf(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                }
                val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
                val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                    if (uri != null) decodeUri(uri)
                }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    if (!hasPermission) permLauncher.launch(Manifest.permission.CAMERA)
                }
                ScanScreen(
                    hasPermission = hasPermission,
                    onRequestPermission = { permLauncher.launch(Manifest.permission.CAMERA) },
                    onPickImage = { pickImage.launch(arrayOf("image/*")) },
                    onClose = { finish() },
                    onFrame = { pixels, w, h -> tryDecode(pixels, w, h) }
                )
            }
        }
    }

    private fun decodeUri(uri: Uri) {
        executor.execute {
            val bitmap = runCatching {
                val src = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(src) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE; d.isMutableRequired = false }
            }.getOrNull()
            val bytes = bitmap?.let { ColorQr.decode(it.copy(Bitmap.Config.ARGB_8888, false)) }
            if (bytes == null) {
                runOnUiThread { Toast.makeText(this, "未识别到课表二维码，请确保图片清晰完整", Toast.LENGTH_LONG).show() }
            } else finishWith(bytes)
        }
    }

    private fun tryDecode(pixels: IntArray, w: Int, h: Int) {
        if (done.get()) return
        val bytes = ColorQr.decode(pixels, w, h) ?: return
        finishWith(bytes)
    }

    private fun finishWith(bytes: ByteArray) {
        if (!done.compareAndSet(false, true)) return
        val json = runCatching { if (ShareCodec.isV2(bytes)) ShareCodec.unpackToJson(bytes) else ColorQr.decompress(bytes) }.getOrNull()
        runOnUiThread {
            if (json == null) {
                Toast.makeText(this, "二维码数据损坏", Toast.LENGTH_LONG).show()
                done.set(false)
            } else {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_JSON, json))
                finish()
            }
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}

@Composable
private fun ScanScreen(
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onPickImage: () -> Unit,
    onClose: () -> Unit,
    onFrame: (IntArray, Int, Int) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Box(Modifier.fillMaxSize()) {
            if (hasPermission) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val previewView = PreviewView(ctx).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
                        val future = ProcessCameraProvider.getInstance(ctx)
                        val analysisExecutor = Executors.newSingleThreadExecutor()
                        future.addListener({
                            val provider = future.get()
                            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                            val analysis = ImageAnalysis.Builder()
                                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .setTargetResolution(android.util.Size(1280, 960))
                                .build()
                            var last = 0L
                            analysis.setAnalyzer(analysisExecutor) { image ->
                                val now = System.currentTimeMillis()
                                if (now - last < 150) { image.close(); return@setAnalyzer }
                                last = now
                                try {
                                    val plane = image.planes[0]
                                    val buffer: ByteBuffer = plane.buffer
                                    val rowStride = plane.rowStride
                                    val w = image.width
                                    val h = image.height
                                    val pixels = IntArray(w * h)
                                    val row = ByteArray(rowStride)
                                    buffer.rewind()
                                    for (y in 0 until h) {
                                        val remaining = buffer.remaining()
                                        buffer.get(row, 0, minOf(rowStride, remaining))
                                        val base = y * w
                                        for (x in 0 until w) {
                                            val i = x * 4
                                            val r = row[i].toInt() and 0xFF
                                            val g = row[i + 1].toInt() and 0xFF
                                            val b = row[i + 2].toInt() and 0xFF
                                            pixels[base + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                                        }
                                    }
                                    onFrame(pixels, w, h)
                                } catch (_: Exception) {
                                } finally {
                                    image.close()
                                }
                            }
                            runCatching {
                                provider.unbindAll()
                                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    }
                )
                // 取景框
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(260.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .padding(0.dp)
                ) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.9f),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(48f, 48f)
                        )
                    }
                }
            } else {
                Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("需要相机权限来扫描二维码", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onRequestPermission, modifier = Modifier.padding(top = 16.dp)) { Text("授予权限") }
                }
            }

            Text(
                "将对方的多色课表二维码对准取景框\n保持画面明亮、正对、完整",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 24.dp)
            )
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("取消", color = Color.White) }
                Button(onClick = onPickImage, modifier = Modifier.weight(1f)) { Text("从相册识别") }
            }
            @Suppress("UNUSED_VARIABLE") val unused = context
        }
    }
}

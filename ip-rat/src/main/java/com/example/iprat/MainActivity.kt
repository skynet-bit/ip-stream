package com.example.iprat

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private var latestBitmap: Bitmap? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCamera()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        startServer()

        setContent {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(text = "IP-RAT Running...")
                Text(text = "Stream available at http://<this-device-ip>:8080/video")
            }
        }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val imageAnalyzer = ImageAnalysis.Builder()
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { image ->
                        processImage(image)
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e("IP-RAT", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImage(image: ImageProxy) {
        val bitmap = image.toBitmap()
        // Rotate bitmap if needed (CameraX usually provides image in landscape)
        val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
        latestBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        image.close()
    }

    private fun startServer() {
        embeddedServer(CIO, port = 8080) {
            routing {
                get("/video") {
                    call.respondBytesWriter(ContentType.parse("multipart/x-mixed-replace; boundary=--boundary")) {
                        while (true) {
                            val bitmap = latestBitmap
                            if (bitmap != null) {
                                val stream = ByteArrayOutputStream()
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                                val data = stream.toByteArray()

                                writeStringUtf8("--boundary\r\n")
                                writeStringUtf8("Content-Type: image/jpeg\r\n")
                                writeStringUtf8("Content-Length: ${data.size}\r\n")
                                writeStringUtf8("\r\n")
                                writeFully(data)
                                writeStringUtf8("\r\n")
                                flush()
                            }
                            delay(100) // ~10 FPS
                        }
                    }
                }
            }
        }.start(wait = false)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

// Extension to convert ImageProxy to Bitmap (simplified for this example)
fun ImageProxy.toBitmap(): Bitmap {
    val plane = planes[0]
    val buffer = plane.buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    // This part is tricky because ImageProxy format is usually YUV_420_888
    // For simplicity, we assume we can get a bitmap. 
    // In a real app, you'd use a YUV to RGB converter.
    // For this prototype, I'll use a more robust way if possible, or just the basics.
    // Actually, ImageAnalysis can be configured to output RGBA_8888 in newer CameraX
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        copyPixelsFromBuffer(buffer)
    }
}

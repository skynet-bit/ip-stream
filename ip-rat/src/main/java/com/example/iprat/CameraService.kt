package com.example.iprat

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.IBinder
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class CameraService : LifecycleService() {
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var latestBitmap: Bitmap? = null
    private var server: CIOApplicationEngine? = null

    override fun onCreate() {
        super.onCreate()
        val channelId = "camera_service_channel"
        val channel = NotificationChannel(channelId, "Camera Stream", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Service Active")
            .setContentText("Camera streaming in background")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        // Critical: Start as Foreground to keep camera alive
        startForeground(1, notification)
        
        startCamera()
        startServer()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { image ->
                        val bitmap = image.toBitmap()
                        val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
                        latestBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                        image.close()
                    }
                }
            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, imageAnalyzer)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startServer() {
        server = embeddedServer(CIO, port = 8080) {
            routing {
                get("/video") {
                    call.respondBytesWriter(ContentType.parse("multipart/x-mixed-replace; boundary=--boundary")) {
                        try {
                            while (true) {
                                latestBitmap?.let { bitmap ->
                                    val stream = ByteArrayOutputStream()
                                    bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                                    val data = stream.toByteArray()
                                    writeStringUtf8("--boundary\r\nContent-Type: image/jpeg\r\n\r\n")
                                    writeFully(data)
                                    writeStringUtf8("\r\n")
                                    flush()
                                }
                                delay(100)
                            }
                        } catch (e: Exception) { }
                    }
                }
            }
        }.start(wait = false)
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}

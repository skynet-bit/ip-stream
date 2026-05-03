package com.example.iprat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    // Permission launcher to handle the user's choice
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCameraService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Check permissions immediately on launch
        if (allPermissionsGranted()) {
            startCameraService()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        // 2. Set up a simple UI to show the status
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF121212) // Dark theme look
                ) {
                    StatusScreen()
                }
            }
        }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * Starts the Background Service. 
     * This allows the camera and Ktor server to live even after this Activity is closed.
     */
    private fun startCameraService() {
        val intent = Intent(this, CameraService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }
}

@Composable
fun StatusScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "SYSTEM SERVICE ACTIVE",
            color = Color.Green,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "The camera stream is now managed by a background process.",
            color = Color.White,
            modifier = Modifier.padding(top = 8.dp),
            lineHeight = 22.sp
        )
        Text(
            text = "You can safely minimize this app.",
            color = Color.LightGray,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 16.dp)
        )
    }
}
rivate fun processImage(image: ImageProxy) {
        try {
            // Using built-in toBitmap from CameraX 1.3+
            val bitmap = image.toBitmap()
            val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
            latestBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) {
            Log.e("IP-RAT", "Error processing image", e)
        } finally {
            image.close()
        }
    }

    private fun startServer() {
        embeddedServer(CIO, port = 8080) {
            routing {
                get("/video") {
                    call.respondBytesWriter(ContentType.parse("multipart/x-mixed-replace; boundary=--boundary")) {
                        try {
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
                        } catch (e: Exception) {
                            Log.d("IP-RAT", "Client disconnected")
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

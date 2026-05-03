package com.example.ipview

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var ipAddress by remember { mutableStateOf("192.168.1.6") }
            var isStreaming by remember { mutableStateOf(false) }

            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TextField(
                    value = ipAddress,
                    onValueChange = { ipAddress = it },
                    label = { Text("IP-RAT IP Address") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { isStreaming = !isStreaming }) {
                    Text(if (isStreaming) "Stop" else "Start Viewer")
                }
                Spacer(modifier = Modifier.height(16.dp))
                if (isStreaming) {
                    MjpegViewer(ipAddress)
                }
            }
        }
    }
}

@Composable
fun MjpegViewer(ip: String) {
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(ip) {
        val client = HttpClient(CIO)
        try {
            client.prepareGet("http://$ip:8080/video").execute { response ->
                val channel = response.bodyAsChannel()
                while (isActive && !channel.isClosedForRead) {
                    // MJPEG parsing logic
                    // We look for JPEG start (0xFF 0xD8) and end (0xFF 0xD9)
                    // For simplicity in this prototype, we'll read chunks and try to decode.
                    // A better way is to parse the multipart boundary.
                    
                    val imageData = readJpegFromChannel(channel)
                    if (imageData != null) {
                        val decoded = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
                        if (decoded != null) {
                            withContext(Dispatchers.Main) {
                                bitmap = decoded
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("IP-VIEW", "Connection error", e)
        } finally {
            client.close()
        }
    }

    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = "Stream",
            modifier = Modifier.fillMaxSize()
        )
    } ?: Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
        Text("Connecting to http://$ip:8080/video...")
    }
}

suspend fun readJpegFromChannel(channel: ByteReadChannel): ByteArray? {
    val output = ByteArrayOutputStream()
    // Find start of JPEG 0xFF 0xD8
    var foundStart = false
    while (!foundStart && !channel.isClosedForRead) {
        val b = channel.readByte().toInt() and 0xFF
        if (b == 0xFF) {
            val next = channel.readByte().toInt() and 0xFF
            if (next == 0xD8) {
                output.write(0xFF)
                output.write(0xD8)
                foundStart = true
            }
        }
    }

    if (!foundStart) return null

    // Read until end of JPEG 0xFF 0xD9
    var foundEnd = false
    while (!foundEnd && !channel.isClosedForRead) {
        val b = channel.readByte().toInt() and 0xFF
        output.write(b)
        if (b == 0xFF) {
            val next = channel.readByte().toInt() and 0xFF
            output.write(next)
            if (next == 0xD9) {
                foundEnd = true
            }
        }
    }

    return if (foundEnd) output.toByteArray() else null
}

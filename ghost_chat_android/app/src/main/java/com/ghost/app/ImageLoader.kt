package com.ghost.app

import android.graphics.BitmapFactory
import android.widget.ImageView
import kotlinx.coroutines.*
import java.net.URL

/**
 * Lightweight async image loader for ESP32-hosted images.
 * No third-party libs. Tags views to prevent recycled-view conflicts.
 */
object ImageLoader {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun load(imageView: ImageView, url: String) {
        imageView.tag = url
        imageView.setImageDrawable(null)

        scope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val bytes = GhostApi.downloadBytes(url)
                    if (bytes != null) {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } else null
                }
                if (imageView.tag == url && bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                }
            } catch (_: Exception) { }
        }
    }
}

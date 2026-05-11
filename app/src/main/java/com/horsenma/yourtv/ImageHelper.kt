package com.horsenma.yourtv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.horsenma.yourtv.requests.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap


class ImageHelper(private val context: Context) {
    private val cacheDir = context.cacheDir

    private var dir: File = File(cacheDir, LOGO_CACHE_DIR)
    private val files = ConcurrentHashMap<String, File>()

    init {
        if (!dir.exists()) {
            dir.mkdir()
        }
        dir.listFiles()?.forEach { file ->
            files[file.name] = file
        }
    }

    private suspend fun downloadImage(url: String, file: File): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .build()

                HttpClient.okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext false
                    response.bodyAlias()?.byteStream()?.copyTo(file.outputStream())
                    true
                }
            } catch (e: Exception) {
//                Log.e(TAG, "downloadImage error $url", e)
                Log.e(TAG, "downloadImage error $url")
                false
            }
        }
    }

    suspend fun preloadImage(
        key: String,
        urlList: List<String>,
    ) {
        val file = files[key]
        if (file != null) {
            Log.d(TAG, "image exists ${file.absolutePath}")
            return
        }

        val httpUrls = urlList.map { it.trim() }
            .filter { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
            .distinct()

        if (httpUrls.isEmpty()) {
            return
        }

        for (url in httpUrls) {
            val file = File(cacheDir, "$LOGO_CACHE_DIR/$key")
            if (downloadImage(url, file)) {
                files[file.name] = file
                Log.d(TAG, "downloadImage success $url ${file.absolutePath}")
                break
            }
        }
    }

    fun loadImage(
        key: String,
        imageView: androidx.appcompat.widget.AppCompatImageView,
        bitmap: Bitmap,
        url: String,
    ) {
        loadImage(key, imageView, bitmap, listOfNotNull(url.takeIf { it.isNotBlank() }))
    }

    fun loadImage(
        key: String,
        imageView: androidx.appcompat.widget.AppCompatImageView,
        bitmap: Bitmap,
        urls: List<String>,
    ) {
        val file = files[key]
        if (file != null) {
            Log.d(TAG, "image exists ${file.absolutePath}")
            Glide.with(context)
                .load(file)
                .fitCenter()
                .into(imageView)
            return
        }

        val validUrls = urls.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (validUrls.isEmpty()) {
            Log.i(TAG, "$key image bitmap")
            Glide.with(context)
                .load(bitmap)
                .fitCenter()
                .into(imageView)
            return
        }

        Log.i(TAG, "$key image ${validUrls.first()}")
        val fallback = BitmapDrawable(context.resources, bitmap)
        buildImageRequest(validUrls, fallback)
            .placeholder(fallback)
            .fitCenter()
            .into(imageView)
    }

    private fun buildImageRequest(urls: List<String>, fallback: Drawable): RequestBuilder<Drawable> {
        val glide = Glide.with(context)
        var request = glide.load(urls.last()).error(fallback).fitCenter()
        urls.dropLast(1).asReversed().forEach { url ->
            request = glide.load(url).error(request).fitCenter()
        }
        return request
    }

    fun clearImage() {
        val dir = File(cacheDir, LOGO_CACHE_DIR)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
        files.clear()
        dir.mkdirs()
    }

    fun clearLegacyLogoCaches() {
        LEGACY_LOGO_CACHE_DIRS.forEach { dirname ->
            val legacyDir = File(cacheDir, dirname)
            if (legacyDir.exists()) {
                legacyDir.deleteRecursively()
                Log.i(TAG, "cleared legacy image cache ${legacyDir.absolutePath}")
            }
        }
    }

    companion object {
        const val TAG = "ImageHelper"
        private const val LOGO_CACHE_DIR = "logo_fishtv_v2"
        private val LEGACY_LOGO_CACHE_DIRS = emptyList<String>()
    }
}

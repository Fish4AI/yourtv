package com.horsenma.yourtv



import com.horsenma.yourtv.MainViewModel.Companion.CACHE_FILE_NAME
import com.horsenma.yourtv.MainViewModel.Companion.DEFAULT_CHANNELS_FILE
import com.horsenma.yourtv.MainViewModel.Companion.DEFAULT_WEBCHANNELS_FILE
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.horsenma.yourtv.Utils.getUrls
import com.horsenma.yourtv.data.Global.gson
import com.horsenma.yourtv.data.Global.typeSourceList
import com.horsenma.yourtv.data.ReqSettings
import com.horsenma.yourtv.data.ReqSourceAdd
import com.horsenma.yourtv.data.ReqSourceCache
import com.horsenma.yourtv.data.ReqSources
import com.horsenma.yourtv.data.RespSourceCacheItem
import com.horsenma.yourtv.data.RespSourceCacheList
import com.horsenma.yourtv.data.RespSettings
import com.horsenma.yourtv.data.Source
import com.horsenma.yourtv.requests.HttpClient
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets


class SimpleServer(private val context: Context, private val viewModel: MainViewModel) :
    NanoHTTPD("0.0.0.0", PORT) {
    private val handler = Handler(Looper.getMainLooper())

    init {
        try {
            start()
            Log.i(TAG, "HTTP config server started at http://${PortUtil.lan()}:$PORT")
        } catch (e: Exception) {
            Log.e(TAG, "init", e)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/api/settings" -> handleSettings()
            "/api/sources" -> handleSources()
            "/api/source-list" -> handleSourceList()
            "/api/switch-source" -> handleSwitchSource(session)
            "/api/delete-source-cache" -> handleDeleteSourceCache(session)
            "/api/import-text" -> handleImportText(session)
            "/api/import-uri" -> handleImportUri(session)
            "/api/proxy" -> handleProxy(session)
            "/api/epg" -> handleEPG(session)
            "/api/default-channel" -> handleDefaultChannel(session)
            "/api/remove-source" -> handleRemoveSource(session)
            "/logo.png", "/favicon.ico" -> handleLogo()
            else -> handleStaticContent()
        }
    }

    private fun handleSettings(): Response {
        val response: String
        try {
            val file = File(context.filesDir, CACHE_FILE_NAME)
            var str = if (file.exists()) {
                file.readText()
            } else {
                ""
            }
            if (str.isEmpty()) {
                str = context.resources.openRawResource(DEFAULT_CHANNELS_FILE).bufferedReader()
                    .use { it.readText() }
            }

            var history = mutableListOf<Source>()

            if (!SP.sources.isNullOrEmpty()) {
                try {
                    val sources: List<Source> = gson.fromJson(SP.sources!!, typeSourceList)
                    history = sources.toMutableList()
                } catch (e: Exception) {
                    e.printStackTrace()
                    SP.sources = SP.DEFAULT_SOURCES
                }
            }

            val respSettings = RespSettings(
                channelUri = SP.configUrl ?: "",
                channelText = str,
                channelDefault = SP.channel,
                proxy = SP.proxy ?: "",
                epg = SP.epg ?: "",
                history = history
            )
            response = gson.toJson(respSettings) ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "handleSettings", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message
            )
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", response)
    }

    suspend fun fetchSources(url: String): String {
        val urls = getUrls(url)

        var sources = ""
        var success = false
        for (u in urls) {
            Log.i(TAG, "request $u")
            withContext(Dispatchers.IO) {
                try {
                    val request = okhttp3.Request.Builder().url(u).build()
                    val response = HttpClient.okHttpClient.newCall(request).execute()

                    if (response.isSuccessful) {
                        sources = response.bodyAlias()?.string() ?: ""
                        success = true
                    } else {
                        Log.e(TAG, "Request status ${response.codeAlias()}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "fetchSources", e)
                }
            }

            if (success) break
        }

        return sources
    }

    private fun handleSources(): Response {
        val response = if (!SP.sources.isNullOrEmpty()) {
            try {
                val sources = gson.fromJson(SP.sources, typeSourceList) as? List<Source>
                sources?.map { it.uri }?.joinToString("\n") ?: ""
            } catch (e: Exception) {
                ""
            }
        } else {
            ""
        }
        try {
            val decoded = SourceDecoder.decodeHexSource(response) ?: response
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                decoded
            )
        } catch (e: Exception) {
            Log.e(TAG, "解碼源失敗: ${e.message}")
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                response
            )
        }
    }

    private fun handleSourceList(): Response {
        return try {
            newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                gson.toJson(buildSourceCacheList())
            )
        } catch (e: Exception) {
            Log.e(TAG, "handleSourceList", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
        }
    }

    private fun handleSwitchSource(session: IHTTPSession): Response {
        return try {
            val filename = readSourceFilename(session)
            if (!isValidSourceFilename(filename)) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid filename")
            }

            runBlocking {
                switchSourceByFilename(filename)
            }

            newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                gson.toJson(buildSourceCacheList())
            )
        } catch (e: Exception) {
            Log.e(TAG, "handleSwitchSource", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
        }
    }

    private fun handleDeleteSourceCache(session: IHTTPSession): Response {
        return try {
            val filename = readSourceFilename(session)
            if (!isValidSourceFilename(filename)) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid filename")
            }
            val prefs = context.getSharedPreferences("SourceCache", Context.MODE_PRIVATE)
            val activeFilename = prefs.getString("active_source", SourceCatalog.DEFAULT_IPTV_FILENAME)
                ?: SourceCatalog.DEFAULT_IPTV_FILENAME
            if (isBuiltInSource(filename)) {
                prefs.edit()
                    .putBoolean(deletedKey(filename), true)
                    .remove("url_$filename")
                    .apply()
            } else {
                File(context.filesDir, "cache_$filename").delete()
                prefs.edit()
                    .remove("cache_$filename")
                    .remove("cache_time_$filename")
                    .remove("url_$filename")
                    .apply()
            }

            if (filename == activeFilename) {
                val nextFilename = buildSourceCacheList().sources.firstOrNull()?.filename
                if (nextFilename != null) {
                    runBlocking {
                        switchSourceByFilename(nextFilename)
                    }
                } else {
                    prefs.edit().remove("active_source").apply()
                }
            }

            newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                gson.toJson(buildSourceCacheList())
            )
        } catch (e: Exception) {
            Log.e(TAG, "handleDeleteSourceCache", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message)
        }
    }

    private fun readSourceFilename(session: IHTTPSession): String {
        val body = readBody(session)
        if (!body.isNullOrBlank()) {
            runCatching {
                gson.fromJson(body, ReqSourceCache::class.java)?.filename?.trim()
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        session.parameters["filename"]?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        session.parms["filename"]?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        return ""
    }

    private fun buildSourceCacheList(): RespSourceCacheList {
        val prefs = context.getSharedPreferences("SourceCache", Context.MODE_PRIVATE)
        val activeFilename = prefs.getString("active_source", SourceCatalog.DEFAULT_IPTV_FILENAME)
            ?: SourceCatalog.DEFAULT_IPTV_FILENAME
        val filenames = linkedSetOf<String>()
        SourceCatalog.builtInSources()
            .filter { !isSourceDeleted(prefs, it.filename) }
            .forEach { filenames.add(it.filename) }

        prefs.all.keys
            .filter { it.startsWith("cache_") && !it.startsWith("cache_time_") }
            .map { it.removePrefix("cache_") }
            .filter { isValidSourceFilename(it) }
            .filter { !isSourceDeleted(prefs, it) }
            .forEach { filenames.add(it) }

        val items = filenames.map { filename ->
            val url = sourceUrl(filename, prefs.getString("url_$filename", "") ?: "")
            val cacheFile = File(context.filesDir, "cache_$filename")
            val cachedText = prefs.getString("cache_$filename", null)
            RespSourceCacheItem(
                filename = filename,
                name = sourceName(filename, url),
                url = url,
                active = filename == activeFilename,
                builtIn = isBuiltInSource(filename),
                cached = isBuiltInSource(filename) || !cachedText.isNullOrBlank() || cacheFile.exists(),
                cacheTime = prefs.getLong("cache_time_$filename", 0L),
            )
        }

        return RespSourceCacheList(active = activeFilename, sources = items)
    }

    private suspend fun switchSourceByFilename(filename: String) {
        val prefs = context.getSharedPreferences("SourceCache", Context.MODE_PRIVATE)
        when (filename) {
            SourceCatalog.DEFAULT_IPTV_FILENAME -> switchBundledSource(
                DEFAULT_CHANNELS_FILE,
                SourceCatalog.DEFAULT_IPTV_FILENAME,
                "default://channels"
            )
            SourceCatalog.DEFAULT_WEB_FILENAME -> switchBundledSource(
                DEFAULT_WEBCHANNELS_FILE,
                SourceCatalog.DEFAULT_WEB_FILENAME,
                "default://webchannelsiniptv"
            )
            SourceCatalog.FISH_FILENAME -> switchBuiltInRemoteSource(
                SourceCatalog.FISH_FILENAME,
                SourceCatalog.FISH_URL
            )
            else -> {
                val cachedContent = prefs.getString("cache_$filename", null)
                val url = prefs.getString("url_$filename", "") ?: ""
                if (!cachedContent.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        viewModel.tryStr2Channels(cachedContent, File(context.filesDir, "cache_$filename"), "", filename)
                    }
                    prefs.edit().putString("active_source", filename).apply()
                    "已切换到 ${sourceName(filename, url)}".showToast()
                } else if (url.isNotBlank()) {
                    viewModel.importFromUrl(url, filename, skipHistory = true)
                    prefs.edit().putString("active_source", filename).apply()
                } else {
                    throw IllegalArgumentException("Source cache not found: $filename")
                }
            }
        }
    }

    private fun switchBundledSource(resourceId: Int, filename: String, url: String) {
        val str = context.resources.openRawResource(resourceId).bufferedReader().use { it.readText() }
        context.getSharedPreferences("SourceCache", Context.MODE_PRIVATE)
            .edit()
            .remove(deletedKey(filename))
            .putString("active_source", filename)
            .putString("url_$filename", url)
            .apply()
        viewModel.importFromText(str, filename)
        "已切换到 ${sourceName(filename, url)}".showToast()
    }

    private suspend fun switchBuiltInRemoteSource(filename: String, url: String) {
        context.getSharedPreferences("SourceCache", Context.MODE_PRIVATE)
            .edit()
            .remove(deletedKey(filename))
            .putString("active_source", filename)
            .putString("url_$filename", url)
            .apply()
        viewModel.importFromUrl(url, filename, skipHistory = true, forceDownload = true)
        "已切换到 ${sourceName(filename, url)}".showToast()
    }

    private fun sourceUrl(filename: String, cachedUrl: String): String {
        return SourceCatalog.sourceUrl(filename, cachedUrl)
    }

    private fun sourceName(filename: String, url: String): String {
        return SourceCatalog.sourceName(context, filename, url)
    }

    private fun isValidSourceFilename(filename: String): Boolean {
        return SourceCatalog.isValidSourceFilename(filename)
    }

    private fun isBuiltInSource(filename: String): Boolean {
        return SourceCatalog.isBuiltInSource(filename)
    }

    private fun deletedKey(filename: String): String {
        return SourceCatalog.deletedKey(filename)
    }

    private fun isSourceDeleted(prefs: android.content.SharedPreferences, filename: String): Boolean {
        return SourceCatalog.isSourceDeleted(prefs, filename)
    }

    private fun handleImportText(session: IHTTPSession): Response {
        R.string.start_config_channel.showToast()
        val response = ""
        try {
            readBody(session)?.let {
                val filename = cacheTextSource(it, session.parameters["name"]?.firstOrNull())
                viewModel.importFromText(it, filename)
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleImportText", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message
            )
        }
        return newFixedLengthResponse(Response.Status.OK, "text/plain", response)
    }

    private fun cacheTextSource(text: String, requestedName: String?): String {
        val filename = sourceFilenameFromName(requestedName)
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        val isHex = normalized.trim().matches(Regex("^[0-9a-fA-F]+$"))
        val contentToCache = if (isHex) normalized else SourceEncoder.encodeJsonSource(normalized)
        File(context.filesDir, "cache_$filename").writeText(contentToCache)
        context.getSharedPreferences("SourceCache", Context.MODE_PRIVATE)
            .edit()
            .remove(deletedKey(filename))
            .putString("cache_$filename", contentToCache)
            .putLong("cache_time_$filename", System.currentTimeMillis())
            .putString("url_$filename", "text://$filename")
            .putString("active_source", filename)
            .apply()
        return filename
    }

    private fun sourceFilenameFromName(requestedName: String?): String {
        val cleanName = requestedName
            ?.substringAfterLast("/")
            ?.substringBeforeLast(".")
            ?.replace(Regex("[^A-Za-z0-9._-]+"), "_")
            ?.trim('_', '.', '-')
            ?.takeIf { it.isNotBlank() }
            ?: "manual_${System.currentTimeMillis()}"
        return if (cleanName.endsWith(".txt")) cleanName else "$cleanName.txt"
    }

    private fun handleImportUri(session: IHTTPSession): Response {
        Log.d(TAG, "Received /api/import-uri request: method=${session.method}, uri=${session.uri}")
        R.string.start_config_channel.showToast()
        val response = ""
        try {
            val body = readBody(session)
            Log.d(TAG, "Request body: $body")
            if (body.isNullOrEmpty()) {
                Log.e(TAG, "Request body is null or empty")
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    MIME_PLAINTEXT,
                    "Request body is empty"
                )
            }
            val req = gson.fromJson(body, ReqSourceAdd::class.java)
            if (req == null) {
                Log.e(TAG, "Failed to parse request body: $body")
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    MIME_PLAINTEXT,
                    "Invalid request body"
                )
            }
            Log.d(TAG, "Parsed request: id=${req.id}, uri=${req.uri}")
            val uri = Uri.parse(req.uri)
            if (uri.scheme.isNullOrEmpty()) {
                Log.e(TAG, "Invalid URI: ${req.uri}")
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    MIME_PLAINTEXT,
                    "Invalid URI: ${req.uri}"
                )
            }
            handler.post {
                Log.d(TAG, "Calling importFromUri: uri=$uri, id=${req.id}")
                viewModel.importFromUri(uri, req.id)
                R.string.source_update_success.showToast()
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleImportUri error: ${e.message}", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "Error processing request: ${e.message}"
            )
        }
        return newFixedLengthResponse(Response.Status.OK, "text/plain", response)
    }

    private fun handleProxy(session: IHTTPSession): Response {
        try {
            readBody(session)?.let {
                handler.post {
                    val req = gson.fromJson(it, ReqSettings::class.java)
                    if (req.proxy != null) {
                        SP.proxy = req.proxy
                        R.string.default_proxy_set_success.showToast()
                        Log.i(TAG, "set proxy success")
                    } else {
                        R.string.default_proxy_set_failure.showToast()
                        Log.i(TAG, "set proxy failure")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleProxy", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message
            )
        }
        val response = ""
        return newFixedLengthResponse(Response.Status.OK, "text/plain", response)
    }

    private fun handleEPG(session: IHTTPSession): Response {
        try {
            readBody(session)?.let {
                handler.post {
                    val req = gson.fromJson(it, ReqSettings::class.java)
                    if (req.epg != null) {
                        SP.epg = req.epg
                        viewModel.updateEPG()
                        R.string.default_epg_set_success.showToast()
                    } else {
                        R.string.default_epg_set_failure.showToast()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleEPG", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message
            )
        }
        val response = ""
        return newFixedLengthResponse(Response.Status.OK, "text/plain", response)
    }

    private fun handleDefaultChannel(session: IHTTPSession): Response {
        R.string.start_set_default_channel.showToast()
        val response = ""
        try {
            readBody(session)?.let {
                handler.post {
                    val req = gson.fromJson(it, ReqSettings::class.java)
                    if (req.channel != null && req.channel > -1) {
                        SP.channel = req.channel
                        R.string.default_channel_set_success.showToast()
                    } else {
                        R.string.default_channel_set_failure.showToast()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleDefaultChannel", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message
            )
        }
        return newFixedLengthResponse(Response.Status.OK, "text/plain", response)
    }

    private fun handleRemoveSource(session: IHTTPSession): Response {
        val response = ""
        try {
            readBody(session)?.let {
                handler.post {
                    val req = gson.fromJson(it, ReqSources::class.java)
                    Log.i(TAG, "req $req")
                    if (req.sourceId.isNotEmpty()) {
                        val res = viewModel.sources.removeSource(req.sourceId)
                        if (res) {
                            Log.i(TAG, "remove source success ${req.sourceId}")
                        } else {
                            Log.i(TAG, "remove source failure ${req.sourceId}")
                        }
                    } else {
                        Log.i(TAG, "remove source failure, sourceId is empty")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleRemoveSource", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message
            )
        }
        return newFixedLengthResponse(Response.Status.OK, "text/plain", response)
    }

    private fun readBody(session: IHTTPSession): String? {
        val map = HashMap<String, String>()
        session.parseBody(map)
        return map["postData"]
    }

    private fun handleStaticContent(): Response {
        val html = loadHtmlFromResource(R.raw.index)
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun handleLogo(): Response {
        val bytes = context.resources.openRawResource(R.drawable.logo0).use { it.readBytes() }
        return newFixedLengthResponse(
            Response.Status.OK,
            "image/png",
            bytes.inputStream(),
            bytes.size.toLong()
        )
    }

    private fun loadHtmlFromResource(resourceId: Int): String {
        val inputStream = context.resources.openRawResource(resourceId)
        return inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    companion object {
        const val TAG = "SimpleServer"
        const val PORT = 34567
    }
}

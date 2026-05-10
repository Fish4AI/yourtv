package com.horsenma.yourtv


import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toFile
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.horsenma.yourtv.Utils.getDateFormat
import com.horsenma.yourtv.Utils.getUrls
import com.horsenma.yourtv.data.EPG
import com.horsenma.yourtv.data.Global.gson
import com.horsenma.yourtv.data.Global.typeEPGMap
import com.horsenma.yourtv.data.Global.typeTvList
import com.horsenma.yourtv.data.Source
import com.horsenma.yourtv.data.SourceType
import com.horsenma.yourtv.data.TV
import com.horsenma.yourtv.models.EPGXmlParser
import com.horsenma.yourtv.models.Sources
import com.horsenma.yourtv.models.TVGroupModel
import com.horsenma.yourtv.models.TVListModel
import com.horsenma.yourtv.models.TVModel
import com.horsenma.yourtv.requests.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.delay
import com.horsenma.yourtv.data.PlayerType
import com.horsenma.yourtv.data.Global
import com.google.gson.reflect.TypeToken
import com.horsenma.yourtv.data.StableSource


class MainViewModel : ViewModel() {

    private var firstloadcode = false
    private var cachedCodeContent: String? = null
    private var cachedFileContent: String? = null
    private lateinit var context: Context // 存储 Context

    private val _playTrigger = MutableLiveData<TVModel?>()
    val playTrigger: LiveData<TVModel?> get() = _playTrigger

    // 添加公共方法来触发播放
    fun triggerPlay(tvModel: TVModel?) {
        _playTrigger.postValue(tvModel)
    }

    private val _currentTvModel = MutableLiveData<TVModel?>()
    val currentTvModel: LiveData<TVModel?> get() = _currentTvModel

    fun setCurrentTvModel(model: TVModel?) {
        _currentTvModel.value = model
    }

    fun storeUsersInfo(usersInfo: List<String>) {
        Log.d(TAG, "Storing users_info: $usersInfo")
    }
    private var timeFormat = if (SP.displaySeconds) "HH:mm:ss" else "HH:mm"

    private lateinit var appDirectory: File
    var listModel: List<TVModel> = emptyList()
    val groupModel = TVGroupModel()
    private var cacheFile: File? = null
    private var cacheChannels = ""
    private var cacheWebChannels = ""
    private var initialized = false

    private lateinit var cacheEPG: File
    private var epgUrl = SP.epg

    private lateinit var imageHelper: ImageHelper

    val sources = Sources()

    private val _channelsOk = MutableLiveData<Boolean>()
    val channelsOk: LiveData<Boolean>
        get() = _channelsOk

    fun setDisplaySeconds(displaySeconds: Boolean) {
        timeFormat = if (displaySeconds) "HH:mm:ss" else "HH:mm"
        SP.displaySeconds = displaySeconds
    }

    fun setChannelsOk(value: Boolean) {
        _channelsOk.postValue(value)
    }

    fun getTime(): String {
        return getDateFormat(timeFormat)
    }

    fun updateEPG() {
        viewModelScope.launch {
            var success = false
            if (!epgUrl.isNullOrEmpty()) {
                success = updateEPG(epgUrl!!)
            }
            if (!success && !SP.epg.isNullOrEmpty()) {
                updateEPG(SP.epg!!)
            }
        }
    }

    fun updateConfig() {
        Log.d(TAG, "Remote auto config is disabled in standalone mode")
    }

    private fun getCache(): String {
        return if (cacheFile!!.exists()) {
            cacheFile!!.readText()
        } else {
            ""
        }
    }

    fun init(context: Context) {
        this.context = context
        val application = context.applicationContext as YourTVApplication
        imageHelper = application.imageHelper

        if (groupModel.getAllList() == null || groupModel.getAllList()!!.tvList.value.isNullOrEmpty()) {
            groupModel.addTVListModel(TVListModel(context.getString(R.string.my_favorites), 0))
            groupModel.addTVListModel(TVListModel(context.getString(R.string.all_channels), 1))
        }

        appDirectory = context.filesDir
        cacheFile = File(appDirectory, CACHE_FILE_NAME)
        try {
            if (!cacheFile!!.exists()) {
                cacheFile!!.createNewFile()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create cache file: ${e.message}", e)
        }

        // Step 1: Immediately play the bundled test source.
        viewModelScope.launch(Dispatchers.Main) {
            // 播放稳定源
            var defaultChannel: TVModel? = loadBundledStableChannel(context)
            val stableSources = SP.getStableSources()
            if (defaultChannel != null) {
                groupModel.setCurrent(defaultChannel)
                triggerPlay(defaultChannel)
                Log.i(TAG, "Playing bundled test channel immediately: ${defaultChannel.tv.title}, url=${defaultChannel.getVideoUrl()}")
            } else if (stableSources.isNotEmpty()) {
                val selectedSource = stableSources.maxByOrNull { it.timestamp }
                if (selectedSource != null) {
                    val tv = TV(
                        id = selectedSource.id,
                        name = selectedSource.name,
                        title = selectedSource.title,
                        description = selectedSource.description,
                        logo = selectedSource.logo,
                        image = selectedSource.image,
                        uris = selectedSource.uris,
                        videoIndex = selectedSource.videoIndex,
                        headers = selectedSource.headers,
                        group = selectedSource.group,
                        sourceType = SourceType.valueOf(selectedSource.sourceType),
                        number = selectedSource.number,
                        child = selectedSource.child
                    )
                    defaultChannel = TVModel(tv).apply {
                        setLike(SP.getLike(tv.id))
                        setGroupIndex(2)
                        listIndex = 0
                    }

                    // 打印 TV 数据为 JSON
                    val tvJson = Global.gson.toJson(defaultChannel.tv)
                    Log.d(TAG, "Stable source TV JSON: $tvJson")

                    groupModel.setCurrent(defaultChannel)
                    triggerPlay(defaultChannel)
                    Log.i(TAG, "Playing latest stable channel immediately: ${defaultChannel.tv.title}, url: ${defaultChannel.getVideoUrl()}")
                } else {
                    Log.w(TAG, "Selected stable source is null")
                }
            } else {
                Log.w(TAG, "Selected stable source is null")
                try {
                    val inputStream = context.resources.openRawResource(R.raw.rawstablesource)
                    val jsonString = inputStream.bufferedReader().use { it.readText() }
                    val type = object : TypeToken<List<TV>>() {}.type
                    val stableSources: List<TV> = Global.gson.fromJson(jsonString, type)
                    val tv = stableSources.firstOrNull { it.uris.any { uri -> uri.isNotBlank() } }
                    if (tv != null) {
                        val bundledChannel = TVModel(tv).apply {
                            setLike(SP.getLike(tv.id))
                            setGroupIndex(2)
                            listIndex = 0
                        }
                        defaultChannel = bundledChannel
                        groupModel.setCurrent(bundledChannel)
                        triggerPlay(bundledChannel)
                        Log.i(TAG, "Playing fallback bundled test channel from raw: ${bundledChannel.tv.title}, url=${bundledChannel.getVideoUrl()}")
                    } else {
                        Log.w(TAG, "No stable sources found in rawstablesource.txt")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load random stable source from rawstablesource.txt: ${e.message}", e)
                }
            }

            // Step 2: Delay 5 seconds to load channels from cache or default, update list without interrupting playback
            delay(5_000L)
            var channelsLoaded = false

            val filename = context.getSharedPreferences("SourceCache", Context.MODE_PRIVATE).getString("active_source", null)
            if (filename != null) {
                viewModelScope.launch {loadActiveSource()}
                delay(3_000L)
            }
            if (_channelsOk.value == true) {
                channelsLoaded = true
                Log.d(TAG, "Channels loaded from active_source")
            }

            if (!channelsLoaded && cacheFile!!.exists()) {
                try {
                    cachedFileContent = cachedFileContent ?: withContext(Dispatchers.IO) { cacheFile!!.readText() }
                    if (cachedFileContent!!.isNotEmpty()) {
                        withContext(Dispatchers.Default) {
                            tryStr2Channels(cachedFileContent!!, cacheFile, "", "")
                        }
                        Log.d(TAG, "Channels loaded from cacheFile")
                        channelsLoaded = true
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load cacheFile: ${e.message}", e)
                }
            }
            if (!channelsLoaded) {
                try {
                    cacheChannels = withContext(Dispatchers.IO) {
                        context.resources.openRawResource(DEFAULT_CHANNELS_FILE).bufferedReader().use { it.readText() }
                    }
                    if (cacheChannels.isNotEmpty()) {
                        withContext(Dispatchers.Default) {
                            tryStr2Channels(cacheChannels, null, "", "")
                        }
                        Log.d(TAG, "Channels loaded from /raw/channels.txt")
                        channelsLoaded = true
                        delay(300L)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load /raw/channels.txt: ${e.message}", e)
                }
            }

            if (!channelsLoaded) {
                try {
                    cacheWebChannels = withContext(Dispatchers.IO) {
                        context.resources.openRawResource(DEFAULT_WEBCHANNELS_FILE).bufferedReader().use { it.readText() }
                    }
                    if (cacheWebChannels.isNotEmpty()) {
                        withContext(Dispatchers.Default) {
                            tryStr2Channels(cacheWebChannels, null, "", "")
                        }
                        Log.d(TAG, "Web channels loaded from /raw/webchannelsiniptv")
                    } else {
                        Log.w(TAG, "Web channels file is empty: /raw/webchannelsiniptv")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load /raw/webchannelsiniptv: ${e.message}", e)
                }
            }

            if (channelsLoaded && stableSources.isEmpty() && defaultChannel == null) {
                val firstChannel = listModel.firstOrNull()
                if (firstChannel != null) {
                    groupModel.setCurrent(firstChannel)
                    triggerPlay(firstChannel)
                    Log.i(TAG, "Playing default channel from cache/raw: ${firstChannel.tv.title}, url=${firstChannel.getVideoUrl()}")
                } else {
                    Log.w(TAG, "No channels available in listModel after loading")
                }
            }

            initialized = true
            _channelsOk.value = channelsLoaded

        }

        viewModelScope.launch(Dispatchers.IO) {
            cacheEPG = File(appDirectory, CACHE_EPG)
            try {
                if (!cacheEPG.exists()) {
                    cacheEPG.createNewFile()
                } else if (readEPG(cacheEPG.readText())) {
                    Log.i(TAG, "cacheEPG success")
                } else {
                    Log.i(TAG, "cacheEPG failure")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle EPG cache: ${e.message}", e)
            }
        }
    }

    private suspend fun loadBundledStableChannel(context: Context): TVModel? {
        return try {
            val tv = withContext(Dispatchers.IO) {
                val jsonString = context.resources.openRawResource(R.raw.rawstablesource)
                    .bufferedReader()
                    .use { it.readText() }
                val type = object : TypeToken<List<TV>>() {}.type
                val stableSources: List<TV> = Global.gson.fromJson(jsonString, type)
                stableSources.firstOrNull { it.uris.any { uri -> uri.isNotBlank() } }
            }

            tv?.let {
                TVModel(it).apply {
                    setLike(SP.getLike(it.id))
                    setGroupIndex(2)
                    listIndex = 0
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bundled test source from rawstablesource.txt: ${e.message}", e)
            null
        }
    }

    suspend fun preloadLogo() {
        if (!this::imageHelper.isInitialized) {
            return
        }

        withContext(Dispatchers.IO) { // 添加后台线程调度
            for (tvModel in listModel) {
                var name = tvModel.tv.name
                if (name.isEmpty()) {
                    name = tvModel.tv.title
                }
                val url = tvModel.tv.logo
                var urls =
                    listOf(
                        "https://live.fanmingming.cn/tv/$name.png"
                    ) + getUrls("https://raw.githubusercontent.com/fanmingming/live/main/tv/$name.png")
                if (url.isNotEmpty()) {
                    urls = (getUrls(url) + urls).distinct()
                }

                imageHelper.preloadImage(name, urls)
            }
        }
    }

    suspend fun readEPG(input: InputStream): Boolean = withContext(Dispatchers.IO) {
        try {
            val res = EPGXmlParser().parse(input)

            withContext(Dispatchers.Main) {
                val e1 = mutableMapOf<String, List<EPG>>()
                for (m in listModel) {
                    val name = m.tv.name.ifEmpty { m.tv.title }.lowercase()
                    if (name.isEmpty()) {
                        continue
                    }

                    for ((n, epg) in res) {
                        if (name.contains(n, ignoreCase = true)) {
                            m.setEpg(epg)
                            e1[name] = epg
                            break
                        }
                    }
                }
                cacheEPG.writeText(gson.toJson(e1))
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun readEPG(str: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val res: Map<String, List<EPG>> = gson.fromJson(str, typeEPGMap)

            withContext(Dispatchers.Main) {
                for (m in listModel) {
                    val name = m.tv.name.ifEmpty { m.tv.title }.lowercase()
                    if (name.isEmpty()) {
                        continue
                    }

                    val epg = res[name]
                    if (epg != null) {
                        m.setEpg(epg)
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun updateEPG(url: String): Boolean {
        val urls = url.split(",").flatMap { u -> getUrls(u) }

        var success = false
        for (a in urls) {
            withContext(Dispatchers.IO) {
                try {
                    val request = okhttp3.Request.Builder().url(a).build()
                    val response = HttpClient.okHttpClient.newCall(request).execute()

                    if (response.isSuccessful) {
                        response.bodyAlias()?.byteStream()?.let { stream ->
                            if (readEPG(stream)) {
                                success = true
                            }
                        } ?: run {
                            Log.e(TAG, "EPG $a response body is null")
                        }
                    } else {
                        Log.e(TAG, "EPG $a ${response.codeAlias()}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "EPG $a error")
                }
            }

            if (success) {
                break
            }
        }

        return success
    }

    suspend fun importFromUrl(url: String, id: String = "", skipHistory: Boolean = false, forceDownload: Boolean = false) {
        Log.d(TAG, "importFromUrl: url=$url, id=$id, skipHistory=$skipHistory, forceDownload=$forceDownload")
        if (url.isBlank()) {
            Log.w(TAG, "importFromUrl: Skipping empty URL")
            R.string.sources_download_error.showToast()
            return
        }

        //val filename = if (id.isNotBlank()) id else url.substringAfterLast("/").takeIf { it.isNotBlank() } ?: "source_${url.hashCode()}.txt"
        val rawFilename = url.substringAfterLast("/").takeIf { it.isNotBlank() }?.substringBeforeLast(".") ?: "source_${url.hashCode()}"
        val filename = "$rawFilename.txt"
        val prefs = context.getSharedPreferences("SourceCache", Context.MODE_PRIVATE)
        val cacheKey = "cache_$filename"
        val cacheTimeKey = "cache_time_$filename"
        val urlKey = "url_$filename"
        val cachedContent = prefs.getString(cacheKey, null)
        val cacheTime = prefs.getLong(cacheTimeKey, 0)
        val cacheDuration = 24 * 60 * 60 * 1000
        val cacheCodeFile = File(appDirectory, "cache_$filename")
        val MAX_CACHE_FILES = 10

        // 检查缓存文件数量并清理
        val cacheKeys = prefs.all.keys.filter { it.startsWith("cache_") && !it.startsWith("cache_time_") }
        if (cacheKeys.size >= MAX_CACHE_FILES) {
            val timeKeys = prefs.all.entries
                .filter { it.key.startsWith("cache_time_") }
                .map { it.key to (it.value as? Long ?: 0L) }
                .sortedBy { it.second }
            if (timeKeys.isNotEmpty()) {
                val oldestTimeKey = timeKeys.first().first
                val oldestFilename = oldestTimeKey.removePrefix("cache_time_")
                val oldestCacheKey = "cache_$oldestFilename"
                val oldestUrlKey = "url_$oldestFilename"
                val oldestCacheFile = File(appDirectory, "cache_$oldestFilename")
                if (oldestCacheFile.exists()) {
                    oldestCacheFile.delete()
                    Log.d(TAG, "Deleted oldest cache file: cache_$oldestFilename")
                }
                viewModelScope.launch(Dispatchers.IO) {
                    with(prefs.edit()) {
                        remove(oldestCacheKey)
                        remove(oldestTimeKey)
                        remove(oldestUrlKey)
                        apply()
                    }
                }
                Log.d(TAG, "Removed oldest cache entries: $oldestCacheKey, $oldestTimeKey, $oldestUrlKey")
            }
        }

        // 检查缓存，并更新时间戳
        if (!forceDownload && cachedContent != null && cacheCodeFile.exists() ) {
            Log.d(TAG, "importFromUrl: Using cached content for filename=$filename, cacheTime=$cacheTime")
            viewModelScope.launch(Dispatchers.IO) {
                with(prefs.edit()) {
                    putLong("cache_time_$filename", System.currentTimeMillis())
                    putString("active_source", filename)
                    apply()
                }
            }
            // 在主线程解析频道列表，确保 LiveData 操作安全
            viewModelScope.launch(Dispatchers.Default) {
                val isHex = cachedContent.trim().matches(Regex("^[0-9a-fA-F]+$"))
                val contentToParse = if (isHex) {
                    withContext(Dispatchers.IO) { // 解码操作在 IO 线程
                        SourceDecoder.decodeHexSource(cachedContent) ?: cachedContent
                    }
                } else {
                    cachedContent
                }
                tryStr2Channels(contentToParse, cacheCodeFile, if (skipHistory) "" else url, id)
                _channelsOk.postValue(true)
            }
            return
        }

        // 下载
        Log.d(TAG, "importFromUrl: Download filename=$filename")
        Log.d(TAG, "importFromUrl: Download url=$url")
        val result = downloadText(url)
        when {
            result.isSuccess -> {
                val content = result.getOrNull() ?: ""
                if (content.isEmpty()) {
                    Log.w(TAG, "importFromUrl: Downloaded empty content for url=$url")
                    R.string.sources_download_error.showToast()
                    return
                }
                val isHex = content.trim().matches(Regex("^[0-9a-fA-F]+$"))
                val normalizedContent = if (isHex) {
                    SourceDecoder.decodeHexSource(content) ?: content
                } else {
                    content.replace("\r\n", "\n").replace("\r", "\n")
                }
                val contentToCache = withContext(Dispatchers.IO) {
                    if (isHex) content else SourceEncoder.encodeJsonSource(normalizedContent)
                }
                withContext(Dispatchers.IO) {
                    try {
                        cacheCodeFile.writeText(contentToCache)
                        Log.d(TAG, "importFromUrl: Wrote cache_$filename for filename=$filename, content length=${contentToCache.length}")
                    } catch (e: Exception) {
                        Log.e(TAG, "importFromUrl: Failed to write cache_$filename: ${e.message}")
                    }
                }
                withContext(Dispatchers.Default) {
                    tryStr2Channels(normalizedContent, cacheCodeFile, if (skipHistory) "" else url, id)
                }
                withContext(Dispatchers.Main) {
                    SP.lastDownloadTime = System.currentTimeMillis()
                    viewModelScope.launch(Dispatchers.IO) {
                        with(prefs.edit()) {
                            putString(cacheKey, contentToCache)
                            putLong(cacheTimeKey, System.currentTimeMillis())
                            putString(urlKey, url)
                            putString("active_source", filename)
                            apply()
                        }
                    }
                    Log.d(TAG, "importFromUrl: Cached content for filename=$filename, isHex=$isHex")
                    _channelsOk.value = true
                }
            }
            result.isFailure -> {
                Log.e(TAG, "importFromUrl: Download failed for url=$url: ${result.exceptionOrNull()?.message}")
                R.string.sources_download_error.showToast()
            }
        }
    }

    private suspend fun downloadText(url: String): Result<String> {
        return withContext(Dispatchers.IO) {
            val errors = mutableListOf<String>()
            for (candidateUrl in getUrls(url)) {
                try {
                    val request = okhttp3.Request.Builder().url(candidateUrl).build()
                    HttpClient.okHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            return@withContext Result.success(response.bodyAlias()?.string() ?: "")
                        }
                        errors += "$candidateUrl -> HTTP ${response.codeAlias()}"
                    }
                } catch (e: Exception) {
                    errors += "$candidateUrl -> ${e.message}"
                    Log.w(TAG, "downloadText failed for $candidateUrl: ${e.message}")
                }
            }
            Result.failure(IllegalStateException(errors.joinToString("; ")))
        }
    }

    fun reset(context: Context) {
        val filename = "default_channels.txt"
        val defaultUrl = "default://channels"
        val prefs = context.getSharedPreferences("SourceCache", Context.MODE_PRIVATE)

        val str = try {
            context.resources.openRawResource(R.raw.channels).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "reset: Failed to read R.raw.channels: ${e.message}")
            R.string.channel_read_error.showToast()
            return
        }

        try {
            viewModelScope.launch(Dispatchers.IO) {
                with(prefs.edit()) {
                    putString("active_source", filename)
                    putString("url_$filename", defaultUrl)
                    apply()
                }
            }
            str2Channels(str)
            Log.d(TAG, "reset: Processed default channels from R.raw.channels")
            _channelsOk.value = true
        } catch (e: Exception) {
            Log.e(TAG, "reset: Failed to process default channels: ${e.message}")
            R.string.channel_read_error.showToast()
        }
    }

    fun importFromUri(uri: Uri, id: String = "") {
        if (uri.scheme == "file") {
            val file = uri.toFile()
            Log.i(TAG, "file $file")
            val str = if (file.exists()) {
                file.readText()
            } else {
                R.string.file_not_exist.showToast()
                return
            }
            viewModelScope.launch(Dispatchers.Default) {
                tryStr2Channels(str, file, uri.toString(), id)
            }
        } else {
            viewModelScope.launch {
                importFromUrl(uri.toString(), id = id)
                Log.d(TAG, "SP.sources after importFromUri: ${SP.sources}")
                // 通知 init 重新加载
                if (listModel.isNotEmpty()) {
                    _channelsOk.value = true
                }
            }
        }
    }

    fun importFromText(str: String, id: String = "") {
        viewModelScope.launch(Dispatchers.Default) {
            tryStr2Channels(str, null, "", id)
        }
    }

    fun tryStr2Channels(str: String, file: File?, url: String, id: String = "") {
        try {
            if (str.isEmpty()) {
                Log.w(TAG, "Input string is empty for url=$url")
                R.string.channel_read_error.showToast()
                return
            }
            //context.getString(R.string.Loading_live_source_channels).showToast()
            val isPlainText = str.trim().startsWith("#EXTM3U") ||
                    str.trim().startsWith("http://") ||
                    str.trim().startsWith("https://")
            val isHex = str.trim().matches(Regex("^[0-9a-fA-F]+$"))
            val targetFile = file ?: cacheFile
            Log.d(TAG, "tryStr2Channels: Input str length=${str.length}, isPlainText=$isPlainText, isHex=$isHex, url=$url")
            if (str2Channels(str)) {
                persistChannelCacheAsync(str, targetFile, isPlainText, isHex)
                if (url.isNotEmpty()) {
                    com.horsenma.yourtv.SP.configUrl = url
                    val source = Source(id = id, uri = url)
                    viewModelScope.launch(Dispatchers.Main) { // 切换到主线程更新 Sources LiveData
                        sources.addSource(source)
                        Log.d(TAG, "tryStr2Channels: Added source: $source, SP.sources: ${source}")
                    }
                }
                viewModelScope.launch {
                    withContext(Dispatchers.Main) {
                        _channelsOk.value = true
                    }
                }
            } else {
                Log.w(TAG, "str2Channels failed for url=$url")
                R.string.channel_import_error.showToast()
            }
        } catch (e: Exception) {
            Log.e(TAG, "tryStr2Channels: Failed for url=$url: ${e.message}", e)
            R.string.channel_read_error.showToast()
        }
    }

    private fun persistChannelCacheAsync(str: String, targetFile: File?, isPlainText: Boolean, isHex: Boolean) {
        if (isPlainText) {
            cacheChannels = str
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val decodedStr = when {
                    isPlainText -> str
                    isHex -> SourceDecoder.decodeHexSource(str) ?: str
                    else -> SourceDecoder.decodeHexSource(str) ?: str
                }
                val fileToWrite = targetFile
                val contentToWrite = when {
                    fileToWrite == null -> null
                    isHex -> str
                    else -> SourceEncoder.encodeJsonSource(decodedStr)
                }
                contentToWrite?.let { fileToWrite?.writeText(it) }
                cacheChannels = decodedStr
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist channel cache: ${e.message}", e)
                cacheChannels = str
            }
        }
    }

    private fun str2Channels(str: String): Boolean {
        if (initialized && str == cacheChannels) {
            Log.w(TAG, "same channels, skipping parsing")
            return true
        }

        if (str.isEmpty()) {
            Log.w(TAG, "Input string is empty")
            return false
        }

        R.string.parsing_live_source.showToast()

        var string = str
        val isPlainText = str.trim().startsWith("#EXTM3U") ||
                str.trim().startsWith("http://") ||
                str.trim().startsWith("https://")
        val isHex = str.trim().matches(Regex("^[0-9a-fA-F]+$"))

        Log.d(TAG, "str2Channels: isPlainText=$isPlainText, isHex=$isHex, str length=${str.length}")

        try {
            if (isHex) {
                string = SourceDecoder.decodeHexSource(str) ?: str
                Log.d(TAG, "str2Channels: Decoded HEX, new string length=${string.length}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode string: ${e.message}")
        }

        if (string.isEmpty()) {
            Log.w(TAG, "channels is empty after processing")
            return false
        }

        val currentTvTitle = groupModel.getCurrent()?.tv?.title
        Log.d(TAG, "str2Channels: Saving currentTvTitle=$currentTvTitle")

        // 分流：提取 webview:// 地址
        val lines = string.split("\n", "\r\n", "\r").filter { it.isNotBlank() }
        val webviewTVs = mutableListOf<com.horsenma.mytv1.data.TV>()
        val iptvLines = mutableListOf<String>()
        var currentTV: com.horsenma.mytv1.data.TV? = null

        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) continue

            if (trimmedLine.startsWith("#EXTM3U")) {
                iptvLines.add(trimmedLine)
                val epgIndex = trimmedLine.indexOf("x-tvg-url=\"")
                if (epgIndex != -1) {
                    val endIndex = trimmedLine.indexOf("\"", epgIndex + 11)
                    if (endIndex != -1) epgUrl = trimmedLine.substring(epgIndex + 11, endIndex)
                }
            } else if (trimmedLine.startsWith("#EXTINF")) {
                iptvLines.add(trimmedLine)
                currentTV = com.horsenma.mytv1.data.TV(uris = emptyList(), block = null)
                val info = trimmedLine.split(",", limit = 2)
                if (info.size < 2) {
                    Log.w(TAG, "Invalid #EXTINF line: $trimmedLine")
                    currentTV = null
                    continue
                }
                currentTV = currentTV.copy(title = info.last().trim(), name = info.last().trim())

                val extinf = info.first()
                val nameStart = extinf.indexOf("tvg-name=\"") + 10
                val nameEnd = extinf.indexOf("\"", nameStart)
                currentTV = currentTV.copy(
                    name = if (nameStart > 9 && nameEnd > nameStart) {
                        extinf.substring(nameStart, nameEnd)
                    } else {
                        currentTV.title
                    }
                )

                val logoStart = extinf.indexOf("tvg-logo=\"") + 10
                val logoEnd = extinf.indexOf("\"", logoStart)
                currentTV = currentTV.copy(
                    logo = if (logoStart > 9 && logoEnd > logoStart) {
                        extinf.substring(logoStart, logoEnd)
                    } else {
                        ""
                    }
                )

                val groupStart = extinf.indexOf("group-title=\"") + 13
                val groupEnd = extinf.indexOf("\"", groupStart)
                currentTV = currentTV.copy(
                    group = if (groupStart > 12 && groupEnd > groupStart) {
                        extinf.substring(groupStart, groupEnd)
                    } else {
                        ""
                    }
                )
            } else if (trimmedLine.startsWith("webview://") && currentTV != null) {
                val url = trimmedLine.removePrefix("webview://")
                val domain = Uri.parse(url).host ?: ""
                // 读取 webview_loading_blacklist.json（缓存结果）
                val blacklistMap: Map<String, List<String>> by lazy {
                    try {
                        val jsonText = context.assets.open("webview_loading_blacklist.json").bufferedReader().use { it.readText() }
                        Global.gson.fromJson(jsonText, object : TypeToken<Map<String, List<String>>>() {}.type)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to read webview_loading_blacklist.json: ${e.message}")
                        emptyMap()
                    }
                }
                // 从 Global.blockMap 或 blacklistMap 获取屏蔽列表
                val blockList = Global.blockMap[currentTV.group]
                    ?: blacklistMap.entries.find { it.key == domain || domain.endsWith(".${it.key}") }?.value
                    ?: listOf("ad.js", "banner.css")
                currentTV = currentTV.copy(
                    uris = listOf(url),
                    block = blockList,
                    id = url.hashCode(),
                    started = "document.querySelector('.floatNav').style.display = 'none'",
                    script = "", // 移除脚本设置，依赖 WebFragment 的 scriptMap
                    selector = "",
                    finished = ""
                )
                webviewTVs.add(currentTV)
                currentTV = null
            } else if (!trimmedLine.startsWith("#") && currentTV != null) {
                iptvLines.add(trimmedLine)
                currentTV = null
            }
        }

        // 处理 WebView 直播源
        val webviewModels = mutableListOf<TVModel>()
        if (webviewTVs.isNotEmpty()) {
            try {
                Log.d(TAG, "str2Channels: Found ${webviewTVs.size} WebView channels")
                // 按 group + name 去重 WebView 频道
                val webviewMap = mutableMapOf<String, MutableList<com.horsenma.mytv1.data.TV>>()
                for (tv in webviewTVs) {
                    val key = (tv.group.orEmpty() + tv.name.orEmpty()).ifEmpty { tv.title.orEmpty() }
                    webviewMap.computeIfAbsent(key) { mutableListOf() }.add(tv)
                }
                webviewModels.addAll(webviewMap.values.mapIndexed { index, tvs ->
                    val uris = tvs.flatMap { it.uris }.distinct()
                    TVModel(
                        com.horsenma.yourtv.data.TV(
                            id = tvs[0].id ?: -1,
                            name = tvs[0].name.orEmpty(),
                            title = tvs[0].title.orEmpty(),
                            logo = tvs[0].logo.orEmpty(),
                            uris = uris,
                            group = tvs[0].group.orEmpty(),
                            playerType = PlayerType.WEBVIEW,
                            block = tvs[0].block.orEmpty(),
                            script = tvs[0].script.orEmpty(),
                            selector = tvs[0].selector.orEmpty(),
                            started = tvs[0].started.orEmpty(),
                            finished = tvs[0].finished.orEmpty(),
                            headers = emptyMap(), // 避免 headers 类型不匹配
                            description = null,
                            image = null,
                            videoIndex = 0,
                            sourceType = SourceType.UNKNOWN,
                            number = -1, // 统一设置为 -1，与原逻辑一致
                            child = emptyList()
                        )
                    ).apply {
                        setLike(SP.getLike(tvs[0].id ?: -1))
                        setGroupIndex(2)
                        listIndex = index
                    }
                })
                Log.d(TAG, "str2Channels: Parsed ${webviewModels.size} WebView channels")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse WebView channels: ${e.message}")
            }
        }

        // 处理 IPTV 直播源
        val iptvList: List<TV> = if (iptvLines.isNotEmpty()) {
            val iptvContent = iptvLines.joinToString("\n")
            when {
                iptvContent.startsWith("[") -> {
                    try {
                        gson.fromJson(iptvContent, typeTvList) ?: emptyList()
                    } catch (e: Exception) {
                        Log.e(TAG, "IPTV JSON parsing failed: ${e.message}")
                        emptyList()
                    }
                }
                iptvContent.startsWith("#") -> {
                    val tvMap = mutableMapOf<String, MutableList<TV>>()
                    var currentTV: TV? = null
                    for (line in iptvLines) {
                        val trimmedLine = line.trim()
                        if (trimmedLine.isEmpty()) continue

                        if (trimmedLine.startsWith("#EXTM3U")) {
                            continue
                        } else if (trimmedLine.startsWith("#EXTINF")) {
                            var lastKey: String? = null // 跟踪上一个频道的 key
                            if (currentTV != null && currentTV.uris.isNotEmpty()) {
                                val key = (currentTV.group + currentTV.name).ifEmpty { currentTV.title }
                                if (key != lastKey) {
                                    tvMap.computeIfAbsent(key) { mutableListOf() }.add(currentTV)
                                    lastKey = key
                                } else {
                                    tvMap[key]?.last()?.uris?.toMutableList()?.addAll(currentTV.uris)
                                }
                            }
                            currentTV = TV()
                            val info = trimmedLine.split(",", limit = 2)
                            if (info.size < 2) continue
                            currentTV = currentTV.copy(title = info.last().trim())

                            val extinf = info.first()
                            val nameStart = extinf.indexOf("tvg-name=\"") + 10
                            val nameEnd = extinf.indexOf("\"", nameStart)
                            currentTV = currentTV.copy(
                                name = if (nameStart > 9 && nameEnd > nameStart) {
                                    extinf.substring(nameStart, nameEnd)
                                } else {
                                    currentTV.title
                                }
                            )

                            val logoStart = extinf.indexOf("tvg-logo=\"") + 10
                            val logoEnd = extinf.indexOf("\"", logoStart)
                            currentTV = currentTV.copy(
                                logo = if (logoStart > 9 && logoEnd > logoStart) {
                                    extinf.substring(logoStart, logoEnd)
                                } else {
                                    ""
                                }
                            )

                            val numStart = extinf.indexOf("tvg-chno=\"") + 10
                            val numEnd = extinf.indexOf("\"", numStart)
                            currentTV = currentTV.copy(
                                number = if (numStart > 9 && numEnd > numStart) {
                                    extinf.substring(numStart, numEnd).toIntOrNull() ?: -1
                                } else {
                                    -1
                                }
                            )

                            val groupStart = extinf.indexOf("group-title=\"") + 13
                            val groupEnd = extinf.indexOf("\"", groupStart)
                            currentTV = currentTV.copy(
                                group = if (groupStart > 12 && groupEnd > groupStart) {
                                    extinf.substring(groupStart, groupEnd)
                                } else {
                                    ""
                                }
                            )
                        } else if (trimmedLine.startsWith("#EXTVLCOPT:http-")) {
                            if (currentTV != null) {
                                val keyValue = trimmedLine.substringAfter("#EXTVLCOPT:http-").split("=", limit = 2)
                                if (keyValue.size == 2) {
                                    currentTV = currentTV.copy(
                                        headers = (currentTV.headers ?: emptyMap()).toMutableMap().apply {
                                            this[keyValue[0]] = keyValue[1]
                                        }
                                    )
                                }
                            }
                        } else if (!trimmedLine.startsWith("#") && currentTV != null) {
                            currentTV = currentTV.copy(
                                uris = currentTV.uris.toMutableList().apply { add(trimmedLine) }
                            )
                        }
                    }

                    var lastKey: String? = null // 跟踪上一个频道的 key
                    if (currentTV != null && currentTV.uris.isNotEmpty()) {
                        val key = (currentTV.group + currentTV.name).ifEmpty { currentTV.title }
                        if (key != lastKey) {
                            tvMap.computeIfAbsent(key) { mutableListOf() }.add(currentTV)
                            lastKey = key
                        } else {
                            tvMap[key]?.last()?.uris?.toMutableList()?.addAll(currentTV.uris)
                        }
                    }

                    tvMap.values.map { tvs ->
                        val uris = tvs.flatMap { it.uris }.distinct()
                        TV(
                            id = -1,
                            name = tvs[0].name,
                            title = tvs[0].title,
                            description = null,
                            logo = tvs[0].logo,
                            image = null,
                            uris = uris,
                            videoIndex = 0,
                            headers = tvs[0].headers,
                            group = tvs[0].group,
                            sourceType = SourceType.UNKNOWN,
                            number = tvs[0].number,
                            child = emptyList(),
                            playerType = PlayerType.IPTV
                        )
                    }.filter { it.uris.isNotEmpty() }
                }
                else -> emptyList()
            }
        } else {
            emptyList()
        }

        if (iptvList.isEmpty() && webviewModels.isEmpty()) {
            Log.w(TAG, "str2Channels: Parsed TV list is empty")
            return false
        }

        // 合并 IPTV 和 WebView 频道
        viewModelScope.launch(Dispatchers.Main) {
            groupModel.setTVListModelList(
                listOf(
                    TVListModel(context.getString(R.string.my_favorites), 0),
                    TVListModel(context.getString(R.string.all_channels), 1)
                )
            )

            // 生成 IPTV TVModel
            val iptvModels = iptvList.mapIndexed { index, tv ->
                TVModel(tv.copy(id = index)).apply {
                    setLike(SP.getLike(index))
                    setGroupIndex(2)
                    listIndex = index
                }
            }

            // 合并所有 TVModel
            val modelMap = mutableMapOf<String, TVModel>()
            (iptvModels + webviewModels).forEach { tvModel ->
                val key = (tvModel.tv.group + tvModel.tv.name).ifEmpty { tvModel.tv.title }
                if (modelMap.containsKey(key)) {
                    modelMap[key]?.tv?.uris = (modelMap[key]?.tv?.uris.orEmpty() + tvModel.tv.uris).distinct()
                } else {
                    modelMap[key] = tvModel
                }
            }
            val listModelNew = modelMap.values.sortedBy { it.listIndex }.toMutableList()

            val groupMap = mutableMapOf<String, MutableList<TVModel>>()
            listModelNew.forEach { tvModel ->
                val group = tvModel.tv.group.ifEmpty { context.getString(R.string.unknown) }
                groupMap.computeIfAbsent(group) { mutableListOf() }.add(tvModel)
            }

            groupMap.forEach { (group, tvModels) ->
                val existingGroup = groupModel.tvGroupValue.find { it.getName() == group }
                if (existingGroup != null) {
                    existingGroup.setTVListModel(tvModels)
                } else {
                    val newGroup = TVListModel(group, groupModel.tvGroupValue.size)
                    newGroup.setTVListModel(tvModels)
                    groupModel.addTVListModel(newGroup)
                }
            }

            listModel = listModelNew
            groupModel.tvGroupValue[1].setTVListModel(listModelNew)

            // 仅当当前无有效 groupModel.current 或非稳定源时，恢复或设置默认
            val currentStableSource = SP.getStableSources().firstOrNull { it.id == groupModel.getCurrent()?.tv?.id }
            if (currentTvTitle != null) {
                val matchingTvModel = listModelNew.firstOrNull { it.tv.title == currentTvTitle }
                if (matchingTvModel != null) {
                    groupModel.setCurrent(matchingTvModel)
                    Log.d(TAG, "str2Channels: Restored groupModel.current to: ${matchingTvModel.tv.title}")
                }
            } else if (groupModel.getCurrent() == null || currentStableSource == null) {
                // 仅当无稳定源时设置默认频道
                if (listModelNew.isNotEmpty()) {
                    groupModel.setCurrent(listModelNew[0])
                    Log.d(TAG, "str2Channels: Set default groupModel.current to: ${listModelNew[0].tv.title}")
                }
            }

            viewModelScope.launch(Dispatchers.IO) { preloadLogo() }
            Log.d(TAG, "str2Channels: Updated listModel size=${listModel.size}")
            R.string.live_source_parsed.showToast()
            groupModel.setChange()
        }

        return true
    }

    fun clearCacheChannels() {
        cacheChannels = ""
        Log.d(TAG, "clearCacheChannels: Cache cleared")
    }

    fun loadActiveSource() {
        val prefs = context.getSharedPreferences("SourceCache", Context.MODE_PRIVATE)
        val filename = prefs.getString("active_source", null) ?: return
        val cacheKey = "cache_$filename"
        val cacheTimeKey = "cache_time_$filename"
        val cachedContent = prefs.getString(cacheKey, null)
        val cacheTime = prefs.getLong(cacheTimeKey, 0L)
        val cacheFile = File(context.filesDir, "cache_$filename")
        val url = prefs.getString("url_$filename", "") ?: ""
        val cacheDuration = 24 * 60 * 60 * 1000L

        if (filename == "default_channels.txt" || filename == "webchannelsiniptv.txt") {
            val resourceId = if (filename == "default_channels.txt") R.raw.channels else R.raw.webchannelsiniptv
            Log.d(TAG, "loadActiveSource: Loading $filename from R.raw")
            viewModelScope.launch(Dispatchers.IO) {
                val str = try {
                    context.resources.openRawResource(resourceId).bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    Log.e(TAG, "loadActiveSource: Failed to read R.raw.$filename: ${e.message}")
                    prefs.edit().remove("active_source").apply()
                    return@launch
                }
                withContext(Dispatchers.Default) {
                    tryStr2Channels(str, null, "", filename)
                    _channelsOk.postValue(true)
                }
                return@launch
            }
            return
        }

        if (cachedContent != null && cacheFile.exists() && System.currentTimeMillis() - cacheTime < cacheDuration) {
            Log.d(TAG, "loadActiveSource: Loading active source $filename")
            viewModelScope.launch(Dispatchers.IO) {
                with(prefs.edit()) {
                    putLong(cacheTimeKey, System.currentTimeMillis())
                    apply()
                }
            }
            viewModelScope.launch(Dispatchers.IO) { // 合并到 IO 线程
                val isHex = cachedContent.trim().matches(Regex("^[0-9a-fA-F]+$"))
                val contentToParse = if (isHex) {
                    SourceDecoder.decodeHexSource(cachedContent) ?: cachedContent // 已由内部 IO 线程处理
                } else {
                    cachedContent
                }
                withContext(Dispatchers.Default) {
                    tryStr2Channels(contentToParse, cacheFile, "", filename)
                    _channelsOk.postValue(true)
                }
            }
        }
    }

    companion object {
        private const val TAG = "MainViewModel"
        const val CACHE_FILE_NAME = "codechannels.txt"
        const val CACHE_EPG = "epg.xml"
        val DEFAULT_CHANNELS_FILE = R.raw.channels
        val DEFAULT_WEBCHANNELS_FILE = R.raw.webchannelsiniptv
    }
}

package com.horsenma.yourtv

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

object SourceCatalog {
    const val DEFAULT_IPTV_FILENAME = "default_channels.txt"
    const val DEFAULT_WEB_FILENAME = "webchannelsiniptv.txt"
    const val FISH_FILENAME = "fish_source.txt"
    const val FISH_URL = "https://live.zbds.top/tv/iptv4.m3u"

    data class BuiltInSource(
        val filename: String,
        val nameRes: Int,
        val url: String,
        val rawRes: Int? = null,
    )

    fun builtInSources(): List<BuiltInSource> = listOf(
        BuiltInSource(DEFAULT_IPTV_FILENAME, R.string.default_iptv_channel, "default://channels", R.raw.channels),
        BuiltInSource(FISH_FILENAME, R.string.fish_source, FISH_URL, null),
        BuiltInSource(DEFAULT_WEB_FILENAME, R.string.default_web_channel, "default://webchannelsiniptv", R.raw.webchannelsiniptv),
    )

    fun builtInSource(filename: String): BuiltInSource? {
        return builtInSources().firstOrNull { it.filename == filename }
    }

    fun isBuiltInSource(filename: String): Boolean {
        return builtInSource(filename) != null
    }

    fun sourceUrl(filename: String, cachedUrl: String): String {
        return builtInSource(filename)?.url ?: cachedUrl
    }

    fun sourceName(context: Context, filename: String, url: String): String {
        builtInSource(filename)?.let { return context.getString(it.nameRes) }
        val fromUrl = runCatching {
            Uri.parse(url).lastPathSegment
                ?.substringBeforeLast(".")
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
        return fromUrl ?: filename.substringBeforeLast(".").ifBlank { filename }
    }

    fun isValidSourceFilename(filename: String): Boolean {
        return filename.isNotBlank() &&
                filename.endsWith(".txt") &&
                !filename.contains("/") &&
                !filename.contains("\\") &&
                !filename.contains("..")
    }

    fun deletedKey(filename: String): String {
        return "deleted_$filename"
    }

    fun isSourceDeleted(prefs: SharedPreferences, filename: String): Boolean {
        return prefs.getBoolean(deletedKey(filename), false)
    }
}

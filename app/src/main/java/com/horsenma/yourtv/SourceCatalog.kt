package com.horsenma.yourtv

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

object SourceCatalog {
    const val DEFAULT_IPTV_FILENAME = "default_channels.txt"
    const val DEFAULT_WEB_FILENAME = "webchannelsiniptv.txt"
    const val FISH_FILENAME = "fish_source.txt"
    const val FISH_URL = "https://live.zbds.top/tv/iptv4.txt"

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

    fun repairSourceText(filename: String, text: String): String {
        if (filename != FISH_FILENAME) return text
        return text.lineSequence()
            .map { line ->
                fishCctvVideoFallbacks.entries.fold(line) { repaired, (audioUrl, videoUrl) ->
                    repaired.replace(audioUrl, videoUrl)
                }
            }
            .joinToString("\n")
    }

    private val fishCctvVideoFallbacks = mapOf(
        "https://piccpndali.v.myalicdn.com/audio/cctv1_2.m3u8" to "https://t.freetv.fun/live/cctv1-8m1080.m3u8",
        "https://piccpndali.v.myalicdn.com/audio/cctv2_2.m3u8" to "https://t.freetv.fun/live/cctv2-8m1080.m3u8",
        "https://piccpndali.v.myalicdn.com/audio/cctv3_2.m3u8" to "https://t.freetv.fun/live/cctv3-8m1080.m3u8",
        "https://piccpndali.v.myalicdn.com/audio/cctveurope_2.m3u8" to "https://t.freetv.fun/live/cctv4-8m1080.m3u8",
        "https://piccpndali.v.myalicdn.com/audio/cctv5_2.m3u8" to "https://t.freetv.fun/live/cctv5-8m1080.m3u8",
        "https://piccpndali.v.myalicdn.com/audio/cctv5plus_2.m3u8" to "https://t.freetv.fun/live/cctv5plus-8m1080.m3u8",
        "https://piccpndali.v.myalicdn.com/audio/cctv6_2.m3u8" to "https://t.freetv.fun/live/cctv6-8m1080.m3u8",
        "https://piccpndali.v.myalicdn.com/audio/cctv7_2.m3u8" to "https://t.freetv.fun/live/cctv7-8m1080.m3u8",
        "https://piccpndali.v.myalicdn.com/audio/cctv8_2.m3u8" to "https://t.freetv.fun/live/cctv8-8m1080.m3u8",
        "https://piccpndali.v.myalicdn.com/audio/cctv9_2.m3u8" to "https://t.freetv.fun/live/cctv9-8m1080.m3u8",
        "https://piccpndali.v.myalicdn.com/audio/cctv10_2.m3u8" to "https://t.freetv.fun/live/cctv10-8m1080.m3u8",
        "https://piccpndali.v.myalicdn.com/audio/cctv11_2.m3u8" to "https://t.freetv.fun/live/cctv11-8m1080.m3u8",
        "https://piccpndali.v.myalicdn.com/audio/cctv12_2.m3u8" to "https://t.freetv.fun/live/cctv12-8m1080.m3u8",
        "https://piccpndali.v.myalicdn.com/audio/cctv14_2.m3u8" to "https://t.freetv.fun/live/cctv14-8m1080.m3u8",
        "https://piccpndali.v.myalicdn.com/audio/cctv16_2.m3u8" to "https://t.freetv.fun/live/cctv16-8m1080.m3u8",
        "https://piccpndali.v.myalicdn.com/audio/cctv17_2.m3u8" to "https://t.freetv.fun/live/cctv17-8m1080.m3u8",
    )
}

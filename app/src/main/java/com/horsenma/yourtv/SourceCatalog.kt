package com.horsenma.yourtv

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import java.security.MessageDigest

object SourceCatalog {
    const val DEFAULT_IPTV_FILENAME = "default_channels.txt"
    const val DEFAULT_WEB_FILENAME = "webchannelsiniptv.txt"
    const val FISH_FILENAME = "fish_source.txt"
    const val FISH_URL = "https://live.zbds.top/tv/iptv4.txt"
    const val DEFAULT_STARTUP_FILENAME = DEFAULT_WEB_FILENAME
    private const val DEFAULT_STARTUP_MIGRATED_KEY = "default_startup_web_migrated_v3"

    data class BuiltInSource(
        val filename: String,
        val nameRes: Int,
        val url: String,
        val rawRes: Int? = null,
    )

    fun builtInSources(): List<BuiltInSource> = listOf(
        BuiltInSource(DEFAULT_WEB_FILENAME, R.string.default_web_channel, "default://webchannelsiniptv", R.raw.webchannelsiniptv),
        BuiltInSource(FISH_FILENAME, R.string.fish_source, FISH_URL, null),
    )

    fun ensureDefaultStartupSource(prefs: SharedPreferences) {
        val migrated = prefs.getBoolean(DEFAULT_STARTUP_MIGRATED_KEY, false)
        val activeFilename = prefs.getString("active_source", null)
        val startupSource = builtInSource(DEFAULT_STARTUP_FILENAME) ?: return
        val startupDeleted = isSourceDeleted(prefs, DEFAULT_STARTUP_FILENAME)

        if (!startupDeleted && (activeFilename == null || isRetiredSource(activeFilename))) {
            prefs.edit()
                .putString("active_source", startupSource.filename)
                .putString("url_${startupSource.filename}", startupSource.url)
                .putBoolean(DEFAULT_STARTUP_MIGRATED_KEY, true)
                .apply()
        } else if (!migrated) {
            prefs.edit()
                .putBoolean(DEFAULT_STARTUP_MIGRATED_KEY, true)
                .apply()
        }
    }

    fun builtInSource(filename: String): BuiltInSource? {
        return builtInSources().firstOrNull { it.filename == filename }
    }

    fun isBuiltInSource(filename: String): Boolean {
        return builtInSource(filename) != null
    }

    fun isRetiredSource(filename: String?): Boolean {
        return filename == DEFAULT_IPTV_FILENAME
    }

    fun sanitizeLogoUrl(logo: String?): String {
        val value = logo?.trim().orEmpty()
        val lower = value.lowercase()
        return if (lower.contains("yourtv") || lower.contains("yourtvapp")) "" else value
    }

    fun logoKey(name: String): String {
        val normalized = name.trim()
            .uppercase()
            .replace("（", "(")
            .replace("）", ")")
            .replace(" ", "")
            .replace("-", "")
            .replace("_", "")

        return when {
            normalized in setOf("CCTV4K", "CCTV4K超高清", "CCTV超高清") -> "CCTV4K"
            normalized in setOf("CCTV5+", "CCTV5PLUS", "CCTV5体育赛事") -> "CCTV5+"
            normalized in setOf("CCTV4中文国际", "CCTV4") -> "CCTV4"
            normalized in setOf("CCTV4(亚)", "CCTV4亚洲", "CCTV4中文国际(亚)", "CCTV4ASIA") -> "CCTV4(亚)"
            normalized in setOf("CCTV4(欧)", "CCTV4欧洲", "CCTV4中文国际(欧)", "CCTV4EUROPE") -> "CCTV4(欧)"
            normalized in setOf("CCTV4(美)", "CCTV4美洲", "CCTV4中文国际(美)", "CCTV4AMERICA") -> "CCTV4(美)"
            normalized.startsWith("CCTV") -> normalized
            else -> name.trim()
        }
    }

    fun defaultLogoUrls(name: String): List<String> {
        val key = logoKey(name).takeIf { it.isNotBlank() } ?: return emptyList()
        val encodedKey = Uri.encode(key)
        return listOf(
            localLogoUrl(key),
            "https://live.fanmingming.cn/tv/$encodedKey.png"
        ) + Utils.getUrls("https://raw.githubusercontent.com/fanmingming/live/main/tv/$encodedKey.png")
    }

    fun logoUrls(name: String, explicitLogo: String?): List<String> {
        val key = logoKey(name).takeIf { it.isNotBlank() } ?: return emptyList()
        val explicit = sanitizeLogoUrl(explicitLogo).takeIf { it.isNotEmpty() }
        return (listOf(localLogoUrl(key)) + listOfNotNull(explicit) + defaultLogoUrls(key))
            .distinct()
    }

    private fun localLogoUrl(key: String): String {
        return "file:///android_asset/tv_logos/${logoAssetFileName(key)}.png"
    }

    private fun logoAssetFileName(key: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(key.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
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

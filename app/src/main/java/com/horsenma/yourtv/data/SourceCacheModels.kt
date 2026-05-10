package com.horsenma.yourtv.data

data class ReqSourceCache(
    val filename: String = "",
)

data class RespSourceCacheItem(
    val filename: String,
    val name: String,
    val url: String,
    val active: Boolean,
    val builtIn: Boolean,
    val cached: Boolean,
    val cacheTime: Long,
)

data class RespSourceCacheList(
    val active: String,
    val sources: List<RespSourceCacheItem>,
)

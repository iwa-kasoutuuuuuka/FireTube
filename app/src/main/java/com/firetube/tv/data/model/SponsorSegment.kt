package com.firetube.tv.data.model

import com.google.gson.annotations.SerializedName
import java.io.Serializable

/**
 * SponsorBlock スキップ区間データ
 */
data class SponsorSegment(
    @SerializedName("segment")
    val segment: List<Double>, // [start_seconds, end_seconds]
    @SerializedName("category")
    val category: String,       // "sponsor", "intro", "outro", "selfpromo", "interaction"
    @SerializedName("UUID")
    val uuid: String? = null
) : Serializable {

    val startMs: Long
        get() = (segment.getOrNull(0)?.times(1000))?.toLong() ?: 0L

    val endMs: Long
        get() = (segment.getOrNull(1)?.times(1000))?.toLong() ?: 0L

    fun contains(positionMs: Long): Boolean {
        return positionMs in startMs..endMs
    }
}

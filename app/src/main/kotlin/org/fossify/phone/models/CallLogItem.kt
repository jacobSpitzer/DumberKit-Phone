package org.fossify.phone.models

import org.fossify.commons.helpers.DAY_SECONDS
import java.util.TimeZone

sealed class CallLogItem {
    data class Date(
        val timestamp: Long,
        val dayCode: String,
    ) : CallLogItem()

    fun getItemId(): Int {
        return when (this) {
            is Date -> {
                // The call-history headers are grouped by LOCAL calendar day.
                // A UTC epoch-day ID assigned to these headers makes e.g. 23:00
                // on Sunday and 01:00 on Monday share the same ID in Israel,
                // crashing RecyclerView with duplicate stable IDs.
                val localTimestamp = timestamp + TimeZone.getDefault().getOffset(timestamp)
                val localEpochDay = Math.floorDiv(localTimestamp, DAY_SECONDS * 1000L)
                -localEpochDay.toInt() - 1
            }
            is RecentCall -> id
        }
    }
}

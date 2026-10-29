package com.navaneeth.filer.ui.util

import android.content.Context
import android.text.format.DateUtils
import com.navaneeth.filer.R

/**
 * "About 2 h 15 min left · done around 11:40 PM" for an indexing estimate in seconds; null when
 * there's no estimate yet. Past today, the finish time includes the weekday ("Sat 9:10 AM").
 */
fun formatTimeLeft(context: Context, seconds: Int, now: Long = System.currentTimeMillis()): String? {
    if (seconds <= 0) return null
    val minutes = (seconds + 59) / 60
    val left = when {
        seconds < 60 -> context.getString(R.string.eta_under_minute)
        minutes < 60 -> context.getString(R.string.eta_minutes, minutes)
        minutes < 24 * 60 -> if (minutes % 60 == 0) context.getString(R.string.eta_hours, minutes / 60)
            else context.getString(R.string.eta_hours_minutes, minutes / 60, minutes % 60)
        else -> context.getString(R.string.eta_days_hours, minutes / (24 * 60), (minutes / 60) % 24)
    }
    val finish = now + seconds * 1000L
    var flags = DateUtils.FORMAT_SHOW_TIME
    if (!DateUtils.isToday(finish)) flags = flags or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY
    return context.getString(R.string.eta_with_finish, left, DateUtils.formatDateTime(context, finish, flags))
}

package com.vortexx.player.core.util

import java.util.Locale

object Format {

    /** 0:05, 3:07, 1:02:03. Negative or unknown durations render as 0:00. */
    fun duration(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L)) / 1000L
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }

    /** Human-friendly total such as "3 h 12 min" or "45 min". */
    fun longDuration(ms: Long): String {
        val totalMinutes = ms.coerceAtLeast(0L) / 60_000L
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> "$hours h $minutes min"
            hours > 0 -> "$hours h"
            else -> "$minutes min"
        }
    }

    fun bytes(bytes: Long): String {
        if (bytes < 1024) return "${bytes.coerceAtLeast(0)} B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024.0
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return String.format(Locale.ROOT, if (value >= 100) "%.0f %s" else "%.1f %s", value, units[unit])
    }

    /** Short resolution badge from a video's height, e.g. 4K, 1080p, 720p. */
    fun resolutionBadge(width: Int, height: Int): String? {
        val shortSide = minOf(width, height)
        if (shortSide <= 0) return null
        return when {
            shortSide >= 2160 -> "4K"
            shortSide >= 1440 -> "1440p"
            shortSide >= 1080 -> "1080p"
            shortSide >= 720 -> "720p"
            shortSide >= 480 -> "480p"
            else -> "${shortSide}p"
        }
    }

    fun speed(speed: Float): String {
        val rounded = Math.round(speed * 100f) / 100f
        val text = if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString() else rounded.toString()
        return "${text}x"
    }
}

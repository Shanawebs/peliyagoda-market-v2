package lk.peliyagoda.marketpro.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Stable, human-readable identity for separate authenticated Box photos.
 * Keep full seconds and a short immutable photo UUID to avoid displaying two
 * photographs captured in the same minute as if they were the same image.
 */
internal object MarketCloudPhotoLabels {
    private val colombo = ZoneId.of("Asia/Colombo")
    private val timeFormat = DateTimeFormatter.ofPattern("dd MMM, HH:mm:ss", Locale.ENGLISH)

    fun localTimestamp(utc: String): String = runCatching {
        Instant.parse(utc).atZone(colombo).format(timeFormat)
    }.getOrElse { utc.replace('T', ' ').take(19) }

    fun title(position: Int, utc: String): String =
        "Photo ${position + 1} • ${localTimestamp(utc)}"

    fun detail(bytes: Long, photoId: String): String =
        "${bytes / 1024L} KB • ID ${photoId.take(8)} • Tap To Preview"
}

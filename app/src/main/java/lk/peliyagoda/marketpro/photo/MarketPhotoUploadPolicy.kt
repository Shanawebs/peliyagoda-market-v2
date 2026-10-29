package lk.peliyagoda.marketpro.photo
import java.time.LocalDate

/** Read-only policy enforcing date role boundaries already required by Cloud. */
internal object MarketPhotoUploadPolicy {
    const val MAX_CLOUD_BYTES = 16L * 1024L * 1024L
    const val BATCH = 4
    fun eligible(day: String, today: String, owner: Boolean): Boolean = runCatching {
        val d=LocalDate.parse(day)
        val current=LocalDate.parse(today)
        d.toString()==day && current.toString()==today &&
            !d.isAfter(current) && (owner || d==current)
    }.getOrDefault(false)
    fun allowedLength(bytes: Long) = bytes in 100L..MAX_CLOUD_BYTES
}

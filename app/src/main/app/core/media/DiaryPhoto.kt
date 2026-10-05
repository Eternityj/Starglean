package com.starglean.app.core.media

/** A local, app-owned photo reference. It never contains the source URI or filename. */
data class DiaryPhoto(
    val ref: String,
    val width: Int,
    val height: Int,
    val mimeType: String = MIME_TYPE,
    val sizeBytes: Long,
) {
    init { DiaryPhotoRules.validate(this) }

    /** Avoid disclosing even the opaque local identifier in crash reports and logs. */
    override fun toString(): String =
        "DiaryPhoto(ref=<private>, width=$width, height=$height, mimeType=$mimeType, sizeBytes=$sizeBytes)"

    companion object { const val MIME_TYPE = "image/jpeg" }
}

class PhotoStoreException(message: String) : IllegalArgumentException(message)

object DiaryPhotoRules {
    const val MAX_ATTACHMENTS = 2
    const val REF_BYTES = 42
    const val MAX_LONG_EDGE = 2_048
    const val MAX_SOURCE_BYTES = 24L * 1_024L * 1_024L
    const val MAX_SOURCE_PIXELS = 40_000_000L
    const val MAX_STORED_BYTES = 8L * 1_024L * 1_024L

    private val refPattern = Regex("photo-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun isValidRef(ref: String): Boolean = refPattern.matches(ref)

    fun validate(photo: DiaryPhoto) {
        if (!isValidRef(photo.ref) || photo.width !in 1..MAX_LONG_EDGE ||
            photo.height !in 1..MAX_LONG_EDGE || photo.mimeType != DiaryPhoto.MIME_TYPE ||
            photo.sizeBytes !in 1..MAX_STORED_BYTES) invalidReference()
    }

    /** Apply to each draft/diary, not to the store as a whole. */
    fun validateAttachments(photos: List<DiaryPhoto>) {
        if (photos.size > MAX_ATTACHMENTS || photos.map { it.ref }.toSet().size != photos.size) {
            throw PhotoStoreException("Attach no more than two different photos to an entry.")
        }
        photos.forEach(::validate)
    }

    internal fun requireValidRef(ref: String) {
        if (!isValidRef(ref)) invalidReference()
    }

    private fun invalidReference(): Nothing =
        throw PhotoStoreException("This local photo reference is invalid.")
}

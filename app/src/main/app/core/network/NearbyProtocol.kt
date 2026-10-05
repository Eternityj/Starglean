package com.starglean.app.core.network

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** A token is fetched immediately before dispatch. Implementations may refresh it. */
fun interface AccessTokenProvider { suspend fun accessToken(): String? }

data class ReaderLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val capturedAt: Instant,
    val isMock: Boolean,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(accuracyMeters.isFinite() && accuracyMeters > 0.0)
    }
    internal fun fields() = linkedMapOf<String, Any?>(
        "latitude" to latitude, "longitude" to longitude, "accuracy_m" to accuracyMeters,
        "captured_at" to capturedAt.toString(), "is_mock" to isMock,
    )
    override fun toString() = "ReaderLocation(redacted)"
}

enum class EntryVisibility(val wire: String) { Public("public"), Private("private") }
enum class ReactionKind(val wire: String) { Hug("hug"), Understand("understand"), Thanks("thanks"), Joy("joy") }
enum class ReportCategory(val wire: String) { Privacy("privacy"), Harassment("harassment"), Inappropriate("inappropriate"), Spam("spam") }
enum class WeatherKind(val wire: String) { Clear("clear"), Cloudy("cloudy"), Rain("rain"), Snow("snow"), Fog("fog"), Unknown("unknown") }
enum class Daylight(val wire: String) { Day("day"), Dusk("dusk"), Night("night") }
data class WeatherSnapshot(
    val kind: WeatherKind,
    val daylight: Daylight,
    val temperatureCelsius: Int? = null,
    val capturedAt: Instant? = null,
) {
    init {
        require(temperatureCelsius == null || temperatureCelsius in -100..100)
        require(kind == WeatherKind.Unknown || capturedAt != null) {
            "Recorded weather requires its original observation time"
        }
    }
    internal fun fields() = linkedMapOf<String, Any?>(
        "kind" to kind.wire, "daylight" to daylight.wire, "temperature_c" to temperatureCelsius,
        "captured_at" to capturedAt?.toString(), "is_demo" to false,
    )
}
data class MediaManifestItem(val id: UUID, val contentType: String, val bytes: Int, val sha256: String) {
    init {
        require(contentType in setOf("image/jpeg", "image/png", "image/webp"))
        require(bytes in 1..15_000_000)
        require(sha256.matches(Regex("[a-f0-9]{64}")))
    }
    internal fun fields() = mapOf("id" to id.toString(), "content_type" to contentType, "bytes" to bytes, "sha256" to sha256)
}

data class PublishEntryRequest(
    val requestId: UUID,
    val title: String,
    val body: String,
    val paperStyle: String,
    val fontStyle: String,
    val weather: WeatherSnapshot,
    val mediaManifest: List<MediaManifestItem> = emptyList(),
    val visibility: EntryVisibility,
    val location: ReaderLocation,
    val consentVersion: Int = 1,
    val shareConfirmed: Boolean,
) {
    init {
        require(shareConfirmed) { "Explicit publication consent is required" }
        require(mediaManifest.size <= 2 && mediaManifest.map { it.id }.distinct().size == mediaManifest.size)
    }
    override fun toString() = "PublishEntryRequest(requestId=$requestId, content=redacted, visibility=$visibility, location=redacted)"
}

/** Stable hash used to fence a durable retry without persisting plaintext or coordinates. */
fun publicationFingerprint(request: PublishEntryRequest): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(JsonValue.encodeObject(request.payload()).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

internal fun PublishEntryRequest.payload() = linkedMapOf<String, Any?>(
    "request_id" to requestId.toString(), "title" to title, "body" to body,
    "paper_style" to paperStyle, "font_style" to fontStyle,
    "weather_snapshot" to weather.fields(), "media_manifest" to mediaManifest.map { it.fields() },
).apply {
    putAll(location.fields()); put("movement_status", "settled")
    put("consent_version", consentVersion); put("share_confirmed", shareConfirmed)
    if (visibility == EntryVisibility.Private) put("visibility", visibility.wire)
}

data class PrepareOpenRequest(val requestId: UUID, val generation: Long, val location: ReaderLocation)
data class SessionLocationRequest(
    val requestId: UUID,
    val readerSessionId: UUID,
    val generation: Long,
    val sequence: Long,
    val location: ReaderLocation,
)
data class ResumeContentRequest(val readerSessionId: UUID, val generation: Long, val sequence: Long, val location: ReaderLocation)

class NearbyResponse internal constructor(val status: String, private val fields: Map<String, Any?>) {
    fun string(name: String): String? = fields[name] as? String
    fun long(name: String): Long? = (fields[name] as? Number)?.toLong()
    fun nonNegativeInteger(name: String): Long? = when (val number = fields[name]) {
        is Long -> number.takeIf { it >= 0L }
        is Int -> number.toLong().takeIf { it >= 0L }
        else -> null
    }
    fun boundedOptionalLong(name: String, range: LongRange): Long? {
        if (!fields.containsKey(name)) return null
        val number = fields[name] as? Number ?: throw ProtocolException("Invalid $name")
        val double = number.toDouble()
        val long = number.toLong()
        if (!double.isFinite() || double != long.toDouble() || long !in range) {
            throw ProtocolException("Invalid $name")
        }
        return long
    }
    fun int(name: String): Int? = (fields[name] as? Number)?.let { number ->
        val double = number.toDouble(); val long = number.toLong()
        long.takeIf { double.isFinite() && double == long.toDouble() && it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    }
    fun boolean(name: String): Boolean? = fields[name] as? Boolean
    @Suppress("UNCHECKED_CAST") fun objectValue(name: String): Map<String, Any?>? = fields[name] as? Map<String, Any?>
    @Suppress("UNCHECKED_CAST") fun objects(name: String): List<Map<String, Any?>> {
        val list = fields[name] as? List<*> ?: throw ProtocolException("Missing $name")
        return list.map { it as? Map<String, Any?> ?: throw ProtocolException("Invalid $name") }
    }
    override fun toString() = "NearbyResponse(status=$status, fields=redacted)"
}

data class AccountSnapshot(
    val status: String,
    val accountTimeZone: String?,
    val dailyLimit: Int?,
    /** Today's opened encounters; null until the server reports it. */
    val readsUsedToday: Int? = null,
)
data class EntryReceipt(val status: String, val entryId: UUID?, val visibility: String?, val publicationState: String?)
data class PublicationStatus(val status: String, val requestId: UUID?, val entryId: UUID?, val visibility: String?, val publicationState: String?, val mediaReady: Boolean?)
data class MediaDescriptor(
    val id: UUID, val ordinal: Int, val contentType: String, val bytes: Int,
    val sha256: String, val width: Int?, val height: Int?,
)
data class ReadMedia(val bytes: ByteArray, val contentType: String, val sha256: String) {
    override fun equals(other: Any?) = other is ReadMedia && bytes.contentEquals(other.bytes) && contentType == other.contentType && sha256 == other.sha256
    override fun hashCode() = 31 * (31 * bytes.contentHashCode() + contentType.hashCode()) + sha256.hashCode()
    override fun toString() = "ReadMedia(bytes=redacted, contentType=$contentType, sha256=$sha256)"
}
data class OwnerAnchor(val latitude: Double, val longitude: Double, val accuracyMeters: Double, val capturedAt: Instant) {
    override fun toString() = "OwnerAnchor(redacted)"
}
data class OwnEntry(
    val requestId: UUID, val entryId: UUID, val title: String, val body: String, val createdAt: Instant,
    val visibility: String, val publicationState: String, val paperStyle: String, val fontStyle: String,
    val weather: WeatherSnapshot, val anchor: OwnerAnchor, val media: List<MediaDescriptor>,
) { override fun toString() = "OwnEntry(requestId=$requestId, entryId=$entryId, content=redacted)" }
data class OwnEntriesCursor(val createdAt: Instant, val entryId: UUID)
data class OwnEntriesSnapshot(val status: String, val items: List<OwnEntry>, val nextCursor: OwnEntriesCursor?)
data class MediaUploadTarget(val status: String, val bucket: String?, val objectPath: String?, val contentType: String?, val bytes: Int?, val sha256: String?)
data class PreparedOpen(val status: String, val readerSessionId: UUID?, val generation: Long?, val stagedUntil: Instant?)
data class DiaryContent(
    val title: String, val body: String, val paperStyle: String, val fontStyle: String,
    val weather: WeatherSnapshot?, val media: List<MediaDescriptor>,
) { override fun toString() = "DiaryContent(redacted)" }
data class OpenedDiary(
    val status: String,
    val readerSessionId: UUID?,
    val generation: Long?,
    val expiresAt: Instant?,
    val accountDate: LocalDate?,
    val used: Int?,
    val dailyLimit: Int?,
    val committedAt: Instant?,
    val content: DiaryContent?,
    val sequence: Long? = null,
    val createdAt: Instant? = null,
) { override fun toString() = "OpenedDiary(status=$status, readerSessionId=$readerSessionId, content=redacted)" }
data class LeaseDecision(val status: String, val readerSessionId: UUID?, val generation: Long?, val sequence: Long?, val expiresAt: Instant?, val remainingMillis: Long?)
data class ReactionSummary(val entryId: UUID, val kind: ReactionKind, val count: Int, val unread: Int)
data class ReactionInbox(val status: String, val seenThrough: Instant?, val items: List<ReactionSummary>)

sealed interface NetworkResult<out T> {
    data class Success<T>(val value: T) : NetworkResult<T>
    data object AuthenticationRequired : NetworkResult<Nothing>
    data class HttpFailure(val statusCode: Int) : NetworkResult<Nothing>
    data class ServiceFailure(val status: String) : NetworkResult<Nothing>
    data class TransportFailure(val cause: Exception) : NetworkResult<Nothing>
    data class InvalidResponse(val reason: String) : NetworkResult<Nothing>
}

internal fun NearbyResponse.uuid(name: String): UUID? = string(name)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
internal fun NearbyResponse.instant(name: String): Instant? = string(name)?.let { runCatching { Instant.parse(it) }.getOrNull() }
internal fun NearbyResponse.date(name: String): LocalDate? = string(name)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

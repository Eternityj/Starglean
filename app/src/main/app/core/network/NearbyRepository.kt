package com.starglean.app.core.network

import java.time.ZoneId
import java.util.UUID

class NearbyRepository(private val client: SupabaseNearbyClient) {
    fun scoped(accessToken: String) = NearbyRepository(client.scoped(accessToken))
    suspend fun registerAccount(timeZone: String) = client.command("register_account", mapOf("account_timezone" to timeZone)).map {
        AccountSnapshot(it.status, it.string("account_timezone"), it.int("daily_limit"), it.int("reads_used_today"))
    }
    suspend fun account() = client.command("account_get", emptyMap()).map {
        AccountSnapshot(it.status, it.string("account_timezone"), it.int("daily_limit"), it.int("reads_used_today"))
    }

    /** Explicit account bootstrap. Never creates an account after an ambiguous failure. */
    suspend fun ensureAccount(timeZone: String): NetworkResult<AccountSnapshot> {
        runCatching { ZoneId.of(timeZone) }.getOrElse {
            return NetworkResult.InvalidResponse("Invalid account timezone")
        }
        val existing = account()
        when (existing) {
            is NetworkResult.Success -> return if (existing.value.status == "ACCOUNT_READY") existing
                else NetworkResult.InvalidResponse("Unexpected account status")
            is NetworkResult.ServiceFailure -> if (existing.status != "ACCOUNT_SETUP_REQUIRED") return existing
            else -> return existing
        }
        return when (val registered = registerAccount(timeZone)) {
            is NetworkResult.Success -> if (registered.value.status == "ACCOUNT_READY") registered
                else NetworkResult.InvalidResponse("Unexpected account registration status")
            is NetworkResult.ServiceFailure -> if (registered.status == "TIMEZONE_IMMUTABLE") {
                // Another actor-bound request won account creation. Read its immutable zone;
                // never overwrite it with the device's current zone.
                when (val raced = account()) {
                    is NetworkResult.Success -> if (raced.value.status == "ACCOUNT_READY") raced
                        else NetworkResult.InvalidResponse("Unexpected account status after registration race")
                    else -> raced
                }
            } else registered
            else -> registered
        }
    }

    suspend fun publish(request: PublishEntryRequest) = client.command(
        if (request.visibility == EntryVisibility.Public) "create_public" else "create_entry",
        request.payload(),
    ).map { EntryReceipt(it.status, it.uuid("entry_id"), it.string("visibility"), it.string("publication_state")) }

    suspend fun publicationStatus(requestId: UUID) = client.command("publication_status", mapOf("request_id" to requestId.toString())).map {
        PublicationStatus(it.status, it.uuid("request_id"), it.uuid("entry_id"), it.string("visibility"), it.string("publication_state"), it.boolean("media_ready"))
    }
    suspend fun cancelPublicationRequest(requestId: UUID) = client.command("cancel_publication_request", mapOf("request_id" to requestId.toString())).map {
        PublicationStatus(it.status, it.uuid("request_id"), it.uuid("entry_id"), it.string("visibility"), it.string("publication_state"), it.boolean("media_ready"))
    }
    suspend fun ownEntries(limit: Int = 20, before: OwnEntriesCursor? = null) = client.command("own_entries", linkedMapOf<String, Any?>(
        "limit" to limit,
    ).apply { if (before != null) { put("before_created_at", before.createdAt.toString()); put("before_id", before.entryId.toString()) } }).map { response ->
        if (response.status != "FOUND") OwnEntriesSnapshot(response.status, emptyList(), null)
        else response.objects("items").map { it.toOwnEntry() }.let { items ->
            OwnEntriesSnapshot(response.status, items, items.lastOrNull()?.let { OwnEntriesCursor(it.createdAt, it.entryId) })
        }
    }
    suspend fun ownEntry(entryId: UUID) = client.command("own_entry", mapOf("entry_id" to entryId.toString())).map { response ->
        if (response.status != "FOUND") null else response.objectValue("item")?.toOwnEntry()
            ?: throw ProtocolException("Missing own entry")
    }
    suspend fun mediaUploadTarget(entryId: UUID, mediaId: UUID) = client.command("media_upload", mapOf(
        "entry_id" to entryId.toString(), "media_id" to mediaId.toString(),
    )).map { MediaUploadTarget(it.status, it.string("bucket"), it.string("object_path"), it.string("content_type"), it.int("bytes"), it.string("sha256")) }
    suspend fun uploadMedia(target: MediaUploadTarget, bytes: ByteArray): NetworkResult<Unit> {
        val bucket = target.bucket ?: return NetworkResult.InvalidResponse("Missing upload bucket")
        val path = target.objectPath ?: return NetworkResult.InvalidResponse("Missing upload path")
        val type = target.contentType ?: return NetworkResult.InvalidResponse("Missing content type")
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (target.status != "UPLOAD_READY" || target.bytes != bytes.size || target.sha256 != digest) {
            return NetworkResult.InvalidResponse("Upload metadata mismatch")
        }
        return client.uploadObject(bucket, path, type, bytes)
    }
    suspend fun readMedia(mediaId: UUID, maxBytes: Int = 15_000_000) = client.readPrivateMedia(mediaId, maxBytes)
    suspend fun finalizeMedia(entryId: UUID, mediaId: UUID) = client.edgeCommand("finalize", mapOf(
        "entry_id" to entryId.toString(), "media_id" to mediaId.toString(),
    )).map { it.status }
    suspend fun deleteEntryWithMedia(entryId: UUID) = client.edgeCommand("delete_entry", mapOf("entry_id" to entryId.toString())).map { it.status }
    suspend fun deleteAccountWithMedia() = client.edgeCommand("delete_account", emptyMap()).map { it.status }
    suspend fun removeMedia(entryId: UUID, mediaId: UUID) = client.command("media_remove", mapOf(
        "entry_id" to entryId.toString(), "media_id" to mediaId.toString(),
    ))
    suspend fun accountStateGet() = client.command("account_state_get", emptyMap())
    suspend fun accountStatePut(expectedVersion: Long, state: Map<String, Any?>) = client.command("account_state_put", mapOf(
        "expected_version" to expectedVersion, "state" to state,
    ))
    suspend fun deleteAccount() = simple("account_delete", mapOf("confirmation" to "DELETE"))

    suspend fun prepareOpen(request: PrepareOpenRequest) = client.command("prepare_open", linkedMapOf<String, Any?>(
        "request_id" to request.requestId.toString(), "generation" to request.generation,
    ).apply { putAll(request.location.fields()) }).map {
        PreparedOpen(it.status, it.uuid("reader_session_id"), it.long("generation"), it.instant("staged_until"))
    }

    suspend fun commitOpen(request: SessionLocationRequest) = sessionLocation("open_commit", request).map { it.toOpenedDiary() }
    suspend fun validate(request: SessionLocationRequest) = sessionLocation("validate", request).map {
        LeaseDecision(it.status, it.uuid("reader_session_id"), it.long("generation"), it.long("sequence"),
            it.instant("server_expires_at"), it.boundedOptionalLong("remaining_ms", 1L..5_000L))
    }
    suspend fun resumeContent(request: ResumeContentRequest) = client.command("resume_content", linkedMapOf<String, Any?>(
        "reader_session_id" to request.readerSessionId.toString(), "generation" to request.generation, "sequence" to request.sequence,
    ).apply { putAll(request.location.fields()) }).map { it.toOpenedDiary() }

    suspend fun endSession(sessionId: UUID) = simple("end_session", mapOf("reader_session_id" to sessionId.toString()))
    suspend fun react(sessionId: UUID, kind: ReactionKind, requestId: UUID) = simple("react", mapOf(
        "request_id" to requestId.toString(), "reader_session_id" to sessionId.toString(), "kind" to kind.wire,
    ))
    suspend fun reactionInbox(seenThrough: java.time.Instant? = null) = client.command("reaction_inbox",
        if (seenThrough == null) emptyMap() else mapOf("seen_through" to seenThrough.toString())).map { response ->
        if (response.status != "FOUND") ReactionInbox(response.status, null, emptyList()) else ReactionInbox(response.status,
            response.instant("seen_through") ?: throw ProtocolException("Missing inbox watermark"), response.objects("items").map { item ->
            ReactionSummary(item.requiredUuid("entry_id"), ReactionKind.entries.firstOrNull { it.wire == item["kind"] }
                ?: throw ProtocolException("Invalid reaction kind"), item.requiredInt("count"), item.requiredInt("unread"))
        })
    }
    suspend fun report(sessionId: UUID, category: ReportCategory, detail: String?) = simple("report", linkedMapOf(
        "reader_session_id" to sessionId.toString(), "category" to category.wire, "detail" to detail,
    ))
    suspend fun block(sessionId: UUID) = simple("block", mapOf("reader_session_id" to sessionId.toString()))
    suspend fun withdraw(entryId: UUID) = simple("withdraw_publication", mapOf("entry_id" to entryId.toString()))
    suspend fun delete(entryId: UUID) = simple("delete_entry", mapOf("entry_id" to entryId.toString()))
    suspend fun setVisibility(entryId: UUID, visibility: EntryVisibility) = client.command("set_visibility", mapOf(
        "entry_id" to entryId.toString(), "visibility" to visibility.wire,
    )).map { EntryReceipt(it.status, it.uuid("entry_id"), it.string("visibility"), it.string("publication_state")) }
    suspend fun appeal(entryId: UUID, detail: String) = simple("appeal", mapOf("entry_id" to entryId.toString(), "detail" to detail))
    suspend fun appealStatus(entryId: UUID) = client.command("appeal_status", mapOf("entry_id" to entryId.toString()))

    private suspend fun simple(action: String, payload: Map<String, Any?>) = client.command(action, payload).map { it.status }
    private suspend fun sessionLocation(action: String, request: SessionLocationRequest) = client.command(action, linkedMapOf<String, Any?>(
        "request_id" to request.requestId.toString(), "reader_session_id" to request.readerSessionId.toString(),
        "generation" to request.generation, "sequence" to request.sequence,
    ).apply { putAll(request.location.fields()) })
}

private fun NearbyResponse.toOpenedDiary(): OpenedDiary {
    val title = string("title")
    val body = string("body")
    val content = if (title != null && body != null) DiaryContent(title, body,
        string("paper_style") ?: throw ProtocolException("Missing paper style"),
        string("font_style") ?: throw ProtocolException("Missing font style"),
        objectValue("weather_snapshot")?.toWeather(), objects("media").map { it.toMedia() }) else null
    return OpenedDiary(status, uuid("reader_session_id"), long("generation"), instant("server_expires_at"),
        date("account_date"), int("used"), int("daily_limit"), instant("server_committed_at"), content,
        sequence = long("sequence"), createdAt = instant("created_at"))
}

private inline fun <T, R> NetworkResult<T>.map(transform: (T) -> R): NetworkResult<R> = when (this) {
    is NetworkResult.Success -> try { NetworkResult.Success(transform(value)) } catch (error: RuntimeException) {
        NetworkResult.InvalidResponse(error.message ?: "Invalid response")
    } catch (error: ProtocolException) {
        NetworkResult.InvalidResponse(error.message ?: "Invalid response")
    }
    NetworkResult.AuthenticationRequired -> NetworkResult.AuthenticationRequired
    is NetworkResult.HttpFailure -> this
    is NetworkResult.ServiceFailure -> this
    is NetworkResult.TransportFailure -> this
    is NetworkResult.InvalidResponse -> this
}

private fun Map<String, Any?>.requiredString(name: String) = this[name] as? String ?: throw ProtocolException("Missing $name")
private fun Map<String, Any?>.requiredInt(name: String): Int {
    val number = this[name] as? Number ?: throw ProtocolException("Missing $name")
    val double = number.toDouble(); val long = number.toLong()
    return long.takeIf { double.isFinite() && double == long.toDouble() && it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        ?: throw ProtocolException("Invalid $name")
}
private fun Map<String, Any?>.requiredDouble(name: String) = (this[name] as? Number)?.toDouble() ?: throw ProtocolException("Missing $name")
private fun Map<String, Any?>.requiredUuid(name: String) = runCatching { UUID.fromString(requiredString(name)) }.getOrElse { throw ProtocolException("Invalid $name") }
private fun Map<String, Any?>.requiredInstant(name: String) = runCatching { java.time.Instant.parse(requiredString(name)) }.getOrElse { throw ProtocolException("Invalid $name") }
@Suppress("UNCHECKED_CAST") private fun Map<String, Any?>.requiredObject(name: String) = this[name] as? Map<String, Any?> ?: throw ProtocolException("Missing $name")
@Suppress("UNCHECKED_CAST") private fun Map<String, Any?>.objectList(name: String) = (this[name] as? List<*>)?.map { it as? Map<String, Any?> ?: throw ProtocolException("Invalid $name") } ?: throw ProtocolException("Missing $name")
private fun Map<String, Any?>.toWeather() = WeatherSnapshot(
    WeatherKind.entries.firstOrNull { it.wire == this["kind"] } ?: throw ProtocolException("Invalid weather kind"),
    Daylight.entries.firstOrNull { it.wire == this["daylight"] } ?: throw ProtocolException("Invalid daylight"),
    (this["temperature_c"] as? Number)?.toInt(), (this["captured_at"] as? String)?.let {
        runCatching { java.time.Instant.parse(it) }.getOrElse { throw ProtocolException("Invalid weather captured_at") }
    },
)
private fun Map<String, Any?>.toMedia() = MediaDescriptor(requiredUuid("id"), requiredInt("ordinal"), requiredString("content_type"),
    requiredInt("bytes"), requiredString("sha256").also { if (!it.matches(Regex("[a-f0-9]{64}"))) throw ProtocolException("Invalid media sha256") },
    (this["width"] as? Number)?.toInt(), (this["height"] as? Number)?.toInt())
private fun Map<String, Any?>.toOwnEntry() = OwnEntry(requiredUuid("request_id"), requiredUuid("entry_id"), requiredString("title"),
    requiredString("body"), requiredInstant("created_at"), requiredString("visibility"), requiredString("publication_state"),
    requiredString("paper_style"), requiredString("font_style"), requiredObject("weather_snapshot").toWeather(),
    requiredObject("anchor").let { OwnerAnchor(it.requiredDouble("latitude"), it.requiredDouble("longitude"), it.requiredDouble("accuracy_m"), it.requiredInstant("captured_at")) },
    objectList("media").map { it.toMedia() })

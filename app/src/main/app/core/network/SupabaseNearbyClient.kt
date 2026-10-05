package com.starglean.app.core.network

import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

fun interface HttpsConnectionFactory { fun open(url: URL): HttpsURLConnection }
fun interface AuthRejectionHandler { suspend fun onAuthRejected(rejectedAccessToken: String) }

class SupabaseNearbyClient(
    private val endpoint: URL,
    private val publishableKey: String,
    private val tokenProvider: AccessTokenProvider,
    private val connectionFactory: HttpsConnectionFactory = HttpsConnectionFactory { it.openConnection() as HttpsURLConnection },
    private val connectTimeoutMillis: Int = 8_000,
    private val readTimeoutMillis: Int = 12_000,
    private val maxResponseBytes: Int = 256 * 1024,
    private val authRejectionHandler: AuthRejectionHandler? = null,
) {
    private val baseUrl = endpoint.toString().trimEnd('/')
    private val rpcUrl = URL(baseUrl + "/rest/v1/rpc/nearby_public_command")

    /** Fixed credential scope for one actor-bound operation lease. */
    fun scoped(accessToken: String): SupabaseNearbyClient = SupabaseNearbyClient(
        endpoint, publishableKey, AccessTokenProvider { accessToken }, connectionFactory,
        connectTimeoutMillis, readTimeoutMillis, maxResponseBytes, authRejectionHandler,
    )
    init {
        require(endpoint.protocol == "https") { "Supabase endpoint must use HTTPS" }
        require(endpoint.userInfo == null && endpoint.query == null && endpoint.ref == null &&
            (endpoint.path.isEmpty() || endpoint.path == "/") && endpoint.host.endsWith(".supabase.co") &&
            (endpoint.port == -1 || endpoint.port == 443)) { "Invalid Supabase project endpoint" }
        require(publishableKey.startsWith("sb_publishable_") && publishableKey.length in 20..256) {
            "A Supabase publishable key is required"
        }
        require(connectTimeoutMillis in 1..30_000 && readTimeoutMillis in 1..30_000)
        require(maxResponseBytes in 1024..1_048_576)
    }

    /** No command is retried here. Callers retry only immutable idempotent request objects. */
    suspend fun command(action: String, payload: Map<String, Any?>): NetworkResult<NearbyResponse> {
        if (!action.matches(Regex("[a-z_]{1,40}"))) return NetworkResult.InvalidResponse("Invalid action")
        val token = tokenProvider.accessToken()?.takeIf { it.isNotBlank() } ?: return NetworkResult.AuthenticationRequired
        val body = JsonValue.encodeObject(linkedMapOf("action" to action, "payload" to payload)).toByteArray(Charsets.UTF_8)
        if (body.size > 70 * 1024) return NetworkResult.InvalidResponse("Request is too large")
        val result = runInterruptible(Dispatchers.IO) {
        var connection: HttpsURLConnection? = null
        try {
            connection = connectionFactory.open(rpcUrl).apply {
                requestMethod = "POST"
                doOutput = true
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
                setFixedLengthStreamingMode(body.size)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("apikey", publishableKey)
                setRequestProperty("Authorization", "Bearer $token")
            }
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > maxResponseBytes) throw ProtocolException("Response is too large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: ByteArray(0)
            if (code == 401) NetworkResult.AuthenticationRequired
            else if (code !in 200..299) NetworkResult.HttpFailure(code)
            else {
                val objectValue = JsonValue.parseObject(bytes.toString(Charsets.UTF_8))
                val status = objectValue["status"] as? String
                    ?: throw ProtocolException("Missing response status")
                when (status) {
                    "AUTH_REQUIRED" -> NetworkResult.AuthenticationRequired
                    "INVALID_REQUEST", "REQUEST_CONFLICT", "ACCOUNT_SETUP_REQUIRED", "TIMEZONE_IMMUTABLE",
                    "SERVICE_DISABLED", "LOCATION_UNRELIABLE", "CLOUD_ENTRY_LIMIT", "LIFETIME_SUBMISSION_LIMIT" -> NetworkResult.ServiceFailure(status)
                    else -> NetworkResult.Success(NearbyResponse(status, objectValue))
                }
            }
        } catch (error: ProtocolException) {
            NetworkResult.InvalidResponse(error.message ?: "Invalid response")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            NetworkResult.TransportFailure(error)
        } finally {
            connection?.disconnect()
        }
        }
        return notifyAuthRejection(result, token)
    }

    /** Upload is deliberately single-attempt and never uses Storage upsert. */
    suspend fun uploadObject(bucket: String, objectPath: String, contentType: String, bytes: ByteArray): NetworkResult<Unit> {
        if (bucket != "nearby-private-photos" || !objectPath.matches(Regex("[a-f0-9-]{36}/[a-f0-9-]{36}/[a-f0-9-]{36}")) ||
            contentType !in setOf("image/jpeg", "image/png", "image/webp") || bytes.isEmpty() || bytes.size > 15_000_000) {
            return NetworkResult.InvalidResponse("Invalid media upload")
        }
        val token = tokenProvider.accessToken()?.takeIf { it.isNotBlank() } ?: return NetworkResult.AuthenticationRequired
        val result = runInterruptible(Dispatchers.IO) {
        var connection: HttpsURLConnection? = null
        try {
            connection = connectionFactory.open(URL("$baseUrl/storage/v1/object/$bucket/$objectPath")).apply {
                requestMethod = "POST"; doOutput = true; instanceFollowRedirects = false
                connectTimeout = connectTimeoutMillis; readTimeout = readTimeoutMillis
                setFixedLengthStreamingMode(bytes.size)
                setRequestProperty("Content-Type", contentType); setRequestProperty("apikey", publishableKey)
                setRequestProperty("Authorization", "Bearer $token"); setRequestProperty("x-upsert", "false")
            }
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            if (code in 200..299) NetworkResult.Success(Unit)
            else if (code == 401) NetworkResult.AuthenticationRequired
            else NetworkResult.HttpFailure(code)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { NetworkResult.TransportFailure(error) }
        finally { connection?.disconnect() }
        }
        return notifyAuthRejection(result, token)
    }

    suspend fun edgeCommand(action: String, identifiers: Map<String, String>): NetworkResult<NearbyResponse> {
        val allowed = setOf("finalize", "delete_entry", "delete_account")
        if (action !in allowed || identifiers.any { (key, value) -> key !in setOf("entry_id", "media_id") || runCatching { java.util.UUID.fromString(value) }.isFailure })
            return NetworkResult.InvalidResponse("Invalid media command")
        val token = tokenProvider.accessToken()?.takeIf { it.isNotBlank() } ?: return NetworkResult.AuthenticationRequired
        val body = JsonValue.encodeObject(linkedMapOf<String, Any?>("action" to action).apply { putAll(identifiers) }).toByteArray()
        val result = runInterruptible(Dispatchers.IO) {
            var connection: HttpsURLConnection? = null
            try {
                connection = connectionFactory.open(URL("$baseUrl/functions/v1/nearby-private-media")).apply {
                    requestMethod = "POST"; doOutput = true; instanceFollowRedirects = false
                    connectTimeout = connectTimeoutMillis; readTimeout = readTimeoutMillis
                    setFixedLengthStreamingMode(body.size); setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json"); setRequestProperty("apikey", publishableKey)
                    setRequestProperty("Authorization", "Bearer $token")
                }
                connection.outputStream.use { it.write(body) }
                val code = connection.responseCode
                if (code == 401) NetworkResult.AuthenticationRequired
                else if (code !in 200..299) {
                    val errorStatus = connection.errorStream?.use { stream ->
                        runCatching { JsonValue.parseObject(stream.readBounded(maxResponseBytes).toString(Charsets.UTF_8))["status"] as? String }.getOrNull()
                    }
                    if (errorStatus == "AUTH_REQUIRED") NetworkResult.AuthenticationRequired
                    else if (errorStatus != null) NetworkResult.ServiceFailure(errorStatus) else NetworkResult.HttpFailure(code)
                } else {
                    val bytes = connection.inputStream.use { it.readBounded(maxResponseBytes) }
                    val objectValue = JsonValue.parseObject(bytes.toString(Charsets.UTF_8))
                    val status = objectValue["status"] as? String ?: throw ProtocolException("Missing response status")
                    if (status == "AUTH_REQUIRED") NetworkResult.AuthenticationRequired
                    else NetworkResult.Success(NearbyResponse(status, objectValue))
                }
            } catch (error: ProtocolException) { NetworkResult.InvalidResponse(error.message ?: "Invalid media") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { NetworkResult.TransportFailure(error) }
            finally { connection?.disconnect() }
        }
        return notifyAuthRejection(result, token)
    }

    suspend fun readPrivateMedia(mediaId: java.util.UUID, maxBytes: Int = 15_000_000): NetworkResult<ReadMedia> {
        require(maxBytes in 1..15_000_000)
        val token = tokenProvider.accessToken()?.takeIf { it.isNotBlank() } ?: return NetworkResult.AuthenticationRequired
        val body = JsonValue.encodeObject(mapOf("action" to "read", "media_id" to mediaId.toString())).toByteArray()
        val result = runInterruptible(Dispatchers.IO) {
            var connection: HttpsURLConnection? = null
            try {
                connection = connectionFactory.open(URL("$baseUrl/functions/v1/nearby-private-media")).apply {
                    requestMethod = "POST"; doOutput = true; instanceFollowRedirects = false
                    connectTimeout = connectTimeoutMillis; readTimeout = readTimeoutMillis
                    setFixedLengthStreamingMode(body.size); setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "image/jpeg"); setRequestProperty("apikey", publishableKey)
                    setRequestProperty("Authorization", "Bearer $token")
                }
                connection.outputStream.use { it.write(body) }
                val code = connection.responseCode
                if (code == 401) NetworkResult.AuthenticationRequired
                else if (code !in 200..299) NetworkResult.HttpFailure(code)
                else {
                    val contentType = connection.getHeaderField("Content-Type")?.substringBefore(';')?.trim()
                    if (contentType != "image/jpeg") throw ProtocolException("Invalid media content type")
                    val digestHeader = connection.getHeaderField("Digest") ?: throw ProtocolException("Missing media digest")
                    val expected = digestHeader.removePrefix("sha-256=").takeIf { it != digestHeader }
                        ?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
                        ?: throw ProtocolException("Invalid media digest")
                    val bytes = connection.inputStream.use { it.readBounded(maxBytes) }
                    val actual = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                    if (!java.security.MessageDigest.isEqual(expected, actual)) throw ProtocolException("Media digest mismatch")
                    NetworkResult.Success(ReadMedia(bytes, contentType, actual.joinToString("") { "%02x".format(it) }))
                }
            } catch (error: ProtocolException) { NetworkResult.InvalidResponse(error.message ?: "Invalid media") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { NetworkResult.TransportFailure(error) }
            finally { connection?.disconnect() }
        }
        return notifyAuthRejection(result, token)
    }

    private suspend fun <T> notifyAuthRejection(result: NetworkResult<T>, requestToken: String): NetworkResult<T> {
        if (result === NetworkResult.AuthenticationRequired) {
            try { authRejectionHandler?.onAuthRejected(requestToken) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Authentication state is reconciled again on foreground entry. */ }
        }
        return result
    }

    private fun java.io.InputStream.readBounded(limit: Int): ByteArray {
        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val count = read(buffer); if (count < 0) break
            if (output.size() + count > limit) throw ProtocolException("Response is too large")
            output.write(buffer, 0, count) }
        return output.toByteArray()
    }
}

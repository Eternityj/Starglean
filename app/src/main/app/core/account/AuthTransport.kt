package com.starglean.app.core.account

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URI
import java.io.ByteArrayOutputStream
import java.net.ProtocolException
import java.util.Base64

class AuthHttpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap(),
) {
    override fun toString() = "AuthHttpResponse(status=$status, body=<redacted>, headers=${headers.keys})"
}

interface AuthTransport {
    suspend fun request(
        method: String,
        path: String,
        body: String? = null,
        accessToken: String? = null,
    ): AuthHttpResponse

    /** Revoke a verification-only session without affecting the user's sessions on other devices. */
    suspend fun revokeTemporarySession(accessToken: String): AuthHttpResponse =
        request("POST", "/auth/v1/logout?scope=local", accessToken = accessToken)
}

/** Small, dependency-free transport. Call it from a dispatcher suitable for blocking I/O. */
class UrlConnectionAuthTransport(
    baseUrl: String,
    private val publishableKey: String,
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 20_000,
    private val maxResponseBytes: Int = 1_048_576,
) : AuthTransport {
    private val endpoint = URI(baseUrl.trimEnd('/')).also {
        require(it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null && it.fragment == null && it.query == null) {
            "Supabase endpoint must be a plain HTTPS origin"
        }
        require(it.port == -1 || it.port == 443) { "Supabase endpoint must use the standard HTTPS port" }
        require(it.path.isNullOrEmpty()) { "Supabase endpoint must not contain a path" }
    }.toString()

    init {
        require(isPublicClientKey(publishableKey)) { "A Supabase publishable or legacy anon key is required" }
    }

    override suspend fun request(method: String, path: String, body: String?, accessToken: String?): AuthHttpResponse {
        return executeRequest(method, path, body, accessToken, connectTimeoutMillis, readTimeoutMillis)
    }

    /** Uses shorter per-stage socket timeouts for best-effort cleanup; this is not a total wall-clock deadline. */
    override suspend fun revokeTemporarySession(accessToken: String): AuthHttpResponse = executeRequest(
        method = "POST",
        path = "/auth/v1/logout?scope=local",
        body = null,
        accessToken = accessToken,
        requestConnectTimeoutMillis = TEMPORARY_SESSION_TIMEOUT_MILLIS,
        requestReadTimeoutMillis = TEMPORARY_SESSION_TIMEOUT_MILLIS,
    )

    private fun executeRequest(
        method: String,
        path: String,
        body: String?,
        accessToken: String?,
        requestConnectTimeoutMillis: Int,
        requestReadTimeoutMillis: Int,
    ): AuthHttpResponse {
        val connection = (URL(endpoint + "/" + path.trimStart('/')).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = requestConnectTimeoutMillis
            readTimeout = requestReadTimeoutMillis
            instanceFollowRedirects = false
            setRequestProperty("apikey", publishableKey)
            setRequestProperty("Accept", "application/json")
            if (accessToken != null) setRequestProperty("Authorization", "Bearer $accessToken")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > maxResponseBytes) throw ProtocolException("Authentication response is too large")
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }.orEmpty()
            return AuthHttpResponse(status, responseBody, connection.headerFields)
        } catch (error: IOException) {
            throw AuthNetworkException(error)
        } finally {
            connection.disconnect()
        }
    }

    private fun isPublicClientKey(value: String): Boolean {
        if (value.startsWith("sb_publishable_") && value.length > 20) return true
        val payload = value.split('.').getOrNull(1) ?: return false
        return runCatching {
            val decoded = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
            decoded.contains("\"role\":\"anon\"") && !decoded.contains("service_role")
        }.getOrDefault(false)
    }

    private companion object {
        const val TEMPORARY_SESSION_TIMEOUT_MILLIS = 5_000
    }
}

class AuthNetworkException(cause: IOException) : IOException("Authentication service could not be reached", cause)

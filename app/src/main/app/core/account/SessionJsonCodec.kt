package com.starglean.app.core.account

import org.json.JSONObject

internal object SessionJsonCodec {
    fun encode(value: AuthSession): ByteArray = JSONObject()
        .put("version", 1)
        .put("user_id", value.userId)
        .put("email", value.email ?: JSONObject.NULL)
        .put("is_anonymous", value.isAnonymous)
        .put("access_token", value.accessToken)
        .put("refresh_token", value.refreshToken)
        .put("expires_at", value.expiresAtEpochSeconds)
        .toString().toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): AuthSession {
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.getInt("version") == 1) { "Unsupported session format" }
        return AuthSession(
            userId = json.getString("user_id"),
            email = if (json.isNull("email")) null else json.getString("email"),
            isAnonymous = json.optBoolean("is_anonymous", false),
            accessToken = json.getString("access_token"),
            refreshToken = json.getString("refresh_token"),
            expiresAtEpochSeconds = json.getLong("expires_at"),
        ).also {
            require(it.userId.isNotBlank() && it.accessToken.isNotBlank() && it.refreshToken.isNotBlank())
        }
    }
}

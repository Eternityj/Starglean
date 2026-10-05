package com.starglean.app.core.location

/** Permission to use foreground location is separate from permission to publish an entry. */
internal enum class AppLocationConsent { NotAsked, Enabled, Declined }

internal fun appLocationConsentVersion(): Int = 1

internal fun decodeAppLocationConsent(version: Int, decision: String?): AppLocationConsent =
    if (version != appLocationConsentVersion()) AppLocationConsent.NotAsked
    else AppLocationConsent.entries.firstOrNull { it.name == decision } ?: AppLocationConsent.NotAsked

internal fun mayUseAppLocation(consent: AppLocationConsent, precisePermission: Boolean): Boolean =
    consent == AppLocationConsent.Enabled && precisePermission

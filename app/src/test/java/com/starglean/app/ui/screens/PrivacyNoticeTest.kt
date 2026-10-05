package com.starglean.app.ui.screens

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PrivacyNoticeTest {
    private fun notice() = JSONObject("""{
        "title":"Privacy & sharing", "updated":"28 September 2026",
        "contact":"support@example.com",
        "sections":[{"title":"Location", "text":"Foreground use only."}],
        "providers":[{"name":"Provider privacy", "url":"https://example.com/privacy"}]
    }""")

    @Test fun validOfflineNoticeCanBePresented() {
        assertEquals("Privacy & sharing", parsePrivacyNotice(notice().toString()).getString("title"))
    }

    @Test fun incompleteLaterSectionIsRejectedBeforePresentation() {
        val input = notice()
        input.getJSONArray("sections").put(JSONObject("""{"title":"Incomplete"}"""))
        assertTrue(runCatching { parsePrivacyNotice(input.toString()) }.isFailure)
    }

    @Test fun emptyNoticeIsNotSilentlyAccepted() {
        val input = notice().put("sections", org.json.JSONArray())
        assertTrue(runCatching { parsePrivacyNotice(input.toString()) }.isFailure)
        assertTrue(runCatching { parsePrivacyNotice("not json") }.isFailure)
    }

    @Test fun providerLinksMustBeHttpsWithoutEmbeddedCredentials() {
        listOf("javascript:alert(1)", "http://example.com", "https://user@example.com", "https:").forEach { url ->
            val input = notice()
            input.getJSONArray("providers").getJSONObject(0).put("url", url)
            assertTrue(url, runCatching { parsePrivacyNotice(input.toString()) }.isFailure)
        }
    }

    @Test fun missingContactIsRejectedBeforePresentation() {
        val input = notice().apply { remove("contact") }
        assertTrue(runCatching { parsePrivacyNotice(input.toString()) }.isFailure)
    }
}

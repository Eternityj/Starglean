package com.starglean.app.ui.screens

import org.json.JSONObject
import java.net.URI

/** Validate the whole offline document before presenting it, including its later sections. */
internal fun parsePrivacyNotice(text: String): JSONObject = JSONObject(text).also { document ->
    require(document.getString("title").isNotBlank())
    require(document.getString("updated").isNotBlank())
    require(document.getString("contact").matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")))
    val sections = document.getJSONArray("sections")
    require(sections.length() > 0)
    repeat(sections.length()) { index ->
        val section = sections.getJSONObject(index)
        require(section.getString("title").isNotBlank())
        require(section.getString("text").isNotBlank())
    }
    val providers = document.getJSONArray("providers")
    require(providers.length() > 0)
    repeat(providers.length()) { index ->
        val provider = providers.getJSONObject(index)
        require(provider.getString("name").isNotBlank())
        val link = URI(provider.getString("url"))
        require(link.scheme == "https" && !link.host.isNullOrBlank() && link.userInfo == null)
    }
}

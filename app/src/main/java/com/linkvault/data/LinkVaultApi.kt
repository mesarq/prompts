package com.linkvault.data

import com.linkvault.BuildConfig
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

private val apiBase = "${BuildConfig.SUPABASE_URL}/functions/v1/linkvault-api"

@Serializable
data class LinkItem(
    val id: String,
    val title: String? = null,
    val url: String? = null,
    val description: String? = null,
    val item_type: String? = null,
    val is_favorite: Boolean = false
)

class LinkVaultApi {
    private suspend fun token(): String =
        Supabase.accessToken() ?: error("Please sign in again.")

    suspend fun listItems(query: String = ""): List<LinkItem> {
        val response = Supabase.http.get("$apiBase/items") {
            header(HttpHeaders.Authorization, "Bearer ${token()}")
            if (query.isNotBlank()) parameter("q", query)
        }
        if (!response.status.isSuccess()) error("Could not load saved items (${response.status.value}).")
        val payload: JsonObject = response.body()
        val values = payload["items"] ?: return emptyList()
        return Json { ignoreUnknownKeys = true }.decodeFromJsonElement(values)
    }

    suspend fun saveLink(url: String) {
        val jsonUrl = JsonPrimitive(url).toString()
        val response = Supabase.http.post("$apiBase/items") {
            header(HttpHeaders.Authorization, "Bearer ${token()}")
            contentType(ContentType.Application.Json)
            setBody("""{"url":$jsonUrl,"item_type":"link"}""")
        }
        if (!response.status.isSuccess()) error("Save failed (${response.status.value}).")
    }
}

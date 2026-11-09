package com.linkvault.data

import com.linkvault.BuildConfig
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

private val apiBase = "${BuildConfig.SUPABASE_URL}/functions/v1/linkvault-api"
private val apiJson = Json { ignoreUnknownKeys = true }

@Serializable
data class LinkItem(
    val id: String,
    val title: String? = null,
    val url: String? = null,
    val description: String? = null,
    val type: String? = null,
    val is_favorite: Boolean = false
)

class LinkVaultApi {
    private suspend fun token(): String =
        Supabase.accessToken() ?: error("Please sign in again.")

    private fun errorMessage(payload: JsonObject, fallback: String): String =
        payload["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: fallback

    suspend fun listItems(query: String = ""): List<LinkItem> {
        val response = Supabase.http.get("$apiBase/items") {
            header(HttpHeaders.Authorization, "Bearer ${token()}")
            if (query.isNotBlank()) parameter("q", query)
        }
        val payload: JsonObject = response.body()
        if (!response.status.isSuccess()) {
            error(errorMessage(payload, "Could not load saved items (${response.status.value})."))
        }
        val values = payload["data"] as? JsonArray ?: return emptyList()
        return apiJson.decodeFromJsonElement(values)
    }

    suspend fun saveLink(url: String) {
        val body = buildJsonObject {
            put("type", "link")
            put("url", url)
        }
        val response = Supabase.http.post("$apiBase/items") {
            header(HttpHeaders.Authorization, "Bearer ${token()}")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val payload: JsonObject = response.body()
        if (!response.status.isSuccess()) {
            error(errorMessage(payload, "Save failed (${response.status.value})."))
        }
    }
}

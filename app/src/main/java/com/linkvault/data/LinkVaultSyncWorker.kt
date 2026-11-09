package com.linkvault.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

@Serializable
private data class PushOperation(
    val operation_id: String,
    val entity: String,
    val action: String,
    val entity_id: String? = null,
    val base_version: Int? = null,
    val payload: JsonObject
)

class LinkVaultSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val userId = Supabase.client.auth.currentSessionOrNull()?.user?.id ?: return Result.failure()
        val token = Supabase.accessToken() ?: return Result.retry()
        val dao = LocalDatabase.get(applicationContext).vaultDao()
        val pending = dao.pending(userId, 100)
        if (pending.isEmpty()) return Result.success()

        return try {
            val operations = pending.map { row ->
                PushOperation(
                    operation_id = row.operationId,
                    entity = row.entity,
                    action = row.action,
                    entity_id = row.entityId,
                    base_version = row.baseVersion,
                    payload = Json.parseToJsonElement(row.payload).jsonObject
                )
            }
            val response: JsonObject = Supabase.http.post("${com.linkvault.BuildConfig.SUPABASE_URL}/functions/v1/linkvault-sync/push") {
                header(HttpHeaders.Authorization, "Bearer $token")
                header("apikey", com.linkvault.BuildConfig.SUPABASE_PUBLISHABLE_KEY)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("operations", Json.parseToJsonElement(Json.encodeToString(operations))) })
            }.body()
            val results = response["data"]?.jsonObject?.get("results")?.jsonArray ?: return Result.retry()
            val byId = results.associateBy { it.jsonObject["operation_id"]?.jsonPrimitive?.contentOrNull }
            pending.forEach { row ->
                val result = byId[row.operationId]?.jsonObject
                if (result?.get("success")?.jsonPrimitive?.content == "true") {
                    dao.acknowledge(row.operationId)
                } else {
                    val error = result?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                        ?: if (result?.get("conflict")?.jsonPrimitive?.content == "true") "Conflict requires resolution" else "Sync rejected operation"
                    dao.recordFailure(row.operationId, error)
                }
            }
            if (dao.pending(userId, 1).isNotEmpty()) Result.retry() else Result.success()
        } catch (e: Exception) {
            pending.forEach { dao.recordFailure(it.operationId, e.message ?: "Network error") }
            Result.retry()
        }
    }
}

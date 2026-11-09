package com.linkvault.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
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

class LinkVaultSyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val json = Json { ignoreUnknownKeys = true }
    private val base = "${com.linkvault.BuildConfig.SUPABASE_URL}/functions/v1/linkvault-sync"

    override suspend fun doWork(): Result {
        val userId = Supabase.client.auth.currentSessionOrNull()?.user?.id ?: return Result.failure()
        val token = Supabase.accessToken() ?: return Result.retry()
        val dao = LocalDatabase.get(applicationContext).vaultDao()
        return try {
            val pending = dao.pending(userId, 100)
            if (pending.isNotEmpty()) {
                val operations = pending.map { row ->
                    PushOperation(row.operationId, row.entity, row.action, row.entityId, row.baseVersion,
                        json.parseToJsonElement(row.payload).jsonObject)
                }
                val pushed: JsonObject = Supabase.http.post("$base/push") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header("apikey", com.linkvault.BuildConfig.SUPABASE_PUBLISHABLE_KEY)
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("operations", json.parseToJsonElement(json.encodeToString(operations))) })
                }.body()
                val results = pushed["data"]?.jsonObject?.get("results")?.jsonArray ?: return Result.retry()
                val byId = results.associateBy { it.jsonObject["operation_id"]?.jsonPrimitive?.contentOrNull }
                pending.forEach { row ->
                    val result = byId[row.operationId]?.jsonObject
                    if (result?.get("success")?.jsonPrimitive?.booleanOrNull == true) {
                        dao.acknowledge(row.operationId)
                    } else {
                        val error = result?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                            ?: if (result?.get("conflict")?.jsonPrimitive?.booleanOrNull == true) "Conflict requires resolution" else "Sync rejected operation"
                        dao.recordFailure(row.operationId, error)
                    }
                }
            }
            pullChanges(userId, token, dao)
            // Permanent validation errors/conflicts remain in the outbox for visibility and explicit resolution.
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private suspend fun pullChanges(userId: String, token: String, dao: LocalVaultDao) {
        var state = dao.syncState(userId) ?: SyncStateEntity(userId)
        var cursor = if (state.fullSyncComplete) state.cursor else null
        var pages = 0
        var continuePull: Boolean
        do {
            val pulled: JsonObject = Supabase.http.get("$base/pull") {
                header(HttpHeaders.Authorization, "Bearer $token")
                header("apikey", com.linkvault.BuildConfig.SUPABASE_PUBLISHABLE_KEY)
                parameter("limit", "250")
                cursor?.let { parameter("cursor", it) }
            }.body()
            val data = pulled["data"]?.jsonObject ?: error("Sync response missing data")
            val full = data["full_sync"]?.jsonPrimitive?.booleanOrNull == true
            if (full) {
                dao.clearRecordType(userId, "item")
                dao.clearRecordType(userId, "category")
                dao.clearRecordType(userId, "tag")
                dao.clearRecordType(userId, "collection")
            }
            saveArray(userId, "item", data["items"]?.jsonArray, dao)
            saveArray(userId, "category", data["categories"]?.jsonArray, dao)
            saveArray(userId, "tag", data["tags"]?.jsonArray, dao)
            saveArray(userId, "collection", data["collections"]?.jsonArray, dao)

            if (!full) {
                data["changes"]?.jsonArray?.forEach { change ->
                    val c = change.jsonObject
                    if (c["operation"]?.jsonPrimitive?.contentOrNull == "delete") {
                        val type = c["entity_type"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                        val id = c["entity_id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                        dao.markDeleted(userId, type, id)
                    }
                }
            }
            cursor = data["cursor"]?.jsonPrimitive?.contentOrNull ?: state.cursor
            val hasMore = data["has_more"]?.jsonPrimitive?.booleanOrNull == true
            val truncated = data["truncated"]?.jsonPrimitive?.booleanOrNull == true
            state = SyncStateEntity(userId, cursor ?: state.cursor, System.currentTimeMillis(), true)
            dao.saveSyncState(state)
            pages++
            continuePull = if (full) true else hasMore
            if (truncated) error("Initial sync is larger than the current snapshot page; refusing to advance past missing records")
        } while (continuePull && pages < 20)
    }

    private suspend fun saveArray(userId: String, type: String, array: JsonArray?, dao: LocalVaultDao) {
        if (array.isNullOrEmpty()) return
        dao.putRecords(array.mapNotNull { value ->
            val obj = value.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            VaultRecordEntity(userId, type, id, value.toString(), false, System.currentTimeMillis())
        })
    }
}

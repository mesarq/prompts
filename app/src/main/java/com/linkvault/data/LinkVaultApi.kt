package com.linkvault.data

import com.linkvault.BuildConfig
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

private val apiBase = "${BuildConfig.SUPABASE_URL}/functions/v1/linkvault-api"
private val apiJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable data class LinkItem(
    val id:String, val type:String="link", val title:String?=null, val url:String?=null,
    val description:String?=null, val content:String?=null, val category_id:String?=null,
    val collection_id:String?=null, val is_favorite:Boolean=false, val site_name:String?=null,
    val thumbnail_url:String?=null, val favicon_url:String?=null, val metadata_status:String?=null,
    val created_at:String?=null, val deleted_at:String?=null, val parent_item_id:String?=null, val item_tags:List<ItemTag>?=null
)
@Serializable data class ItemTag(val tag_id:String,val tags:Tag?=null)
@Serializable data class Category(val id:String,val name:String,val color:String?=null,val is_system:Boolean=false)
@Serializable data class Tag(val id:String,val name:String,val color:String?=null)
@Serializable data class VaultCollection(val id:String,val name:String,val description:String?=null,val color:String?=null)

class LinkVaultApi {
    private suspend fun token():String=Supabase.accessToken() ?: error("Please sign in again.")
    private fun errorMessage(p:JsonObject,f:String)=p["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: f
    private suspend fun request(method:HttpMethod,path:String,params:Map<String,String> = emptyMap(),body:JsonObject?=null):JsonObject {
        val response=Supabase.http.request {
            url { takeFrom("$apiBase$path") }
            this.method=method; header(HttpHeaders.Authorization,"Bearer ${token()}")
            params.forEach{(k,v)->parameter(k,v)}
            if(body!=null){contentType(ContentType.Application.Json);setBody(body.toString())}
        }
        val payload:JsonObject=response.body()
        if(!response.status.isSuccess()) throw IllegalStateException(errorMessage(payload,"Request failed (${response.status.value})."))
        return payload
    }
    private fun obj(block:JsonObjectBuilder.()->Unit)=buildJsonObject(block)
    private inline fun <reified T> array(p:JsonObject):List<T> = p["data"]?.let{apiJson.decodeFromJsonElement<List<T>>(it)} ?: emptyList()
    private inline fun <reified T> one(p:JsonObject):T=apiJson.decodeFromJsonElement(p["data"] ?: throw IllegalStateException("Missing response data"))
    suspend fun listItems(q:String="",category:String?=null,favorite:Boolean=false,type:String?=null,collection:String?=null,trashed:Boolean=false,limit:Int?=null,offset:Int?=null):List<LinkItem>{
        if(trashed)return array(request(HttpMethod.Get,"/items/trash"))
        val p=mutableMapOf<String,String>();if(q.isNotBlank())p["q"]=q;category?.let{p["category_id"]=it}
        if(favorite)p["favorite"]="true";type?.let{p["type"]=it};collection?.let{p["collection_id"]=it}
        limit?.let{p["limit"]=it.toString()};offset?.let{p["offset"]=it.toString()}
        return array(request(HttpMethod.Get,"/items",p))
    }
    suspend fun saveLink(url:String,categoryId:String?=null,collectionId:String?=null,tags:List<String> = emptyList())=one<LinkItem>(request(HttpMethod.Post,"/items",body=obj{
        put("type","link");put("url",url);categoryId?.let{put("category_id",it)};collectionId?.let{put("collection_id",it)}
        put("tags",JsonArray(tags.map{JsonPrimitive(it)}))
    }))
    suspend fun saveNote(title:String,content:String,categoryId:String?=null,tags:List<String> = emptyList(),parentId:String?=null)=one<LinkItem>(request(HttpMethod.Post,"/items",body=obj{
        put("type","note");put("title",title);put("content",content);categoryId?.let{put("category_id",it)}
        parentId?.let{put("parent_item_id",it)};put("tags",JsonArray(tags.map{JsonPrimitive(it)}))
    }))
    suspend fun updateItem(id:String,values:JsonObject)=one<LinkItem>(request(HttpMethod.Patch,"/items/$id",body=values))
    suspend fun deleteItem(id:String)=request(HttpMethod.Delete,"/items/$id")
    suspend fun deleteForever(id:String)=request(HttpMethod.Delete,"/items/$id/permanent")
    suspend fun restoreItem(id:String)=updateItem(id,obj{put("deleted_at",JsonNull)})
    suspend fun categories()=array<Category>(request(HttpMethod.Get,"/categories"))
    suspend fun createCategory(name:String)=one<Category>(request(HttpMethod.Post,"/categories",body=obj{put("name",name)}))
    suspend fun updateCategory(id:String,name:String)=one<Category>(request(HttpMethod.Patch,"/categories/$id",body=obj{put("name",name)}))
    suspend fun deleteCategory(id:String)=request(HttpMethod.Delete,"/categories/$id")
    suspend fun tags()=array<Tag>(request(HttpMethod.Get,"/tags"))
    suspend fun createTag(name:String)=one<Tag>(request(HttpMethod.Post,"/tags",body=obj{put("name",name)}))
    suspend fun deleteTag(id:String)=request(HttpMethod.Delete,"/tags/$id")
    suspend fun collections()=array<VaultCollection>(request(HttpMethod.Get,"/collections"))
    suspend fun createCollection(name:String,description:String="")=one<VaultCollection>(request(HttpMethod.Post,"/collections",body=obj{put("name",name);put("description",description)}))
    suspend fun updateCollection(id:String,name:String,description:String="")=one<VaultCollection>(request(HttpMethod.Patch,"/collections/$id",body=obj{put("name",name);put("description",description)}))
    suspend fun deleteCollection(id:String)=request(HttpMethod.Delete,"/collections/$id")
}

package com.linkvault

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import coil.compose.AsyncImage
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.linkvault.data.*
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.JsonNull
import java.util.UUID

private val Ink = Color(0xFF0A0B10)
private val Panel = Color(0xFF151722)
private val PanelRaised = Color(0xFF1D2030)
private val Accent = Color(0xFFB9A7FF)
private val Mint = Color(0xFF8CE5D0)
private val Muted = Color(0xFFA5A8B8)

class MainActivity : ComponentActivity() {
    private var sharedText by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedText = readSharedText(intent)
        setContent {
            LinkVaultTheme {
                var signedIn by remember { mutableStateOf(false) }
                var authReady by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    runCatching { Supabase.client.auth.awaitInitialization(); signedIn = Supabase.client.auth.currentSessionOrNull() != null }
                    authReady = true
                }
                when {
                    !authReady -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
                    signedIn -> VaultScreen(sharedText, { sharedText = null }, {
                        lifecycleScope.launch {
                            Supabase.client.auth.signOut()
                            Supabase.client.auth.currentSessionOrNull()?.user?.id?.let { OfflineStore(this@MainActivity).apply { clearUser(it); close() } }
                            signedIn = false
                        }
                    })
                    else -> AuthScreen { signedIn = true }
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); sharedText = readSharedText(intent)
    }
    private fun readSharedText(intent: Intent?): String? {
        if (intent == null) return null
        val values = mutableListOf<String>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let(values::add)
                intent.getCharSequenceExtra(Intent.EXTRA_HTML_TEXT)?.toString()?.let { values.add(android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_COMPACT).toString()) }
                intent.dataString?.let(values::add)
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { values.add(readSharedUri(it)) }
                intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i).text?.toString()?.let(values::add)
                    clip.getItemAt(i).uri?.let { values.add(readSharedUri(it)) }
                } }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)?.forEach { values.add(it.toString()) }
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach { values.add(readSharedUri(it)) }
                intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i).text?.toString()?.let(values::add)
                    clip.getItemAt(i).uri?.let { values.add(readSharedUri(it)) }
                } }
            }
            Intent.ACTION_VIEW -> intent.dataString?.let(values::add)
        }
        return values.firstNotNullOfOrNull(::normalizeLink)
    }

    private fun readSharedUri(uri: Uri): String {
        val mime = contentResolver.getType(uri).orEmpty()
        if (!mime.startsWith("text/")) return uri.toString()
        return runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText().take(200_000) } ?: uri.toString()
        }.getOrDefault(uri.toString())
    }
}

private fun normalizeLink(raw: String): String? {
    val text = raw.trim()
    val extracted = Regex("https?://[^\\s<>\\\"']+", RegexOption.IGNORE_CASE).find(text)?.value?.trimEnd('.', ',', ')', ']', '!', '?')
    val value = extracted ?: if (text.matches(Regex("^[A-Za-z0-9.-]+\\.[A-Za-z]{2,}(/.*)?$"))) "https://$text" else return null
    val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
    return value.takeIf { uri.scheme in listOf("http","https") && !uri.host.isNullOrBlank() }
}

@Composable
private fun LinkVaultTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Accent, onPrimary = Color(0xFF21163F),
        secondary = Mint, onSecondary = Color(0xFF082D27),
        tertiary = Color(0xFFFFC58A), background = Ink,
        surface = Panel, surfaceVariant = PanelRaised,
        onBackground = Color(0xFFF5F2FF), onSurface = Color(0xFFF5F2FF),
        onSurfaceVariant = Muted, outline = Color(0xFF424456)
    )
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(10.dp),
        small = RoundedCornerShape(14.dp),
        medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(28.dp),
        extraLarge = RoundedCornerShape(36.dp)
    )
    MaterialTheme(colorScheme = scheme, shapes = shapes, typography = Typography()) {
        Surface(Modifier.fillMaxSize(), color = Ink, contentColor = Color(0xFFF5F2FF)) { content() }
    }
}

@Composable
private fun AuthScreen(onSignedIn: () -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }
    var create by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(PanelRaised, Ink, Ink))).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Surface(shape=RoundedCornerShape(20.dp),color=Accent.copy(alpha=.14f)) { Icon(Icons.Default.Bookmarks, null, tint = Accent, modifier = Modifier.padding(14.dp).size(34.dp)) }
        Spacer(Modifier.height(18.dp))
        Text("Your links, safely kept.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Sign in to your private LinkVault.", color = Muted)
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(18.dp))
        Button(onClick = { scope.launch {
            busy = true; error = null
            try {
                if (create) Supabase.client.auth.signUpWith(Email) { this.email=email.trim(); this.password=password }
                else Supabase.client.auth.signInWith(Email) { this.email=email.trim(); this.password=password }
                Supabase.client.auth.awaitInitialization()
                if (Supabase.client.auth.currentSessionOrNull()!=null) onSignedIn() else error="Check your email to confirm your account."
            } catch(e:Exception) { error=e.message ?: "Authentication failed." } finally { busy=false }
        } }, enabled=!busy && email.isNotBlank() && password.length>=6, modifier=Modifier.fillMaxWidth()) {
            Text(if(busy) "Please wait…" else if(create) "Create account" else "Sign in")
        }
        TextButton(onClick={ create=!create; error=null }) { Text(if(create) "Already registered? Sign in" else "Create an account") }
    }
}

private enum class Page { Home, Categories, Notes, Favorites, More }

@Composable
private fun VaultScreen(sharedText: String?, onSharedConsumed: () -> Unit, onSignOut: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val api = remember { LinkVaultApi() }
    val offlineStore = remember { OfflineStore(context) }
    val localDao = remember { LocalDatabase.get(context).vaultDao() }
    val cacheUserId = Supabase.client.auth.currentSessionOrNull()?.user?.id ?: "signed-out"
    var page by remember { mutableStateOf(Page.Home) }
    var items by remember { mutableStateOf<List<LinkItem>>(emptyList()) }
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var tags by remember { mutableStateOf<List<Tag>>(emptyList()) }
    var collections by remember { mutableStateOf<List<VaultCollection>>(emptyList()) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var selectedCollection by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var offline by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var addDialog by remember { mutableStateOf(false) }
    var categoryDialog by remember { mutableStateOf(false) }
    var editItem by remember { mutableStateOf<LinkItem?>(null) }
    var readNote by remember { mutableStateOf<LinkItem?>(null) }
    var categoryItem by remember { mutableStateOf<LinkItem?>(null) }
    var menuItem by remember { mutableStateOf<LinkItem?>(null) }
    var detailItem by remember { mutableStateOf<LinkItem?>(null) }
    var linkedNotes by remember { mutableStateOf<List<LinkItem>>(emptyList()) }
    var addCollectionDialog by remember { mutableStateOf(false) }
    var collectionToRename by remember { mutableStateOf<VaultCollection?>(null) }
    var addTagDialog by remember { mutableStateOf(false) }
    var trashMode by remember { mutableStateOf(false) }
    var sharedPrefill by remember { mutableStateOf("") }
    var noteParentId by remember { mutableStateOf<String?>(null) }
    var trashed by remember { mutableStateOf<List<LinkItem>>(emptyList()) }
    var snackbar = remember { SnackbarHostState() }

    suspend fun queueOfflineItem(type: String, title: String, value: String, categoryId: String?, collectionId: String?, parentId: String?) : LinkItem {
        val id = UUID.randomUUID().toString()
        val item = if (type == "note") LinkItem(id=id, type="note", title=title, content=value, category_id=categoryId, parent_item_id=parentId)
            else LinkItem(id=id, type="link", title=title.ifBlank { value }, url=value, category_id=categoryId, collection_id=collectionId)
        val payload = buildJsonObject {
            put("type", type); put("title", title); categoryId?.let { put("category_id", it) }
            if (type == "note") { put("content", value); parentId?.let { put("parent_item_id", it) } }
            else { put("url", value); collectionId?.let { put("collection_id", it) } }
        }
        localDao.putRecord(VaultRecordEntity(cacheUserId, "item", id, Json.encodeToString(item)))
        localDao.enqueue(SyncOutboxEntity(UUID.randomUUID().toString(), cacheUserId, "item", "create", id, null, payload.toString()))
        offline = true
        message = "Saved on this device. It will sync when you're back online."
        SyncScheduler.enqueue(context)
        return item
    }

    suspend fun updateItemResilient(item: LinkItem, values: kotlinx.serialization.json.JsonObject) {
        try {
            api.updateItem(item.id, values)
        } catch (e: Exception) {
            val updated = item.copy(
                title = values["title"]?.jsonPrimitive?.contentOrNull ?: item.title,
                description = values["description"]?.jsonPrimitive?.contentOrNull ?: item.description,
                content = values["content"]?.jsonPrimitive?.contentOrNull ?: item.content,
                category_id = if (values.containsKey("category_id")) values["category_id"]?.jsonPrimitive?.contentOrNull else item.category_id,
                collection_id = values["collection_id"]?.jsonPrimitive?.contentOrNull ?: item.collection_id,
                is_favorite = values["is_favorite"]?.jsonPrimitive?.booleanOrNull ?: item.is_favorite
            )
            localDao.putRecord(VaultRecordEntity(cacheUserId, "item", item.id, Json.encodeToString(updated)))
            localDao.enqueue(SyncOutboxEntity(UUID.randomUUID().toString(), cacheUserId, "item", "update", item.id, null, values.toString()))
            offline = true
            message = "Change saved offline and queued to sync."
            SyncScheduler.enqueue(context)
        }
    }

    suspend fun deleteItemResilient(item: LinkItem, restore: Boolean = false) {
        try { if (restore) deleteItemResilient(item, restore=true) else api.deleteItem(item.id) }
        catch (e: Exception) {
            val timestamp = if (restore) JsonNull else kotlinx.serialization.json.JsonPrimitive(java.time.Instant.now().toString())
            val values = buildJsonObject { put("deleted_at", timestamp) }
            val updated = item.copy(deleted_at = if (restore) null else java.time.Instant.now().toString())
            localDao.putRecord(VaultRecordEntity(cacheUserId, "item", item.id, Json.encodeToString(updated), !restore))
            localDao.enqueue(SyncOutboxEntity(UUID.randomUUID().toString(), cacheUserId, "item", if (restore) "restore" else "delete", item.id, null, values.toString()))
            offline = true
            message = "Change saved offline and queued to sync."
            SyncScheduler.enqueue(context)
        }
    }

    suspend fun reload() {
        busy = true
        try {
            items = api.listItems(query, selectedCategory, page==Page.Favorites, if(page==Page.Notes) "note" else null, selectedCollection, trashMode)
            categories = api.categories(); tags = api.tags(); collections = api.collections()
            // Persist the latest server snapshot locally before displaying it as synchronized.
            localDao.putRecords(items.map { VaultRecordEntity(cacheUserId, "item", it.id, Json.encodeToString(it), it.deleted_at != null) } +
                categories.map { VaultRecordEntity(cacheUserId, "category", it.id, Json.encodeToString(it)) } +
                tags.map { VaultRecordEntity(cacheUserId, "tag", it.id, Json.encodeToString(it)) } +
                collections.map { VaultRecordEntity(cacheUserId, "collection", it.id, Json.encodeToString(it)) })
            offline = false
            offlineStore.saveSnapshot(cacheUserId, Json.encodeToString(items))
            SyncScheduler.enqueue(context)
            if (trashMode) trashed = items
            message = null
        } catch (e:Exception) {
            val localItems = runCatching { localDao.records(cacheUserId, "item").map { Json.decodeFromString<LinkItem>(it.payload) } }.getOrDefault(emptyList())
            val cached = offlineStore.loadSnapshot(cacheUserId)
            items = if (localItems.isNotEmpty()) localItems else cached?.let { runCatching { Json.decodeFromString<List<LinkItem>>(it) }.getOrDefault(emptyList()) } ?: emptyList()
            categories = runCatching { localDao.records(cacheUserId, "category").map { Json.decodeFromString<Category>(it.payload) } }.getOrDefault(emptyList())
            tags = runCatching { localDao.records(cacheUserId, "tag").map { Json.decodeFromString<Tag>(it.payload) } }.getOrDefault(emptyList())
            collections = runCatching { localDao.records(cacheUserId, "collection").map { Json.decodeFromString<VaultCollection>(it.payload) } }.getOrDefault(emptyList())
            offline = true
            if (trashMode) trashed = items.filter { it.deleted_at != null }
            message = e.message ?: "Couldn't refresh. Showing saved offline copy."
        } finally { busy=false }
    }
    LaunchedEffect(page, selectedCategory, selectedCollection, query, trashMode) { delay(300); reload() }
    LaunchedEffect(sharedText) {
        if (!sharedText.isNullOrBlank()) { val url=normalizeLink(sharedText); if(url!=null) { sharedPrefill=url; addDialog=true; message="Shared link received."; }; onSharedConsumed() }
    }

    var importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("Cannot read backup file")
                val backup = Json.decodeFromString<List<LinkItem>>(raw)
                backup.forEach { saved ->
                    if (saved.type == "note") api.saveNote(saved.title ?: "Imported note", saved.content ?: "", saved.category_id)
                    else saved.url?.let { api.saveLink(it, saved.category_id, saved.collection_id) }
                }
                reload()
                message = "Imported ${backup.size} items."
            }.onFailure { message = it.message ?: "Backup import failed." }
        }
    }

    fun launchReload(block: suspend () -> Unit) { scope.launch { busy=true; try { block(); reload() } catch(e:Exception) { message=e.message ?: "Action failed." } finally { busy=false } } }

    Scaffold(
        containerColor=Ink,
        snackbarHost={ SnackbarHost(snackbar) },
        floatingActionButton={
            FloatingActionButton(onClick={ addDialog=true }, containerColor=Accent, contentColor=Ink) { Icon(Icons.Default.Add, "Add") }
        },
        bottomBar={
            NavigationBar(containerColor=Panel, tonalElevation=8.dp) {
                NavigationBarItem(selected=page==Page.Home, onClick={page=Page.Home; trashMode=false}, icon={Icon(Icons.Default.Home,null)}, label={Text("Home")})
                NavigationBarItem(selected=page==Page.Categories, onClick={page=Page.Categories; trashMode=false}, icon={Icon(Icons.Default.Folder,null)}, label={Text("Categories")})
                NavigationBarItem(selected=page==Page.Notes, onClick={page=Page.Notes; trashMode=false}, icon={Icon(Icons.Default.Description,null)}, label={Text("Notes")})
                NavigationBarItem(selected=page==Page.Favorites, onClick={page=Page.Favorites; trashMode=false}, icon={Icon(Icons.Default.Favorite,null)}, label={Text("Favorites")})
                NavigationBarItem(selected=page==Page.More, onClick={page=Page.More; trashMode=false}, icon={Icon(Icons.Default.MoreHoriz,null)}, label={Text("More")})
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=18.dp)) {
            Surface(
                modifier=Modifier.fillMaxWidth().padding(top=12.dp,bottom=14.dp),
                shape=RoundedCornerShape(28.dp),
                color=PanelRaised
            ) {
                Row(Modifier.background(Brush.horizontalGradient(listOf(Color(0xFF27213F), PanelRaised, PanelRaised))).padding(horizontal=18.dp, vertical=18.dp),
                    verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("LinkVault", style=MaterialTheme.typography.headlineLarge, fontWeight=FontWeight.ExtraBold)
                        Text(if(offline) "Offline · showing cached library" else "Your personal corner of the internet.", color=Muted, style=MaterialTheme.typography.bodyMedium)
                    }
                    IconButton(onClick=onSignOut) { Icon(Icons.Default.AccountCircle, "Account", tint=Accent) }
                }
            }
            if(page==Page.Home || page==Page.Favorites || page==Page.Notes || trashMode) {
                OutlinedTextField(query,{query=it},modifier=Modifier.fillMaxWidth(),singleLine=true, shape=RoundedCornerShape(22.dp),
                    leadingIcon={Icon(Icons.Default.Search,null)},placeholder={Text("Search your vault…")})
                Spacer(Modifier.height(8.dp))
                if(page==Page.Home) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected=selectedCategory==null,onClick={selectedCategory=null},label={Text("All")})
                    categories.forEach { c -> FilterChip(selected=selectedCategory==c.id,onClick={selectedCategory=if(selectedCategory==c.id)null else c.id},label={Text(c.name)}) }
                }
            }
            message?.let { Text(it,color=if(offline) Color(0xFFFFC46B) else Muted,modifier=Modifier.padding(vertical=5.dp),style=MaterialTheme.typography.bodySmall) }
            when {
                page==Page.Categories -> CategoriesPage(categories, items, onAdd={categoryDialog=true},
                    onSelect={selectedCategory=it; page=Page.Home},
                    onRename={cat,name->launchReload { api.updateCategory(cat.id,name) }},
                    onDelete={cat->launchReload { api.deleteCategory(cat.id) }})
                page==Page.More -> MorePage(tags,collections,trashed,trashMode,
                    onCreateCollection={addCollectionDialog=true}, onCreateTag={addTagDialog=true},
                    onRenameCollection={collectionToRename=it}, onDeleteCollection={col->launchReload { api.deleteCollection(col.id) }},
                    onCollection={id->selectedCollection=id;page=Page.Home},
                    onTrash={trashMode=true;page=Page.More},
                    onRestore={item->launchReload { api.restoreItem(item.id) }},
                    onDeleteForever={item->launchReload { api.deleteForever(item.id) }},
                    onExport={
                        val text=Json.encodeToString(items)
                        val send=Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT,text)
                        context.startActivity(Intent.createChooser(send,"Export LinkVault backup"))
                    },
                    onDeleteTag={tag->launchReload { api.deleteTag(tag.id) }}, onImport={ importLauncher.launch("application/json") })
                else -> {
                    Row(Modifier.fillMaxWidth().padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text(when { trashMode->"Recently deleted"; page==Page.Notes->"Your notes";page==Page.Favorites->"Favorites";else->"Recently saved" },fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                        if(busy) CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)
                        else Text("${items.size} items",color=Muted,style=MaterialTheme.typography.labelMedium)
                    }
                    if(items.isEmpty() && !busy) EmptyState(if(page==Page.Notes) "No notes yet" else "Your vault is ready", if(page==Page.Notes) "Create your first note." else "Save something worth keeping.")
                    else LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=20.dp)) {
                        items(items,key={it.id}) { item ->
                            AnimatedVisibility(visible=true, enter=fadeIn() + slideInVertically(initialOffsetY={it/5})) {
                            ItemCard(item, categories, collections, onMenu={menuItem=item}, onFavorite={launchReload { updateItemResilient(item,buildJsonObject { put("is_favorite",!item.is_favorite) }) }}, onOpen={
                                if(item.type=="note") readNote=item
                                else {
                                    detailItem=item
                                    scope.launch {
                                        linkedNotes = runCatching { api.listItems(type="note").filter { it.parent_item_id==item.id } }.getOrDefault(emptyList())
                                    }
                                }
                            })
                        }
                    }
                }
            }
        }
    }
    }

    if(addDialog) AddItemDialog(categories,collections,initialUrl=sharedPrefill,parentId=noteParentId,onDismiss={addDialog=false; noteParentId=null},onSave={type,title,value,cat,col,tagnames,parentId->
        launchReload {
            val tagIds=runCatching { tagnames.split(",").map{it.trim()}.filter{it.isNotEmpty()}.map { name ->
                tags.firstOrNull{it.name.equals(name,true)}?.id ?: api.createTag(name).id
            } }.getOrDefault(emptyList())
            val saved = try {
                if(type=="note") api.saveNote(title,value,cat,tagIds,parentId)
                else api.saveLink(normalizeLink(value) ?: error("Enter a valid http or https URL."),cat,col,tagIds)
            } catch (e: Exception) {
                val normalized = if(type=="link") normalizeLink(value) ?: error("Enter a valid http or https URL.") else value
                queueOfflineItem(type,title,normalized,cat,col,parentId)
            }
            items = (listOf(saved) + items).distinctBy { it.id }
            addDialog=false
        }
    })
    if(categoryDialog) NameDialog("New category","Category name",onDismiss={categoryDialog=false},onSave={name->launchReload { api.createCategory(name); categoryDialog=false }})
    if(addCollectionDialog) NameDialog("New collection","Collection name",onDismiss={addCollectionDialog=false},onSave={name->launchReload { api.createCollection(name); addCollectionDialog=false }})
    collectionToRename?.let { col -> NameDialog("Rename collection","Collection name",col.name,{collectionToRename=null},{name->launchReload { api.updateCollection(col.id,name,col.description ?: "");collectionToRename=null }}) }
    if(addTagDialog) NameDialog("New tag","Tag name",onDismiss={addTagDialog=false},onSave={name->launchReload { api.createTag(name); addTagDialog=false }})
    editItem?.let { item -> EditItemDialog(item,categories,onDismiss={editItem=null},onSave={title,desc,content,cat->
        launchReload { updateItemResilient(item,buildJsonObject {
            put("title",title);put("description",desc);put("content",content);cat?.let{put("category_id",it)}
        });editItem=null }
    }) }
    categoryItem?.let { item -> CategoryPicker(categories,item.category_id,onDismiss={categoryItem=null},onSelect={id->
        launchReload { updateItemResilient(item,buildJsonObject { if(id==null) put("category_id",JsonNull) else put("category_id",id) }); categoryItem=null }
    }) }
    readNote?.let { note ->
        AlertDialog(
            onDismissRequest={readNote=null},
            title={Text(note.title?.takeIf { it.isNotBlank() } ?: "Note", fontWeight=FontWeight.Bold)},
            text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(note.content?.takeIf { it.isNotBlank() } ?: "This note is empty.", style=MaterialTheme.typography.bodyLarge)
                note.parent_item_id?.let { parentId ->
                    items.firstOrNull { it.id==parentId }?.url?.let { url ->
                        Text("Attached to: $url",color=Accent,style=MaterialTheme.typography.bodySmall)
                    }
                }
            }},
            confirmButton={TextButton(onClick={editItem=note;readNote=null}) { Text("Edit") }},
            dismissButton={TextButton(onClick={readNote=null}) { Text("Close") }}
        )
    }
    detailItem?.let { item ->
        AlertDialog(
            onDismissRequest={detailItem=null},
            title={Text(item.title ?: item.site_name ?: "Saved link", fontWeight=FontWeight.Bold)},
            text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                item.url?.takeIf { it.isNotBlank() }?.let { url ->
                    Text(
                        text = url,
                        color = Accent,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().clickable { runCatching { uriHandler.openUri(url) } }
                    )
                }
                item.description?.takeIf{it.isNotBlank()}?.let { Text(it,color=Muted) }
                Divider(color=Muted.copy(alpha=.2f))
                Text("Notes about this link",fontWeight=FontWeight.SemiBold)
                if(linkedNotes.isEmpty()) Text("No notes yet. Add one from here or the link menu.",color=Muted)
                linkedNotes.forEach { note ->
                    Card(colors=CardDefaults.cardColors(containerColor=Ink),modifier=Modifier.fillMaxWidth().clickable { readNote=note }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(note.title?.takeIf{it.isNotBlank()} ?: "Untitled note",fontWeight=FontWeight.SemiBold)
                            Text(note.content ?: "",color=Muted,maxLines=5)
                            Text("Tap to read or edit",color=Accent,style=MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }},
            confirmButton={TextButton(onClick={detailItem=null}){Text("Close")}},
            dismissButton={TextButton(onClick={noteParentId=item.id;addDialog=true;detailItem=null}){Text("Add note")}}
        )
    }
    menuItem?.let { item ->
        AlertDialog(onDismissRequest={menuItem=null},title={Text(item.title ?: item.url ?: "Saved item")},
            text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(onClick={categoryItem=item;menuItem=null}) { Text("Change category") }
                TextButton(onClick={editItem=item;menuItem=null}) { Text("Edit details / note") }
                if(item.type=="link") TextButton(onClick={noteParentId=item.id; addDialog=true; menuItem=null}) { Text("Add a note about this link") }
                TextButton(onClick={launchReload { api.updateItem(item.id,buildJsonObject { put("is_favorite",!item.is_favorite) });menuItem=null }}) { Text(if(item.is_favorite)"Remove favorite" else "Add to favorites") }
                TextButton(onClick={launchReload {
                    deleteItemResilient(item);menuItem=null
                    val result=snackbar.showSnackbar("Moved to Recently deleted",actionLabel="Undo")
                    if(result==SnackbarResult.ActionPerformed) api.restoreItem(item.id)
                    reload()
                }}) { Text("Move to Recently deleted",color=Color(0xFFFF8A80)) }
                if(trashMode) TextButton(onClick={launchReload { api.restoreItem(item.id);menuItem=null }}) { Text("Restore") }
            }},confirmButton={TextButton(onClick={menuItem=null}){Text("Close")}})
    }
}

@Composable
private fun ItemCard(item:LinkItem,categories:List<Category>,collections:List<VaultCollection>,onMenu:()->Unit,onFavorite:()->Unit,onOpen:()->Unit) {
    val category=categories.firstOrNull{it.id==item.category_id}?.name ?: "Uncategorized"
    Card(colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(26.dp),modifier=Modifier.fillMaxWidth().border(1.dp,Color.White.copy(alpha=.055f),RoundedCornerShape(26.dp)).animateContentSize().clickable{onOpen()}) {
        Column(Modifier.padding(14.dp)) {
            if(!item.thumbnail_url.isNullOrBlank()) {
                AsyncImage(model=item.thumbnail_url,contentDescription="Link preview",modifier=Modifier.fillMaxWidth().height(148.dp),contentScale=androidx.compose.ui.layout.ContentScale.Crop)
                Spacer(Modifier.height(10.dp))
            }
            Row(verticalAlignment=Alignment.CenterVertically) {
                Icon(if(item.type=="note") Icons.Default.Description else Icons.Default.Language,null,tint=Accent)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title?.takeIf{it.isNotBlank()} ?: item.site_name ?: item.url ?: "Untitled note",fontWeight=FontWeight.SemiBold,maxLines=2)
                    Text(item.site_name ?: item.url ?: "Personal note",color=Muted,style=MaterialTheme.typography.bodySmall,maxLines=1)
                }
                IconButton(onClick=onFavorite){Icon(if(item.is_favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,null,tint=if(item.is_favorite) Color(0xFFFF91B5) else Muted)}
                IconButton(onClick=onMenu){Icon(Icons.Default.MoreVert,null,tint=Muted)}
            }
            val summary=item.description ?: item.content
            if(!summary.isNullOrBlank()) Text(summary,color=Muted,maxLines=if(item.type=="note") 5 else 3,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(top=8.dp))
            if(item.type=="link") Text("Tap to preview · notes & details",color=Accent.copy(alpha=.9f),style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(top=6.dp))
            Row(Modifier.padding(top=10.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick={},label={Text(category)})
                item.collection_id?.let { id-> collections.firstOrNull{it.id==id}?.let { AssistChip(onClick={},label={Text(it.name)}) } }
                item.item_tags?.forEach { it.tags?.let { tag->AssistChip(onClick={},label={Text("#"+tag.name)}) } }
            }
        }
    }
}

@Composable
private fun CategoriesPage(categories:List<Category>,items:List<LinkItem>,onAdd:()->Unit,onSelect:(String)->Unit,onRename:(Category,String)->Unit,onDelete:(Category)->Unit) {
    var rename by remember { mutableStateOf<Category?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment=Alignment.CenterVertically) { Text("Organize your vault",fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f)); Button(onClick=onAdd){Text("Add")}}
        LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(vertical=12.dp)) {
            items(categories,key={it.id}) { c ->
                Card(colors=CardDefaults.cardColors(containerColor=Panel),modifier=Modifier.fillMaxWidth().clickable{onSelect(c.id)}) {
                    Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder,null,tint=Accent)
                        Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)){Text(c.name,fontWeight=FontWeight.Medium);Text(if(c.is_system)"Default category" else "Tap to filter",color=Muted,style=MaterialTheme.typography.bodySmall)}
                        if(!c.is_system) {
                            IconButton(onClick={rename=c}){Icon(Icons.Default.Edit,null)}
                            IconButton(onClick={onDelete(c)}){Icon(Icons.Default.Delete,null,tint=Color(0xFFFF8A80))}
                        }
                    }
                }
            }
        }
    }
    rename?.let { c->NameDialog("Rename category","Category name",c.name,{rename=null},{onRename(c,it);rename=null}) }
}

@Composable
private fun MorePage(tags:List<Tag>,collections:List<VaultCollection>,trashed:List<LinkItem>,trashMode:Boolean,onCreateCollection:()->Unit,onCreateTag:()->Unit,onRenameCollection:(VaultCollection)->Unit,onDeleteCollection:(VaultCollection)->Unit,onCollection:(String)->Unit,onTrash:()->Unit,onRestore:(LinkItem)->Unit,onDeleteForever:(LinkItem)->Unit,onExport:()->Unit,onDeleteTag:(Tag)->Unit,onImport:()->Unit) {
    Column(Modifier.fillMaxSize()) {
        Text("Your spaces",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
        FilledTonalButton(onClick=onCreateCollection,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp)) { Icon(Icons.Default.CreateNewFolder,null); Spacer(Modifier.width(8.dp)); Text("Create collection") }
        collections.forEach { c -> Card(colors=CardDefaults.cardColors(containerColor=Panel),modifier=Modifier.fillMaxWidth().padding(vertical=3.dp)) { Row(Modifier.clickable{onCollection(c.id)}.padding(start=14.dp,end=4.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically) { Icon(Icons.Default.CollectionsBookmark,null,tint=Accent); Column(Modifier.weight(1f).padding(start=12.dp)) { Text(c.name,fontWeight=FontWeight.Medium); Text(c.description ?: "Collection",color=Muted,style=MaterialTheme.typography.bodySmall) }; IconButton(onClick={onRenameCollection(c)}) { Icon(Icons.Default.Edit,"Rename collection") }; IconButton(onClick={onDeleteCollection(c)}) { Icon(Icons.Default.Delete,"Delete collection",tint=Color(0xFFFF8A80)) } } } }
        Divider(color=Muted.copy(alpha=.2f))
        Row(verticalAlignment=Alignment.CenterVertically){Text("Tags",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));TextButton(onClick=onCreateTag){Text("Add tag")}}
        tags.forEach { t->Row(verticalAlignment=Alignment.CenterVertically){Text("#${t.name}",modifier=Modifier.weight(1f));IconButton(onClick={onDeleteTag(t)}){Icon(Icons.Default.Close,null)}}}
        Divider(color=Muted.copy(alpha=.2f))
        TextButton(onClick=onExport){Icon(Icons.Default.FileDownload,null);Spacer(Modifier.width(8.dp));Text("Export backup (JSON)")}
        TextButton(onClick=onImport){Icon(Icons.Default.FileUpload,null);Spacer(Modifier.width(8.dp));Text("Import backup (JSON)")}
        TextButton(onClick=onTrash){Icon(Icons.Default.DeleteSweep,null);Spacer(Modifier.width(8.dp));Text("Recently deleted")}
        if(trashMode) {
            Text("Recently deleted",fontWeight=FontWeight.SemiBold)
            trashed.forEach { item->Row(verticalAlignment=Alignment.CenterVertically){Text(item.title ?: item.url ?: "Deleted item",modifier=Modifier.weight(1f));TextButton(onClick={onRestore(item)}){Text("Restore")};TextButton(onClick={onDeleteForever(item)}){Text("Delete forever")}}}
        }
    }
}

@Composable
private fun EmptyState(title:String,subtitle:String) {
    Box(Modifier.fillMaxWidth().padding(40.dp),contentAlignment=Alignment.Center) { Column(horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Default.Bookmarks,null,tint=Muted,modifier=Modifier.size(42.dp));Spacer(Modifier.height(12.dp));Text(title,fontWeight=FontWeight.SemiBold);Text(subtitle,color=Muted)} }
}

@Composable
private fun NameDialog(title:String,label:String,initial:String="",onDismiss:()->Unit,onSave:(String)->Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest=onDismiss,title={Text(title)},text={OutlinedTextField(value,{value=it},label={Text(label)},singleLine=true)},
        confirmButton={TextButton(onClick={onSave(value.trim())},enabled=value.isNotBlank()){Text("Save")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}

@Composable
private fun AddItemDialog(categories:List<Category>,collections:List<VaultCollection>,initialUrl:String,parentId:String?,onDismiss:()->Unit,onSave:(String,String,String,String?,String?,String,String?)->Unit) {
    var type by remember { mutableStateOf("link") }; var title by remember { mutableStateOf("") }
    var value by remember { mutableStateOf(initialUrl) }; var category by remember { mutableStateOf<String?>(null) }
    var collection by remember { mutableStateOf<String?>(null) }; var tags by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(type=="note")"New note" else "Save a link")},
        text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row { FilterChip(selected=type=="link",onClick={type="link"},label={Text("Link")});Spacer(Modifier.width(8.dp));FilterChip(selected=type=="note",onClick={type="note"},label={Text("Note")}) }
            OutlinedTextField(title,{title=it},label={Text("Title")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(value,{value=it},label={Text(if(type=="note")"Write your note" else "URL")},modifier=Modifier.fillMaxWidth(),minLines=if(type=="note")6 else 1,maxLines=if(type=="note")12 else 2)
            var expanded by remember { mutableStateOf(false) }
            Box { OutlinedButton(onClick={expanded=true}){Text(categories.firstOrNull{it.id==category}?.name ?: "Uncategorized")};DropdownMenu(expanded,{expanded=false}){categories.forEach{c->DropdownMenuItem(text={Text(c.name)},onClick={category=c.id;expanded=false})}}}
            if(type=="link" && collections.isNotEmpty()) {
                var exp by remember{mutableStateOf(false)}
                Box{OutlinedButton(onClick={exp=true}){Text(collections.firstOrNull{it.id==collection}?.name ?: "No collection")};DropdownMenu(exp, {exp=false}){collections.forEach{c->DropdownMenuItem(text={Text(c.name)},onClick={collection=c.id;exp=false})}}}
            }
            OutlinedTextField(tags,{tags=it},label={Text("Tags (comma separated)")},singleLine=true)
        }},
        confirmButton={TextButton(onClick={onSave(type,title,value,category,collection,tags,parentId)},enabled=value.isNotBlank()){Text("Save")}},
        dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}

@Composable
private fun EditItemDialog(item:LinkItem,categories:List<Category>,onDismiss:()->Unit,onSave:(String,String,String,String?)->Unit) {
    var title by remember{mutableStateOf(item.title ?: "")};var desc by remember{mutableStateOf(item.description ?: "")}
    var content by remember{mutableStateOf(item.content ?: "")};var cat by remember{mutableStateOf(item.category_id)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Edit item")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        OutlinedTextField(title,{title=it},label={Text("Title")})
        OutlinedTextField(desc,{desc=it},label={Text("Description")})
        if(item.type=="note") OutlinedTextField(content,{content=it},label={Text("Note")},minLines=6,maxLines=16)
        var exp by remember{mutableStateOf(false)}
        Box{OutlinedButton(onClick={exp=true}){Text(categories.firstOrNull{it.id==cat}?.name ?: "Category")};DropdownMenu(exp,{exp=false}){categories.forEach{c->DropdownMenuItem(text={Text(c.name)},onClick={cat=c.id;exp=false})}}}
    }},confirmButton={TextButton(onClick={onSave(title,desc,content,cat)} ){Text("Save")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}

@Composable
private fun CategoryPicker(categories:List<Category>,current:String?,onDismiss:()->Unit,onSelect:(String?)->Unit) {
    AlertDialog(onDismissRequest=onDismiss,title={Text("Change category")},text={Column{categories.forEach{c->TextButton(onClick={onSelect(c.id)}){Text(c.name)}}}},
        confirmButton={TextButton(onClick={onSelect(null)}){Text("Uncategorized")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}

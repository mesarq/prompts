package com.linkvault

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.linkvault.data.LinkItem
import com.linkvault.data.LinkVaultApi
import com.linkvault.data.Supabase
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import kotlinx.coroutines.launch

private val Ink = Color(0xFF0B0D12)
private val Panel = Color(0xFF151923)
private val Accent = Color(0xFF9B8CFF)
private val Muted = Color(0xFF9AA3B2)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LinkVaultTheme {
                var signedIn by remember { mutableStateOf(Supabase.client.auth.currentSessionOrNull() != null) }
                if (signedIn) VaultScreen(
                    onSignOut = { lifecycleScope.launch { Supabase.client.auth.signOut(); signedIn = false } }
                ) else AuthScreen(onSignedIn = { signedIn = true })
            }
        }
    }
}

@Composable
private fun LinkVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, secondary = Color(0xFF73D6C3),
            background = Ink, surface = Panel,
            onBackground = Color(0xFFF3F4F8), onSurface = Color(0xFFF3F4F8)
        ),
        content = content
    )
}

@Composable
private fun AuthScreen(onSignedIn: () -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var createAccount by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Ink).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Bookmark, null, tint = Accent, modifier = Modifier.size(42.dp))
        Spacer(Modifier.height(18.dp))
        Text("Your links, safely kept.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Sign in to your private LinkVault.", color = Muted)
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(18.dp))
        Button(onClick = {
            scope.launch {
                busy = true; error = null
                try {
                    if (createAccount) Supabase.client.auth.signUpWith(Email) {
                        this.email = email.trim(); this.password = password
                    } else Supabase.client.auth.signInWith(Email) {
                        this.email = email.trim(); this.password = password
                    }
                    if (Supabase.client.auth.currentSessionOrNull() != null) onSignedIn()
                    else error = "Check your email to confirm your account, then sign in."
                } catch (e: Exception) { error = e.message ?: "Authentication failed." }
                finally { busy = false }
            }
        }, enabled = !busy && email.isNotBlank() && password.length >= 6,
            modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(if (busy) "Please wait…" else if (createAccount) "Create account" else "Sign in")
        }
        TextButton(onClick = { createAccount = !createAccount; error = null }) {
            Text(if (createAccount) "Already have an account? Sign in" else "New to LinkVault? Create an account")
        }
    }
}

@Composable
private fun VaultScreen(onSignOut: () -> Unit) {
    val scope = rememberCoroutineScope()
    val api = remember { LinkVaultApi() }
    var saved by remember { mutableStateOf<List<LinkItem>>(emptyList()) }
    var search by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun refresh(query: String = search) {
        scope.launch {
            busy = true
            try { saved = api.listItems(query); message = null }
            catch (e: Exception) { message = e.message ?: "Unable to load your vault." }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { refresh("") }

    Column(Modifier.fillMaxSize().background(Ink).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("LinkVault", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("A quieter place for everything worth keeping.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onSignOut) { Text("Sign out") }
        }
        OutlinedTextField(search, { search = it; refresh(it) }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Search your vault") })
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(newUrl, { newUrl = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Paste a link to save") }, singleLine = true)
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = {
                scope.launch {
                    try { api.saveLink(newUrl.trim()); newUrl = ""; message = "Saved. Fetching page details…"; refresh("") }
                    catch (e: Exception) { message = e.message ?: "Could not save link." }
                }
            }, enabled = newUrl.startsWith("http://") || newUrl.startsWith("https://")) {
                Icon(Icons.Default.Add, "Save link")
            }
        }
        message?.let { Text(it, color = Muted, modifier = Modifier.padding(vertical = 8.dp)) }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("YOUR LIBRARY", color = Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text("${saved.size} items", color = Muted, style = MaterialTheme.typography.labelMedium)
        }
        if (!busy && saved.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Bookmark, null, tint = Muted, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Your vault is ready", fontWeight = FontWeight.SemiBold)
                    Text("Save a link above and it will appear here.", color = Muted)
                }
            }
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(saved, key = { it.id }) { item ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(item.title ?: item.url ?: "Saved item", fontWeight = FontWeight.SemiBold)
                        item.description?.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(5.dp)); Text(it, color = Muted, maxLines = 2)
                        }
                        item.url?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Accent, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                    }
                }
            }
        }
    }
}

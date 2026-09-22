package net.tspigot.radio.ui.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import net.tspigot.radio.AppSettings
import net.tspigot.radio.R
import net.tspigot.radio.ui.theme.TSpigotRadioTheme

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TSpigotRadioTheme {
                SettingsScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var chatName by remember { mutableStateOf(AppSettings.getChatName(context)) }
    var showDialog by remember { mutableStateOf(false) }
    var bookmarkOnLike by remember { mutableStateOf(AppSettings.getBookmarkOnLike(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            item { SettingsGroupHeader("Chat") }
            item {
                ListItem(
                    headlineContent = { Text("Set chat name") },
                    supportingContent = { Text(chatName.ifBlank { "Not set" }) },
                    trailingContent = {
                        IconButton(
                            enabled = chatName.isNotEmpty(),
                            onClick = {
                                AppSettings.setChatName(context, "")
                                chatName = ""
                            }
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.undo),
                                contentDescription = "Reset chat name"
                            )
                        }
                    },
                    modifier = Modifier.clickable { showDialog = true }
                )
            }

            item { SettingsGroupHeader("Interface") }
            item {
                ListItem(
                    headlineContent = { Text("Bookmark songs on like") },
                    trailingContent = {
                        Switch(
                            checked = bookmarkOnLike,
                            onCheckedChange = {
                                bookmarkOnLike = it
                                AppSettings.setBookmarkOnLike(context, it)
                            }
                        )
                    }
                )
            }
        }
    }

    if (showDialog) {
        var draft by remember { mutableStateOf(chatName) }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Set chat name") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    AppSettings.setChatName(context, draft)
                    chatName = draft.trim()
                    showDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SettingsGroupHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}
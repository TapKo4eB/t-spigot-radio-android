package net.tspigot.radio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import net.tspigot.radio.ui.theme.TSpigotRadioTheme

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Wrap in the same theme you use in MainActivity, e.g. RadioTheme { ... }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            TabRow(selectedTabIndex = 0) {
                Tab(selected = true, onClick = {}, text = { Text("Chat") })
            }

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
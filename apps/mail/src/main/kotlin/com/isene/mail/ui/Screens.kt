package com.isene.mail.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.isene.mail.viewmodel.MailViewModel
import com.isene.mail.viewmodel.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import uniffi.fe2o3_mobile_core.Mail

private val dayFmt = SimpleDateFormat("d MMM", Locale.getDefault())
private val fullFmt = SimpleDateFormat("EEE d MMM yyyy HH:mm", Locale.getDefault())

/** "Someone <s@x>" reads better as "Someone" in a narrow list. */
private fun shortFrom(from: String): String {
    val name = from.substringBefore('<').trim().trim('"')
    return name.ifEmpty { from.trim().trim('<', '>') }
}

@Composable
fun MailList(vm: MailViewModel, ui: UiState, modifier: Modifier = Modifier, onOpen: (Mail) -> Unit) {
    if (ui.mails.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                if (ui.accounts.isEmpty()) "Paste your accounts in Settings, then pull down to fetch."
                else "Nothing here. Tap ↻ to fetch.",
                Modifier.padding(32.dp),
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        return
    }
    LazyColumn(modifier.fillMaxSize()) {
        items(ui.mails, key = { it.messageId }) { m ->
            val read = m.messageId in ui.readIds
            Column(Modifier.fillMaxWidth().clickable { onOpen(m) }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        shortFrom(m.from),
                        Modifier.weight(1f),
                        fontSize = 14.sp,
                        maxLines = 1,
                        fontWeight = if (read) FontWeight.Normal else FontWeight.Bold,
                        color = if (read) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                    )
                    if (m.hasAttachments) {
                        Text("📎", fontSize = 12.sp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        if (m.date > 0) dayFmt.format(Date(m.date * 1000)) else "",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                Text(
                    m.subject,
                    fontSize = 13.sp,
                    maxLines = 2,
                    fontWeight = if (read) FontWeight.Normal else FontWeight.SemiBold,
                    color = if (read) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                )
            }
            Divider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageScreen(vm: MailViewModel, mail: Mail, onBack: () -> Unit) {
    val body by vm.body.collectAsState()
    val ui by vm.ui.collectAsState()
    LaunchedEffect(mail.messageId) { vm.open(mail) }
    val read = mail.messageId in ui.readIds

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { vm.closeBody(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                title = { Text(mail.subject, maxLines = 1, fontSize = 16.sp) },
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // The explicit act. Nothing else on this phone publishes
                // read state, so the laptop keeps its say.
                Button(onClick = { vm.setRead(mail, !read) }, modifier = Modifier.weight(1f)) {
                    Text(if (read) "Mark unread" else "Mark READ")
                }
                OutlinedButton(onClick = { vm.closeBody(); onBack() }) { Text("Close") }
            }
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            HeaderRow("From", mail.from)
            HeaderRow("To", mail.to)
            HeaderRow("Date", if (mail.date > 0) fullFmt.format(Date(mail.date * 1000)) else "—")
            HeaderRow("Account", mail.account)
            Spacer(Modifier.width(8.dp))
            Divider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(Modifier.width(8.dp))
            if (body == null) {
                CircularProgressIndicator(strokeWidth = 2.dp)
            } else {
                Text(body!!, fontSize = 14.sp, fontFamily = FontFamily.SansSerif)
            }
        }
    }
}

@Composable
private fun HeaderRow(key: String, value: String) {
    if (value.isBlank()) return
    Row {
        Text(
            key,
            Modifier.width(64.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(value, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
    }
}

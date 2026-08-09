package com.isene.mail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.isene.mail.data.Attachments
import com.isene.mail.viewmodel.MailViewModel
import com.isene.mail.viewmodel.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import uniffi.fe2o3_mobile_core.Message

private val LINK = androidx.compose.ui.graphics.Color(0xFF7FC8E8)

private val dayFmt = SimpleDateFormat("d MMM", Locale.getDefault())
private val fullFmt = SimpleDateFormat("EEE d MMM yyyy HH:mm", Locale.getDefault())

/** "Someone <s@x>" reads better as "Someone" in a narrow list. */
private fun shortFrom(from: String): String {
    val name = from.substringBefore('<').trim().trim('"')
    return name.ifEmpty { from.trim().trim('<', '>') }
}

@Composable
fun MailList(vm: MailViewModel, ui: UiState, modifier: Modifier = Modifier, onOpen: (Message) -> Unit) {
    if (ui.mails.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                when {
                    ui.accounts.isEmpty() -> "Add your accounts in Settings, then tap ↻."
                    ui.filter == "removed" -> "Nothing removed on this phone."
                    else -> "Nothing here. Tap ↻ to fetch."
                },
                Modifier.padding(32.dp),
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        return
    }
    // No navigationBarsPadding here: the Scaffold's content padding
    // already carries the system bar inset, and adding it again would
    // leave a second gap the height of the nav bar.
    LazyColumn(modifier.fillMaxSize()) {
        items(ui.mails, key = { it.messageId }) { m ->
            val read = m.messageId in ui.readIds
            // Swipe takes the message off this phone — or puts it back,
            // when this IS the list of the ones taken off. Nothing leaves
            // the device either way: the laptop never hears about it.
            val removedView = ui.filter == "removed"
            val swipe = rememberSwipeToDismissBoxState(
                confirmValueChange = { v ->
                    if (v == SwipeToDismissBoxValue.Settled) false
                    else { if (removedView) vm.restore(m) else vm.dismiss(m); true }
                },
            )
            SwipeToDismissBox(
                state = swipe,
                backgroundContent = {
                    Box(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Text(
                            if (removedView) "put back" else "remove here",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                },
            ) {
                Column(
                    Modifier.background(MaterialTheme.colorScheme.background)
                        .fillMaxWidth().clickable { onOpen(m) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
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
                        when (m.source) { "rss" -> "◆ "; "discord" -> "● "; else -> "" } + m.subject,
                        fontSize = 13.sp,
                        maxLines = 2,
                        fontWeight = if (read) FontWeight.Normal else FontWeight.SemiBold,
                        color = if (read) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageScreen(vm: MailViewModel, mail: Message, onBack: () -> Unit) {
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
            // Scaffold insets its content but not its bars, so this is
            // what keeps the buttons clear of the system navigation.
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // The explicit act. Nothing else on this phone publishes
                // read state, so the laptop keeps its say.
                Button(onClick = { vm.setRead(mail, !read) }, modifier = Modifier.weight(1f)) {
                    Text(if (read) "Mark unread" else "Mark READ")
                }
                if (mail.link.isNotEmpty()) {
                    val ctx = LocalContext.current
                    OutlinedButton(onClick = {
                        runCatching {
                            ctx.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(mail.link),
                                )
                            )
                        }
                    }) { Text("Open") }
                }
                OutlinedButton(onClick = { vm.dismiss(mail); vm.closeBody(); onBack() }) {
                    Text("Remove")
                }
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
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(Modifier.width(8.dp))
            // Only once the body is here: the attachments live in the same
            // raw message, so before it arrives there is nothing to list.
            val held = vm.current(mail.messageId) ?: mail
            if (body != null && held.raw.isNotEmpty()) {
                AttachmentRows(held.raw) { msg -> vm.status(msg) }
            }
            if (body == null) {
                CircularProgressIndicator(strokeWidth = 2.dp)
            } else {
                Text(linkify(body!!), fontSize = 14.sp, fontFamily = FontFamily.SansSerif)
            }
        }
    }
}

private val URL_RE = Regex("""(https?://[^\s<>"')\]]+)""")

/**
 * The body with its URLs made tappable.
 *
 * A mail whose whole point is a link is not much use as flat text you
 * have to retype. Trailing punctuation is left out of the link: a URL at
 * the end of a sentence takes the full stop with it otherwise, and the
 * result 404s.
 */
private fun linkify(text: String): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (m in URL_RE.findAll(text)) {
        append(text.substring(at, m.range.first))
        val url = m.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '\u00a0')
        withLink(LinkAnnotation.Url(url)) {
            withStyle(SpanStyle(color = LINK, textDecoration = TextDecoration.Underline)) {
                append(url)
            }
        }
        // Whatever the trim gave back is ordinary text again.
        append(m.value.substring(url.length))
        at = m.range.last + 1
    }
    append(text.substring(at))
}

/**
 * A row per attachment; tapping writes it to the cache and hands it to
 * whatever app can open it.
 */
@Composable
private fun AttachmentRows(raw: String, onStatus: (String) -> Unit) {
    val ctx = LocalContext.current
    // Parsing walks the whole message, so do it once per message rather
    // than on every recomposition.
    val list = remember(raw) { Attachments.list(raw) }
    if (list.isEmpty()) return

    list.forEachIndexed { i, a ->
        Row(
            Modifier.fillMaxWidth()
                .clickable {
                    val uri = Attachments.save(ctx, raw, i, a.filename)
                    when {
                        uri == null -> onStatus("Could not read ${a.filename}")
                        !Attachments.open(ctx, uri, a.mimeType) ->
                            onStatus("Nothing here opens ${a.mimeType}")
                        else -> {}
                    }
                }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("📎", fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                a.filename,
                Modifier.weight(1f),
                fontSize = 13.sp,
                maxLines = 1,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                Attachments.humanSize(a.size),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
    Spacer(Modifier.width(8.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
    Spacer(Modifier.width(8.dp))
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

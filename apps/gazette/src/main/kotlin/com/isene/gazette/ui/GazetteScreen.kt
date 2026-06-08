package com.isene.gazette.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.isene.gazette.GazetteViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GazetteScreen(vm: GazetteViewModel) {
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) {}
            vm.setFolder(uri.toString())
        }
    }

    vm.message?.let { msg ->
        LaunchedEffect(msg) { snackbar.showSnackbar(msg); vm.clearMessage() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("gazette", style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold)
                        vm.currentDate()?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                actions = {
                    if (vm.currentPdf() != null) {
                        IconButton(onClick = { openPdf(ctx, vm.currentPdf(), vm) }) {
                            Icon(Icons.Filled.PictureAsPdf, contentDescription = "Open PDF")
                        }
                    }
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reload")
                    }
                    IconButton(onClick = { pickFolder.launch(null) }) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = "Choose news folder")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            when {
                vm.folderUri == null -> EmptyState(onPick = { pickFolder.launch(null) })
                vm.issues.isEmpty() -> NoIssues(folderName = vm.folderName)
                else -> {
                    DateBar(vm)
                    HorizontalDivider()
                    IssueBody(vm.content)
                }
            }
        }
    }
}

@Composable
private fun DateBar(vm: GazetteViewModel) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentPadding = PaddingValues(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(vm.issues.size) { i ->
            val issue = vm.issues[i]
            FilterChip(
                selected = i == vm.selected,
                onClick = { vm.select(i) },
                label = { Text(issue.date, style = MaterialTheme.typography.labelLarge) },
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
    }
}

/** Render the issue Markdown. The format is simple and line-oriented: each
 *  body paragraph is a single line, so a line walk is sufficient. */
@Composable
private fun IssueBody(md: String) {
    val uriHandler = LocalUriHandler.current
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        var linkN = 0
        for (raw in md.lines()) {
            val line = raw.trimEnd()
            when {
                line.startsWith("# ") -> {} // title shown in the app bar
                line.startsWith("## ") -> {
                    Spacer(Modifier.height(16.dp))
                    Text(line.substring(3), style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold, color = accent)
                    HorizontalDivider(Modifier.padding(top = 4.dp, bottom = 6.dp))
                }
                line.startsWith("### ") -> {
                    Spacer(Modifier.height(12.dp))
                    Text(line.substring(4), style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(3.dp))
                }
                line.startsWith("http://") || line.startsWith("https://") -> {
                    linkN += 1
                    val n = linkN
                    val url = line
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) {
                                append("[$n] ")
                            }
                            withStyle(SpanStyle(color = accent, fontFamily = FontFamily.Monospace,
                                textDecoration = TextDecoration.Underline)) { append(url) }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                            .clickable { runCatching { uriHandler.openUri(url) } },
                    )
                }
                line.isBlank() -> Spacer(Modifier.height(6.dp))
                else -> Text(line, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 1.dp))
            }
        }
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun EmptyState(onPick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Welcome to gazette", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(
            "Choose your synced news folder (the one holding " +
                "news-YYYY-MM-DD.md issues — e.g. the Syncthing ~/.news folder).",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(18.dp))
        TextButton(onClick = onPick) { Text("Choose folder") }
    }
}

@Composable
private fun NoIssues(folderName: String?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No issues yet", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "No news-YYYY-MM-DD.md files in " + (folderName ?: "the chosen folder") +
                " yet. They appear once the daily run syncs an issue in.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun openPdf(ctx: android.content.Context, uri: Uri?, vm: GazetteViewModel) {
    if (uri == null) return
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { ctx.startActivity(intent) }
        .onFailure { vm.message = "No PDF viewer installed." }
}

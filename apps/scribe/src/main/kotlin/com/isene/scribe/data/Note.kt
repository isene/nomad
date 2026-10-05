package com.isene.scribe.data

import android.net.Uri
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** A text file in the notes folder. */
data class NoteRef(val uri: Uri, val name: String, val modified: Long, val size: Long)

/** What the list shows of a note: read once, kept until the file changes. */
class NoteInfo(text: String) {
    val tags: List<String> = tagsOf(text)
    val images: List<String> = imagesOf(text)
    val preview: String = previewOf(text)
    /** The whole note in lower case, for search. */
    val lower: String = text.lowercase()
    val pinned: Boolean = tags.any { it.equals("pinned", ignoreCase = true) }
}

/** The folder inside the notes folder where pictures live. */
const val IMG = "img"

// A tag is # and a word, at the start of a line or after a space: "#idea".
// "# Heading" and "example.com/#top" are not tags.
private val TAG = Regex("""(?:^|(?<=\s))#(\p{L}[\p{L}\p{N}_-]*)""")

// A picture is a Markdown image link: ![](img/name.jpg)
private val IMAGE = Regex("""!\[[^\]]*\]\(([^)\s]+)\)""")

fun tagsOf(text: String): List<String> =
    TAG.findAll(text).map { it.groupValues[1] }.distinctBy { it.lowercase() }.toList()

fun imagesOf(text: String): List<String> =
    IMAGE.findAll(text).map { it.groupValues[1] }.toList()

private fun onlyTags(line: String): Boolean = line.split(' ').all { it.startsWith("#") && it.length > 1 }

/** The first lines of a note, without its picture links and its line of tags. */
fun previewOf(text: String): String =
    text.lineSequence().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("![") && !onlyTags(it) }
        .take(3).joinToString("\n") { it.take(120) }

/** A file name for a note that has none yet: its first line of text. */
fun titleOf(text: String): String {
    val line = text.lineSequence().map { it.trim() }
        .firstOrNull { it.isNotEmpty() && !it.startsWith("![") }.orEmpty()
    val name = line.trimStart('#', ' ')
        .replace(Regex("""[/\\:*?"<>|\p{Cc}]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '.').take(60).trim(' ', '.')
    return name.ifEmpty { "Note " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm")) }
}

/** `name.md`, or `name 2.md`, `name 3.md` when the folder has that name already. */
fun freeName(name: String, taken: Set<String>): String {
    var n = 1
    var pick = "$name.md"
    while (pick.lowercase() in taken) pick = "$name ${++n}.md"
    return pick
}

/** The text with a picture link on a line of its own at the top. */
fun withPicture(text: String, link: String): String {
    val line = "![]($link)\n"
    return if (text.startsWith("![") || text.isEmpty()) line + text else line + "\n" + text
}

/** The text without the link to one picture. */
fun withoutPicture(text: String, path: String): String {
    val link = """!\[[^\]]*\]\(""" + Regex.escape(path) + """\)"""
    val whole = Regex("""(?m)^$link[ \t]*\n?""").find(text)
    val cut = if (whole != null) text.removeRange(whole.range) else text.replaceFirst(Regex(link), "")
    return cut.trimStart('\n')
}

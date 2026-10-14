package com.isene.scribe.data

import android.net.Uri
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** A text file in the notes folder. */
data class NoteRef(val uri: Uri, val name: String, val modified: Long, val size: Long)

/** What the list and the cards show of a note: read once, kept until the file changes. */
class NoteInfo(text: String) {
    private val allTags = tagsOf(text)
    /** The tags to show and pick from. A card colour is not one of them. */
    val tags: List<String> = allTags.filterNot { it.startsWith(CARD, ignoreCase = true) }
    val images: List<String> = imagesOf(text)
    val preview: String = previewOf(text)
    /** The whole note in lower case, for search. */
    val lower: String = text.lowercase()
    val pinned: Boolean = tags.any { it.equals("pinned", ignoreCase = true) }
    val colour: CardColour? = colourOf(allTags)
}

/** A card colour, light and dark as Keep has them. */
class CardColour(val name: String, val light: Long, val dark: Long)

val CARD_COLOURS = listOf(
    CardColour("red", 0xFFFAAFA8, 0xFF77172E),
    CardColour("orange", 0xFFF39F76, 0xFF692B17),
    CardColour("yellow", 0xFFFFF8B8, 0xFF7C4A03),
    CardColour("green", 0xFFE2F6D3, 0xFF264D3B),
    CardColour("teal", 0xFFB4DDD3, 0xFF0C625D),
    CardColour("blue", 0xFFD4E4ED, 0xFF256377),
    CardColour("darkblue", 0xFFAECCDC, 0xFF284255),
    CardColour("purple", 0xFFD3BFDB, 0xFF472E5B),
    CardColour("pink", 0xFFF6E2DD, 0xFF6C394F),
    CardColour("brown", 0xFFE9E3D4, 0xFF4B443A),
    CardColour("grey", 0xFFEFEFF1, 0xFF232427),
)

// A card's colour is a tag, #card-red, so it travels with the note.
private const val CARD = "card-"
private val CARD_TAG = Regex("#card-[\\p{L}\\p{N}_-]*", RegexOption.IGNORE_CASE)

/** Where a colour comes when sorting by colour: in the order of the swatches, notes with none last. */
fun colourRank(c: CardColour?): Int = c?.let { CARD_COLOURS.indexOf(it) } ?: CARD_COLOURS.size

fun colourOf(tags: List<String>): CardColour? = tags.firstNotNullOfOrNull { t ->
    if (!t.startsWith(CARD, ignoreCase = true)) null
    else CARD_COLOURS.firstOrNull { it.name.equals(t.substring(CARD.length), ignoreCase = true) }
}

/**
 * The text with its card colour set to `name`, or with none when null.
 * The tag goes on the last line when that line holds tags, as keep-import
 * writes them, or on a line of its own at the end.
 */
fun withColour(text: String, name: String?): String {
    val end = if (text.endsWith("\n")) "\n" else ""
    val lines = text.removeSuffix("\n").split('\n').mapNotNull { line ->
        val words = line.split(' ')
        val kept = words.filterNot { CARD_TAG.matches(it) }
        when {
            kept.size == words.size -> line
            kept.all { it.isEmpty() } -> null
            else -> kept.joinToString(" ")
        }
    }.toMutableList()
    while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
    if (name != null) {
        val tag = "#$CARD$name"
        when {
            lines.isEmpty() -> lines += tag
            onlyTags(lines.last().trim()) -> lines[lines.size - 1] = lines.last().trimEnd() + " " + tag
            else -> { lines += ""; lines += tag }
        }
    }
    return lines.joinToString("\n") + end
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

/**
 * The first lines of a note, without its picture links and its line of
 * tags: as many as a card shows. The list shows the first three.
 */
fun previewOf(text: String): String =
    text.lineSequence().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("![") && !onlyTags(it) }
        .take(8).joinToString("\n") { it.take(120) }

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

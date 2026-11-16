package com.isene.science

import java.util.Locale

// The facts and the rules of the two subjects, in plain Kotlin with no
// Android in it, so the laptop tests them as they are. The colours and
// the wording follow the laptop's elements and isotopes apps.

/** One element, a row of assets/elements.tsv (made by tools/science-data). */
data class Element(
    val number: Int,
    val symbol: String,
    val name: String,
    val category: String,
    val phase: String,
    val mass: Double?,
    val density: Double?,
    val melt: Double?,
    val boil: Double?,
    val group: Int?,
    val period: Int,
    val block: String,
    /** Column 1..18 and row 1..11 in the table as it is drawn. */
    val x: Int,
    val y: Int,
    val shells: String,
    val config: String,
    val electronegativity: Double?,
    val ionization: Double?,
    val year: String,
    /** The title of its Wikipedia page ("Mercury (Element)"). */
    val wiki: String,
    val summary: String,
)

fun parseElements(tsv: String): List<Element> = tsv.lineSequence().filter { it.isNotBlank() }.map { line ->
    val c = line.split('\t')
    fun s(i: Int) = c.getOrElse(i) { "" }
    Element(
        number = s(0).toInt(), symbol = s(1), name = s(2), category = s(3), phase = s(4),
        mass = s(5).toDoubleOrNull(), density = s(6).toDoubleOrNull(),
        melt = s(7).toDoubleOrNull(), boil = s(8).toDoubleOrNull(),
        group = s(9).toIntOrNull(), period = s(10).toIntOrNull() ?: 0, block = s(11),
        x = s(12).toInt(), y = s(13).toInt(), shells = s(14), config = s(15),
        electronegativity = s(16).toDoubleOrNull(), ionization = s(17).toDoubleOrNull(),
        year = s(18), wiki = s(19).ifEmpty { s(2) }, summary = s(20),
    )
}.toList()

/** The families of the table, each with its colour, in the order they are matched. */
val FAMILIES: List<Pair<String, Int>> = listOf(
    "alkali metal" to rgb(255, 99, 71),
    "alkaline earth" to rgb(255, 165, 0),
    "post-transition" to rgb(120, 220, 120),
    "transition metal" to rgb(255, 208, 0),
    "metalloid" to rgb(0, 206, 209),
    "nonmetal" to rgb(100, 150, 255),
    "noble gas" to rgb(190, 120, 255),
    "lanthanide" to rgb(255, 121, 198),
    "actinide" to rgb(240, 90, 150),
)
val FAMILY_UNKNOWN = rgb(150, 150, 150)

fun familyColor(category: String): Int =
    FAMILIES.firstOrNull { category.contains(it.first) }?.second ?: FAMILY_UNKNOWN

/** Rows the table needs: seven periods, 119 and 120, a gap, three rows below. */
const val TABLE_COLS = 18
const val TABLE_ROWS = 11.4f

/** Where a row of the data is drawn: the rows under the main table sit a little apart. */
fun tableRow(y: Int): Float = if (y <= 8) y - 1f else y - 0.6f

/** The element drawn at a point, in cells from the top left of the table. */
fun elementAt(all: List<Element>, cx: Float, cy: Float): Element? = all.firstOrNull { e ->
    val top = tableRow(e.y)
    cx >= e.x - 1 && cx < e.x && cy >= top && cy < top + 1
}

/** The ways a nucleus falls apart, as the chart colours them. */
enum class Decay(val label: String, val color: Int) {
    Stable("stable", rgb(245, 245, 245)),
    BetaMinus("β−", rgb(90, 150, 255)),
    BetaPlus("β+ / EC", rgb(255, 105, 90)),
    Alpha("α", rgb(255, 205, 70)),
    Fission("fission", rgb(200, 120, 255)),
    Proton("proton", rgb(255, 150, 220)),
    Neutron("neutron", rgb(110, 225, 210)),
    Other("other", rgb(170, 170, 175)),
    Unknown("unknown", rgb(95, 95, 105)),
}

fun decayOf(code: String): Decay = when {
    code.isEmpty() -> Decay.Unknown
    code.startsWith("SF") -> Decay.Fission
    code.startsWith("2B-") || code.startsWith("B-") -> Decay.BetaMinus
    code.startsWith("EC") || code.startsWith("B+") || code.startsWith("2EC") || code.startsWith("2B+") -> Decay.BetaPlus
    code == "A" -> Decay.Alpha
    code == "P" || code == "2P" -> Decay.Proton
    code == "N" || code == "2N" -> Decay.Neutron
    else -> Decay.Other
}

/** "B-2N" as "β−2n", "EC+B+" as "EC+β+", "A" as "α". Cluster decays ("14C") stay as they are. */
fun decayText(code: String): String {
    if (code == "IT" || !KNOWN.matches(code)) return code
    return code.replace("SF", " fission ").replace("B-", "β−").replace("B+", "β+")
        .replace("EC", "§").replace("A", "α").replace("N", "n").replace("P", "p").replace("§", "EC").trim()
}

private val KNOWN = Regex("^(A|IT|2?(B-|B\\+|EC|SF|P|N).*)$")

/** One nuclide, a row of assets/isotopes.csv (the IAEA table). */
data class Nuclide(
    val z: Int,
    val n: Int,
    val symbol: String,
    val spin: String,
    /** Share of the element as found in nature, in percent. */
    val abundance: Double?,
    val stable: Boolean,
    val halfLife: Double?,
    /** (code, percent) for each way it decays, the main one first. */
    val decays: List<Pair<String, Double?>>,
    val binding: Double?,
    val massExcess: Double?,
    val year: String,
) {
    val a: Int get() = z + n
    val main: Decay
        get() = if (stable) Decay.Stable else decays.firstOrNull()?.let { decayOf(it.first) } ?: Decay.Unknown
}

fun parseNuclides(csv: String): List<Nuclide> = csv.lineSequence()
    .filter { it.isNotBlank() && !it.startsWith("#") }
    .map { line ->
        val c = line.split(',')
        fun s(i: Int) = c.getOrElse(i) { "" }
        Nuclide(
            z = s(0).toInt(), n = s(1).toInt(), symbol = s(2), spin = s(3),
            abundance = s(4).toDoubleOrNull(),
            stable = s(5) == "STABLE", halfLife = s(5).toDoubleOrNull(),
            decays = s(7).split(' ').filter { it.isNotEmpty() }.map {
                val p = it.split(':')
                p[0] to p.getOrNull(1)?.toDoubleOrNull()
            },
            binding = s(8).toDoubleOrNull(), massExcess = s(9).toDoubleOrNull(), year = s(10),
        )
    }.toList()

/** Half-life in a unit a person reads, from nanoseconds to billions of years. */
fun halfLifeText(n: Nuclide): String {
    if (n.stable) return "stable"
    val t = n.halfLife ?: return "unknown"
    val units = listOf(
        3.156e16 to "Gy", 3.156e13 to "My", 3.156e7 to "y", 86400.0 to "d", 3600.0 to "h",
        60.0 to "min", 1.0 to "s", 1e-3 to "ms", 1e-6 to "µs",
    )
    for ((scale, unit) in units) if (t >= scale) {
        val v = t / scale
        return if (v >= 100) "${num(v, 0)} $unit" else "${num(v, 3)} $unit".replace(".000 ", " ")
    }
    return "${num(t * 1e9, 3)} ns"
}

/** The decay modes as a short line. A branch under a hundredth of a percent reads "tiny". */
fun decaySummary(n: Nuclide, max: Int = 4): String {
    if (n.stable) return "stable"
    if (n.decays.isEmpty()) return "unknown"
    val parts = n.decays.take(max).map { (code, pct) ->
        when {
            pct == null -> decayText(code)
            pct >= 0.01 -> "${decayText(code)} ${trim(pct)}%"
            else -> "${decayText(code)} tiny"
        }
    }
    return (if (n.decays.size > max) parts + "…" else parts).joinToString(", ")
}

/** The isotope to show first for an element: the most common in nature, else the longest lived. */
fun mainIsotope(all: List<Nuclide>, z: Int): Nuclide? {
    val own = all.filter { it.z == z }
    return own.filter { it.abundance != null }.maxByOrNull { it.abundance!! }
        ?: own.filter { it.stable }.firstOrNull()
        ?: own.maxByOrNull { it.halfLife ?: -1.0 }
}

/** `99.2742` and `100`, never `99.274200` or `100.0000`. */
fun trim(v: Double): String = num(v, 4).trimEnd('0').trimEnd('.')

fun num(v: Double, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", v)

/** Kelvin with Celsius beside it: "1337.33 K (1064.18 °C)". */
fun temperature(k: Double?): String = if (k == null) "" else "${trim(k)} K (${num(k - 273.15, 2).trimEnd('0').trimEnd('.')} °C)"

fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

/** The plain text Wikipedia answers with, cut into headings and paragraphs. */
data class Piece(val heading: Boolean, val text: String)

fun articlePieces(text: String): List<Piece> = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.map {
    if (it.startsWith("==")) Piece(true, it.trim('=', ' ')) else Piece(false, it)
}.filter { it.text.isNotEmpty() }.toList()

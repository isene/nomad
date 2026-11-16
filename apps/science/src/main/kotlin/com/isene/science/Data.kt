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

// ── particles ───────────────────────────────────────────────────────────

/** A row of assets/particles.tsv: the Standard Model, plus the proton and the neutron. */
data class Particle(
    val symbol: String, val name: String, val kind: String, val generation: Int, val mass: String,
    val charge: String, val spin: String, val colorCharge: String, val forces: String,
    val discovered: String, val antiparticle: String, val blurb: String, val wiki: String,
    /** Column 0..5 and row 0..3 in the chart. */
    val col: Int, val row: Int,
)

fun parseParticles(tsv: String): List<Particle> = tsv.lineSequence().filter { it.isNotBlank() }.map {
    val c = it.split('\t')
    Particle(c[0], c[1], c[2], c[3].toInt(), c[4], c[5], c[6], c[7], c[8], c[9], c[10], c[11], c[12], c[13].toInt(), c[14].toInt())
}.toList()

/** The kinds of particle: the name in the data, the name shown, the colour. */
val KINDS: List<Triple<String, String, Int>> = listOf(
    Triple("Quark", "quark", rgb(255, 140, 90)),
    Triple("Lepton", "lepton", rgb(120, 200, 255)),
    Triple("Gauge", "gauge boson", rgb(255, 200, 90)),
    Triple("Scalar", "scalar boson", rgb(200, 140, 255)),
    Triple("Composite", "composite", rgb(150, 220, 150)),
)

fun kindOf(kind: String): Triple<String, String, Int> = KINDS.firstOrNull { it.first == kind } ?: KINDS.last()

// ── stars ───────────────────────────────────────────────────────────────

/** A row of assets/stars.tsv. Luminosity, radius and mass are in Suns. */
data class Star(
    val name: String, val designation: String, val constellation: String, val spectral: String,
    val distPc: Double?, val mag: Double?, val absmag: Double?, val teff: Double?, val lum: Double?,
    val radius: Double?, val mass: Double?, val wiki: String, val summary: String,
) {
    /** The letter of its spectral class (O B A F G K M), or "?". */
    val letter: String get() = spectral.firstOrNull { it in "OBAFGKMWLTYSCR" }?.toString() ?: "?"
}

fun parseStars(tsv: String): List<Star> = tsv.lineSequence().filter { it.isNotBlank() }.map {
    val c = it.split('\t')
    fun d(i: Int) = c.getOrElse(i) { "" }.toDoubleOrNull()
    Star(c[0], c[1], c[2], c[3], d(5), d(6), d(7), d(8), d(9), d(10), d(11), c.getOrElse(12) { "" }.ifEmpty { c[0] }, c.getOrElse(13) { "" })
}.toList()

private val STAR_STOPS = listOf(
    40000.0 to Triple(120, 150, 255), 20000.0 to Triple(160, 195, 255), 9700.0 to Triple(225, 235, 255),
    7200.0 to Triple(255, 245, 200), 5800.0 to Triple(255, 215, 90), 4400.0 to Triple(255, 150, 60),
    3000.0 to Triple(255, 90, 60),
)

/** A star's colour from its temperature, the scale the whole suite uses. */
fun starColor(teff: Double): Int {
    val t = teff.coerceIn(3000.0, 40000.0)
    for (i in 0 until STAR_STOPS.size - 1) {
        val (t0, a) = STAR_STOPS[i]
        val (t1, b) = STAR_STOPS[i + 1]
        if (t <= t0 && t >= t1) {
            val f = (Math.log(t0) - Math.log(t)) / (Math.log(t0) - Math.log(t1))
            fun mix(x: Int, y: Int) = (x + (y - x) * f).toInt()
            return rgb(mix(a.first, b.first), mix(a.second, b.second), mix(a.third, b.third))
        }
    }
    return rgb(120, 150, 255)
}

/** Where a temperature sits across the HR diagram, 0 at the left: hot stars are to the left. */
fun hrX(teff: Double): Float = ((Math.log10(45000.0) - Math.log10(teff)) / (Math.log10(45000.0) - Math.log10(2400.0))).toFloat()

/** Where a luminosity sits down the HR diagram, 0 at the top: bright stars are at the top. */
fun hrY(lum: Double): Float = ((6.5 - Math.log10(lum)) / 11.0).toFloat()

// ── exoplanets ──────────────────────────────────────────────────────────

/** How a planet was found, as the diagram colours it. */
enum class Method(val label: String, val color: Int) {
    Transit("transit", rgb(105, 175, 255)),
    RadialVelocity("radial velocity", rgb(255, 175, 80)),
    Microlensing("microlensing", rgb(190, 130, 255)),
    Imaging("direct imaging", rgb(120, 230, 150)),
    Timing("timing", rgb(255, 120, 170)),
    Astrometry("astrometry", rgb(110, 230, 225)),
    Other("other", rgb(170, 170, 175)),
    Home("our own", rgb(255, 255, 255)),
}

fun methodOf(s: String): Method = when {
    s == "Transit" -> Method.Transit
    s == "Radial Velocity" -> Method.RadialVelocity
    s == "Microlensing" -> Method.Microlensing
    s == "Imaging" -> Method.Imaging
    s == "Astrometry" -> Method.Astrometry
    s.contains("Timing") -> Method.Timing
    else -> Method.Other
}

/** A planet. Orbit in AU, radius and mass in Earths, the star in Suns. */
data class Planet(
    val name: String, val host: String, val method: Method, val year: Int?, val period: Double?,
    val orbit: Double, val radius: Double?, val mass: Double?, val temperature: Double?,
    val starTeff: Double?, val starRadius: Double?, val spectype: String, val distPc: Double?,
) {
    /** Bulk density in g/cm³: Earth is 5.51, Saturn 0.69. */
    val density: Double? get() = if (mass != null && radius != null && radius > 0) 5.513 * mass / (radius * radius * radius) else null

    /** Gravity at the surface, in Earths. */
    val gravity: Double? get() = if (mass != null && radius != null && radius > 0) mass / (radius * radius) else null

    /** What kind of world the size suggests. */
    val kind: String
        get() = when {
            radius != null -> when {
                radius < 1.25 -> "Earth-sized"
                radius < 2.0 -> "super-Earth"
                radius < 6.0 -> "Neptune-like"
                radius < 15.0 -> "gas giant"
                else -> "very large"
            }
            mass == null -> "unknown"
            mass < 2.0 -> "Earth-mass"
            mass < 10.0 -> "super-Earth"
            mass < 50.0 -> "Neptune-mass"
            else -> "giant"
        }

    /** The size it is drawn at: its radius, or one worked out from its mass. */
    val plotRadius: Double get() = radius ?: mass?.let(::massToRadius) ?: 1.0

    /** The star's light in Suns, from its size and temperature. */
    val starLight: Double? get() = if (starRadius != null && starTeff != null && starRadius > 0 && starTeff > 0) starRadius * starRadius * Math.pow(starTeff / 5772.0, 4.0) else null

    /** The habitable zone in AU: between 1.1 and 0.53 times the sunlight Earth gets. */
    val zone: Pair<Double, Double>? get() = starLight?.let { Math.sqrt(it / 1.1) to Math.sqrt(it / 0.53) }
    val inZone: Boolean get() = zone?.let { orbit >= it.first && orbit <= it.second } ?: false

    /** Sunlight on the planet, in Earths. */
    val sunlight: Double? get() = starLight?.let { it / (orbit * orbit) }
}

/** A radius from a mass, by the rule of Chen and Kipping (2017). */
fun massToRadius(m: Double): Double = when {
    m < 2.04 -> 1.008 * Math.pow(m, 0.279)
    m < 132.0 -> 0.808 * Math.pow(m, 0.589)
    else -> 17.74 * Math.pow(m, -0.044)
}

private val HOME = listOf(
    listOf("Mercury", 87.97, 0.387, 0.383, 0.055, 440.0), listOf("Venus", 224.7, 0.723, 0.949, 0.815, 232.0),
    listOf("Earth", 365.26, 1.0, 1.0, 1.0, 255.0), listOf("Mars", 686.98, 1.524, 0.532, 0.107, 210.0),
    listOf("Jupiter", 4332.6, 5.204, 11.21, 317.8, 122.0), listOf("Saturn", 10759.0, 9.583, 9.45, 95.16, 90.0),
    listOf("Uranus", 30687.0, 19.19, 4.01, 14.54, 64.0), listOf("Neptune", 60190.0, 30.07, 3.88, 17.15, 51.0),
)

/** The NASA table, with our own eight planets added for scale. A planet with no orbit, or with neither size nor mass, stays out. */
fun parsePlanets(csv: String): List<Planet> {
    val all = ArrayList<Planet>(6400)
    for (line in csv.lineSequence()) {
        if (line.isBlank() || line.startsWith("#")) continue
        val f = line.split(',')
        if (f.size < 19) continue
        fun d(i: Int) = f[i].trim().toDoubleOrNull()
        val orbit = d(6) ?: continue
        if (d(7) == null && d(8) == null) continue
        all += Planet(f[0], f[1], methodOf(f[3]), f[4].toIntOrNull(), d(5), orbit, d(7), d(8), d(9), d(11), d(12), f[14], d(15))
    }
    for (h in HOME) all += Planet(
        h[0] as String, "the Sun", Method.Home, null, h[1] as Double, h[2] as Double, h[3] as Double,
        h[4] as Double, h[5] as Double, 5772.0, 1.0, "G2V", 0.0,
    )
    return all
}

/** Where an orbit sits across the diagram, 0 at the left: 0.002 AU to 30,000 AU. */
fun orbitX(au: Double): Float = ((Math.log10(au) + 2.7) / 7.2).toFloat()

/** Where a radius sits down the diagram, 0 at the top: a quarter of an Earth to a hundred. */
fun radiusY(earths: Double): Float = ((2.0 - Math.log10(earths)) / 2.6).toFloat()

// ── the universe ────────────────────────────────────────────────────────

/** A rung of the ladder, a row of assets/rungs.tsv. Its picture is rung/NN.jpg, counted from 01. */
data class Rung(val name: String, val size: Double, val sizeText: String, val blurb: String)

fun parseRungs(tsv: String): List<Rung> = tsv.lineSequence().filter { it.isNotBlank() }.map {
    val c = it.split('\t')
    Rung(c[0], c[1].toDouble(), c[2], c[3])
}.toList()

/** Where a size in metres sits on the ruler, 0 at 10⁻³⁵ m and 1 at 10²⁷ m. */
fun rulerAt(metres: Double): Float = ((Math.log10(metres) + 35) / 62).toFloat()

/** The rung nearest a place on the ruler. */
fun rungNear(rungs: List<Rung>, at: Float): Int = rungs.indices.minByOrNull { Math.abs(rulerAt(rungs[it].size) - at) } ?: 0

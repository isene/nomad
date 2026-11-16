package com.isene.science

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two data files as they ship, read by the rules the app uses. */
class DataTest {
    private val elements = parseElements(File("src/main/assets/elements.tsv").readText())
    private val nuclides = parseNuclides(File("src/main/assets/isotopes.csv").readText())
    private fun nuclide(z: Int, n: Int) = nuclides.first { it.z == z && it.n == n }

    @Test fun every_element_is_there_with_a_place_of_its_own() {
        assertEquals((1..126).toList(), elements.map { it.number })
        assertEquals(126, elements.map { it.x to it.y }.toSet().size)
        assertTrue(elements.all { it.x in 1..TABLE_COLS && tableRow(it.y) + 1 <= TABLE_ROWS })
        val gold = elements[78]
        assertEquals("Au", gold.symbol)
        assertEquals("Gold", gold.wiki)
        assertEquals("Mercury (Element)", elements[79].wiki)
        assertTrue(gold.summary.startsWith("Gold is a chemical element"))
    }

    @Test fun a_tap_finds_the_element_drawn_there() {
        for (e in elements) assertEquals(e, elementAt(elements, e.x - 0.5f, tableRow(e.y) + 0.5f))
        assertNull(elementAt(elements, 8.5f, 0.5f))    // the gap between hydrogen and helium
        assertNull(elementAt(elements, 5.5f, 8.2f))    // the gap above the lanthanides
    }

    @Test fun each_family_has_its_colour() {
        assertEquals(FAMILIES[0].second, familyColor("alkali metal"))
        assertEquals(FAMILIES[0].second, familyColor("unknown, but predicted to be an alkali metal"))
        assertEquals(FAMILIES[1].second, familyColor("alkaline earth metal"))
        assertEquals(FAMILIES[2].second, familyColor("unknown, probably post-transition metal"))
        assertEquals(FAMILIES[3].second, familyColor("transition metal"))
        assertEquals(FAMILIES[5].second, familyColor("diatomic nonmetal"))
        assertEquals(FAMILY_UNKNOWN, familyColor("hypothetical"))
        assertEquals(FAMILIES.size + 1, (FAMILIES.map { it.second } + FAMILY_UNKNOWN).toSet().size)
    }

    @Test fun the_isotopes_read_as_on_the_laptop() {
        assertEquals(3386, nuclides.size)
        assertEquals(244, nuclides.count { it.stable })
        val c14 = nuclide(6, 8)
        assertEquals(14, c14.a)
        assertEquals(Decay.BetaMinus, c14.main)
        assertEquals("5700 y", halfLifeText(c14))
        assertEquals("β− 100%", decaySummary(c14))
        assertEquals("stable", halfLifeText(nuclide(6, 6)))
        assertEquals(Decay.Stable, nuclide(6, 6).main)
        assertEquals("EC+β+ 100%, β+p 62%, β+α 37.9%", decaySummary(nuclide(6, 3)))
        assertEquals("unknown", halfLifeText(nuclide(65, 108)))
        assertEquals(Decay.BetaMinus, nuclide(65, 108).main)
    }

    @Test fun half_lives_take_the_unit_a_person_reads() {
        fun of(t: Double) = halfLifeText(nuclide(6, 8).copy(halfLife = t))
        assertEquals("4.468 Gy", of(1.41e17))
        assertEquals("704 My", of(2.2217e16))
        assertEquals("20.367 min", of(1222.0))
        assertEquals("16.300 ms", of(0.0163))
        assertEquals("1 s", of(1.0))
        assertEquals("3.510 ns", of(3.51e-9))
    }

    @Test fun decay_codes_get_a_family_and_a_name() {
        assertEquals(Decay.Alpha, decayOf("A"))
        assertEquals(Decay.BetaPlus, decayOf("EC+B+"))
        assertEquals(Decay.BetaPlus, decayOf("2EC"))
        assertEquals(Decay.BetaMinus, decayOf("2B-"))
        assertEquals(Decay.Fission, decayOf("SF"))
        assertEquals(Decay.Proton, decayOf("2P"))
        assertEquals(Decay.Neutron, decayOf("N"))
        assertEquals(Decay.Other, decayOf("IT"))
        assertEquals(Decay.Other, decayOf("14C"))
        assertEquals(Decay.Unknown, decayOf(""))
        assertEquals("α", decayText("A"))
        assertEquals("β−2n", decayText("B-2N"))
        assertEquals("ECp", decayText("ECP"))
        assertEquals("fission", decayText("SF"))
        assertEquals("14C", decayText("14C"))
        assertEquals("24NE", decayText("24NE"))
        assertEquals("IT", decayText("IT"))
    }

    @Test fun an_element_opens_on_its_most_common_isotope() {
        assertEquals(12, mainIsotope(nuclides, 6)!!.a)       // carbon-12
        assertEquals(238, mainIsotope(nuclides, 92)!!.a)     // uranium-238
        assertEquals(97, mainIsotope(nuclides, 43)!!.a)      // technetium has none in nature: the longest lived
        assertNull(mainIsotope(nuclides, 126))
    }

    @Test fun an_article_is_cut_into_headings_and_paragraphs() {
        val pieces = articlePieces("Gold is a metal.\n\n\n== History ==\nOld.\n=== Rome ===\nCoins.\n")
        assertEquals(
            listOf(false to "Gold is a metal.", true to "History", false to "Old.", true to "Rome", false to "Coins."),
            pieces.map { it.heading to it.text },
        )
    }

    @Test fun temperatures_come_with_celsius() {
        assertEquals("1337.33 K (1064.18 °C)", temperature(1337.33))
        assertEquals("", temperature(null))
    }

    private fun asset(name: String) = File("src/main/assets/$name").readText()

    @Test fun the_standard_model_fills_its_chart() {
        val all = parseParticles(asset("particles.tsv"))
        assertEquals(19, all.size)
        assertEquals(19, all.map { it.col to it.row }.toSet().size)
        assertTrue(all.all { it.col in 0..5 && it.row in 0..3 })
        assertEquals(6, all.count { it.kind == "Quark" })
        assertEquals(6, all.count { it.kind == "Lepton" })
        val top = all.first { it.symbol == "t" }
        assertEquals("172.57 GeV/c²", top.mass)
        assertEquals("strong, electromagnetic, weak, gravity", top.forces)
        assertEquals("weak, gravity", all.first { it.name == "electron neutrino" }.forces)
        assertEquals("quark", kindOf("Quark").second)
    }

    @Test fun the_sun_sits_where_the_sun_belongs() {
        val all = parseStars(asset("stars.tsv"))
        assertEquals(461, all.size)
        val sun = all.first { it.name == "Sun" }
        assertEquals("G", sun.letter)
        assertEquals(hrY(1.0), hrY(sun.lum!!), 0f)
        val sirius = all.first { it.name == "Sirius" }
        assertTrue("a hotter star is further left", hrX(sirius.teff!!) < hrX(sun.teff!!))
        assertTrue("a brighter star is higher", hrY(sirius.lum!!) < hrY(sun.lum!!))
        assertTrue(all.filter { it.teff != null && it.lum != null }.all { hrX(it.teff!!) in 0f..1f && hrY(it.lum!!) in 0f..1f })
        assertEquals(rgb(255, 215, 90), starColor(5800.0))
        assertEquals(rgb(255, 90, 60), starColor(2000.0))
    }

    @Test fun the_planets_read_as_on_the_laptop() {
        val all = parsePlanets(asset("exoplanets.csv"))
        assertEquals(8, all.count { it.method == Method.Home })
        assertEquals(6309, all.size - 8)
        assertTrue(all.all { orbitX(it.orbit) in 0f..1f && radiusY(it.plotRadius) in 0f..1f })
        val earth = all.first { it.name == "Earth" }
        assertEquals("Earth-sized", earth.kind)
        assertTrue(earth.inZone)
        assertEquals(5.513, earth.density!!, 1e-9)
        assertEquals(1.0, earth.sunlight!!, 1e-9)
        assertTrue(!all.first { it.name == "Venus" }.inZone)
        assertEquals("gas giant", all.first { it.name == "Jupiter" }.kind)
        assertEquals(Method.RadialVelocity, all.first { it.name == "11 Com b" }.method)
        assertEquals(Method.Timing, methodOf("Pulsar Timing"))
        assertEquals(1.008, massToRadius(1.0), 1e-9)
    }

    @Test fun every_rung_has_its_picture_and_its_place() {
        val rungs = parseRungs(asset("rungs.tsv"))
        assertEquals(37, rungs.size)
        assertEquals("Human body", rungs[14].name)
        assertEquals("1.70 m", rungs[14].sizeText)
        for (i in rungs.indices) assertTrue(File("src/main/assets/rung/%02d.jpg".format(i + 1)).length() > 5000)
        assertEquals(rungs.map { it.size }.sorted(), rungs.map { it.size })
        assertTrue(rungs.all { rulerAt(it.size) in 0f..1f })
        assertEquals(14, rungNear(rungs, rulerAt(1.7)))
        assertEquals(0, rungNear(rungs, 0f))
        assertEquals(36, rungNear(rungs, 1f))
    }

    @Test fun numbers_keep_three_figures() {
        assertEquals("0.0123", short(0.012345))
        assertEquals("4.56", short(4.5601))
        assertEquals("78.9", short(78.91))
        assertEquals("1230", short(1230.4))
        assertEquals("832,000", short(832000.0))
        assertEquals("1", short(1.0))
    }
}

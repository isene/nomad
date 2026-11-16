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
}

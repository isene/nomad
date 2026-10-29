package com.isene.pointer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class PhoneDocumentsTest {
    private val top = Files.createTempDirectory("phone").toFile().apply {
        resolve(".tasks").mkdir()
        resolve(".tasks/todo.hl").writeText("Example\n")
    }

    @Test fun hiddenFolderIsReached() {
        val id = childId(childId(ROOT, ".tasks"), "todo.hl")
        assertEquals("Example\n", inside(top, id)!!.readText())
        assertEquals(top.canonicalFile, inside(top, ROOT))
    }

    @Test fun noWayOut() {
        assertNull(inside(top, "phone/../etc/passwd"))
        assertNull(inside(top, "phone/.tasks/../../x"))
        assertNull(inside(top, "/etc/passwd"))
        assertNull(inside(top, "phonex"))
    }
}

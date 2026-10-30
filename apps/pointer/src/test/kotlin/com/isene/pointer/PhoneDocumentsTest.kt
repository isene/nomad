package com.isene.pointer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
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

    @Test fun newFileOnlyInsideItsFolder() {
        val dir = childId(ROOT, ".tasks")
        assertEquals(top.resolve(".tasks/new.txt").canonicalFile, newChild(top, dir, "new.txt"))
        assertNull(newChild(top, dir, "todo.hl"))
        assertNull(newChild(top, dir, "../x.txt"))
        assertNull(newChild(top, dir, "a/b.txt"))
        assertNull(newChild(top, childId(dir, "todo.hl"), "x.txt"))
        assertNotNull(newChild(top, ROOT, "x.txt"))
    }
}

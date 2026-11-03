package com.isene.tasks

import com.isene.tasks.viewmodel.markerOf
import com.isene.tasks.viewmodel.withMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkerTest {
    @Test fun a_marker_is_swapped_for_another() {
        assertEquals("[x] Buy milk", withMarker("[_] Buy milk", "[x]"))
        assertEquals("[O] Buy milk", withMarker("[x] Buy milk", "[O]"))
    }

    @Test fun an_item_without_a_marker_gets_one_in_front() {
        assertEquals("[_] Buy milk", withMarker("Buy milk", "[_]"))
        assertEquals("[_] [link] here", withMarker("[link] here", "[_]"))
    }

    @Test fun the_marker_is_read_from_the_start_only() {
        assertEquals("[O]", markerOf("[O] Write the post"))
        assertNull(markerOf("Write [x] later"))
        assertNull(markerOf("[x]no space"))
    }
}

package com.isene.pointer

import androidx.compose.ui.geometry.Offset
import com.isene.pointer.ui.pinchStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Two fingers size a picture at any size. Up to 0.4.2 they did only
 *  after a double tap, and a pinch at the picture's own size did nothing. */
class PinchTest {
    @Test
    fun two_fingers_zoom_a_picture_at_its_own_size() {
        assertEquals(2f to Offset.Zero, pinchStep(1f, Offset.Zero, 2, 2f, Offset.Zero, 1000, 2000))
    }

    @Test
    fun one_finger_at_its_own_size_is_the_pagers() {
        assertNull(pinchStep(1f, Offset.Zero, 1, 1f, Offset(40f, 0f), 1000, 2000))
    }

    @Test
    fun one_finger_moves_a_zoomed_picture_but_not_off_the_screen() {
        // At 2x on a 1000 wide screen the picture may move 500 either way.
        assertEquals(2f to Offset(500f, 30f), pinchStep(2f, Offset(450f, 0f), 1, 1f, Offset(100f, 30f), 1000, 2000))
    }

    @Test
    fun the_size_stays_between_one_and_eight_times() {
        assertEquals(1f, pinchStep(1f, Offset.Zero, 2, 0.5f, Offset.Zero, 1000, 2000)!!.first)
        assertEquals(8f, pinchStep(6f, Offset.Zero, 2, 2f, Offset.Zero, 1000, 2000)!!.first)
    }
}

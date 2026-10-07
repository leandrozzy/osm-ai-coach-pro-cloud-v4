package com.osmaicoach.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelProbeTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun img(w: Int, h: Int, bg: Int = rgb(10, 20, 50)) = PixelProbe.Img(w, h, IntArray(w * h) { bg })

    private fun paint(i: PixelProbe.Img, x0: Int, x1: Int, y0: Int, y1: Int, c: Int) {
        for (y in y0 until y1) for (x in x0 until x1) i.px[y * i.w + x] = c
    }

    @Test fun osmTopBarNeedsBothPills() {
        val i = img(200, 100)
        assertFalse(PixelProbe.isOsmTopBar(i))
        paint(i, 20, 40, 0, 9, rgb(60, 190, 240))
        assertFalse(PixelProbe.isOsmTopBar(i))
        paint(i, 44, 60, 0, 9, rgb(255, 140, 0))
        assertTrue(PixelProbe.isOsmTopBar(i))
    }

    @Test fun pinkAdIsNotOsm() {
        assertFalse(PixelProbe.isOsmTopBar(img(200, 100, rgb(230, 60, 150))))
    }

    @Test fun orangeShirtMeansTrainingOnly() {
        val i = img(100, 100, rgb(255, 255, 255))
        assertFalse(PixelProbe.orangeShirt(i, 0.5f))
        paint(i, 0, 5, 40, 60, rgb(255, 140, 0))
        assertTrue(PixelProbe.orangeShirt(i, 0.5f))
        val blue = img(100, 100, rgb(255, 255, 255))
        paint(blue, 0, 5, 40, 60, rgb(30, 60, 160))
        assertFalse(PixelProbe.orangeShirt(blue, 0.5f))
    }

    @Test fun resultBadgeColors() {
        fun badge(c: Int): Char? {
            val i = img(200, 100)
            paint(i, 46, 63, 46, 54, c)
            return PixelProbe.resultBadge(i, 0.2f, 0.5f)
        }
        assertEquals('V', badge(rgb(60, 200, 0)))
        assertEquals('D', badge(rgb(220, 20, 20)))
        assertEquals('E', badge(rgb(255, 160, 0)))
        assertNull(PixelProbe.resultBadge(img(200, 100), 0.2f, 0.5f))
    }

    @Test fun homeIconDetection() {
        val i = img(200, 100)
        assertEquals(false, PixelProbe.homeIcon(i, 0.2f, 0.5f))
        paint(i, 22, 30, 45, 55, rgb(255, 255, 255))
        assertEquals(true, PixelProbe.homeIcon(i, 0.2f, 0.5f))
    }

    @Test fun refereeThermometerColor() {
        fun sev(c: Int): String? {
            val i = img(400, 400)
            paint(i, 190, 210, 190, 200, c)
            return PixelProbe.refereeSeverity(i)
        }
        assertEquals("Brando", sev(rgb(30, 150, 255)))
        assertEquals("Médio", sev(rgb(255, 150, 0)))
        assertEquals("Rigoroso", sev(rgb(230, 20, 20)))
        assertNull(PixelProbe.refereeSeverity(img(400, 400)))
    }

    @Test fun frameHashTellsDuplicatesFromDifferentScreens() {
        fun px(f: (Int, Int) -> Int) = IntArray(FrameHash.W * FrameHash.H) { f(it % FrameHash.W, it / FrameHash.W) }
        val a = FrameHash.of(px { x, _ -> (255 - x * 7) * 0x010101 })
        val same = FrameHash.of(px { x, _ -> (255 - x * 7) * 0x010101 })
        val other = FrameHash.of(px { x, _ -> (x * 7) * 0x010101 })
        assertEquals(0, FrameHash.distance(a, same))
        assertTrue(FrameHash.distance(a, other) >= 12)
    }
}

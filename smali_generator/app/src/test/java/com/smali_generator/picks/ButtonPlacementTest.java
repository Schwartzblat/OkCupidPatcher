package com.smali_generator.picks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ButtonPlacementTest {

    /**
     * The real card on the device this was measured on: a 60dp FAB at density
     * 2.625, so 158px, and a 12dp gap, so 32px. The button is laid out
     * coincident with the FAB, so it has to travel 190px to clear it -- which
     * is exactly the displacement the first, margin-based version inflicted on
     * the FAB instead.
     */
    @Test public void clearsTheFabOnTheMeasuredDevice() {
        int size = ButtonPlacement.size(ButtonPlacement.dp(60f, 2.625f), 2.625f);
        int gap = ButtonPlacement.dp(ButtonPlacement.GAP_DP, 2.625f);
        assertEquals(158, size);
        assertEquals(32, gap);
        assertEquals(190, ButtonPlacement.offset(size, gap));
        assertEquals(-190f, ButtonPlacement.translationX(190, false), 0f);
    }

    /**
     * The property that makes overlap impossible rather than unlikely: the two
     * views start out on top of each other, so the offset must exceed the
     * size, never merely equal it.
     */
    @Test public void offsetAlwaysExceedsTheSize() {
        for (int size : new int[]{0, 1, 2, 57, 158, 1000}) {
            assertTrue("size " + size, ButtonPlacement.offset(size, 0) > size);
            assertTrue("size " + size, ButtonPlacement.offset(size, -5) > size);
            assertTrue("size " + size, ButtonPlacement.offset(size, 12) > size);
        }
    }

    @Test public void offsetIgnoresANegativeSize() {
        assertEquals(10, ButtonPlacement.offset(-1000, 10));
    }

    @Test public void offsetClampsRatherThanOverflowing() {
        assertEquals(Integer.MAX_VALUE, ButtonPlacement.offset(Integer.MAX_VALUE, 100));
    }

    /** Inward from the end edge: negative X under LTR, positive under RTL. */
    @Test public void translationFollowsTheLayoutDirection() {
        assertEquals(-190f, ButtonPlacement.translationX(190, false), 0f);
        assertEquals(190f, ButtonPlacement.translationX(190, true), 0f);
    }

    /**
     * Clamped, not flipped. A flip would move the button further onto the FAB,
     * which is the one outcome this class exists to prevent.
     */
    @Test public void negativeOffsetDoesNotFlipTheDirection() {
        assertEquals(0f, ButtonPlacement.translationX(-190, false), 0f);
        assertEquals(0f, ButtonPlacement.translationX(-190, true), 0f);
    }

    @Test public void sizeMatchesTheFabWhenItHasOne() {
        assertEquals(180, ButtonPlacement.size(180, 3f));
    }

    /**
     * MATCH_PARENT (-1), WRAP_CONTENT (-2) and "not measured yet" (0) are not
     * sizes, so each falls back to the card's documented 60dp.
     */
    @Test public void sizeFallsBackForEveryNonSize() {
        int expected = ButtonPlacement.dp(ButtonPlacement.FALLBACK_SIZE_DP, 2f);
        assertEquals(expected, ButtonPlacement.size(0, 2f));
        assertEquals(expected, ButtonPlacement.size(-1, 2f));
        assertEquals(expected, ButtonPlacement.size(-2, 2f));
    }

    /** A stubbed or zeroed DisplayMetrics must not produce an invisible button. */
    @Test public void sizeSurvivesAUselessDensity() {
        assertEquals(Math.round(ButtonPlacement.FALLBACK_SIZE_DP),
                ButtonPlacement.size(0, 0f));
        assertEquals(Math.round(ButtonPlacement.FALLBACK_SIZE_DP),
                ButtonPlacement.size(0, -3f));
    }

    @Test public void dpRounds() {
        assertEquals(0, ButtonPlacement.dp(0f, 2.75f));
        assertEquals(55, ButtonPlacement.dp(20f, 2.75f));
        assertEquals(165, ButtonPlacement.dp(60f, 2.75f));
    }

    /** A positive dp is never rounded away to nothing. */
    @Test public void dpKeepsAtLeastOnePixel() {
        assertEquals(1, ButtonPlacement.dp(0.1f, 0.5f));
    }
}

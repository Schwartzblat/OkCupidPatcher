package com.smali_generator.picks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LikedOnceTest {

    @Test public void firstClaimWins() {
        LikedOnce likes = new LikedOnce();
        assertTrue(likes.claim("12345"));
    }

    /**
     * The duplicate this exists to stop: Epoxy rebinds a card every time it
     * scrolls back, and the vote is asynchronous, so a second tap must send
     * nothing.
     */
    @Test public void secondClaimForTheSamePersonIsRefused() {
        LikedOnce likes = new LikedOnce();
        assertTrue(likes.claim("12345"));
        assertFalse(likes.claim("12345"));
        assertEquals(1, likes.size());
    }

    @Test public void differentPeopleAreIndependent() {
        LikedOnce likes = new LikedOnce();
        assertTrue(likes.claim("12345"));
        assertTrue(likes.claim("67890"));
        assertEquals(2, likes.size());
    }

    /** A failed vote liked nobody, so the card has to become tappable again. */
    @Test public void releaseAllowsARetry() {
        LikedOnce likes = new LikedOnce();
        assertTrue(likes.claim("12345"));
        likes.release("12345");
        assertFalse(likes.isClaimed("12345"));
        assertTrue(likes.claim("12345"));
    }

    @Test public void releasingSomethingNobodyClaimedIsANoop() {
        LikedOnce likes = new LikedOnce();
        likes.release("12345");
        likes.release(null);
        assertEquals(0, likes.size());
    }

    /**
     * A blank id is not a person. Claiming it would let one unusable card
     * block every other card that failed to resolve an id.
     */
    @Test public void blankIdsAreNeverClaimable() {
        LikedOnce likes = new LikedOnce();
        assertFalse(likes.claim(null));
        assertFalse(likes.claim(""));
        assertFalse(likes.claim("   "));
        assertEquals(0, likes.size());
        assertFalse(likes.isClaimed(null));
        assertFalse(likes.isClaimed(""));
    }

    /** What a recycled card reads to come back already spent. */
    @Test public void isClaimedReportsTheClaim() {
        LikedOnce likes = new LikedOnce();
        assertFalse(likes.isClaimed("12345"));
        likes.claim("12345");
        assertTrue(likes.isClaimed("12345"));
        assertFalse(likes.isClaimed("67890"));
    }

    @Test public void resetClearsEverything() {
        LikedOnce likes = new LikedOnce();
        likes.claim("12345");
        likes.reset();
        assertEquals(0, likes.size());
        assertTrue(likes.claim("12345"));
    }

    /** The production path is a single process-wide instance. */
    @Test public void sharedInstanceIsStable() {
        assertTrue(LikedOnce.get() == LikedOnce.get());
    }
}

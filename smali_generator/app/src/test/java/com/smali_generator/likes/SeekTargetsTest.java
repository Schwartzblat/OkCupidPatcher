package com.smali_generator.likes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class SeekTargetsTest {

    private static PageParser.Attributes attrs(Integer age, Double score) {
        return new PageParser.Attributes(age, score, null, null, null, null);
    }

    private static String id(int i) {
        return String.format("realid%016d", i);
    }

    @Test public void picksOnlyCardsWithoutAnId() {
        SweepStore store = new SweepStore();
        store.upsert("/a.jpeg", id(1), attrs(28, 0.9), 0, 1L);
        store.upsert("/b.jpeg", null, attrs(29, 0.8), 1, 1L);
        store.upsert("/c.jpeg", id(3), attrs(30, 0.7), 2, 1L);

        List<String> targets = SeekTargets.pick(store, 10);

        assertEquals(1, targets.size());
        assertEquals("/b.jpeg", targets.get(0));
    }

    @Test public void newestUnnamedComesFirst() {
        // DESC_TIMESTAMP: position 0 is the most recent like, which is the
        // card a new arrival creates and the one the user is waiting on.
        SweepStore store = new SweepStore();
        store.upsert("/old.jpeg", null, attrs(28, 0.9), 90, 1L);
        store.upsert("/new.jpeg", null, attrs(29, 0.8), 0, 1L);

        List<String> targets = SeekTargets.pick(store, 10);

        assertEquals("/new.jpeg", targets.get(0));
        assertEquals("/old.jpeg", targets.get(1));
    }

    @Test public void cardsCarryingAnAttributeKeyOutrankOnesWithout() {
        // bringIntoView can only aim with an attribute; a card with none can
        // only be found by a blind walk, so it must not consume the budget first.
        SweepStore store = new SweepStore();
        store.upsert("/bare.jpeg", null, null, 0, 1L);
        store.upsert("/keyed.jpeg", null, attrs(29, 0.8), 50, 1L);

        List<String> targets = SeekTargets.pick(store, 10);

        assertEquals("/keyed.jpeg", targets.get(0));
        assertEquals("/bare.jpeg", targets.get(1));
    }

    @Test public void honoursTheCap() {
        SweepStore store = new SweepStore();
        for (int i = 0; i < 10; i++) {
            store.upsert("/p" + i + ".jpeg", null, attrs(20 + i, 0.5), i, 1L);
        }
        assertEquals(3, SeekTargets.pick(store, 3).size());
    }

    @Test public void aCapOfZeroPicksNothing() {
        SweepStore store = new SweepStore();
        store.upsert("/a.jpeg", null, attrs(28, 0.9), 0, 1L);
        assertTrue(SeekTargets.pick(store, 0).isEmpty());
    }

    @Test public void aFullyNamedStorePicksNothing() {
        SweepStore store = new SweepStore();
        store.upsert("/a.jpeg", id(1), attrs(28, 0.9), 0, 1L);
        assertTrue(SeekTargets.pick(store, 10).isEmpty());
    }

    @Test public void aNullStoreIsEmptyNotACrash() {
        assertTrue(SeekTargets.pick(null, 10).isEmpty());
    }

    @Test public void theOrderIsStableAcrossCalls() {
        SweepStore store = new SweepStore();
        for (int i = 0; i < 6; i++) {
            store.upsert("/p" + i + ".jpeg", null, attrs(20 + i, 0.5), i, 1L);
        }
        assertEquals(SeekTargets.pick(store, 6), SeekTargets.pick(store, 6));
    }
}

package com.smali_generator.likes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

public class FrontierTest {

    @Test public void startsWithTheDefaultSortAndNoCursor() {
        Frontier f = new Frontier(Arrays.asList("A", "B"), 60);
        Frontier.Probe p = f.next();
        assertEquals("A", p.sort);
        assertNull(p.cursor);           // page 1 of the first sort
    }

    @Test public void neverRepeatsAPair() {
        Frontier f = new Frontier(Arrays.asList("A", "B"), 60);
        f.addCursor("c1");
        Set<String> seen = new HashSet<String>();
        Frontier.Probe p;
        while ((p = f.next()) != null) {
            assertTrue(seen.add(p.sort + "|" + p.cursor));
        }
        // 2 sorts x (null + c1) = 4 pairs
        assertEquals(4, seen.size());
    }

    @Test public void newCursorsExpandTheFrontier() {
        Frontier f = new Frontier(Arrays.asList("A"), 60);
        assertNotNull(f.next());        // A/null
        assertNull(f.next());           // exhausted
        f.addCursor("c1");
        Frontier.Probe p = f.next();
        assertEquals("c1", p.cursor);
    }

    @Test public void duplicateCursorsAreIgnored() {
        Frontier f = new Frontier(Arrays.asList("A"), 60);
        f.addCursor("c1");
        f.addCursor("c1");
        f.next();
        f.next();
        assertNull(f.next());
    }

    @Test public void stopsAtTheCap() {
        Frontier f = new Frontier(Arrays.asList("A", "B", "C"), 2);
        assertNotNull(f.next());
        assertNotNull(f.next());
        assertNull(f.next());
        assertEquals(2, f.issued());
    }

    @Test public void nullAndBlankCursorsAreIgnored() {
        Frontier f = new Frontier(Arrays.asList("A"), 60);
        f.addCursor(null);
        f.addCursor("  ");
        f.next();
        assertNull(f.next());
    }

    @Test public void capHoldsEvenWhenCursorsKeepArriving() {
        Frontier f = new Frontier(Arrays.asList("A", "B"), 3);
        int handed = 0;
        for (int i = 0; i < 20; i++) {
            f.addCursor("c" + i);
            if (f.next() != null) handed++;
        }
        assertEquals(3, handed);
        assertEquals(3, f.issued());
        assertNull(f.next());
    }

    @Test public void zeroCapIssuesNothing() {
        Frontier f = new Frontier(Arrays.asList("A"), 0);
        assertNull(f.next());
        assertEquals(0, f.issued());
    }

    @Test public void literalNullTextCursorIsNotConfusedWithPageOne() {
        Frontier f = new Frontier(Arrays.asList("A"), 60);
        f.addCursor("null");
        assertNull(f.next().cursor);
        assertEquals("null", f.next().cursor);
        assertNull(f.next());
    }

    @Test public void separatorInsideNamesDoesNotCollideTwoPairs() {
        Frontier f = new Frontier(Arrays.asList("a", "a|b"), 60);
        f.addCursor("b|c");
        f.addCursor("c");
        Set<String> seen = new HashSet<String>();
        Frontier.Probe p;
        while ((p = f.next()) != null) {
            assertTrue(seen.add(p.sort + "\u0000" + p.cursor));
        }
        assertEquals(6, seen.size());
    }
}

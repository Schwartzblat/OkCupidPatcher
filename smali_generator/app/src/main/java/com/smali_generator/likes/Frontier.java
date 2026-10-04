package com.smali_generator.likes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which (sort order, cursor) pairs are still worth asking for.
 *
 * A cursor is honoured under a different sort than the one that produced it,
 * which is what turns a handful of page boundaries into a traversal. The cap
 * is the only thing bounding it, so it is not optional.
 */
public final class Frontier {

    public static final class Probe {
        public final String sort;       // always a real sort name (usableSortNames drops nulls)
        public final String cursor;     // null means page 1

        Probe(String sort, String cursor) {
            this.sort = sort;
            this.cursor = cursor;
        }
    }

    private final List<String> sorts;
    private final LinkedHashSet<String> cursors = new LinkedHashSet<String>();
    // Keyed by the pair itself, not a joined string: a joined key collides when a
    // cursor reads "null" or a name contains the separator, silently skipping a probe.
    private final Set<List<String>> tried = new HashSet<List<String>>();
    private final int maxRequests;
    private int issued;

    public Frontier(List<String> sortNames, int maxRequests) {
        this.sorts = new ArrayList<String>(sortNames);
        this.maxRequests = maxRequests;
        this.cursors.add(null);         // page 1 is always a valid probe
    }

    public synchronized void addCursor(String cursor) {
        if (cursor == null || cursor.trim().isEmpty()) {
            return;
        }
        cursors.add(cursor);
    }

    /**
     * Admits a specific (sort, cursor) pair chosen by the planner, under the
     * same bookkeeping {@link #next()} uses: the cap and the tried-set. Null
     * means the planner's candidate is spent (cap reached, or already
     * issued) -- the caller falls back to {@link #next()} for this turn
     * rather than wasting a request repeating a probe.
     */
    public synchronized Probe tryProbe(String sort, String cursor) {
        if (sort == null || cursor == null || issued >= maxRequests) {
            return null;
        }
        cursors.add(cursor);
        if (tried.add(Arrays.asList(sort, cursor))) {
            issued++;
            return new Probe(sort, cursor);
        }
        return null;
    }

    public synchronized Probe next() {
        if (issued >= maxRequests) {
            return null;
        }
        for (String sort : sorts) {
            for (String cursor : new ArrayList<String>(cursors)) {
                if (tried.add(Arrays.asList(sort, cursor))) {
                    issued++;
                    return new Probe(sort, cursor);
                }
            }
        }
        return null;
    }

    public synchronized int issued() {
        return issued;
    }
}

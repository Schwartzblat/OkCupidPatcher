package com.smali_generator.likes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Which nameless cards are worth a targeted seek this pass, and in what
 * order. Pure, so the budget policy is pinned by tests rather than by the
 * order {@link SweepStore} happens to iterate in.
 *
 * <p>Two rules, in order:
 *
 * <ol>
 * <li><b>An attribute key first.</b> {@link SeekPlanner}'s "bring into view"
 * step aims by age, match score, online flag or location summary. A card
 * carrying none of those can only be found by a blind walk, so it must not
 * spend the budget ahead of one that can be aimed at.
 * <li><b>Then newest first.</b> Offset 0 in {@code DESC_TIMESTAMP} is the
 * most recent like -- the card a new arrival creates, and the one the user
 * is actually waiting to see named.
 * </ol>
 *
 * Ties break on the photo path purely so the order is stable across calls;
 * nothing depends on which card wins a tie.
 */
public final class SeekTargets {

    private SeekTargets() {
    }

    /**
     * @param cap the most cards to seek in one pass. Each one costs up to
     *     {@link LikesIdentityResolver#MAX_REQUESTS}, so this is what keeps a
     *     pass bounded when many cards are nameless at once.
     * @return photo paths, most worth seeking first; never null
     */
    public static List<String> pick(SweepStore store, int cap) {
        List<String> out = new ArrayList<String>();
        if (store == null || cap <= 0) {
            return out;
        }
        List<SweepStore.Record> unnamed = new ArrayList<SweepStore.Record>();
        for (SweepStore.Record r : store.all()) {
            if (r != null && r.photoPath != null && r.realId == null) {
                unnamed.add(r);
            }
        }
        Collections.sort(unnamed, new Comparator<SweepStore.Record>() {
            @Override public int compare(SweepStore.Record a, SweepStore.Record b) {
                boolean aKeyed = hasAimableKey(a);
                boolean bKeyed = hasAimableKey(b);
                if (aKeyed != bKeyed) {
                    return aKeyed ? -1 : 1;
                }
                if (a.position != b.position) {
                    return a.position < b.position ? -1 : 1;
                }
                return a.photoPath.compareTo(b.photoPath);
            }
        });
        for (SweepStore.Record r : unnamed) {
            if (out.size() >= cap) {
                break;
            }
            out.add(r.photoPath);
        }
        return out;
    }

    /** Exactly the keys {@code SeekPlanner.bringIntoViewForSort} can aim with. */
    private static boolean hasAimableKey(SweepStore.Record r) {
        return r.age != null || r.matchScore != null || r.isOnline != null
                || r.locationSummary != null;
    }
}

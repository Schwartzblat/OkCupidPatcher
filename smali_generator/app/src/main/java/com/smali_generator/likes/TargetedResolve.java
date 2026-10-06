package com.smali_generator.likes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Names the specific cards that have no id yet, by aiming at each one, rather
 * than re-walking every sort in the hope that a window boundary lands on one.
 *
 * <p><b>Why this replaces the blind ladder.</b> A response names exactly one
 * card -- the one its {@code pageInfo.after} points at, which is that window's
 * last entry. So naming {@code n} cards cannot cost fewer than {@code n}
 * requests, and any strategy that spends more than about one request per card
 * is wasting the difference. Walking a whole sort from page 1 spends a request
 * per window and names one card per window, so it only pays while most cards
 * are unknown; once the store is nearly complete it spends ~60 requests per
 * sort to name nothing. Measured on a 123-card list with one new like: the
 * blind ladder spent 364 requests to name one card, 121 of them in a phase
 * that provably could not reach it.
 *
 * <p><b>The aim.</b> To make the target the last entry of a window, the cursor
 * must name whoever sits exactly one window before it ({@link
 * #anchorOffsetFor}). Two things supply the target's offset:
 *
 * <ol>
 * <li>the swept sort, whose offsets {@link SweepStore} already holds on disk
 * -- so a card deep in the list usually costs a single request; and
 * <li>any other sort, walked from page 1 only until the window containing the
 * target appears. That walk is also what places the target, and every window
 * of it names its own boundary, so none of those requests are wasted either.
 * This is the path a brand-new like needs: it sits at offset 0 of the swept
 * sort, where nothing is twenty places before it.
 * </ol>
 *
 * <p>When the occupant of {@code target - 20} is itself unknown, the next
 * anchor in the same residue ({@code target - 40}, {@code - 60} ...) does the
 * job in more than one window -- and each of those windows names its own
 * boundary on the way, so a {@code k}-window chain names {@code k} cards.
 *
 * <p>Pure except for the injected fetcher: no Android, no transport, no clock
 * of its own, so the whole policy is unit-tested against a simulated server.
 */
final class TargetedResolve {

    /** What id, if any, sits at an absolute offset of the order being reasoned about. */
    interface Ids {
        String idAt(int offset);
    }

    static final class Result {
        final int requests;
        final int named;

        Result(int requests, int named) {
            this.requests = requests;
            this.named = named;
        }
    }

    private TargetedResolve() {
    }

    /**
     * The offset to anchor a walk on so that one of its windows ends exactly
     * on {@code targetOffset}: the nearest offset below the target that is
     * congruent to it modulo {@code window} and whose occupant already has an
     * id. Nearest, because the walk from it costs one request per window
     * crossed.
     *
     * @return that offset, or -1 when the target cannot be reached in this
     *     order -- in particular for any target inside the first window,
     *     which is where a brand-new like sits.
     */
    static int anchorOffsetFor(int targetOffset, int window, Ids known) {
        if (known == null || window <= 0) {
            return -1;
        }
        for (int offset = targetOffset - window; offset >= 0; offset -= window) {
            if (known.idAt(offset) != null) {
                return offset;
            }
        }
        return -1;
    }

    /**
     * Names as many of {@code targets} as the budget allows.
     *
     * @param targets photo paths with no id yet
     * @param primarySort the sort {@link SweepStore.Record#position} is
     *     measured in -- the only one whose offsets are known for free
     * @param sorts every sort the app offers, for the targets the primary
     *     sort cannot reach. Pass only the primary sort to stay inside it.
     * @param maxRequests the whole call's request budget
     */
    static Result resolve(List<String> targets, SweepStore store, String primarySort,
                          List<String> sorts, LikesSweep.Fetcher fetcher,
                          LikesSweep.Sleeper sleeper, LikesSweep.Clock clock, int maxRequests) {
        Budget budget = new Budget(maxRequests);
        int namedBefore = countNamed(store, targets);
        if (targets == null || targets.isEmpty() || !Cursors.canMint()) {
            return new Result(0, 0);
        }

        // Pass A: the swept sort, whose offsets cost nothing to know.
        aimWithin(primarySort, offsetsFromStore(store), store, targets, true,
                fetcher, sleeper, clock, budget);

        // Pass B: the other orders, for whatever pass A could not reach --
        // a target inside the swept sort's first window, or one whose whole
        // residue is still unknown there.
        if (sorts != null) {
            for (String sort : sorts) {
                if (budget.spent() || remaining(store, targets).isEmpty()) {
                    break;
                }
                if (sort == null || (primarySort != null && sort.equalsIgnoreCase(primarySort))) {
                    continue;
                }
                Map<String, Integer> offsets = placeTargets(sort, store, targets, fetcher,
                        sleeper, clock, budget);
                aimWithin(sort, offsets, store, targets, false, fetcher, sleeper, clock, budget);
            }
        }
        return new Result(budget.used(), countNamed(store, targets) - namedBefore);
    }

    /**
     * Walks {@code sort} from page 1 only as far as it has to: it stops as
     * soon as every still-unnamed target has been seen in a window, so a
     * target early in that order costs one request rather than a full walk.
     *
     * @return each card's offset in {@code sort}, for every card this walk saw
     */
    private static Map<String, Integer> placeTargets(String sort, final SweepStore store,
                                                     final List<String> targets,
                                                     LikesSweep.Fetcher fetcher,
                                                     LikesSweep.Sleeper sleeper,
                                                     LikesSweep.Clock clock, Budget budget) {
        final Map<String, Integer> offsets = new HashMap<String, Integer>();
        LikesSweep.Result walk = LikesSweep.sweep(sort, fetcher, sleeper, clock, store,
                false, false, null, 0,
                new LikesSweep.PositionSink() {
                    @Override public void at(String photoPath, int position) {
                        offsets.put(photoPath, Integer.valueOf(position));
                    }
                },
                new LikesSweep.Continuation() {
                    @Override public boolean more(SweepStore s) {
                        for (String target : remaining(s, targets)) {
                            if (!offsets.containsKey(target)) {
                                return true;        // still looking for this one
                            }
                        }
                        return false;               // every target placed
                    }
                },
                budget.left());
        budget.charge(walk.requests);
        return offsets;
    }

    /**
     * Aims one chain walk at each target reachable in {@code sort}, nearest
     * target first so that the cards a walk names on its way become anchors
     * for the ones after it.
     *
     * @param offsets each card's offset in this sort
     * @param ownFrame true only for the sort {@code position} is measured in,
     *     where recorded offsets may be written back
     */
    private static void aimWithin(String sort, Map<String, Integer> offsets, SweepStore store,
                                  List<String> targets, boolean ownFrame,
                                  LikesSweep.Fetcher fetcher, LikesSweep.Sleeper sleeper,
                                  LikesSweep.Clock clock, Budget budget) {
        if (sort == null || offsets.isEmpty()) {
            return;
        }
        List<String> queue = byOffset(remaining(store, targets), offsets);
        for (final String target : queue) {
            if (budget.spent()) {
                return;
            }
            if (store.get(target) == null || store.get(target).realId != null) {
                continue;                   // an earlier chain already named it
            }
            Integer targetOffset = offsets.get(target);
            if (targetOffset == null) {
                continue;                   // this order never showed us where it is
            }
            int anchorOffset = anchorOffsetFor(targetOffset.intValue(), LikesSweep.WINDOW,
                    idsAt(store, offsets));
            if (anchorOffset < 0) {
                continue;
            }
            String anchorId = idsAt(store, offsets).idAt(anchorOffset);
            String cursor = Cursors.encode(anchorId);
            if (cursor == null) {
                continue;
            }
            LikesSweep.Result walk = LikesSweep.sweep(sort, fetcher, sleeper, clock, store,
                    ownFrame, false, cursor, anchorOffset + 1, null,
                    new LikesSweep.Continuation() {
                        @Override public boolean more(SweepStore s) {
                            SweepStore.Record r = s.get(target);
                            return r == null || r.realId == null;   // stop the moment it is named
                        }
                    },
                    budget.left());
            budget.charge(walk.requests);
        }
    }

    /** The swept sort's offsets, straight off disk -- the cheapest aim there is. */
    private static Map<String, Integer> offsetsFromStore(SweepStore store) {
        Map<String, Integer> offsets = new HashMap<String, Integer>();
        for (SweepStore.Record r : store.all()) {
            if (r.photoPath != null) {
                offsets.put(r.photoPath, Integer.valueOf(r.position));
            }
        }
        return offsets;
    }

    /** Offset -> the id of whoever is there, for the two maps an aim needs. */
    private static Ids idsAt(final SweepStore store, final Map<String, Integer> offsets) {
        final Map<Integer, String> idByOffset = new HashMap<Integer, String>();
        for (Map.Entry<String, Integer> e : offsets.entrySet()) {
            SweepStore.Record r = store.get(e.getKey());
            if (r != null && r.realId != null && !IdentityStore.isPlaceholder(r.realId)) {
                idByOffset.put(e.getValue(), r.realId);
            }
        }
        return new Ids() {
            @Override public String idAt(int offset) {
                return idByOffset.get(Integer.valueOf(offset));
            }
        };
    }

    /** Targets that still have no id. */
    private static List<String> remaining(SweepStore store, List<String> targets) {
        List<String> out = new ArrayList<String>();
        if (targets == null) {
            return out;
        }
        for (String path : targets) {
            SweepStore.Record r = store.get(path);
            if (r != null && r.realId == null) {
                out.add(path);
            }
        }
        return out;
    }

    /** Nearest first: a chain walk names every boundary it crosses, so order compounds. */
    private static List<String> byOffset(List<String> paths, final Map<String, Integer> offsets) {
        List<String> sorted = new ArrayList<String>(paths);
        Collections.sort(sorted, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                Integer oa = offsets.get(a);
                Integer ob = offsets.get(b);
                int ia = oa == null ? Integer.MAX_VALUE : oa.intValue();
                int ib = ob == null ? Integer.MAX_VALUE : ob.intValue();
                if (ia != ib) {
                    return ia < ib ? -1 : 1;
                }
                return a.compareTo(b);
            }
        });
        return sorted;
    }

    private static int countNamed(SweepStore store, List<String> targets) {
        int n = 0;
        if (targets == null) {
            return 0;
        }
        for (String path : targets) {
            SweepStore.Record r = store.get(path);
            if (r != null && r.realId != null) {
                n++;
            }
        }
        return n;
    }

    /** One call's request budget, shared by every walk it issues. */
    private static final class Budget {
        private final int max;
        private int used;

        Budget(int max) {
            this.max = Math.max(0, max);
        }

        int left() {
            return max - used;
        }

        boolean spent() {
            return used >= max;
        }

        void charge(int requests) {
            used += requests;
        }

        int used() {
            return used;
        }
    }
}

package com.smali_generator.likes;

import java.util.List;

/**
 * Chooses the next (sort, cursor) probe from what {@link ObservationCache}
 * already knows, instead of pulling blindly from {@link Frontier}.
 *
 * <p>Two strategies, tried in order, both pure functions over the cache:
 *
 * <ol>
 * <li><b>Exact seek.</b> The target was already seen, in some sort's window,
 * at raw position {@code j} (0-indexed, {@link PageParser.Entry#index}) of a
 * window of length {@code L} (the widest window observed for that sort). A
 * window fetched with cursor {@code after(X)} returns the {@code L} entries
 * immediately following {@code X}, so to make the target the LAST entry of a
 * fresh window we need an anchor {@code X} whose absolute offset is exactly
 * {@code target.offset - L}: the next {@code L}-entry window starting right
 * after it ends exactly on the target. If an id is already known at that
 * exact offset, minting its cursor is a one-shot seek -- no estimate, no tie
 * group, just arithmetic. (If {@code j == L - 1} the target IS already the
 * boundary and would have resolved already; this path only fires for a
 * smaller {@code j}, i.e. {@code target.offset - L >= 0}.)
 *
 * <li><b>Bring into view.</b> No exact anchor is known yet. Pick the sort
 * whose key we can read off the target's own highlights (age, matchScore,
 * isOnline, or the location summary) and jump to the nearest candidate we
 * have already placed on that same key, so the next response very likely
 * contains the target somewhere in its window -- which is new information
 * (a fresh {@code j}), not a guess to be trusted outright.
 * </ol>
 *
 * Neither strategy mutates anything; {@link LikesIdentityResolver} is the
 * only place a probe actually gets issued.
 */
public final class SeekPlanner {

    public static final class Plan {
        public final String sort;
        public final String cursor;      // never null -- a null-cursor probe is Frontier's job

        Plan(String sort, String cursor) {
            this.sort = sort;
            this.cursor = cursor;
        }
    }

    private SeekPlanner() {
    }

    public static Plan plan(String targetPhotoPath, List<String> sorts, ObservationCache cache) {
        if (targetPhotoPath == null || sorts == null) {
            return null;
        }
        Plan exact = exactSeek(targetPhotoPath, sorts, cache);
        if (exact != null) {
            return exact;
        }
        return bringIntoView(targetPhotoPath, sorts, cache);
    }

    private static Plan exactSeek(String targetPhotoPath, List<String> sorts, ObservationCache cache) {
        for (String sort : sorts) {
            Integer targetOffset = cache.offsetOf(sort, targetPhotoPath);
            Integer length = cache.windowLength(sort);
            if (targetOffset == null || length == null) {
                continue;
            }
            int anchorOffset = targetOffset.intValue() - length.intValue();
            if (anchorOffset < 0) {
                continue;             // the target is too close to page 1 in this sort
            }
            String anchorId = cache.idAtOffset(sort, anchorOffset);
            if (anchorId == null) {
                continue;
            }
            String cursor = Cursors.canMint() ? Cursors.encode(anchorId) : null;
            if (cursor != null) {
                return new Plan(sort, cursor);
            }
        }
        return null;
    }

    private static Plan bringIntoView(String targetPhotoPath, List<String> sorts, ObservationCache cache) {
        PageParser.Attributes target = cache.attributesOf(targetPhotoPath);
        if (target == null) {
            return null;
        }
        for (String sort : sorts) {
            Plan p = bringIntoViewForSort(sort, target, cache);
            if (p != null) {
                return p;
            }
        }
        return null;
    }

    private static Plan bringIntoViewForSort(String sort, PageParser.Attributes target,
                                             ObservationCache cache) {
        List<ObservationCache.Candidate> candidates = cache.candidates(sort);
        if (candidates.isEmpty()) {
            return null;
        }
        String key = sort == null ? "" : sort.toUpperCase(java.util.Locale.ROOT);

        if (target.age != null && key.contains("AGE")) {
            ObservationCache.Candidate best = nearestNumeric(candidates, target.age.doubleValue(), true);
            return mint(sort, best);
        }
        if (target.matchScore != null && (key.contains("SCORE") || key.contains("MATCH"))) {
            ObservationCache.Candidate best = nearestNumeric(candidates, target.matchScore.doubleValue(), false);
            return mint(sort, best);
        }
        if (target.isOnline != null
                && (key.contains("ONLINE") || key.contains("ACTIVE") || key.contains("RECENT"))) {
            for (ObservationCache.Candidate c : candidates) {
                if (c.attributes != null && target.isOnline.equals(c.attributes.isOnline)) {
                    return mint(sort, c);
                }
            }
        }
        if (target.locationSummary != null && (key.contains("DISTANCE") || key.contains("LOCATION"))) {
            for (ObservationCache.Candidate c : candidates) {
                if (c.attributes != null && target.locationSummary.equals(c.attributes.locationSummary)) {
                    return mint(sort, c);
                }
            }
        }
        return null;
    }

    private static ObservationCache.Candidate nearestNumeric(List<ObservationCache.Candidate> candidates,
                                                              double targetValue, boolean useAge) {
        ObservationCache.Candidate best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ObservationCache.Candidate c : candidates) {
            if (c.attributes == null) {
                continue;
            }
            Double value = useAge
                    ? (c.attributes.age == null ? null : Double.valueOf(c.attributes.age.doubleValue()))
                    : c.attributes.matchScore;
            if (value == null) {
                continue;
            }
            double distance = Math.abs(value.doubleValue() - targetValue);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = c;
            }
        }
        return best;
    }

    private static Plan mint(String sort, ObservationCache.Candidate c) {
        if (c == null) {
            return null;
        }
        String cursor = Cursors.canMint() ? Cursors.encode(c.id) : null;
        return cursor == null ? null : new Plan(sort, cursor);
    }
}

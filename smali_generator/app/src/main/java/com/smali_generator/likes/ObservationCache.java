package com.smali_generator.likes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything learned about every sort order, for the process lifetime only --
 * never written to disk, same posture as {@link IdentityStore}: this is
 * recovered third-party data.
 *
 * <p>Per sort, this tracks each seen entry's position in that sort's
 * absolute order, rooted at page 1 (absolute offset 0). A window fetched with
 * cursor {@code C} starts at {@code offsetOf(decode(C)) + 1}; page 1 starts
 * at 0. A window whose start cannot yet be placed (its cursor names an id we
 * have not placed either) is first checked for a SHARED entry against
 * something already placed -- two windows that share one entry become one
 * longer known run, exactly as intended -- and only given up on if neither
 * the cursor's id nor any entry in the window is already placed.
 */
public final class ObservationCache {

    private static final ObservationCache INSTANCE = new ObservationCache();

    /**
     * The shared, process-lifetime cache. Every tap's resolution feeds this
     * same instance, which is the entire point of task 2: a second tap
     * benefits from everything the first one learned. Tests use {@code new
     * ObservationCache()} for an isolated instance instead.
     */
    public static ObservationCache get() {
        return INSTANCE;
    }

    private static final class SortTrack {
        final Map<String, Integer> offsetByPhotoPath = new HashMap<String, Integer>();
        final Map<String, Integer> offsetById = new HashMap<String, Integer>();
        final Map<Integer, String> idByOffset = new HashMap<Integer, String>();
        final Map<String, PageParser.Attributes> attributesByPhotoPath =
                new HashMap<String, PageParser.Attributes>();
        Integer maxWindowLength;
    }

    private final Map<String, SortTrack> bySort = new HashMap<String, SortTrack>();

    /**
     * @param sort          the sort this window was fetched under
     * @param requestCursor the raw cursor sent as {@code nextPageKey} (null for page 1)
     * @param page          the parsed response
     * @param boundary      the already-computed {@link BoundaryBinder} decision for this
     *                      same page, so the id it binds is folded into the same offsets
     */
    public synchronized void absorb(String sort, String requestCursor, PageParser.Page page,
                                    BoundaryBinder.Decision boundary) {
        if (sort == null || page == null || page.rawEntries.isEmpty()) {
            return;
        }
        SortTrack track = track(sort);
        int length = page.rawEntries.size();
        if (track.maxWindowLength == null || length > track.maxWindowLength) {
            track.maxWindowLength = length;
        }

        Integer start = startOffset(track, requestCursor);
        if (start == null) {
            start = stitchedStart(track, page);
        }

        if (start == null) {
            // Can't place this window's entries absolutely yet; still worth
            // keeping their attributes for the key-based "bring into view"
            // step, which does not need an offset.
            for (PageParser.Entry e : page.entries) {
                rememberAttributes(track, e);
            }
            return;
        }

        for (PageParser.Entry e : page.entries) {
            int abs = start + e.index;
            track.offsetByPhotoPath.put(e.photoPath, Integer.valueOf(abs));
            rememberAttributes(track, e);
            if (e.realId != null && !IdentityStore.isPlaceholder(e.realId)) {
                track.offsetById.put(e.realId, Integer.valueOf(abs));
                track.idByOffset.put(Integer.valueOf(abs), e.realId);
            }
        }
        if (boundary != null && boundary.bound) {
            int abs = start + (length - 1);
            track.offsetByPhotoPath.put(boundary.path, Integer.valueOf(abs));
            track.offsetById.put(boundary.id, Integer.valueOf(abs));
            track.idByOffset.put(Integer.valueOf(abs), boundary.id);
        }
    }

    private static void rememberAttributes(SortTrack track, PageParser.Entry e) {
        if (e.attributes != null) {
            track.attributesByPhotoPath.put(e.photoPath, e.attributes);
        }
    }

    private Integer startOffset(SortTrack track, String requestCursor) {
        if (requestCursor == null) {
            return Integer.valueOf(0);
        }
        String id = Cursors.decode(requestCursor);
        if (id == null) {
            return null;
        }
        Integer anchor = track.offsetById.get(id);
        return anchor == null ? null : Integer.valueOf(anchor.intValue() + 1);
    }

    /** Back out this window's start from any entry it shares with an already-placed one. */
    private Integer stitchedStart(SortTrack track, PageParser.Page page) {
        for (PageParser.Entry e : page.entries) {
            Integer known = track.offsetByPhotoPath.get(e.photoPath);
            if (known != null) {
                return Integer.valueOf(known.intValue() - e.index);
            }
        }
        return null;
    }

    public synchronized int size() {
        int n = 0;
        for (SortTrack t : bySort.values()) {
            n += t.offsetByPhotoPath.size();
        }
        return n;
    }

    public synchronized boolean hasTarget(String photoPath) {
        for (SortTrack t : bySort.values()) {
            if (t.offsetByPhotoPath.containsKey(photoPath) || t.attributesByPhotoPath.containsKey(photoPath)) {
                return true;
            }
        }
        return false;
    }

    public synchronized Integer offsetOf(String sort, String photoPath) {
        SortTrack track = bySort.get(sort);
        return track == null ? null : track.offsetByPhotoPath.get(photoPath);
    }

    public synchronized String idAtOffset(String sort, int offset) {
        SortTrack track = bySort.get(sort);
        return track == null ? null : track.idByOffset.get(Integer.valueOf(offset));
    }

    /** The widest window observed for this sort, or null if none yet. */
    public synchronized Integer windowLength(String sort) {
        SortTrack track = bySort.get(sort);
        return track == null ? null : track.maxWindowLength;
    }

    /** The target's attributes, from whichever sort first observed them (intrinsic to the person). */
    public synchronized PageParser.Attributes attributesOf(String photoPath) {
        for (SortTrack track : bySort.values()) {
            PageParser.Attributes a = track.attributesByPhotoPath.get(photoPath);
            if (a != null) {
                return a;
            }
        }
        return null;
    }

    /** Every (id, offset, attributes) triple known for a sort, for the planner's nearest-key search. */
    public synchronized List<Candidate> candidates(String sort) {
        SortTrack track = bySort.get(sort);
        List<Candidate> out = new ArrayList<Candidate>();
        if (track == null) {
            return out;
        }
        for (Map.Entry<Integer, String> e : track.idByOffset.entrySet()) {
            PageParser.Attributes attrs = null;
            for (Map.Entry<String, Integer> p : track.offsetByPhotoPath.entrySet()) {
                if (p.getValue().equals(e.getKey())) {
                    attrs = track.attributesByPhotoPath.get(p.getKey());
                    break;
                }
            }
            out.add(new Candidate(e.getValue(), e.getKey().intValue(), attrs));
        }
        return out;
    }

    private SortTrack track(String sort) {
        SortTrack track = bySort.get(sort);
        if (track == null) {
            track = new SortTrack();
            bySort.put(sort, track);
        }
        return track;
    }

    public static final class Candidate {
        public final String id;
        public final int offset;
        public final PageParser.Attributes attributes;   // may be null

        Candidate(String id, int offset, PageParser.Attributes attributes) {
            this.id = id;
            this.offset = offset;
            this.attributes = attributes;
        }
    }
}

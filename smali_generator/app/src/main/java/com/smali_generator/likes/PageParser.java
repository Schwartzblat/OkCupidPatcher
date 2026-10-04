package com.smali_generator.likes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Reads one LikesYouPageQuery response. Pure org.json, no Android imports.
 *
 * The two org.json implementations (reference on the JVM, Android's on device)
 * disagree on optString(key, fallback) for a JSON null: Android returns the
 * string "null". Every nullable string is therefore read through nullableString,
 * which uses isNull, so JSON null or a missing key is Java null on both.
 */
public final class PageParser {

    /**
     * {@code UserPreviewMatchHighlightsFragment} on a gated {@code
     * MatchPreview} -- the sort keys the seek planner reasons about. All
     * fields are nullable: any of them may be absent on a given entry, and a
     * missing field simply means that key is unusable for this entry, never
     * a parse failure.
     */
    public static final class Attributes {
        public final Integer age;
        public final Double matchScore;
        public final Boolean isOnline;
        public final Boolean isVerified;
        public final Boolean hasIntroMessage;
        public final String locationSummary;   // dynamicHighlight's LocationHighlight.summary

        Attributes(Integer age, Double matchScore, Boolean isOnline, Boolean isVerified,
                   Boolean hasIntroMessage, String locationSummary) {
            this.age = age;
            this.matchScore = matchScore;
            this.isOnline = isOnline;
            this.isVerified = isVerified;
            this.hasIntroMessage = hasIntroMessage;
            this.locationSummary = locationSummary;
        }

        /** True iff every field is null -- nothing here is worth keeping. */
        boolean isEmpty() {
            return age == null && matchScore == null && isOnline == null && isVerified == null
                    && hasIntroMessage == null && locationSummary == null;
        }
    }

    public static final class Entry {
        public final String photoPath;
        public final String realId;     // null for gated MatchPreview entries
        /**
         * This entry's position among {@link Page#rawEntries}, skipped nodes
         * included. The seek planner's exact-seek arithmetic is defined over
         * this raw position, not the filtered {@link Page#entries} index.
         */
        public final int index;
        public final Attributes attributes;   // null when no highlight carried any value

        Entry(String photoPath, String realId, int index, Attributes attributes) {
            this.photoPath = photoPath;
            this.realId = realId;
            this.index = index;
            this.attributes = attributes;
        }
    }

    public static final class Page {
        public final List<Entry> entries;
        /**
         * One element per raw node in source order, including nodes that were
         * skipped from {@link #entries}. The cursor names the server page's
         * actual final node, so the boundary decision must see the skipped
         * ones too: {@link BoundaryBinder#decide} takes this list.
         */
        public final List<BoundaryBinder.CardEntry> rawEntries;
        public final String after;
        public final boolean hasMore;
        public final int total;

        Page(List<Entry> entries, List<BoundaryBinder.CardEntry> rawEntries,
             String after, boolean hasMore, int total) {
            this.entries = Collections.unmodifiableList(entries);
            this.rawEntries = Collections.unmodifiableList(rawEntries);
            this.after = after;
            this.hasMore = hasMore;
            this.total = total;
        }
    }

    private PageParser() {
    }

    public static Page parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            JSONObject connection = new JSONObject(json)
                    .getJSONObject("data").getJSONObject("me")
                    .getJSONObject("likesIncomingWithPreviews");

            JSONArray data = connection.optJSONArray("data");
            List<Entry> entries = new ArrayList<Entry>();
            List<BoundaryBinder.CardEntry> raw = new ArrayList<BoundaryBinder.CardEntry>();
            for (int i = 0; data != null && i < data.length(); i++) {
                JSONObject node = data.optJSONObject(i);
                if (node == null) {
                    raw.add(new BoundaryBinder.CardEntry(false, null, null));
                    continue;
                }
                // Gated entries carry primaryImage at the top level; non-gated
                // ones nest it under user, which also carries the real id.
                JSONObject user = node.optJSONObject("user");
                JSONObject imageHolder = user != null ? user : node;
                JSONObject primary = imageHolder.optJSONObject("primaryImage");
                String url = primary == null ? null : nullableString(primary, "square225");
                String path = PhotoPaths.normalize(url);
                String realId = user == null ? null : nullableString(user, "id");
                // Always recorded, even when skipped below: it keeps its
                // position so the page's true final node stays identifiable.
                raw.add(new BoundaryBinder.CardEntry(true, realId, path));
                if (path == null) {
                    continue;           // no join key -> useless to us
                }
                Attributes attributes = parseHighlights(node.optJSONObject("matchHighlights"));
                entries.add(new Entry(path, realId, i, attributes));
            }

            JSONObject pageInfo = connection.optJSONObject("pageInfo");
            String after = pageInfo == null ? null : nullableString(pageInfo, "after");
            boolean hasMore = pageInfo != null && pageInfo.optBoolean("hasMore", false);
            int total = pageInfo == null ? 0 : pageInfo.optInt("total", 0);
            return new Page(entries, raw, after, hasMore, total);
        } catch (Exception e) {
            return null;
        }
    }

    /** JSON null and absent both map to Java null (never the string "null"). */
    private static String nullableString(JSONObject obj, String key) {
        return obj.isNull(key) ? null : obj.optString(key);
    }

    private static Integer nullableInt(JSONObject obj, String key) {
        return (obj.has(key) && !obj.isNull(key)) ? Integer.valueOf(obj.optInt(key)) : null;
    }

    private static Double nullableDouble(JSONObject obj, String key) {
        return (obj.has(key) && !obj.isNull(key)) ? Double.valueOf(obj.optDouble(key)) : null;
    }

    private static Boolean nullableBoolean(JSONObject obj, String key) {
        return (obj.has(key) && !obj.isNull(key)) ? Boolean.valueOf(obj.optBoolean(key)) : null;
    }

    /**
     * {@code matchHighlights { age matchScore isOnline isVerified
     * hasIntroMessage dynamicHighlight { ... on LocationHighlight { summary } } } }
     * -- every field optional. Returns null (not an empty object) when the
     * fragment itself is absent, or when every field inside it was absent,
     * so a caller can tell "nothing here" from "nothing we recognised" using
     * one check.
     */
    private static Attributes parseHighlights(JSONObject highlights) {
        if (highlights == null) {
            return null;
        }
        JSONObject dynamic = highlights.optJSONObject("dynamicHighlight");
        String locationSummary = dynamic == null ? null : nullableString(dynamic, "summary");
        Attributes attributes = new Attributes(
                nullableInt(highlights, "age"),
                nullableDouble(highlights, "matchScore"),
                nullableBoolean(highlights, "isOnline"),
                nullableBoolean(highlights, "isVerified"),
                nullableBoolean(highlights, "hasIntroMessage"),
                locationSummary);
        return attributes.isEmpty() ? null : attributes;
    }
}

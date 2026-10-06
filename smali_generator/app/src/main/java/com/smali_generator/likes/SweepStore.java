package com.smali_generator.likes;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import org.json.JSONObject;

/**
 * The sweep's persistent memory: one record per distinct card (keyed by its
 * normalized photo path, the same join key the rest of this package uses),
 * written as JSON lines -- one object per line, no wrapping array -- so
 * {@code adb pull} followed by {@code cat}/{@code jq} reads it directly, and
 * a half-written line never corrupts an otherwise-valid earlier one.
 *
 * <p>Conceptually append-only (nothing already recorded is ever discarded)
 * but re-running updates a card's existing line in place rather than adding a
 * duplicate: {@link #upsert} is keyed by photo path, so a second sighting of
 * the same card merges into the same record. This is the deliberate posture
 * change the sweep-and-store design makes: recovered identities now live on
 * disk, not only for the process lifetime like {@link IdentityStore} -- see
 * NOTES.md's "Likes sweep store" section for the record shape and the path,
 * and {@link LikesSweep} for the one place this is ever written from.
 *
 * <p>Deliberately free of any log line that names a photo path or an id --
 * this file is the only place that data is meant to live. Nothing here
 * throws out of {@link #load}: a missing, truncated or unreadable file is
 * just "nothing known yet", never a reason to crash the sweep that was about
 * to run.
 */
public final class SweepStore {

    /** One card's accumulated knowledge. Immutable; {@link #upsert} replaces, never mutates in place. */
    public static final class Record {
        public final String photoPath;
        public final String realId;          // null until some pass names this card
        public final String displayName;     // null until the id has been looked up
        public final long nameSeenMs;        // 0 while displayName is null
        public final Integer age;
        public final Double matchScore;
        public final Boolean isOnline;
        public final Boolean isVerified;
        public final String locationSummary;
        public final int position;           // this card's last-observed absolute offset in the swept sort
        public final long firstSeenMs;
        public final long lastSeenMs;

        Record(String photoPath, String realId, Integer age, Double matchScore, Boolean isOnline,
               Boolean isVerified, String locationSummary, int position, long firstSeenMs, long lastSeenMs,
               String displayName, long nameSeenMs) {
            this.photoPath = photoPath;
            this.realId = realId;
            this.displayName = displayName;
            this.nameSeenMs = nameSeenMs;
            this.age = age;
            this.matchScore = matchScore;
            this.isOnline = isOnline;
            this.isVerified = isVerified;
            this.locationSummary = locationSummary;
            this.position = position;
            this.firstSeenMs = firstSeenMs;
            this.lastSeenMs = lastSeenMs;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("photoPath", photoPath);
                o.put("realId", realId == null ? JSONObject.NULL : realId);
                o.put("displayName", displayName == null ? JSONObject.NULL : displayName);
                o.put("nameSeenMs", nameSeenMs);
                o.put("age", age == null ? JSONObject.NULL : age);
                o.put("matchScore", matchScore == null ? JSONObject.NULL : matchScore);
                o.put("isOnline", isOnline == null ? JSONObject.NULL : isOnline);
                o.put("isVerified", isVerified == null ? JSONObject.NULL : isVerified);
                o.put("locationSummary", locationSummary == null ? JSONObject.NULL : locationSummary);
                o.put("position", position);
                o.put("firstSeenMs", firstSeenMs);
                o.put("lastSeenMs", lastSeenMs);
            } catch (Exception ignored) {
                // org.json only throws for a NaN/infinite double or a null
                // key; neither applies here. Leaves a possibly-partial object
                // rather than propagating, consistent with load()'s policy
                // of never losing the rest of the file over one bad record.
            }
            return o;
        }

        static Record fromJson(JSONObject o) {
            String photoPath = str(o, "photoPath");
            if (photoPath == null) {
                return null;
            }
            return new Record(
                    photoPath,
                    str(o, "realId"),
                    nullableInt(o, "age"),
                    nullableDouble(o, "matchScore"),
                    nullableBool(o, "isOnline"),
                    nullableBool(o, "isVerified"),
                    str(o, "locationSummary"),
                    o.optInt("position", 0),
                    o.optLong("firstSeenMs", 0L),
                    o.optLong("lastSeenMs", 0L),
                    str(o, "displayName"),
                    o.optLong("nameSeenMs", 0L));
        }
    }

    private final Map<String, Record> byPhotoPath = new LinkedHashMap<String, Record>();

    /**
     * The durable half, installed by the device shell ({@link CardDb}).
     *
     * <p>Kept behind an interface for the same reason {@link Log.Sink} is:
     * this class stays free of Android so the merge policy unit-tests on the
     * JVM.
     */
    public interface Persister {
        /** @param changed only the records that changed since the last flush */
        void persist(Collection<Record> changed);
    }

    private Persister persister;
    /** Photo paths changed since the last successful flush, in change order. */
    private final java.util.LinkedHashSet<String> unflushed = new java.util.LinkedHashSet<String>();

    public SweepStore() {
    }

    public synchronized void setPersister(Persister p) {
        this.persister = p;
    }

    /**
     * Writes everything learned since the last flush, and nothing else.
     *
     * <p>Called once per window by {@link LikesSweep#sweep}, so a pass that
     * the process does not survive still contributes every window it
     * completed. Before this existed the whole pass was written once at the
     * end, which meant a four-minute background walk killed at its last
     * request contributed nothing at all and the next pass started over.
     *
     * <p>Never throws, and never loses a change: the persister runs outside
     * this object's lock (a database write must not block the main thread
     * reading counts for the progress bar), and a write that fails leaves its
     * records marked so the next flush carries them again.
     */
    public void flush() {
        Persister target;
        java.util.List<Record> changed = new ArrayList<Record>();
        java.util.List<String> paths;
        synchronized (this) {
            target = persister;
            if (target == null || unflushed.isEmpty()) {
                return;
            }
            paths = new ArrayList<String>(unflushed);
            unflushed.clear();
            for (String path : paths) {
                Record record = byPhotoPath.get(path);
                if (record != null) {
                    changed.add(record);
                }
            }
        }
        try {
            target.persist(changed);
        } catch (Throwable t) {
            synchronized (this) {
                // Re-mark rather than restore: a change made while the write
                // was in flight must survive this.
                unflushed.addAll(paths);
            }
            Log.w("SweepStore: flush failed -- " + t.getClass().getSimpleName());
        }
    }

    /** Package-visible for tests: how much is waiting to be written. */
    synchronized int unflushedCount() {
        return unflushed.size();
    }

    /**
     * Takes a record in as-is, replacing any record already under its photo
     * path. Package-visible and merge-free on purpose: the only callers are
     * the two loaders ({@link #load} and {@link CardDb}), which are
     * reconstructing an already-merged store rather than observing a card.
     * Every observation goes through {@link #upsert}.
     */
    synchronized void adopt(Record record) {
        if (record != null && record.photoPath != null) {
            byPhotoPath.put(record.photoPath, record);
        }
    }

    /** Never throws. A missing or unreadable file just means an empty store. */
    public static SweepStore load(File file) {
        SweepStore store = new SweepStore();
        if (file == null || !file.isFile()) {
            return store;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    Record record = Record.fromJson(new JSONObject(line));
                    if (record != null) {
                        store.byPhotoPath.put(record.photoPath, record);
                    }
                } catch (Exception ignored) {
                    // One malformed line (e.g. a half-written one from a
                    // killed process) must not lose every other line.
                }
            }
        } catch (Exception ignored) {
            // Missing file, permission error, whatever -- empty store.
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
        }
        return store;
    }

    /** Whole-file rewrite, one JSON object per line. Never throws. */
    public synchronized void save(File file) {
        if (file == null) {
            return;
        }
        Writer writer = null;
        try {
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            writer = new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8);
            for (Record record : byPhotoPath.values()) {
                writer.write(record.toJson().toString());
                writer.write("\n");
            }
        } catch (Exception t) {
            Log.w("SweepStore: save failed -- " + t.getClass().getSimpleName());
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public synchronized boolean contains(String photoPath) {
        return photoPath != null && byPhotoPath.containsKey(photoPath);
    }

    /**
     * Merges a freshly observed sighting into this card's record: a null
     * {@code realId} never erases one already known (first write wins, the
     * same policy {@link IdentityStore#rememberIdentity} uses), highlight
     * attributes are refreshed whenever this sighting carried any, the
     * position always moves to the latest observed offset (every new like
     * shifts everyone), {@code firstSeenMs} is preserved from the first
     * sighting, and {@code lastSeenMs} always advances to {@code nowMs}.
     */
    public synchronized void upsert(String photoPath, String realId, PageParser.Attributes attributes,
                                    int position, long nowMs) {
        if (photoPath == null) {
            return;
        }
        Record existing = byPhotoPath.get(photoPath);
        String mergedId = realId;
        if (existing != null && existing.realId != null) {
            if (realId != null && !realId.equals(existing.realId)) {
                Log.w("SweepStore: conflicting id for an already-known entry; keeping the first");
            }
            mergedId = existing.realId;
        }
        Integer age = attributes != null ? attributes.age : (existing != null ? existing.age : null);
        Double matchScore = attributes != null ? attributes.matchScore
                : (existing != null ? existing.matchScore : null);
        Boolean isOnline = attributes != null ? attributes.isOnline
                : (existing != null ? existing.isOnline : null);
        Boolean isVerified = attributes != null ? attributes.isVerified
                : (existing != null ? existing.isVerified : null);
        String locationSummary = attributes != null ? attributes.locationSummary
                : (existing != null ? existing.locationSummary : null);
        long firstSeenMs = existing != null ? existing.firstSeenMs : nowMs;

        // A name, once learned, is never dropped by a later sighting: the
        // grid's own payload never carries one, so a re-observation can only
        // ever know less than the lookup already did.
        String displayName = existing != null ? existing.displayName : null;
        long nameSeenMs = existing != null ? existing.nameSeenMs : 0L;

        byPhotoPath.put(photoPath, new Record(photoPath, mergedId, age, matchScore, isOnline, isVerified,
                locationSummary, position, firstSeenMs, nowMs, displayName, nameSeenMs));
        unflushed.add(photoPath);
    }

    /**
     * Binds a looked-up name to a card. First write wins, like the identity:
     * a person's display name can change between passes, but the first answer
     * was the one verified against this card's own photo path.
     *
     * <p>Silently ignores a card this store has never seen -- the sweep only
     * ever names cards it just stored, so that would be a caller bug, not a
     * condition worth crashing a sweep over.
     */
    public synchronized void rememberName(String photoPath, String displayName, long nowMs) {
        if (photoPath == null || displayName == null || displayName.isEmpty()) {
            return;
        }
        Record e = byPhotoPath.get(photoPath);
        if (e == null || e.displayName != null) {
            return;
        }
        byPhotoPath.put(photoPath, new Record(e.photoPath, e.realId, e.age, e.matchScore, e.isOnline,
                e.isVerified, e.locationSummary, e.position, e.firstSeenMs, e.lastSeenMs,
                displayName, nowMs));
        unflushed.add(photoPath);
    }

    /** Cards that carry a recovered id but have not been named yet -- the lookup queue. */
    public synchronized java.util.List<Record> needingAName() {
        java.util.List<Record> out = new ArrayList<Record>();
        for (Record r : byPhotoPath.values()) {
            if (r.realId != null && r.displayName == null) {
                out.add(r);
            }
        }
        return out;
    }

    public synchronized int countWithName() {
        int n = 0;
        for (Record r : byPhotoPath.values()) {
            if (r.displayName != null) {
                n++;
            }
        }
        return n;
    }

    /**
     * Merges a sighting that came from a <em>different</em> sort than the one
     * {@code position} is measured in, so the recorded offset is left alone.
     *
     * <p>Only one sort's ordering can be stored in a single position column,
     * and that column is the primary sort's. A secondary walk is run for the
     * ids its window boundaries reveal, not for where it happened to place
     * anyone, so letting it overwrite the position would corrupt the one
     * ordering the rest of this package reasons about.
     */
    public synchronized void observeFromOtherSort(String photoPath, String realId,
                                                  PageParser.Attributes attributes, long nowMs) {
        if (photoPath == null) {
            return;
        }
        Record existing = byPhotoPath.get(photoPath);
        upsert(photoPath, realId, attributes, existing != null ? existing.position : 0, nowMs);
    }

    public synchronized int size() {
        return byPhotoPath.size();
    }

    public synchronized int countWithId() {
        int n = 0;
        for (Record r : byPhotoPath.values()) {
            if (r.realId != null) {
                n++;
            }
        }
        return n;
    }

    public synchronized Record get(String photoPath) {
        return photoPath == null ? null : byPhotoPath.get(photoPath);
    }

    public synchronized Collection<Record> all() {
        return new ArrayList<Record>(byPhotoPath.values());
    }

    private static String str(JSONObject o, String key) {
        return (o.has(key) && !o.isNull(key)) ? o.optString(key) : null;
    }

    private static Integer nullableInt(JSONObject o, String key) {
        return (o.has(key) && !o.isNull(key)) ? Integer.valueOf(o.optInt(key)) : null;
    }

    private static Double nullableDouble(JSONObject o, String key) {
        return (o.has(key) && !o.isNull(key)) ? Double.valueOf(o.optDouble(key)) : null;
    }

    private static Boolean nullableBool(JSONObject o, String key) {
        return (o.has(key) && !o.isNull(key)) ? Boolean.valueOf(o.optBoolean(key)) : null;
    }
}

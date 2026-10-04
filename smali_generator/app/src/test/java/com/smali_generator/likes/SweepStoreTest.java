package com.smali_generator.likes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;

import org.junit.Test;

public class SweepStoreTest {

    private static File tempFile() throws IOException {
        File f = File.createTempFile("sweep-store-test", ".jsonl");
        f.deleteOnExit();
        assertTrue(f.delete());     // load() must treat "does not exist yet" as empty, not an error
        return f;
    }

    @Test public void loadOfAMissingFileIsEmptyNotAnError() throws IOException {
        SweepStore store = SweepStore.load(tempFile());
        assertEquals(0, store.size());
    }

    @Test public void loadOfNullIsEmpty() {
        assertEquals(0, SweepStore.load(null).size());
    }

    @Test public void upsertThenGetRoundTripsEveryField() {
        SweepStore store = new SweepStore();
        PageParser.Attributes attrs = attrs(28, 0.91, true, false, "3 miles away");
        store.upsert("/photos/a.jpeg", "realid0000000000000001", attrs, 5, 1000L);

        SweepStore.Record r = store.get("/photos/a.jpeg");
        assertEquals("/photos/a.jpeg", r.photoPath);
        assertEquals("realid0000000000000001", r.realId);
        assertEquals(Integer.valueOf(28), r.age);
        assertEquals(Double.valueOf(0.91), r.matchScore);
        assertEquals(Boolean.TRUE, r.isOnline);
        assertEquals(Boolean.FALSE, r.isVerified);
        assertEquals("3 miles away", r.locationSummary);
        assertEquals(5, r.position);
        assertEquals(1000L, r.firstSeenMs);
        assertEquals(1000L, r.lastSeenMs);
    }

    @Test public void saveThenLoadRoundTripsExactly() throws IOException {
        SweepStore store = new SweepStore();
        store.upsert("/photos/a.jpeg", "realid0000000000000001",
                attrs(28, 0.91, true, false, "3 miles away"), 5, 1000L);
        store.upsert("/photos/b.jpeg", null, null, 6, 1001L);

        File f = tempFile();
        store.save(f);
        SweepStore reloaded = SweepStore.load(f);

        assertEquals(2, reloaded.size());
        SweepStore.Record a = reloaded.get("/photos/a.jpeg");
        assertEquals("realid0000000000000001", a.realId);
        assertEquals(Integer.valueOf(28), a.age);
        assertEquals(5, a.position);
        SweepStore.Record b = reloaded.get("/photos/b.jpeg");
        assertNull(b.realId);
        assertNull(b.age);
        assertEquals(6, b.position);
        f.delete();
    }

    @Test public void saveWritesOneJsonObjectPerLine() throws Exception {
        SweepStore store = new SweepStore();
        store.upsert("/photos/a.jpeg", null, null, 0, 1L);
        store.upsert("/photos/b.jpeg", null, null, 1, 2L);
        File f = tempFile();
        store.save(f);

        java.util.List<String> lines = java.nio.file.Files.readAllLines(f.toPath());
        assertEquals(2, lines.size());
        for (String line : lines) {
            org.json.JSONObject o = new org.json.JSONObject(line);   // must parse standalone
            assertTrue(o.has("photoPath"));
        }
        f.delete();
    }

    @Test public void rerunningUpdatesInPlaceRatherThanDuplicating() {
        SweepStore store = new SweepStore();
        store.upsert("/photos/a.jpeg", null, attrs(20, null, null, null, null), 3, 1000L);
        store.upsert("/photos/a.jpeg", null, attrs(21, null, null, null, null), 9, 2000L);

        assertEquals(1, store.size());          // no duplicate
        SweepStore.Record r = store.get("/photos/a.jpeg");
        assertEquals(Integer.valueOf(21), r.age);   // latest attributes
        assertEquals(9, r.position);                // latest position
        assertEquals(1000L, r.firstSeenMs);         // preserved from the first sighting
        assertEquals(2000L, r.lastSeenMs);          // advanced
    }

    @Test public void aKnownRealIdIsNeverErasedByALaterNullSighting() {
        SweepStore store = new SweepStore();
        store.upsert("/photos/a.jpeg", "realid0000000000000001", null, 3, 1000L);
        store.upsert("/photos/a.jpeg", null, null, 9, 2000L);

        assertEquals("realid0000000000000001", store.get("/photos/a.jpeg").realId);
    }

    @Test public void aConflictingRealIdKeepsTheFirstOne() {
        SweepStore store = new SweepStore();
        store.upsert("/photos/a.jpeg", "firstid0000000000000001", null, 3, 1000L);
        store.upsert("/photos/a.jpeg", "secondid000000000000001", null, 9, 2000L);

        assertEquals("firstid0000000000000001", store.get("/photos/a.jpeg").realId);
    }

    @Test public void attributesArePreservedWhenALaterSightingCarriesNone() {
        SweepStore store = new SweepStore();
        store.upsert("/photos/a.jpeg", null, attrs(20, null, null, null, "here"), 3, 1000L);
        store.upsert("/photos/a.jpeg", null, null, 9, 2000L);

        SweepStore.Record r = store.get("/photos/a.jpeg");
        assertEquals(Integer.valueOf(20), r.age);
        assertEquals("here", r.locationSummary);
    }

    @Test public void containsReflectsWhatHasBeenUpserted() {
        SweepStore store = new SweepStore();
        assertTrue(!store.contains("/photos/a.jpeg"));
        store.upsert("/photos/a.jpeg", null, null, 0, 1L);
        assertTrue(store.contains("/photos/a.jpeg"));
    }

    @Test public void countWithIdCountsOnlyRecordsThatHaveOne() {
        SweepStore store = new SweepStore();
        store.upsert("/photos/a.jpeg", "realid0000000000000001", null, 0, 1L);
        store.upsert("/photos/b.jpeg", null, null, 1, 1L);
        assertEquals(2, store.size());
        assertEquals(1, store.countWithId());
    }

    @Test public void aMalformedLineDoesNotLoseTheRestOfTheFile() throws IOException {
        File f = tempFile();
        java.nio.file.Files.write(f.toPath(), java.util.Arrays.asList(
                "{\"photoPath\":\"/photos/a.jpeg\",\"position\":0,\"firstSeenMs\":1,\"lastSeenMs\":1}",
                "not even json",
                "{\"photoPath\":\"/photos/b.jpeg\",\"position\":1,\"firstSeenMs\":2,\"lastSeenMs\":2}"));
        SweepStore store = SweepStore.load(f);
        assertEquals(2, store.size());
        f.delete();
    }

    private static PageParser.Attributes attrs(Integer age, Double matchScore, Boolean isOnline,
                                                Boolean isVerified, String locationSummary) {
        if (age == null && matchScore == null && isOnline == null && isVerified == null
                && locationSummary == null) {
            return null;
        }
        return new PageParser.Attributes(age, matchScore, isOnline, isVerified, null, locationSummary);
    }

    @Test public void aCardWithAnIdButNoNameIsQueuedForLookup() {
        SweepStore store = new SweepStore();
        store.upsert("/photos/1/2/named.jpeg", "realid0000000000000001", null, 19, 100L);
        store.upsert("/photos/1/2/anon.jpeg", null, null, 3, 100L);

        java.util.List<SweepStore.Record> queue = store.needingAName();
        assertEquals(1, queue.size());
        assertEquals("/photos/1/2/named.jpeg", queue.get(0).photoPath);
    }

    @Test public void onceNamedACardLeavesTheQueueAndIsCounted() {
        SweepStore store = new SweepStore();
        store.upsert("/photos/1/2/x.jpeg", "realid0000000000000001", null, 19, 100L);
        store.rememberName("/photos/1/2/x.jpeg", "Roni", 200L);

        assertEquals("Roni", store.get("/photos/1/2/x.jpeg").displayName);
        assertEquals(200L, store.get("/photos/1/2/x.jpeg").nameSeenMs);
        assertEquals(1, store.countWithName());
        assertTrue(store.needingAName().isEmpty());
    }

    @Test public void aLaterSightingNeverErasesAKnownName() {
        SweepStore store = new SweepStore();
        store.upsert("/photos/1/2/x.jpeg", "realid0000000000000001", null, 19, 100L);
        store.rememberName("/photos/1/2/x.jpeg", "Roni", 200L);

        // The grid payload never carries a name, so re-observing this card
        // must not take the one the lookup already proved.
        store.upsert("/photos/1/2/x.jpeg", null, null, 24, 300L);

        assertEquals("Roni", store.get("/photos/1/2/x.jpeg").displayName);
        assertEquals(24, store.get("/photos/1/2/x.jpeg").position);
    }

    @Test public void theFirstNameWinsLikeTheFirstIdDoes() {
        SweepStore store = new SweepStore();
        store.upsert("/photos/1/2/x.jpeg", "realid0000000000000001", null, 19, 100L);
        store.rememberName("/photos/1/2/x.jpeg", "Roni", 200L);
        store.rememberName("/photos/1/2/x.jpeg", "Someone Else", 300L);

        assertEquals("Roni", store.get("/photos/1/2/x.jpeg").displayName);
    }

    @Test public void namingAnUnknownCardIsIgnoredRatherThanInventingARecord() {
        SweepStore store = new SweepStore();
        store.rememberName("/photos/9/9/ghost.jpeg", "Nobody", 100L);
        assertEquals(0, store.size());
    }

    @Test public void aNameSurvivesASaveAndReload() throws Exception {
        java.io.File f = java.io.File.createTempFile("sweep", ".jsonl");
        f.deleteOnExit();
        SweepStore store = new SweepStore();
        store.upsert("/photos/1/2/x.jpeg", "realid0000000000000001", null, 19, 100L);
        store.rememberName("/photos/1/2/x.jpeg", "Roni", 200L);
        store.save(f);

        SweepStore reloaded = SweepStore.load(f);
        assertEquals("Roni", reloaded.get("/photos/1/2/x.jpeg").displayName);
        assertEquals(200L, reloaded.get("/photos/1/2/x.jpeg").nameSeenMs);
        assertEquals(1, reloaded.countWithName());
    }
}

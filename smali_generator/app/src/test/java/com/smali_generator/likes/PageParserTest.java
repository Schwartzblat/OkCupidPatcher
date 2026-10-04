package com.smali_generator.likes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.util.Scanner;

import org.junit.Test;

public class PageParserTest {

    private static String fixture(String name) {
        InputStream in = PageParserTest.class.getClassLoader().getResourceAsStream(name);
        return new Scanner(in, "UTF-8").useDelimiter("\\A").next();
    }

    @Test public void parsesPageMetadata() {
        PageParser.Page page = PageParser.parse(fixture("page-mixed.json"));
        assertEquals("YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnc=", page.after);
        assertTrue(page.hasMore);
        assertEquals(110, page.total);
    }

    // Review Focus #1: the imageless entry is skipped, not stored under null.
    @Test public void skipsEntriesWithNoRealPhoto() {
        PageParser.Page page = PageParser.parse(fixture("page-mixed.json"));
        assertEquals(3, page.entries.size());
        for (PageParser.Entry e : page.entries) {
            assertNotNull(e.photoPath);
        }
        // The imageless entry sits between bbb and ccc; those two stay adjacent.
        assertEquals("/photos/3/4/bbb.jpeg", page.entries.get(1).photoPath);
        assertEquals("/photos/5/6/ccc.jpeg", page.entries.get(2).photoPath);
    }

    // A non-/photos/ URL (the blurred decoy) in the image slot is rejected by
    // PhotoPaths returning null, and the entry is skipped.
    @Test public void skipsEntriesWhosePhotoIsNotARealPhotoPath() {
        PageParser.Page page = PageParser.parse(
                "{\"data\":{\"me\":{\"likesIncomingWithPreviews\":{\"data\":["
                        + "{\"primaryImage\":{\"square225\":\"https://c.example.com/fuzzyphotos/z.jpeg?h=8\"}},"
                        + "{\"primaryImage\":{\"square225\":\"https://c.example.com/photos/7/8/ddd.jpeg?h=8\"}}],"
                        + "\"pageInfo\":{\"after\":\"x\",\"hasMore\":false,\"total\":2}}}}}");
        assertEquals(1, page.entries.size());
        assertEquals("/photos/7/8/ddd.jpeg", page.entries.get(0).photoPath);
    }

    // Review Focus #3: a non-gated entry hands us an identity for free.
    @Test public void harvestsRealIdsFromNonGatedEntries() {
        PageParser.Page page = PageParser.parse(fixture("page-mixed.json"));
        assertEquals("/photos/1/2/aaa.jpeg", page.entries.get(0).photoPath);
        assertNull(page.entries.get(0).realId);
        assertEquals("/photos/3/4/bbb.jpeg", page.entries.get(1).photoPath);
        assertEquals("realid0000000000000001", page.entries.get(1).realId);
        assertNull(page.entries.get(2).realId);
    }

    // Every raw node keeps its position, skipped ones included, so the
    // page's true final node is identifiable.
    @Test public void rawEntriesKeepSkippedNodesInPlace() {
        PageParser.Page page = PageParser.parse(fixture("page-mixed.json"));
        assertEquals(4, page.rawEntries.size());
        assertEquals("/photos/1/2/aaa.jpeg", page.rawEntries.get(0).path);
        assertEquals("/photos/3/4/bbb.jpeg", page.rawEntries.get(1).path);
        assertNull(page.rawEntries.get(2).path);          // the skipped one
        assertTrue(page.rawEntries.get(2).isCard);
        assertEquals("/photos/5/6/ccc.jpeg", page.rawEntries.get(3).path);
    }

    // The mis-binding regression: the server's final node is unusable, so the
    // decision must skip, not bind the cursor to the last SURVIVING entry.
    @Test public void droppedFinalNodeIsNotBoundToAnEarlierEntry() {
        PageParser.Page page = PageParser.parse(
                "{\"data\":{\"me\":{\"likesIncomingWithPreviews\":{\"data\":["
                        + "{\"primaryImage\":{\"square225\":\"https://c.example.com/photos/7/8/ddd.jpeg?h=8\"}},"
                        + "{\"primaryImage\":{\"square225\":\"https://c.example.com/fuzzyphotos/z.jpeg?h=8\"}}],"
                        + "\"pageInfo\":{\"after\":\"YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnc\",\"hasMore\":true,\"total\":2}}}}}");
        assertEquals(1, page.entries.size());
        BoundaryBinder.Decision d = BoundaryBinder.decide(page.after, page.rawEntries);
        assertFalse(d.bound);
        assertEquals(BoundaryBinder.SkipReason.FINAL_ENTRY_NO_PATH, d.reason);
    }

    @Test public void survivingFinalNodeBindsThroughTheBinder() {
        PageParser.Page page = PageParser.parse(fixture("page-mixed.json"));
        BoundaryBinder.Decision d = BoundaryBinder.decide(page.after, page.rawEntries);
        assertTrue(d.bound);
        assertEquals("/photos/5/6/ccc.jpeg", d.path);
    }

    // Review Focus #2: the final page has no cursor.
    @Test public void finalPageHasNullAfter() {
        PageParser.Page page = PageParser.parse(
                "{\"data\":{\"me\":{\"likesIncomingWithPreviews\":{\"data\":[],"
                        + "\"pageInfo\":{\"after\":null,\"hasMore\":false,\"total\":0}}}}}");
        assertTrue(page.entries.isEmpty());
        assertNull(page.after);
        assertFalse(page.hasMore);
        assertTrue(page.rawEntries.isEmpty());
    }

    // Android's optString(key, null) yields the string "null" for a JSON null;
    // explicit nulls must come out as Java null regardless of implementation.
    @Test public void explicitJsonNullsAreJavaNullNotTheStringNull() {
        PageParser.Page page = PageParser.parse(
                "{\"data\":{\"me\":{\"likesIncomingWithPreviews\":{\"data\":["
                        + "{\"user\":{\"id\":null,\"primaryImage\":"
                        + "{\"square225\":\"https://c.example.com/photos/9/9/eee.jpeg?h=8\"}}}],"
                        + "\"pageInfo\":{\"after\":null,\"hasMore\":false,\"total\":1}}}}}");
        assertEquals(1, page.entries.size());
        assertNull(page.entries.get(0).realId);
        assertNull(page.after);
    }

    @Test public void garbageIsNullNotAnException() {
        assertNull(PageParser.parse("{\"errors\":[{\"message\":\"nope\"}]}"));
        assertNull(PageParser.parse("not json at all"));
        assertNull(PageParser.parse(null));
    }

    // ---- matchHighlights: the seek planner's sort keys ----

    @Test public void entriesCarryTheirRawIndexEvenAcrossSkippedNodes() {
        PageParser.Page page = PageParser.parse(fixture("page-mixed.json"));
        // rawEntries: 0=aaa, 1=bbb, 2=skipped, 3=ccc -- entries keeps 0,1,3.
        assertEquals(0, page.entries.get(0).index);
        assertEquals(1, page.entries.get(1).index);
        assertEquals(3, page.entries.get(2).index);
    }

    @Test public void matchHighlightsArePopulatedWhenPresent() {
        PageParser.Page page = PageParser.parse(
                "{\"data\":{\"me\":{\"likesIncomingWithPreviews\":{\"data\":["
                        + "{\"primaryImage\":{\"square225\":\"https://c.example.com/photos/1/1/h.jpeg?h=8\"},"
                        + "\"matchHighlights\":{\"age\":29,\"matchScore\":87.5,\"isOnline\":true,"
                        + "\"isVerified\":false,\"hasIntroMessage\":true,"
                        + "\"dynamicHighlight\":{\"__typename\":\"LocationHighlight\",\"summary\":\"3 miles away\"}}}],"
                        + "\"pageInfo\":{\"after\":null,\"hasMore\":false,\"total\":1}}}}}");
        PageParser.Attributes a = page.entries.get(0).attributes;
        assertEquals(Integer.valueOf(29), a.age);
        assertEquals(Double.valueOf(87.5), a.matchScore);
        assertEquals(Boolean.TRUE, a.isOnline);
        assertEquals(Boolean.FALSE, a.isVerified);
        assertEquals(Boolean.TRUE, a.hasIntroMessage);
        assertEquals("3 miles away", a.locationSummary);
    }

    @Test public void missingMatchHighlightsIsNullNotAnEmptyObject() {
        PageParser.Page page = PageParser.parse(fixture("page-mixed.json"));
        for (PageParser.Entry e : page.entries) {
            assertNull(e.attributes);
        }
    }

    @Test public void partialMatchHighlightsKeepsOnlyWhatIsPresent() {
        PageParser.Page page = PageParser.parse(
                "{\"data\":{\"me\":{\"likesIncomingWithPreviews\":{\"data\":["
                        + "{\"primaryImage\":{\"square225\":\"https://c.example.com/photos/1/1/i.jpeg?h=8\"},"
                        + "\"matchHighlights\":{\"age\":41}}],"
                        + "\"pageInfo\":{\"after\":null,\"hasMore\":false,\"total\":1}}}}}");
        PageParser.Attributes a = page.entries.get(0).attributes;
        assertEquals(Integer.valueOf(41), a.age);
        assertNull(a.matchScore);
        assertNull(a.isOnline);
        assertNull(a.locationSummary);
    }

    @Test public void matchHighlightsWithEveryFieldNullIsTreatedAsAbsent() {
        PageParser.Page page = PageParser.parse(
                "{\"data\":{\"me\":{\"likesIncomingWithPreviews\":{\"data\":["
                        + "{\"primaryImage\":{\"square225\":\"https://c.example.com/photos/1/1/j.jpeg?h=8\"},"
                        + "\"matchHighlights\":{\"age\":null,\"matchScore\":null}}],"
                        + "\"pageInfo\":{\"after\":null,\"hasMore\":false,\"total\":1}}}}}");
        assertNull(page.entries.get(0).attributes);
    }
}

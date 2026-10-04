package com.smali_generator.likes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class IdentityStoreTest {

    private IdentityStore store;

    @Before public void setUp() {
        store = IdentityStore.newForTest();
    }

    @After public void tearDown() {
        Log.setSink(null);
    }

    @Test public void resolvesAPlaceholderThroughItsPhotoPath() {
        store.rememberCard("placeholder_}abc", "/photos/1/2/x.jpeg");
        store.rememberIdentity("/photos/1/2/x.jpeg", "realid0000000000000001");
        assertEquals("realid0000000000000001", store.realIdForPlaceholder("placeholder_}abc"));
    }

    @Test public void unknownPlaceholderIsNull() {
        assertNull(store.realIdForPlaceholder("placeholder_}nope"));
    }

    @Test public void knownCardWithUnresolvedIdentityIsNull() {
        store.rememberCard("placeholder_}abc", "/photos/1/2/x.jpeg");
        assertNull(store.realIdForPlaceholder("placeholder_}abc"));
        assertEquals("/photos/1/2/x.jpeg", store.photoPathFor("placeholder_}abc"));
    }

    // Review Focus #5: a second, different id for a known path must not win.
    @Test public void firstIdentityWinsOnConflict() {
        store.rememberIdentity("/photos/1/2/x.jpeg", "realid0000000000000001");
        store.rememberIdentity("/photos/1/2/x.jpeg", "realid0000000000000002");
        assertEquals("realid0000000000000001", store.realIdFor("/photos/1/2/x.jpeg"));
        assertEquals(1, store.identityCount());
    }

    @Test public void conflictingIdentityIsLoggedAndRepeatIsNot() {
        final List<String> logged = new ArrayList<String>();
        Log.setSink(new Log.Sink() {
            @Override public void log(String message) {
                logged.add(message);
            }
        });
        store.rememberIdentity("/photos/1/2/x.jpeg", "realid0000000000000001");
        store.rememberIdentity("/photos/1/2/x.jpeg", "realid0000000000000001");
        assertEquals(0, logged.size());
        store.rememberIdentity("/photos/1/2/x.jpeg", "realid0000000000000002");
        assertEquals(1, logged.size());
    }

    @Test public void repeatingTheSameIdentityIsNotAConflict() {
        store.rememberIdentity("/photos/1/2/x.jpeg", "realid0000000000000001");
        store.rememberIdentity("/photos/1/2/x.jpeg", "realid0000000000000001");
        assertEquals(1, store.identityCount());
    }

    @Test public void nullsAreIgnoredNotStored() {
        store.rememberCard(null, "/photos/1/2/x.jpeg");
        store.rememberCard("placeholder_}abc", null);
        store.rememberIdentity(null, "realid0000000000000001");
        store.rememberIdentity("/photos/1/2/x.jpeg", null);
        assertEquals(0, store.identityCount());
        assertNull(store.photoPathFor("placeholder_}abc"));
    }

    @Test public void nullPhotoPathDoesNotEraseAKnownCard() {
        store.rememberCard("placeholder_}abc", "/photos/1/2/x.jpeg");
        store.rememberCard("placeholder_}abc", null);
        assertEquals("/photos/1/2/x.jpeg", store.photoPathFor("placeholder_}abc"));
    }

    @Test public void nullPlaceholderDoesNotDisturbKnownCards() {
        store.rememberCard("placeholder_}abc", "/photos/1/2/x.jpeg");
        store.rememberCard(null, "/photos/9/9/y.jpeg");
        assertEquals("/photos/1/2/x.jpeg", store.photoPathFor("placeholder_}abc"));
        assertNull(store.photoPathFor(null));
    }

    @Test public void recognisesPlaceholderIds() {
        assertTrue(IdentityStore.isPlaceholder("placeholder_}9f2a-..."));
        assertFalse(IdentityStore.isPlaceholder("realid0000000000000001"));
        assertFalse(IdentityStore.isPlaceholder(null));
    }
}

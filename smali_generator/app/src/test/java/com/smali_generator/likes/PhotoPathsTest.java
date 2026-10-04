package com.smali_generator.likes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class PhotoPathsTest {

    @Test public void stripsQueryString() {
        assertEquals("/photos/166/604/d440afc1.jpeg", PhotoPaths.normalize(
                "https://cdn.example.com/photos/166/604/d440afc1.jpeg?cr=4&h=225"));
    }

    @Test public void differentSizesCollapseToOneKey() {
        String a = PhotoPaths.normalize("https://cdn.example.com/photos/1/2/x.jpeg?cr=4&h=225");
        String b = PhotoPaths.normalize("https://cdn.example.com/photos/1/2/x.jpeg?cr=4&h=400");
        assertEquals(a, b);
    }

    @Test public void bareUrlIsAlreadyNormal() {
        assertEquals("/photos/1/2/x.jpeg",
                PhotoPaths.normalize("https://cdn.example.com/photos/1/2/x.jpeg"));
    }

    @Test public void differentHostsDoNotMatter() {
        String a = PhotoPaths.normalize("https://a.example.com/photos/1/2/x.jpeg?h=225");
        String b = PhotoPaths.normalize("https://b.example.com/photos/1/2/x.jpeg?h=400");
        assertEquals(a, b);
    }

    // Review Focus #1: an entry with no image must not produce a key.
    @Test public void nullIsNull() {
        assertNull(PhotoPaths.normalize(null));
    }

    @Test public void blankIsNull() {
        assertNull(PhotoPaths.normalize(""));
    }

    // The blurred decoy lives in a different id space and must never collide
    // with a real photo path, or a card would bind to the wrong person.
    @Test public void blurredDecoyIsRejected() {
        assertNull(PhotoPaths.normalize(
                "https://cdn.example.com/fuzzyphotos/ScLt_1U1JUs.jpeg?h=800"));
    }

    @Test public void unrelatedPathIsRejected() {
        assertNull(PhotoPaths.normalize("https://cdn.example.com/assets/logo.png"));
    }
}

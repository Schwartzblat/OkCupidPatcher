package com.smali_generator.likes;

/**
 * The join key between a gated card and a resolved profile.
 *
 * Both representations of the same person reference the same asset path; only
 * the transform query differs (?cr=4&h=225 on the card, ?cr=4&h=400 on the
 * profile). Matching on the path is what lets a recovered id be bound to a
 * card that was served with no identifier.
 */
public final class PhotoPaths {

    private static final String REAL_PHOTO_SEGMENT = "/photos/";

    private PhotoPaths() {
    }

    public static String normalize(String url) {
        if (url == null) {
            return null;
        }
        String s = url.trim();
        if (s.isEmpty()) {
            return null;
        }
        int query = s.indexOf('?');
        if (query >= 0) {
            s = s.substring(0, query);
        }
        int schemeEnd = s.indexOf("://");
        if (schemeEnd >= 0) {
            int pathStart = s.indexOf('/', schemeEnd + 3);
            if (pathStart < 0) {
                return null;
            }
            s = s.substring(pathStart);
        }
        // Only real-photo paths are join keys. The blurred decoy is served
        // from /fuzzyphotos/<id> in a separate id space -- a real photo can
        // never be derived from it, so it must not become a key.
        if (!s.startsWith(REAL_PHOTO_SEGMENT)) {
            return null;
        }
        return s;
    }
}

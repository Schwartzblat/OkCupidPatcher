package com.smali_generator.likes;

import org.json.JSONObject;

/**
 * Turns a recovered user id into that person's display name.
 *
 * <p>The gated Likes grid never carries a name -- the server sends
 * {@code "------"} for every entry the paywall hides (see
 * {@link IdentityStore#isPlaceholder}). Once a pass has recovered the real id
 * behind a card from a page cursor, though, the name is a single registered
 * query away:
 *
 * <pre>
 * query SingleNotificationInfo($userId: String!) {
 *   me { match(id: $userId) { user { id displayname primaryImage { square225 } } } }
 * }
 * </pre>
 *
 * <p>The id travels as a <em>variable</em>, which is what makes this legal
 * against the operation registry (NOTES.md, "Operation registry"): the
 * document itself is the app's own, read at runtime and never retyped here.
 *
 * <p>The response carries {@code primaryImage.square225} beside the name, and
 * that is the same join key every other class in this package uses. So a
 * resolved name is self-verifying: {@link #parse} returns the photo path the
 * server associated with the name, and the caller binds the name only to the
 * card whose own path matches. A wrong id can therefore never silently
 * attach a stranger's name to a card -- it mismatches and is dropped.
 *
 * <p>Pure and side-effect free: no network, no logging, no identifier ever
 * written anywhere. {@link LikesSweep} owns the requests and the storage.
 */
public final class NameResolver {

    /** The app's own operation name. The document is read from the app, never spelled out here. */
    public static final String OPERATION_NAME = "SingleNotificationInfo";

    private NameResolver() {
    }

    /** One resolved identity: the name, and the photo path the server says it belongs to. */
    public static final class Resolved {
        public final String userId;
        public final String displayName;
        public final String photoPath;      // normalized; null when the response carried no image

        Resolved(String userId, String displayName, String photoPath) {
            this.userId = userId;
            this.displayName = displayName;
            this.photoPath = photoPath;
        }
    }

    /**
     * The variables object for one lookup. Built with org.json rather than
     * string concatenation so an id containing a quote or a backslash cannot
     * break out of the JSON -- ids are base64url today, but the encoder is
     * the server's to change, not ours to assume.
     */
    public static String variablesJson(String userId) {
        if (userId == null || userId.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject().put("userId", userId).toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Reads {@code data.me.match.user} out of a response.
     *
     * <p>Returns null for anything that is not a usable answer -- malformed
     * JSON, a GraphQL error, a null {@code match} (the server declining to
     * describe this person), or a placeholder name. Never throws: a sweep
     * that cannot name one card must still finish the rest.
     */
    public static Resolved parse(String responseJson) {
        if (responseJson == null) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(responseJson);
            JSONObject data = root.optJSONObject("data");
            if (data == null) {
                return null;
            }
            JSONObject me = data.optJSONObject("me");
            JSONObject match = me == null ? null : me.optJSONObject("match");
            JSONObject user = match == null ? null : match.optJSONObject("user");
            if (user == null) {
                return null;
            }
            String id = nullableString(user, "id");
            String name = nullableString(user, "displayname");
            if (!isRealName(name)) {
                return null;
            }
            JSONObject image = user.optJSONObject("primaryImage");
            String photoPath = image == null ? null
                    : PhotoPaths.normalize(nullableString(image, "square225"));
            return new Resolved(id, name, photoPath);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Whether a resolved answer may be bound to the card at {@code cardPath}.
     *
     * <p>A response that named no image cannot be checked, so it is refused:
     * binding an unverifiable name is exactly the mis-binding this join key
     * exists to prevent.
     */
    public static boolean belongsTo(Resolved resolved, String cardPath) {
        return resolved != null
                && resolved.photoPath != null
                && cardPath != null
                && resolved.photoPath.equals(cardPath);
    }

    /**
     * Whether this is a name rather than the anonymized stand-in.
     *
     * <p>The gated grid spells a hidden person {@code "------"}. That is a
     * different placeholder from {@link IdentityStore#isPlaceholder}, which
     * matches the fabricated <em>ids</em> the client mints -- so this check
     * cannot be delegated to it. Any name made only of dashes and spaces is
     * treated as the stand-in, since the exact dash count is the server's to
     * change.
     */
    public static boolean isRealName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c != '-' && c != '\u2013' && c != '\u2014' && !Character.isWhitespace(c)) {
                return true;
            }
        }
        return false;
    }

    /** Android's {@code optString} answers the literal "null" for a JSON null; this does not. */
    private static String nullableString(JSONObject o, String key) {
        if (o == null || o.isNull(key)) {
            return null;
        }
        String s = o.optString(key, null);
        return (s == null || s.isEmpty()) ? null : s;
    }
}

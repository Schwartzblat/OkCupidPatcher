package com.smali_generator.likes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NameResolverTest {

    private static String response(String name, String imageUrl) {
        String image = imageUrl == null ? "null" : "{\"square225\":\"" + imageUrl + "\"}";
        return "{\"data\":{\"me\":{\"match\":{\"__typename\":\"Match\",\"user\":{"
                + "\"id\":\"abc123\",\"displayname\":" + (name == null ? "null" : "\"" + name + "\"")
                + ",\"primaryImage\":" + image + "}}}}}";
    }

    @Test public void readsTheNameAndTheJoinKey() {
        NameResolver.Resolved r = NameResolver.parse(
                response("Roni", "https://cdn.example.com/photos/166/604/d440afc1.jpeg?cr=4&h=225"));
        assertEquals("Roni", r.displayName);
        assertEquals("abc123", r.userId);
        assertEquals("/photos/166/604/d440afc1.jpeg", r.photoPath);
    }

    @Test public void aNameBindsOnlyToTheCardTheServerPairedItWith() {
        NameResolver.Resolved r = NameResolver.parse(
                response("Roni", "https://cdn.example.com/photos/166/604/d440afc1.jpeg?h=225"));
        assertTrue(NameResolver.belongsTo(r, "/photos/166/604/d440afc1.jpeg"));
        assertFalse(NameResolver.belongsTo(r, "/photos/999/999/someoneelse.jpeg"));
    }

    @Test public void anUnverifiableNameIsRefusedRatherThanGuessed() {
        // No image in the response: nothing proves which card this name is for.
        NameResolver.Resolved r = NameResolver.parse(response("Roni", null));
        assertNull(r.photoPath);
        assertFalse(NameResolver.belongsTo(r, "/photos/166/604/d440afc1.jpeg"));
    }

    @Test public void theBlurredPlaceholderIsNotAName() {
        assertNull(NameResolver.parse(response("------", "https://cdn.example.com/photos/1/2/x.jpeg")));
    }

    @Test public void aDeclinedOrErroredLookupIsNotAnAnswer() {
        assertNull(NameResolver.parse("{\"data\":{\"me\":{\"match\":null}}}"));
        assertNull(NameResolver.parse("{\"errors\":[{\"message\":\"FORBIDDEN\"}]}"));
        assertNull(NameResolver.parse("not json at all"));
        assertNull(NameResolver.parse(null));
    }

    @Test public void variablesCarryTheIdAsAVariableNotAsDocumentText() {
        assertEquals("{\"userId\":\"abc-123_XY\"}", NameResolver.variablesJson("abc-123_XY"));
        assertNull(NameResolver.variablesJson(null));
        assertNull(NameResolver.variablesJson(""));
    }

    @Test public void anIdWithJsonMetacharactersCannotBreakOutOfTheVariables() {
        String vars = NameResolver.variablesJson("a\"b\\c");
        assertEquals("{\"userId\":\"a\\\"b\\\\c\"}", vars);
    }
}

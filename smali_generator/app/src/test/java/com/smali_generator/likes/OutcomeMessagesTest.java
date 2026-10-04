package com.smali_generator.likes;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OutcomeMessagesTest {

    @Test public void everyGiveUpOutcomeSaysSomethingVisible() {
        for (LikesIdentityResolver.Outcome o : new LikesIdentityResolver.Outcome[] {
                LikesIdentityResolver.Outcome.GAVE_UP,
                LikesIdentityResolver.Outcome.ABORTED_FAILURES,
                LikesIdentityResolver.Outcome.NOT_READY,
                LikesIdentityResolver.Outcome.ERROR}) {
            String m = OutcomeMessages.messageFor(o);
            assertNotNull(o.name(), m);
            assertTrue(o.name(), !m.trim().isEmpty());
        }
    }

    @Test public void resolvedAndInterruptedAreSilent() {
        assertNull(OutcomeMessages.messageFor(LikesIdentityResolver.Outcome.RESOLVED));
        assertNull(OutcomeMessages.messageFor(LikesIdentityResolver.Outcome.INTERRUPTED));
    }

    @Test public void everyOutcomeIsCoveredExplicitly() {
        // Adding an Outcome without deciding its message must be a conscious act.
        for (LikesIdentityResolver.Outcome o : LikesIdentityResolver.Outcome.values()) {
            OutcomeMessages.messageFor(o);        // must not throw
        }
    }

    @Test public void failureMessagesAreDistinguishable() {
        assertTrue(!OutcomeMessages.messageFor(LikesIdentityResolver.Outcome.ABORTED_FAILURES)
                .equals(OutcomeMessages.messageFor(LikesIdentityResolver.Outcome.NOT_READY)));
    }
}

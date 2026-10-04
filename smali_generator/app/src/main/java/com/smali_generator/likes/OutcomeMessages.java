package com.smali_generator.likes;

/** What the user is told when a traversal ends. Pure, so every outcome is pinned. */
public final class OutcomeMessages {

    private OutcomeMessages() {
    }

    /** @return the message to show, or null when nothing should be shown */
    public static String messageFor(LikesIdentityResolver.Outcome outcome) {
        switch (outcome) {
            case RESOLVED:
                return null;            // the profile opens instead
            case INTERRUPTED:
                return null;            // the process is shutting the thread down
            case ABORTED_FAILURES:
                return "Network trouble while identifying. Try again later";
            case NOT_READY:
                return "Not ready yet. Browse a moment, then try again";
            default:                    // GAVE_UP, ERROR
                return "Couldn't identify this person";
        }
    }
}

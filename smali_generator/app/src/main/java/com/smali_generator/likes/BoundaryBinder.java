package com.smali_generator.likes;

import java.util.List;

/**
 * Pure decision: given a page's raw entries, in source order, and the raw
 * (undecoded) pagination cursor, decide whether to bind the cursor's identity
 * to the page's final entry, and to what.
 *
 * <p>This exists to pin, with a test, the fix for a real bug: a cursor names
 * the page's LAST entry specifically, and an earlier version of the caller
 * tracked "the last entry it had successfully bound as a card" instead of
 * "the page's actual last entry". Those differ whenever the real final entry
 * is skipped for any reason (wrong type, no id, no photo path) -- and when
 * they differ, binding to the wrong one means a resolved identity opens a
 * STRANGER's profile. A function that took only the final pair could not
 * catch that mistake; it would be a trivial pass-through of whatever the
 * caller decided was "final". So this function takes the WHOLE list and
 * decides for itself which element is final.
 *
 * <p>Deliberately named {@code CardEntry}, not {@code Entry}: {@link
 * PageParser} already has a nested {@code Entry} for the parsed-GraphQL-
 * response path, which pre-filters out entries with no usable photo path and
 * is shaped for a different purpose (scanning for ANY resolvable entry, not
 * judging a specific final one). Reusing that name here would make it easy to
 * mix the two up; reusing the type would not fit, since this one has to be
 * able to represent "the raw entry that failed to become a card at all",
 * which {@code PageParser.Entry} cannot -- it is never constructed for those.
 */
public final class BoundaryBinder {

    /**
     * One raw entry on the page, in source order. A non-card still occupies
     * its position in the list -- it is the thing a cursor might still name.
     */
    public static final class CardEntry {
        public final boolean isCard;   // false: null, or not the expected user type
        public final String id;        // the card's own id (may be a placeholder), or null
        public final String path;      // normalized photo path, or null

        public CardEntry(boolean isCard, String id, String path) {
            this.isCard = isCard;
            this.id = id;
            this.path = path;
        }
    }

    public enum SkipReason {
        /** {@code after} was null -- most commonly, this was the last page. */
        NO_CURSOR,
        /** {@code after} was present but {@link Cursors#decode} rejected it. */
        UNDECODABLE_CURSOR,
        /** The page had no entries at all. */
        EMPTY_PAGE,
        /** The page's actual final entry never became a card (null or wrong type). */
        FINAL_ENTRY_NOT_A_CARD,
        /** The page's actual final entry is a card but has no resolvable photo path. */
        FINAL_ENTRY_NO_PATH,
        /** The final entry's own real (non-placeholder) id disagrees with the cursor's. */
        ID_MISMATCH,
    }

    public static final class Decision {
        public final boolean bound;
        public final String path;       // non-null iff bound
        public final String id;         // non-null iff bound
        public final SkipReason reason; // non-null iff !bound

        private Decision(boolean bound, String path, String id, SkipReason reason) {
            this.bound = bound;
            this.path = path;
            this.id = id;
            this.reason = reason;
        }

        static Decision bind(String path, String id) {
            return new Decision(true, path, id, null);
        }

        static Decision skip(SkipReason reason) {
            return new Decision(false, null, null, reason);
        }
    }

    private BoundaryBinder() {
    }

    /**
     * @param cursor  the raw, undecoded {@code nextPagingKey}; may be null
     * @param entries the page's entries, in source order, including any that
     *                never became a card
     */
    public static Decision decide(String cursor, List<CardEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return Decision.skip(SkipReason.EMPTY_PAGE);
        }
        if (cursor == null) {
            return Decision.skip(SkipReason.NO_CURSOR);
        }
        String decodedId = Cursors.decode(cursor);
        if (decodedId == null) {
            return Decision.skip(SkipReason.UNDECODABLE_CURSOR);
        }

        CardEntry last = entries.get(entries.size() - 1);
        if (!last.isCard) {
            return Decision.skip(SkipReason.FINAL_ENTRY_NOT_A_CARD);
        }
        if (last.path == null) {
            return Decision.skip(SkipReason.FINAL_ENTRY_NO_PATH);
        }
        // A null id, or a placeholder (gated) id, carries no information to
        // contradict the cursor with -- the final entry is still the correct
        // element to bind to, we simply have nothing of our own to compare.
        // Only a REAL, non-placeholder id can disagree, and disagreeing means
        // the pairing assumption is wrong for this page: skip rather than
        // silently overwrite a value already known to be real.
        if (last.id != null && !IdentityStore.isPlaceholder(last.id) && !last.id.equals(decodedId)) {
            return Decision.skip(SkipReason.ID_MISMATCH);
        }
        return Decision.bind(last.path, decodedId);
    }
}

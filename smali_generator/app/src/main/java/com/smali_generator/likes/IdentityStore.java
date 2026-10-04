package com.smali_generator.likes;

import java.util.HashMap;
import java.util.Map;

/**
 * Two maps, in memory for the process lifetime. Nothing here is written to
 * disk: recovered identities belong to third parties and must not outlive the
 * process.
 */
public final class IdentityStore {

    /** The prefix the app's own User$Companion generates for gated entries. */
    private static final String PLACEHOLDER_PREFIX = "placeholder_";

    private static final IdentityStore INSTANCE = new IdentityStore();

    private final Map<String, String> pathByPlaceholder = new HashMap<String, String>();
    private final Map<String, String> idByPath = new HashMap<String, String>();
    private final Map<String, String> nameByPath = new HashMap<String, String>();

    private IdentityStore() {
    }

    /** A fresh, unshared store for tests; production code uses {@link #get()}. */
    static IdentityStore newForTest() {
        return new IdentityStore();
    }

    public static IdentityStore get() {
        return INSTANCE;
    }

    public static boolean isPlaceholder(String id) {
        return id != null && id.startsWith(PLACEHOLDER_PREFIX);
    }

    // Overwrite, not first-write-wins like rememberIdentity: placeholder ids are
    // freshly generated per entry per page load (User$Companion makes a new UUID
    // each time), so a later write is a rebuilt card, not a conflicting identity.
    public synchronized void rememberCard(String placeholderId, String photoPath) {
        if (placeholderId == null || photoPath == null) {
            return;
        }
        pathByPlaceholder.put(placeholderId, photoPath);
    }

    public synchronized void rememberIdentity(String photoPath, String realId) {
        if (photoPath == null || realId == null) {
            return;
        }
        String existing = idByPath.get(photoPath);
        if (existing != null) {
            if (!existing.equals(realId)) {
                // First write wins. A path that resolves two ways means the
                // join key is not unique, and silently repointing a card at a
                // different person is the worst possible failure here.
                Log.w("IdentityStore: conflicting id for a known photo path; keeping the first");
            }
            return;
        }
        idByPath.put(photoPath, realId);
    }

    public synchronized String photoPathFor(String placeholderId) {
        return placeholderId == null ? null : pathByPlaceholder.get(placeholderId);
    }

    public synchronized String realIdFor(String photoPath) {
        return photoPath == null ? null : idByPath.get(photoPath);
    }

    public synchronized String realIdForPlaceholder(String placeholderId) {
        return realIdFor(photoPathFor(placeholderId));
    }

    /**
     * The display name recovered for a card, for the grid to draw in place of
     * the anonymized stand-in. Last write wins: unlike an identity, a name is
     * not a claim about <em>who</em> a card is -- the id already settled that
     * -- so a fresher spelling is simply better.
     */
    public synchronized void rememberName(String photoPath, String displayName) {
        if (photoPath == null || displayName == null || displayName.isEmpty()) {
            return;
        }
        nameByPath.put(photoPath, displayName);
    }

    public synchronized String nameFor(String photoPath) {
        return photoPath == null ? null : nameByPath.get(photoPath);
    }

    public synchronized int nameCount() {
        return nameByPath.size();
    }

    public synchronized int identityCount() {
        return idByPath.size();
    }
}

package com.smali_generator.likes;

/**
 * The device-side half of naming a card: finds the app's own
 * {@code SingleNotificationInfo} document and spends one request per
 * unnamed card to turn its recovered id into a display name.
 *
 * <p>Split from {@link NameResolver} on the same line every other pair in
 * this package follows -- the pure, tested decision lives there; the
 * reflection, the network and the clock live here. Nothing in this file is
 * reachable from a JVM unit test.
 *
 * <p>Never logs a name, an id or a photo path: {@link CardDb} is the only
 * place that data is meant to live. The log lines here are counts.
 */
final class NameLookup {

    /**
     * High enough that an expansion across every sort -- which can hand this
     * far more than one window's worth of fresh ids -- is never silently
     * truncated. A backstop, not a tuning knob.
     */
    static final int MAX_LOOKUPS_PER_PASS = 300;
    static final long DELAY_MS = 250L;

    private static volatile String document;
    private static volatile boolean unavailable;

    private NameLookup() {
    }

    /** The app's own document, read once. Null when this build's holder could not be found. */
    static synchronized String document() {
        if (document != null || unavailable) {
            return document;
        }
        try {
            ClassLoader cl = NameLookup.class.getClassLoader();
            Class<?> holder = Class.forName("{{NAME_QUERY_DOC_CLASS_NAME}}", true, cl);
            java.lang.reflect.Method getDoc = holder.getMethod("{{NAME_QUERY_DOC_METHOD_NAME}}");
            Object receiver = java.lang.reflect.Modifier.isStatic(getDoc.getModifiers())
                    ? null : companionInstanceOf(holder);
            document = (String) getDoc.invoke(receiver);
        } catch (Throwable t) {
            unavailable = true;
            Log.w("NameLookup: no name document in this build -- names unavailable");
        }
        return document;
    }

    /** The getter lives on a Kotlin companion; its instance is a static field of the outer class. */
    private static Object companionInstanceOf(Class<?> type) throws Exception {
        Class<?> outer = type.getDeclaringClass();
        if (outer != null) {
            for (java.lang.reflect.Field f : outer.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        && type.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f.get(null);
                }
            }
        }
        return type.newInstance();
    }

    /**
     * Names every card in {@code store} that carries an id but no name yet.
     *
     * <p>Each answer is bound only to the card whose own photo path the
     * server echoed back beside the name ({@link NameResolver#belongsTo}), so
     * a mismatched or unverifiable answer is dropped rather than guessed at.
     *
     * @return how many cards this pass named.
     */
    static int nameUnnamed(SweepStore store) {
        String doc = document();
        if (doc == null || !GraphQlTransport.isReady()) {
            return 0;
        }
        java.util.List<SweepStore.Record> queue = store.needingAName();
        if (queue.isEmpty()) {
            return 0;
        }
        int named = 0;
        int attempts = 0;
        int unverified = 0;
        for (SweepStore.Record record : queue) {
            if (attempts >= MAX_LOOKUPS_PER_PASS) {
                break;
            }
            attempts++;
            String vars = NameResolver.variablesJson(record.realId);
            if (vars == null) {
                continue;
            }
            String body = GraphQlTransport.post(NameResolver.OPERATION_NAME, doc, vars);
            NameResolver.Resolved resolved = NameResolver.parse(body);
            if (NameResolver.belongsTo(resolved, record.photoPath)) {
                store.rememberName(record.photoPath, resolved.displayName, System.currentTimeMillis());
                // One row, written now: naming a full list takes half a
                // minute of requests, and a process killed partway through
                // should keep the names it already paid for.
                store.flush();
                named++;
            } else if (resolved != null) {
                unverified++;
            }
            try {
                Thread.sleep(DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (unverified > 0) {
            Log.w("NameLookup: " + unverified + " answer(s) did not match their card and were dropped");
        }
        return named;
    }
}

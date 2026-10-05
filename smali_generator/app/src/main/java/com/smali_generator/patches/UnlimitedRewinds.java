package com.smali_generator.patches;

import com.smali_generator.Hook;

import java.lang.reflect.Method;

/**
 * Unlimited rewinds -- undo a pass.
 *
 * The rewind manager stores an int token count in which -1 means unlimited, and
 * its setter maps a null argument onto that sentinel. This hook rewrites the
 * argument to null rather than forcing the gate, because the gate is
 *
 * <pre>
 * canRewind() = hasCachedCard &amp;&amp; (tokens &gt; 0 || tokens == -1)
 * </pre>
 *
 * and forcing the whole expression true would also assert that a cached card
 * exists when none does. Filtering the argument leaves hasCachedCard, the
 * replay-state refresh and every other condition untouched.
 *
 * <p>This is the only hook here that calls through to the original, so it is
 * also the only one using the three-argument ArtHooks form.
 *
 * <p>Why it is effective at all, which is worth recording given how much of
 * this app's spend is server-authorised: {@code UserRewindTokenConsume} is a
 * <b>zero-variable</b> mutation whose result the client discards with no
 * rollback path, and the undo itself is a local {@code onReplay} callback. The
 * card comes back without the server granting anything. The server still owns
 * its own ledger and may refuse the debit silently -- that part is not
 * client-side and is not claimed here.
 */
public class UnlimitedRewinds implements Hook {

    /**
     * Backup. ArtHooks rewrites its entry point to the target's pre-hook body,
     * and its signature must match {@link #set_user_tokens_hook} exactly.
     *
     * <p><b>It must be {@code native} with no body, and that is not a style
     * choice.</b> Only the entry point is swapped, so every call has to go
     * through it -- and a backup with a Java body does not: it is empty and
     * returns nothing, so dex2oat inlines it into the replacement at install
     * time, long before any of this runs. The call site then holds a copy of
     * the backup's own (empty) body, the entry-point swap is invisible, and
     * this hook becomes a silent no-op that still reports success -- rewinds
     * are never actually set to unlimited. A native method has no body to
     * copy. ArtHooks refuses a non-native backup outright from 1.0.5; under
     * 1.0.3 it installed one and said nothing, which is how this went
     * unnoticed.
     */
    static native void set_user_tokens_backup(Object thiz, Object tokens);

    /**
     * Instance target, so a static replacement with a leading thiz. The
     * parameter is a boxed Integer; Object keeps this class free of any app
     * type and null is what the setter reads as UNLIMITED_TOKENS.
     */
    static void set_user_tokens_hook(Object thiz, Object tokens) {
        set_user_tokens_backup(thiz, null);
    }

    @Override
    public void load() {
        try {
            Method replacement = UnlimitedRewinds.class.getDeclaredMethod(
                    "set_user_tokens_hook", Object.class, Object.class);
            Method backup = UnlimitedRewinds.class.getDeclaredMethod(
                    "set_user_tokens_backup", Object.class, Object.class);
            HookUtil.install("UnlimitedRewinds",
                    "{{UNLIMITED_REWINDS_CLASS_NAME}}",
                    "{{UNLIMITED_REWINDS_METHOD_NAME}}",
                    "{{UNLIMITED_REWINDS_METHOD_SIG}}",
                    replacement, backup);
        } catch (Throwable t) {
            android.util.Log.e(HookUtil.TAG, "UnlimitedRewinds: " + t);
        }
    }

    @Override
    public void unload() {
    }
}

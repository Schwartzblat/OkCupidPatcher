package com.smali_generator.patches;

import com.smali_generator.Hook;

import java.lang.reflect.Method;

/**
 * Keeps the Priority Likes pitch modal off the screen.
 *
 * <p>The other half of not offering Priority Like. {@link PremiumGate} stops
 * the build <i>claiming</i> the feature by answering its entitlement constant
 * false; this stops it <i>selling</i> it. The modal
 * ({@code PriorityLikesModalScreen}, "Get your Likes seen sooner with Priority
 * Likes, included in Premium Plus") interrupts a liking session at the 15th
 * and the 30th like of the day, and its gate is:
 *
 * <pre>
 * subscription != PREMIUM_PLUS &amp;&amp; (likesCountToday(user) == 15
 *                                  || likesCountToday(user) == 30)
 * </pre>
 *
 * <p>Suppressing it is not cosmetic tidying, it is the fix for a regression
 * {@link PremiumGate}'s denial would otherwise introduce.
 * {@code UserProviderConcrete.getSubscription()} returns the highest tier
 * whose feature the hooked predicate accepts, so once the Premium Plus
 * constant answers false it reports PREMIUM -- true, and exactly what switches
 * this modal back on. Without this hook the patch would have traded a badge
 * that lies for a nag that interrupts.
 *
 * <p>A full 2-argument reimplementation rather than a filtered call-through.
 * The replacement <b>is</b> the whole method -- the original is a pure
 * predicate and the answer wanted is a constant -- so there is nothing to call
 * through to, and NOTES.md's "ArtHooks backup-entry-instruction rule" rules a
 * backup out anyway: this body's entry instruction is a {@code const-string}
 * (the {@code Intrinsics.checkNotNullParameter} literal), which is one of the
 * two shapes measured to crash.
 *
 * <p>Dropping the original's null check with it is deliberate and harmless:
 * the check only ever converted a null argument into a
 * NullPointerException, and nothing downstream of a constant {@code false}
 * reads the argument at all.
 */
public class NoPriorityLikeModal implements Hook {

    /**
     * Instance target with one reference parameter, so the replacement is
     * static with a leading thiz and takes the argument as Object -- which
     * keeps this class free of any app type at compile time, the same shape
     * {@link PremiumGate} uses.
     */
    static boolean should_show_hook(Object thiz, Object user) {
        return false;
    }

    @Override
    public void load() {
        try {
            Method replacement = NoPriorityLikeModal.class.getDeclaredMethod(
                    "should_show_hook", Object.class, Object.class);
            HookUtil.install("NoPriorityLikeModal",
                    "{{PRIORITY_MODAL_CLASS_NAME}}",
                    "{{PRIORITY_MODAL_METHOD_NAME}}",
                    "{{PRIORITY_MODAL_METHOD_SIG}}",
                    replacement);
        } catch (Throwable t) {
            android.util.Log.e(HookUtil.TAG, "NoPriorityLikeModal: " + t);
        }
    }

    @Override
    public void unload() {
    }
}

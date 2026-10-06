package com.smali_generator.patches;

import android.util.Log;

import com.smali_generator.Hook;

import java.lang.reflect.Method;

/**
 * Turns every paid OkCupid feature on, at every class that carries the gate --
 * with one deliberate exception, the tier that only re-skins the Like button.
 *
 * The app has no isPremium() flag: each paid feature is gated by
 * {@code boolean getUserHasPremium(PremiumFeatures feature)}, which looks the
 * feature up in the session's premium map. Forcing that predicate true unlocks
 * all ten PremiumFeatures (ADFREE, ALIST_BASIC, ALIST_PREMIUM,
 * ALIST_PREMIUM_PLUS, INCOGNITO_BUNDLE, INTROS, READ_RECEIPTS,
 * SEE_PUBLIC_QUESTIONS, UNLIMITED_LIKES, VIEW_VOTES) at every call site at
 * once, and everything derived from it (hasOkCupidSubscription,
 * getUserSubscription, the per-screen isAList() wrappers) follows.
 *
 * Two classes carry the predicate on 116.0.0 -- the UserProvider
 * implementation and the session singleton -- so the finder hands over a
 * {@code |}-separated list of {@code class:method:sig} triples rather than one
 * named target, and this hook installs the same replacement on each. Which
 * classes those are is never written down here; that is the whole point.
 *
 * <h2>Why one feature is answered false: Priority Like</h2>
 *
 * Priority Like is the Premium Plus perk that is supposed to get your Likes
 * seen sooner ({@code priority_likes_modal_body}: "Get your Likes seen sooner
 * with Priority Likes, included in Premium Plus"). It is not a separate
 * action, and there is no Priority Like button: it is the ordinary Like,
 * re-skinned. Six presentation sites read this predicate with the
 * Premium-Plus constant and nothing else, and each one only swaps artwork or
 * a label -- the full list is in {@code priority_like_tier.py}, measured off
 * 116.0.0's smali.
 *
 * The priority itself is computed server-side from the real subscription. For
 * an account this patcher has unlocked but that is not actually paying, the
 * server does not honour it, so every one of those six sites shows a Priority
 * Like badge on a Like that goes out as an ordinary
 * {@code VoteActionType.LIKE}. That is exactly the failure mode NOTES.md
 * refuses elsewhere ("a UI claiming 99 boosts that then fails is worse than an
 * honest 0"), so this gate answers that one constant false and the ordinary
 * Like artwork comes back everywhere at once.
 *
 * <h2>The collateral this accepts, stated rather than hidden</h2>
 *
 * The constant has five readers that are not Priority Like sites, all
 * enumerated and all checked:
 *
 * <ul>
 *   <li>{@code UnifiedSettingsUpsellViewModel.updateState} computes
 *       {@code !hasPremiumPlus && eligibleToUpgrade}, so the Premium Plus
 *       upsell row can appear in settings again. That is the honest state of
 *       the account; the patch never bought anything.</li>
 *   <li>{@code MessageThreadMessageViewModel.getIsReadReceiptActivated} is
 *       {@code hasPremiumPlus && message.isReadReceiptActivated()}. The
 *       right-hand side is server-provided and arrives null/false for a
 *       non-paying account (NOTES.md, "Surveyed and rejected": readTime is
 *       null), so the conjunction was already false. Nothing is lost.</li>
 *   <li>{@code SettingsFragment} ORs the three A-List tiers together;
 *       ALIST_BASIC is still true, so its result is unchanged.</li>
 *   <li>{@code MainActivity.onBoostFinished} and
 *       {@code SuperlikeRateCardViewModel} pass it into presentation state
 *       only.</li>
 *   <li>{@code UserProviderConcrete.getSubscription()} returns the highest
 *       tier the predicate accepts, so it reports PREMIUM rather than
 *       PREMIUM_PLUS. Its one reader that acts on the difference is the
 *       Priority Likes sales modal, which {@link NoPriorityLikeModal} turns
 *       off for exactly that reason; the rest is presentation.</li>
 * </ul>
 *
 * None of the features the patch exists for reads it: the likes-you reveal is
 * ALIST_BASIC, votes are VIEW_VOTES, incognito is INCOGNITO_BUNDLE.
 *
 * <h2>Why the denial is verified before it is armed</h2>
 *
 * The constant is matched against {@link Enum#name()}, which is the string the
 * enum's {@code <clinit>} passed to {@code Enum(String, int)} -- R8 keeps it,
 * because the server's own {@code Premiums} block is parsed by that name. The
 * finder additionally proves the parameter type: {@link #load()} compares the
 * enum class it found against the one each hooked target actually takes, read
 * out of the target's own descriptor. If they disagree -- or if the finder
 * never fired -- the denial stays off and the gate behaves exactly as it did
 * before, rather than answering false for a constant on some other enum.
 */
public class PremiumGate implements Hook {

    private static final String TARGETS = "{{PREMIUM_GATE_TARGETS}}";

    /** Dotted FQN of the enum that declares the paid-feature constants. */
    private static final String FEATURE_CLASS = "{{PREMIUM_PLUS_FEATURE_CLASS_NAME}}";

    /** The one constant answered false. See the class comment. */
    private static final String PREMIUM_PLUS = "{{PREMIUM_PLUS_FEATURE_CONSTANT}}";

    /**
     * Armed by {@link #load()} only once the constant has been shown to belong
     * to the enum the hooked predicate takes. Volatile because load() runs on
     * the provider's thread and the replacement runs on whoever asks.
     */
    private static volatile boolean deny_premium_plus = false;

    /**
     * Instance targets only -- the finder's regex cannot match a static method
     * -- so the replacement is static with a leading thiz. The PremiumFeatures
     * argument is a reference, so Object is enough and keeps this class free of
     * any app type at compile time.
     *
     * <p>{@code Enum.name()} is a field read and the comparison is a short
     * String equals, which matters: this predicate is the app's hottest gate,
     * asked on navigation, on every card and on every message row.
     */
    static boolean has_premium_hook(Object thiz, Object feature) {
        if (deny_premium_plus
                && feature instanceof Enum<?>
                && PREMIUM_PLUS.equals(((Enum<?>) feature).name())) {
            return false;
        }
        return true;
    }

    /**
     * True when patch_artifacts left the literal placeholder behind, i.e. the
     * finder never fired. Spelled in two halves so validate-artifactory.py's
     * scan of this tree does not read the example as a real unsubstituted key.
     */
    private static boolean unsubstituted(String value) {
        return value.startsWith("{" + "{");
    }

    /**
     * The single reference parameter of a {@code (L...;)Z} descriptor, dotted.
     * Returns null for anything else, which is the "cannot verify" answer.
     */
    private static String sole_parameter_class(String descriptor) {
        if (descriptor == null || !descriptor.startsWith("(L") || !descriptor.endsWith(";)Z")) {
            return null;
        }
        return descriptor.substring(2, descriptor.length() - 3).replace('/', '.');
    }

    @Override
    public void load() {
        try {
            if (unsubstituted(TARGETS)) {
                // The finder never fired and patch_artifacts left the literal
                // placeholder behind. Compiles fine, hooks nothing -- say so.
                Log.e(HookUtil.TAG, "PremiumGate: placeholder unsubstituted -- "
                        + "the finder did not fire; no premium gate is hooked");
                return;
            }

            Method replacement = PremiumGate.class.getDeclaredMethod(
                    "has_premium_hook", Object.class, Object.class);

            String[] targets = TARGETS.split("\\|");
            int hooked = 0;
            boolean feature_class_agrees = !unsubstituted(FEATURE_CLASS)
                    && !unsubstituted(PREMIUM_PLUS);
            for (String target : targets) {
                String[] parts = target.split(":");
                if (parts.length != 3) {
                    Log.e(HookUtil.TAG, "PremiumGate: malformed target '" + target + "'");
                    continue;
                }
                // Every carrier must take the same enum the tier finder found.
                // One disagreement disarms the denial for all of them: the
                // replacement is shared, so it cannot be right for one target
                // and wrong for another.
                String parameter = sole_parameter_class(parts[2]);
                if (!FEATURE_CLASS.equals(parameter)) {
                    feature_class_agrees = false;
                }
                if (HookUtil.install("PremiumGate", parts[0], parts[1], parts[2], replacement)) {
                    hooked++;
                }
            }

            if (hooked == 0) {
                Log.e(HookUtil.TAG, "PremiumGate: hooked nothing out of "
                        + targets.length + " target(s)");
            } else {
                Log.i(HookUtil.TAG, "PremiumGate: " + hooked + "/" + targets.length
                        + " premium gate(s) forced true");
            }

            deny_premium_plus = feature_class_agrees;
            if (feature_class_agrees) {
                Log.i(HookUtil.TAG, "PremiumGate: " + PREMIUM_PLUS
                        + " answered false -- Priority Like is not offered");
            } else {
                // Not fatal: premium stays unlocked, Priority Like stays on.
                // Loud, because the patched build then advertises a priority
                // the server will not honour.
                Log.e(HookUtil.TAG, "PremiumGate: could not confirm the "
                        + "Priority Like tier against the hooked predicate "
                        + "(tier class '" + FEATURE_CLASS + "') -- leaving every "
                        + "feature true; Priority Like will still be shown");
            }
        } catch (Throwable t) {
            Log.e(HookUtil.TAG, "PremiumGate: " + t);
        }
    }

    @Override
    public void unload() {
    }
}

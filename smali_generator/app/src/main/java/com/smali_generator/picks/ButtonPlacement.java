package com.smali_generator.picks;

/**
 * Where the added Like button sits on a Cupid's Picks card.
 *
 * <p>The button is laid out <b>exactly on top of</b> the SuperLike FAB -- same
 * size, same layout params, same constraints -- and then moved clear of it
 * with {@code View.setTranslationX}. Only the sign and the magnitude of that
 * translation are decided here.
 *
 * <h2>Why a translation and not a margin</h2>
 *
 * The first version computed an end margin instead, and on a device it put the
 * button exactly on top of the FAB: both views reported byte-identical bounds,
 * and the FAB had moved 190px -- one button plus one gap -- inward from where
 * its own XML puts it. The cause is in
 * {@code ConstraintLayout$LayoutParams.<init>(ViewGroup$LayoutParams)}, whose
 * last act is {@code this.mWidget = source.mWidget}: a "copy" of a layout
 * params object <b>shares the source's solver {@code ConstraintWidget} by
 * reference</b>. {@code ConstraintLayout.onLayout} positions every child from
 * {@code ((LayoutParams) child.getLayoutParams()).mWidget}, so two children
 * holding one widget get laid out at one position -- whichever of the two
 * margins reached the solver last.
 *
 * <p>The fix is twofold and this class owns the second half:
 * {@code PicksLikeButton} never calls that constructor (it builds a fresh
 * params object, which gets its own widget), and the offset is applied as a
 * <b>view</b> property rather than a layout one. A translation is honoured for
 * drawing, for {@code getLocationOnScreen} (so a uiautomator dump reports the
 * moved rect) and for touch dispatch -- {@code ViewGroup}'s
 * {@code isTransformedTouchPointInView} inverts the child's matrix -- and
 * every {@code ViewGroup} understands it, so nothing here depends on the
 * parent being a {@code ConstraintLayout} or on margin resolution.
 *
 * <p>Pure, so the arithmetic is unit-tested rather than eyeballed on a device.
 */
public final class ButtonPlacement {

    /** Gap between the two buttons, in dp. Half a button's width reads as a pair. */
    public static final float GAP_DP = 12f;

    /** Used when the FAB's own size cannot be read. The card's FAB is 60dp. */
    public static final float FALLBACK_SIZE_DP = 60f;

    private ButtonPlacement() {
    }

    /**
     * How far along the end edge the button has to move to clear a sibling of
     * the same size that it is laid out on top of.
     *
     * <p>Strictly greater than {@code sizePx}: the two views start out
     * coincident, so an offset equal to the size would leave their edges
     * touching and anything less would leave them overlapping. A non-positive
     * gap is raised to one pixel rather than honoured, which is what makes
     * overlap impossible by construction rather than by hope.
     *
     * @param sizePx the button's side length, in pixels
     * @param gapPx  the space wanted between the two, in pixels
     */
    public static int offset(int sizePx, int gapPx) {
        long total = (long) Math.max(0, sizePx) + Math.max(1, gapPx);
        return (int) Math.min(total, Integer.MAX_VALUE);
    }

    /**
     * The translation that moves a view {@code offsetPx} inward from the end
     * edge -- towards the start, which is negative X under LTR and positive
     * under RTL.
     *
     * @param offsetPx    from {@link #offset}. A negative input is clamped to
     *                    zero rather than flipped, because a flip would move
     *                    the button further onto the sibling it is clearing.
     * @param rightToLeft the resolved layout direction
     */
    public static float translationX(int offsetPx, boolean rightToLeft) {
        int magnitude = Math.max(0, offsetPx);
        return rightToLeft ? magnitude : -magnitude;
    }

    /**
     * The button's side length: the sibling FAB's, so the pair matches, or the
     * card's documented 60dp when the sibling reports nothing usable.
     *
     * <p>A {@code LayoutParams} width can legitimately be {@code MATCH_PARENT}
     * (-1) or {@code WRAP_CONTENT} (-2), and a view that has not been measured
     * yet reports 0. None of those is a size, so all of them fall back.
     *
     * @param siblingSizePx the sibling's measured or specified size
     * @param density       {@code DisplayMetrics.density}; a non-positive
     *                      value falls back to 1, so a stubbed metrics object
     *                      can never produce a zero-sized button
     */
    public static int size(int siblingSizePx, float density) {
        if (siblingSizePx > 0) {
            return siblingSizePx;
        }
        return dp(FALLBACK_SIZE_DP, density);
    }

    /** dp to whole pixels, rounded, with at least one pixel for a positive dp. */
    public static int dp(float valueDp, float density) {
        float scale = density > 0f ? density : 1f;
        int px = Math.round(valueDp * scale);
        if (valueDp > 0f && px < 1) {
            return 1;
        }
        return px;
    }
}

package com.newtube.mobile.ui.playback;

import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * NEWTUBE(motion): where the floating mini card rests. The card is laid out once, at the bottom-end
 * corner (its layout gravity), and every other corner is a translation away from that home place, so
 * no host has to re-lay anything out. The corner it was last dropped in is remembered for the whole
 * process, which makes Home's card and the Search/Channel/Uploads card dock in the same place.
 *
 * <p>{@link MiniCardSwipe} moves the card between corners; hosts only ask for {@link #rest} wherever
 * they used to reset the card's translation to 0.</p>
 */
public final class MiniCardDock {
    public static final int BOTTOM_END = 0;
    public static final int BOTTOM_START = 1;
    public static final int TOP_END = 2;
    public static final int TOP_START = 3;

    private static final float DEFAULT_MARGIN_DP = 12f;

    private static int sCorner = BOTTOM_END;
    private static final List<Runnable> sListeners = new ArrayList<>();
    /** True while the card is under a finger or a spring settles it: layout must not re-seat it. */
    static boolean sInteracting;

    private MiniCardDock() {
    }

    public static int corner() {
        return sCorner;
    }

    public static boolean isTop() {
        return sCorner == TOP_END || sCorner == TOP_START;
    }

    public static void setCorner(int corner) {
        if (corner == sCorner) {
            return;
        }
        sCorner = corner;
        for (Runnable listener : new ArrayList<>(sListeners)) {
            listener.run();
        }
    }

    /** Told when the card is dropped in another corner (a list's bottom inset follows it). */
    public static void addListener(Runnable listener) {
        sListeners.add(listener);
    }

    public static void removeListener(Runnable listener) {
        sListeners.remove(listener);
    }

    /**
     * Keep {@code card} on the remembered corner whenever its layout changes (it is shown, the
     * screen rotates), so it never flashes at its home corner first. Call once per card.
     */
    public static void attach(View card) {
        if (card == null) {
            return;
        }
        card.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (sInteracting || (l == ol && t == ot && r == or && b == ob)) {
                return;
            }
            rest(v);
        });
    }

    /** Put {@code card} on the remembered corner, right now. */
    public static void rest(View card) {
        card.setTranslationX(restX(card, sCorner));
        card.setTranslationY(restY(card, sCorner));
    }

    /** The translationX that puts the card in {@code corner}. */
    public static float restX(View card, int corner) {
        boolean start = corner == BOTTOM_START || corner == TOP_START;
        return start ? margin(card) - card.getLeft() : 0f;
    }

    /** The translationY that puts the card in {@code corner}. */
    public static float restY(View card, int corner) {
        boolean top = corner == TOP_END || corner == TOP_START;
        return top ? margin(card) - card.getTop() : 0f;
    }

    private static float margin(View card) {
        ViewGroup.LayoutParams lp = card.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            int end = ((ViewGroup.MarginLayoutParams) lp).getMarginEnd();
            if (end > 0) {
                return end;
            }
        }
        return DEFAULT_MARGIN_DP * card.getResources().getDisplayMetrics().density;
    }
}

package com.newtube.mobile.ui.playback;

import android.annotation.SuppressLint;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;

import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;

import com.newtube.mobile.ui.common.Haptics;

/**
 * NEWTUBE(motion): the floating mini card follows the finger anywhere on the screen and, on
 * release, springs into the nearest corner - the one its momentum is heading for, not the one under
 * the release point (see {@link MiniCardDock}). The corner is remembered.
 *
 * <p>The card is still dismissed the way it always was, by sliding it off a side: push it over the
 * screen edge, or flick it hard toward the edge it sits at, and it keeps going with the finger's
 * speed while the host closes the session. Taps are untouched: a touch only becomes a drag after
 * the touch slop, and then cancels the card's pending click so the release does not also expand it.</p>
 *
 * <p>Interruptible: grabbing the card while it is still flying to a corner stops it where it is
 * and carries on from that spot (that touch never counts as a tap), and the release velocity is
 * handed to the spring so there is no seam between the drag and the settle.</p>
 *
 * <p>Shared by Home's card (MobileBrowseActivity) and the Search/Channel one
 * (MobileMiniPlayerController); each host does its own closing in {@link Callback}.</p>
 */
public final class MiniCardSwipe implements View.OnTouchListener {
    public interface Callback {
        /** False while the card must not move (closing, entering, no session). */
        boolean canSwipe();

        /** Committed: fly the card to {@code toTranslationX} over {@code durationMs}, then close. */
        void onSwipedAway(float toTranslationX, long durationMs);
    }

    /** A decisive flick toward the edge the card sits at closes it. */
    private static final float DISMISS_VELOCITY_DP = 1000f;
    /** Past this fraction of the card's width over the screen edge, letting go closes it. */
    private static final float DISMISS_OVERSHOOT = 0.4f;
    /** Above this speed the settle overshoots a little, because the gesture carried momentum. */
    private static final float MOMENTUM_VELOCITY_DP = 450f;
    private static final float SETTLE_STIFFNESS = 300f;
    private static final float SETTLE_DAMPING = 1f;
    private static final float SETTLE_DAMPING_MOMENTUM = 0.8f;
    /** Like scroll deceleration: where the card would come to rest if it were thrown. */
    private static final float DECELERATION_RATE = 0.998f;
    private static final float RUBBER_BAND = 0.55f;
    private static final long FLY_MIN_MS = 110;
    private static final long FLY_MAX_MS = 240;

    private final View mCard;
    private final Callback mCallback;
    private final int mTouchSlop;
    private final float mDismissVelocity;
    private final float mMomentumVelocity;
    @Nullable
    private SpringAnimation mSpringX;
    @Nullable
    private SpringAnimation mSpringY;
    private float mDownRawX;
    private float mDownRawY;
    private float mStartTx;
    private float mStartTy;
    private boolean mDragging;
    /** The touch caught the card in flight: no tap, and the card settles again even if not dragged. */
    private boolean mGrabbed;
    /** The touch stopped being a drag (a second finger): consume it to the end. */
    private boolean mIgnoring;
    private boolean mInDismissZone;
    @Nullable
    private VelocityTracker mVelocity;

    /** Returns the swipe, so the host can {@link #stop} it before moving the card itself. */
    public static MiniCardSwipe attach(View card, Callback callback, View... touchTargets) {
        MiniCardSwipe swipe = new MiniCardSwipe(card, callback);
        MiniCardDock.attach(card);
        for (View target : touchTargets) {
            if (target != null) {
                target.setOnTouchListener(swipe);
            }
        }
        return swipe;
    }

    /**
     * The host is about to move the card itself (close, fold, show, enter): stop the springs, which
     * a card.animate().cancel() does not reach, and ignore the rest of a touch still on it. A settle
     * still running kept restoring the card's alpha and position under the host's own fade.
     */
    public void stop() {
        if (mDragging || mGrabbed) {
            mDragging = false;
            mGrabbed = false;
            mIgnoring = true;
            recycleVelocity();
        }
        mInDismissZone = false;
        cancelSprings();
        MiniCardDock.sInteracting = false;
    }

    private MiniCardSwipe(View card, Callback callback) {
        mCard = card;
        mCallback = callback;
        mTouchSlop = ViewConfiguration.get(card.getContext()).getScaledTouchSlop();
        float density = card.getResources().getDisplayMetrics().density;
        mDismissVelocity = DISMISS_VELOCITY_DP * density;
        mMomentumVelocity = MOMENTUM_VELOCITY_DP * density;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownRawX = event.getRawX();
                mDownRawY = event.getRawY();
                mDragging = false;
                mIgnoring = false;
                mGrabbed = false;
                recycleVelocity();
                mVelocity = VelocityTracker.obtain();
                trackVelocity(event);
                if (mCallback.canSwipe() && springsRunning()) {
                    // Caught in flight: stop it where it is and carry on from there. The touch is
                    // consumed so that letting go without moving does not also read as a tap.
                    cancelSprings();
                    mCard.animate().cancel();
                    mStartTx = mCard.getTranslationX();
                    mStartTy = mCard.getTranslationY();
                    MiniCardDock.sInteracting = true;
                    mGrabbed = true;
                    return true;
                }
                return false; // the view still sees the press (ripple, click)
            case MotionEvent.ACTION_MOVE: {
                if (mIgnoring) {
                    return true;
                }
                trackVelocity(event);
                float dx = event.getRawX() - mDownRawX;
                float dy = event.getRawY() - mDownRawY;
                if (!mDragging) {
                    if (Math.hypot(dx, dy) <= mTouchSlop || !mCallback.canSwipe()) {
                        return mGrabbed;
                    }
                    mDragging = true;
                    if (!mGrabbed) {
                        // Drop the press and its pending click: this touch is a drag now.
                        MotionEvent cancel = MotionEvent.obtain(event);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        view.onTouchEvent(cancel);
                        cancel.recycle();
                        mCard.animate().cancel();
                        cancelSprings();
                        mStartTx = mCard.getTranslationX();
                        mStartTy = mCard.getTranslationY();
                    }
                    ViewParent parent = mCard.getParent();
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);
                    }
                    MiniCardDock.sInteracting = true;
                }
                drag(dx, dy);
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (mIgnoring) {
                    mIgnoring = false;
                    return true;
                }
                if (!mDragging && !mGrabbed) {
                    recycleVelocity();
                    return false;
                }
                trackVelocity(event);
                float velocityX = 0f;
                float velocityY = 0f;
                if (mVelocity != null) {
                    mVelocity.computeCurrentVelocity(1000);
                    velocityX = mVelocity.getXVelocity();
                    velocityY = mVelocity.getYVelocity();
                }
                recycleVelocity();
                boolean moved = mDragging;
                mDragging = false;
                mGrabbed = false;
                if (moved) {
                    // Where the finger lifted, which a last MOVE may not have said.
                    drag(event.getRawX() - mDownRawX, event.getRawY() - mDownRawY);
                }
                release(moved ? velocityX : 0f, moved ? velocityY : 0f);
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                // A second finger: not a drag any more. The card settles where it is; the rest of
                // the touch is ignored (no index-0 jump when the first finger lifts).
                if (mDragging || mGrabbed) {
                    mDragging = false;
                    mGrabbed = false;
                    mIgnoring = true;
                    recycleVelocity();
                    dock(0f, 0f);
                }
                return mIgnoring;
            case MotionEvent.ACTION_CANCEL:
                recycleVelocity();
                mIgnoring = false;
                if (mDragging || mGrabbed) {
                    mDragging = false;
                    mGrabbed = false;
                    dock(0f, 0f);
                    return true;
                }
                return false;
            default:
                return mDragging || mGrabbed;
        }
    }

    /** The card is glued to the finger: 1:1 sideways, a little resistance past the top and bottom. */
    private void drag(float dx, float dy) {
        float tx = mStartTx + dx;
        float ty = rubberBand(mStartTy + dy, minTy(), maxTy());
        mCard.setTranslationX(tx);
        mCard.setTranslationY(ty);
        mCard.setAlpha(alphaFor(tx));
        boolean zone = overshoot(tx) > DISMISS_OVERSHOOT * Math.max(1, mCard.getWidth());
        if (zone != mInDismissZone) {
            mInDismissZone = zone;
            Haptics.threshold(mCard, zone);
        }
    }

    /**
     * Decided on the card's position and the finger's speed: over the edge or flicked at it closes
     * the card, anything else docks it.
     */
    private void release(float velocityX, float velocityY) {
        float tx = mCard.getTranslationX();
        float width = Math.max(1, mCard.getWidth());
        float over = overshoot(tx);
        boolean nearEnd = tx > (minTx() + maxTx()) / 2f;
        float edge = nearEnd ? 1f : -1f;
        boolean overEdge = over > DISMISS_OVERSHOOT * width;
        boolean flungOff = Math.abs(velocityX) > mDismissVelocity
                && Math.abs(velocityX) > 1.5f * Math.abs(velocityY)
                && Math.signum(velocityX) == edge;
        if ((overEdge || flungOff) && mCallback.canSwipe()) {
            float direction = overEdge ? (tx > maxTx() ? 1f : -1f) : edge;
            if (!mInDismissZone) {
                Haptics.threshold(mCard, true); // flung before it let go: the click it skipped
            }
            mInDismissZone = false;
            MiniCardDock.sInteracting = false;
            // Off that side of the screen, at the finger's speed.
            float screenWidth = mCard.getResources().getDisplayMetrics().widthPixels;
            float target = direction * screenWidth;
            float remaining = Math.abs(target - tx);
            float speed = Math.max(Math.abs(velocityX), mDismissVelocity);
            long durationMs = Math.round(1000f * remaining / speed);
            durationMs = Math.max(FLY_MIN_MS, Math.min(FLY_MAX_MS, durationMs));
            mCallback.onSwipedAway(target, durationMs);
            return;
        }
        dock(velocityX, velocityY);
    }

    /**
     * Spring into the corner the throw is heading for. The release velocity is the springs' own
     * start velocity, so the card does not hesitate between the drag and the settle, and each axis
     * has its own spring because X and Y rarely move at the same speed.
     */
    private void dock(float velocityX, float velocityY) {
        mInDismissZone = false;
        float projectedX = mCard.getTranslationX() + project(velocityX);
        float projectedY = mCard.getTranslationY() + project(velocityY);
        int best = MiniCardDock.BOTTOM_END;
        float bestDistance = Float.MAX_VALUE;
        for (int corner = MiniCardDock.BOTTOM_END; corner <= MiniCardDock.TOP_START; corner++) {
            float cx = MiniCardDock.restX(mCard, corner) - projectedX;
            float cy = MiniCardDock.restY(mCard, corner) - projectedY;
            float distance = cx * cx + cy * cy;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = corner;
            }
        }
        MiniCardDock.setCorner(best);

        float speed = (float) Math.hypot(velocityX, velocityY);
        float damping = speed > mMomentumVelocity ? SETTLE_DAMPING_MOMENTUM : SETTLE_DAMPING;
        mCard.animate().cancel();
        MiniCardDock.sInteracting = true;
        mSpringX = settle(mSpringX, DynamicAnimation.TRANSLATION_X,
                MiniCardDock.restX(mCard, best), velocityX, damping);
        mSpringY = settle(mSpringY, DynamicAnimation.TRANSLATION_Y,
                MiniCardDock.restY(mCard, best), velocityY, damping);
    }

    private SpringAnimation settle(@Nullable SpringAnimation spring,
            DynamicAnimation.ViewProperty property, float target, float velocity, float damping) {
        if (spring == null) {
            spring = new SpringAnimation(mCard, property, target);
            if (property == DynamicAnimation.TRANSLATION_X) {
                spring.addUpdateListener((animation, value, v) -> mCard.setAlpha(alphaFor(value)));
            }
            spring.addEndListener((animation, canceled, value, v) -> {
                if (!springsRunning()) {
                    MiniCardDock.sInteracting = false;
                }
            });
        }
        spring.cancel();
        spring.getSpring().setStiffness(SETTLE_STIFFNESS).setDampingRatio(damping);
        spring.setStartVelocity(velocity);
        spring.animateToFinalPosition(target);
        return spring;
    }

    private boolean springsRunning() {
        return (mSpringX != null && mSpringX.isRunning()) || (mSpringY != null && mSpringY.isRunning());
    }

    private void cancelSprings() {
        if (mSpringX != null) {
            mSpringX.cancel();
        }
        if (mSpringY != null) {
            mSpringY.cancel();
        }
    }

    /** Where a throw of {@code velocity} px/s would come to rest, as far from the release point. */
    private static float project(float velocity) {
        return (velocity / 1000f) * DECELERATION_RATE / (1f - DECELERATION_RATE);
    }

    private float minTx() {
        return MiniCardDock.restX(mCard, MiniCardDock.BOTTOM_START);
    }

    private float maxTx() {
        return MiniCardDock.restX(mCard, MiniCardDock.BOTTOM_END);
    }

    private float minTy() {
        return MiniCardDock.restY(mCard, MiniCardDock.TOP_END);
    }

    private float maxTy() {
        return MiniCardDock.restY(mCard, MiniCardDock.BOTTOM_END);
    }

    /** How far {@code tx} is past a side of the screen (0 inside), the part that closes the card. */
    private float overshoot(float tx) {
        if (tx > maxTx()) {
            return tx - maxTx();
        }
        if (tx < minTx()) {
            return minTx() - tx;
        }
        return 0f;
    }

    /** The further past the bound, the less the card follows: real things slow before they stop. */
    private float rubberBand(float value, float min, float max) {
        float dimension = Math.max(1, mCard.getHeight()) * 2f;
        if (value > max) {
            float over = value - max;
            return max + (over * dimension * RUBBER_BAND) / (dimension + RUBBER_BAND * over);
        }
        if (value < min) {
            float over = min - value;
            return min - (over * dimension * RUBBER_BAND) / (dimension + RUBBER_BAND * over);
        }
        return value;
    }

    private float alphaFor(float tx) {
        float fraction = Math.min(1f, overshoot(tx) / (Math.max(1, mCard.getWidth()) * 1.2f));
        return 1f - 0.85f * fraction;
    }

    private void trackVelocity(MotionEvent event) {
        if (mVelocity == null) {
            return;
        }
        // Raw coordinates: the card moves under the finger, so view-local ones would lag.
        MotionEvent raw = MotionEvent.obtain(event);
        raw.setLocation(event.getRawX(), event.getRawY());
        mVelocity.addMovement(raw);
        raw.recycle();
    }

    private void recycleVelocity() {
        if (mVelocity != null) {
            mVelocity.recycle();
            mVelocity = null;
        }
    }
}

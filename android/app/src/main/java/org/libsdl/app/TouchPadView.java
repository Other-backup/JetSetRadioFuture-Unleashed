package org.libsdl.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.DisplayCutout;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The on-screen controls, as on the iPhone (port/ios/jsrf_ios.m, NvTouchPad).
 *
 * A stick under the left thumb -- wherever it lands on the left half of the
 * screen is the stick's centre, and it moves the pad's left stick by as far
 * as it goes (the skater's speed follows) -- and the pad's buttons under the
 * right: A B X Y as on the controller, R (talk, spray) and L above them and
 * above the stick, and the Xbox's White and Black as a hollow and a filled
 * circle. A thumb can slide from one button to the next. Back and Start are
 * at the top, the settings at the top left (shown with a controller too).
 * Drawn, not images.
 *
 * What is held goes to the engine through JSRFActivity.nativeTouch
 * (port/android/jsrf_android.c), in the bits and order the input layer
 * reads (src/input/xinput_device.c, kb_merge).
 */
final class TouchPadView extends View {
    interface Listener {
        void onSettings();
    }

    /* The keyboard's bits (nv2a_gl.c, xinput_device.c). */
    static final int KB_UP = 1, KB_DOWN = 2, KB_LEFT = 4, KB_RIGHT = 8, KB_START = 16, KB_BACK = 32;

    private static final int A = 0, B = 1, X = 2, Y = 3, R = 4, L = 5, BLACK = 6, WHITE = 7, COUNT = 8;
    private static final String[] LABEL = { "A", "B", "X", "Y", "R", "L", "●", "○" };
    /* Where each goes among the analog buttons: A B X Y Black White LT RT. */
    private static final int[] ANALOG = { 0, 1, 2, 3, 7, 6, 4, 5 };
    private static final int STICK = -1, P_BACK = 100, P_START = 101, P_GEAR = 102;
    private static final String GEAR = "⚙︎";
    private static final int MAXP = 12;

    private final Listener mListener;
    private final float dp;
    private final boolean mTablet;
    private final Handler mUi = new Handler(Looper.getMainLooper());
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float[] cx = new float[COUNT], cy = new float[COUNT], rad = new float[COUNT];
    private final boolean[] mHeld = new boolean[COUNT];
    private final RectF mBack = new RectF(), mStart = new RectF(), mGear = new RectF();

    /* The pointers being followed, and what each is on: a button, the stick,
     * or one of the pills. */
    private final int[] mPtr = new int[MAXP], mOn = new int[MAXP];
    private int mStickPtr = -1;
    private float mHomeX, mHomeY, mCentreX, mCentreY, mAtX, mAtY, mReach;
    private int mInL, mInT, mInR, mInB;

    private boolean mControls;          /* the pad and Start / Back showing */
    private int mPills;                 /* Start / Back held */
    private final long[] mPillDown = new long[2];
    private final int[] mPillSeq = new int[2];
    private boolean mGearDown;

    TouchPadView(Context c, Listener l) {
        super(c);
        mListener = l;
        dp = c.getResources().getDisplayMetrics().density;
        mTablet = c.getResources().getConfiguration().smallestScreenWidthDp >= 600;
        mLine.setStyle(Paint.Style.STROKE);
        mText.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < MAXP; i++) mPtr[i] = -1;
        setWillNotDraw(false);
    }

    /** The pad and Start / Back, or (with a controller) the settings button alone. */
    void setControls(boolean on) {
        if (on == mControls) return;
        mControls = on;
        reset();
    }

    /** Nothing held (the app is going away, a dialog is coming up). */
    void reset() {
        for (int i = 0; i < MAXP; i++) mPtr[i] = -1;
        mStickPtr = -1;
        mPills = 0;
        mGearDown = false;
        publish();
    }

    /* ---- layout ---------------------------------------------------------- */

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets in) {
        /* Out of the way of the camera's notch, as the iPhone's safe area. */
        int l = 0, t = 0, r = 0, b = 0;
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets c = in.getInsets(WindowInsets.Type.displayCutout());
            l = c.left; t = c.top; r = c.right; b = c.bottom;
        } else if (Build.VERSION.SDK_INT >= 28) {
            DisplayCutout c = in.getDisplayCutout();
            if (c != null) {
                l = c.getSafeInsetLeft(); t = c.getSafeInsetTop();
                r = c.getSafeInsetRight(); b = c.getSafeInsetBottom();
            }
        }
        if (l != mInL || t != mInT || r != mInR || b != mInB) {
            mInL = l; mInT = t; mInR = r; mInB = b;
            place();
        }
        return super.onApplyWindowInsets(in);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        place();
    }

    private void place() {
        float W = getWidth(), H = getHeight();
        if (W <= 0 || H <= 0) return;
        float r = (mTablet ? 36 : 29) * dp, small = r * 0.78f, gap = r * 1.78f;
        float bottom = Math.max(mInB, 10 * dp);
        float bx = W - mInR - 22 * dp - gap - r, by = H - bottom - 12 * dp - gap - r;
        cx[A] = bx;               cy[A] = by + gap;
        cx[B] = bx + gap;         cy[B] = by;
        cx[X] = bx - gap;         cy[X] = by;
        cx[Y] = bx;               cy[Y] = by - gap;
        for (int i = A; i <= Y; i++) rad[i] = r;
        cx[R] = bx - gap * 1.25f;     cy[R] = by - gap * 2.15f;     rad[R] = r;
        cx[BLACK] = bx + gap * 0.55f; cy[BLACK] = by - gap * 2.3f;  rad[BLACK] = small;
        mReach = (mTablet ? 72 : 58) * dp;
        mHomeX = mInL + 26 * dp + mReach;
        mHomeY = H - bottom - 16 * dp - mReach;
        cx[L] = mHomeX + mReach * 0.15f;  cy[L] = mHomeY - mReach - gap * 1.25f;  rad[L] = r;
        cx[WHITE] = cx[L] + gap * 1.5f;   cy[WHITE] = cy[L] - gap * 0.15f;         rad[WHITE] = small;

        float top = mInT + 8 * dp;
        pill(mBack, "BACK", 13, W / 2 - 8 * dp, top, true);
        pill(mStart, "START", 13, W / 2 + 8 * dp, top, false);
        pill(mGear, GEAR, 20, mInL + 8 * dp, top, false);

        /* A thumb that starts at the screen's edge is not the system's Back
         * gesture down where the controls are (Android lets an app keep
         * 200 dp of each side). */
        if (Build.VERSION.SDK_INT >= 29) {
            List<Rect> keep = new ArrayList<>();
            if (mControls) {
                int h0 = (int) (H - 200 * dp);
                keep.add(new Rect(0, h0, (int) (W / 2), (int) H));
                keep.add(new Rect((int) (W / 2), h0, (int) W, (int) H));
            }
            setSystemGestureExclusionRects(keep);
        }
        /* Where things are, on the screen (for the tests that tap them). */
        int[] at = new int[2];
        getLocationOnScreen(at);
        Log.i("JSRF", String.format(Locale.ROOT,
                "touch layout %dx%d at %d,%d: back %d,%d start %d,%d gear %d,%d a %d,%d",
                (int) W, (int) H, at[0], at[1],
                at[0] + (int) mBack.centerX(), at[1] + (int) mBack.centerY(),
                at[0] + (int) mStart.centerX(), at[1] + (int) mStart.centerY(),
                at[0] + (int) mGear.centerX(), at[1] + (int) mGear.centerY(),
                at[0] + (int) cx[A], at[1] + (int) cy[A]));
        invalidate();
    }

    /* A pill's box: text at `size` dp, 14 dp either side, 5 above and below;
     * placed with its right edge (alignRight) or its left edge at x. */
    private void pill(RectF box, String s, float size, float x, float top, boolean alignRight) {
        mText.setTextSize(size * dp);
        mText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        float w = mText.measureText(s) + 28 * dp;
        float h = (mText.descent() - mText.ascent()) + 10 * dp;
        if (alignRight) box.set(x - w, top, x, top + h);
        else box.set(x, top, x + w, top + h);
    }

    /* ---- drawing --------------------------------------------------------- */

    @Override
    protected void onDraw(Canvas c) {
        if (mControls) {
            for (int i = 0; i < COUNT; i++) {
                circle(c, cx[i], cy[i], rad[i], mHeld[i] ? 0.42f : 0.16f, 0.55f);
                mText.setTypeface(Typeface.DEFAULT_BOLD);
                mText.setTextSize(rad[i] * 0.78f);
                mText.setColor(white(0.92f));
                c.drawText(LABEL[i], cx[i], cy[i] - (mText.descent() + mText.ascent()) / 2, mText);
            }
            boolean on = mStickPtr >= 0;
            float sx = on ? mCentreX : mHomeX, sy = on ? mCentreY : mHomeY, kx = sx, ky = sy;
            if (on) {
                float dx = mAtX - sx, dy = mAtY - sy, d = (float) Math.hypot(dx, dy);
                if (d > mReach) { dx *= mReach / d; dy *= mReach / d; }
                kx = sx + dx;
                ky = sy + dy;
            }
            circle(c, sx, sy, mReach, 0.08f, on ? 0.45f : 0.3f);
            circle(c, kx, ky, mReach * 0.42f, on ? 0.45f : 0.25f, 0.6f);
            drawPill(c, mBack, "BACK", 13, (mPills & KB_BACK) != 0);
            drawPill(c, mStart, "START", 13, (mPills & KB_START) != 0);
        }
        drawPill(c, mGear, GEAR, 20, mGearDown);
    }

    private static int white(float a) {
        return Color.argb(Math.round(a * 255), 255, 255, 255);
    }

    private void circle(Canvas c, float x, float y, float r, float fill, float line) {
        mFill.setColor(white(fill));
        c.drawCircle(x, y, r, mFill);
        mLine.setColor(white(line));
        mLine.setStrokeWidth(2 * dp);
        c.drawCircle(x, y, r, mLine);
    }

    private void drawPill(Canvas c, RectF box, String s, float size, boolean down) {
        float rr = 15 * dp;
        mFill.setColor(Color.argb(140, 38, 38, 38));
        c.drawRoundRect(box, rr, rr, mFill);
        mLine.setColor(white(0.25f));
        mLine.setStrokeWidth(1 * dp);
        c.drawRoundRect(box, rr, rr, mLine);
        mText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mText.setTextSize(size * dp);
        mText.setColor(white(down ? 0.5f : 0.9f));
        c.drawText(s, box.centerX(), box.centerY() - (mText.descent() + mText.ascent()) / 2, mText);
    }

    /* ---- touches --------------------------------------------------------- */

    private int buttonAt(float x, float y) {
        int best = -1;
        double bd = 1e9;
        for (int i = 0; i < COUNT; i++) {
            double d = Math.hypot(x - cx[i], y - cy[i]);
            if (d < rad[i] * 1.3 && d < bd) { bd = d; best = i; }
        }
        return best;
    }

    private static boolean near(RectF r, float x, float y, float m) {
        return x >= r.left - m && x <= r.right + m && y >= r.top - m && y <= r.bottom + m;
    }

    private int slotOf(int id) {
        for (int i = 0; i < MAXP; i++) if (mPtr[i] == id) return i;
        return -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int act = e.getActionMasked();
        boolean used = true;
        switch (act) {
        case MotionEvent.ACTION_DOWN:
        case MotionEvent.ACTION_POINTER_DOWN: {
            int i = e.getActionIndex();
            used = down(e.getPointerId(i), e.getX(i), e.getY(i));
            if (act == MotionEvent.ACTION_DOWN && !mControls && !used) return false;
            break;
        }
        case MotionEvent.ACTION_MOVE:
            for (int i = 0; i < e.getPointerCount(); i++) move(e.getPointerId(i), e.getX(i), e.getY(i));
            break;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_POINTER_UP: {
            int i = e.getActionIndex();
            up(e.getPointerId(i), e.getX(i), e.getY(i), true);
            break;
        }
        case MotionEvent.ACTION_CANCEL:
            for (int i = 0; i < MAXP; i++) if (mPtr[i] >= 0) up(mPtr[i], 0, 0, false);
            break;
        default:
            break;
        }
        publish();
        return true;
    }

    private boolean down(int id, float x, float y) {
        int slot = -1, on;
        for (int i = 0; i < MAXP; i++) if (mPtr[i] < 0) { slot = i; break; }
        if (slot < 0) return false;
        float m = 8 * dp;
        if (near(mGear, x, y, m)) {
            on = P_GEAR;
            mGearDown = true;
        } else if (!mControls) {
            return false;
        } else if (near(mStart, x, y, m)) {
            on = P_START;
            pillDown(KB_START, 1);
        } else if (near(mBack, x, y, m)) {
            on = P_BACK;
            pillDown(KB_BACK, 0);
        } else if ((on = buttonAt(x, y)) >= 0) {
            /* a button */
        } else if (mStickPtr < 0 && x < getWidth() * 0.5f) {
            /* The stick, centred where the thumb landed (kept on screen). */
            on = STICK;
            mStickPtr = id;
            mCentreX = Math.max(x, mInL + mReach * 0.6f);
            mCentreY = Math.min(y, getHeight() - mReach * 0.6f);
            mAtX = x;
            mAtY = y;
        } else {
            return false;
        }
        mPtr[slot] = id;
        mOn[slot] = on;
        return true;
    }

    private void move(int id, float x, float y) {
        int i = slotOf(id);
        if (i < 0) return;
        if (mOn[i] == STICK) {
            mAtX = x;
            mAtY = y;
        } else if (mOn[i] >= 0 && mOn[i] < COUNT) {
            int k = buttonAt(x, y);
            if (k >= 0) mOn[i] = k;                 /* slid onto another button */
        }
    }

    private void up(int id, float x, float y, boolean lifted) {
        int i = slotOf(id);
        if (i < 0) return;
        switch (mOn[i]) {
        case STICK:
            mStickPtr = -1;
            break;
        case P_START:
            pillUp(KB_START, 1);
            break;
        case P_BACK:
            pillUp(KB_BACK, 0);
            break;
        case P_GEAR:
            mGearDown = false;
            if (lifted && near(mGear, x, y, 16 * dp) && mListener != null) mListener.onSettings();
            break;
        default:
            break;
        }
        mPtr[i] = -1;
    }

    /* Start and Back are held at least 0.1 s: a quick tap can come and go
     * between two reads of the pad. */
    private void pillDown(int bit, int k) {
        mPills |= bit;
        mPillDown[k] = SystemClock.uptimeMillis();
        mPillSeq[k]++;
    }

    private void pillUp(final int bit, final int k) {
        long held = SystemClock.uptimeMillis() - mPillDown[k];
        if (held >= 100) {
            mPills &= ~bit;
            return;
        }
        final int seq = mPillSeq[k];
        mUi.postDelayed(() -> {
            if (mPillSeq[k] == seq) {
                mPills &= ~bit;
                publish();
            }
        }, 100 - held);
    }

    /* What is held, out to the engine. */
    private void publish() {
        boolean[] held = new boolean[COUNT];
        int analog = 0, dpad = 0, sx = 0, sy = 0;
        for (int i = 0; i < MAXP; i++)
            if (mPtr[i] >= 0 && mOn[i] >= 0 && mOn[i] < COUNT) held[mOn[i]] = true;
        for (int i = 0; i < COUNT; i++) {
            mHeld[i] = held[i];
            if (held[i]) analog |= 1 << ANALOG[i];
        }
        boolean stick = mStickPtr >= 0 && mReach > 0;
        if (stick) {
            float dx = (mAtX - mCentreX) / mReach, dy = (mAtY - mCentreY) / mReach;
            float d = (float) Math.hypot(dx, dy);
            if (d > 1) { dx /= d; dy /= d; }
            sx = Math.round(dx * 32767);
            sy = Math.round(-dy * 32767);           /* the pad's y is up */
            if (dx < -0.6f) dpad |= KB_LEFT;
            if (dx > 0.6f) dpad |= KB_RIGHT;
            if (dy < -0.6f) dpad |= KB_UP;
            if (dy > 0.6f) dpad |= KB_DOWN;
        }
        JSRFActivity.nativeTouch(mPills | dpad, analog, sx, sy, stick, mControls);
        invalidate();
    }
}

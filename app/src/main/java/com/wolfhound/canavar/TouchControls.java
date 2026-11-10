package com.wolfhound.canavar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;

import java.util.HashSet;
import java.util.Set;

/**
 * Compact on-screen controls drawn over the SDL surface.
 *
 *   Left half of the screen : floating stick (touch anywhere, it appears under the finger)
 *                             up/down = walk, left/right = strafe
 *                             (the stick only MOVES the player, it never turns)
 *   Right half of the screen: swipe left/right anywhere (not on a button) = turn the camera
 *   Right side              : FIRE (big), USE, RUN (toggle: blue = run on, gray = walk)
 *                             hold FIRE and slide the finger = turn the camera (aim while shooting)
 *   Top right               : MENU and weapon keys 1-4
 *
 * Multi-touch: stick and buttons work at the same time.
 */
public class TouchControls extends View {

    /** Receives engine key presses (SDL keycodes). */
    public interface KeySink {
        void key(int keysym, boolean down);

        /** Camera turn from a swipe; positive = turn right (already scaled). */
        void look(int dx);

        /** Strafe from the stick: -1 left, 0 none, +1 right. */
        void strafe(int dir);

        /** True while a level is being played (false in menus). */
        boolean inGameplay();
    }

    /** Engine turn units per swiped pixel. Raise it for a faster camera. */
    private static final float LOOK_SENSITIVITY = 4.5f;

    // SDL2 keycodes (must match SDL_keycode.h)
    public static final int K_ESC    = 27;
    public static final int K_SPACE  = 32;
    public static final int K_1      = 49;
    public static final int K_2      = 50;
    public static final int K_3      = 51;
    public static final int K_4      = 52;
    public static final int K_RIGHT  = 1073741903;
    public static final int K_LEFT   = 1073741904;
    public static final int K_DOWN   = 1073741905;
    public static final int K_UP     = 1073741906;
    public static final int K_LCTRL  = 1073742048;  // fire
    public static final int K_LSHIFT = 1073742049;  // run
    public static final int K_LALT   = 1073742050;  // strafe

    private static final class Btn {
        final String label;
        final int key;
        final float textScale;
        float cx, cy, r;
        int pointerId = -1;

        Btn(String label, int key, float textScale) {
            this.label = label;
            this.key = key;
            this.textScale = textScale;
        }
    }

    private final KeySink sink;
    private final float dp;

    private final Btn fire   = new Btn("FIRE", K_LCTRL, 0.34f);
    private final Btn use    = new Btn("USE", K_SPACE, 0.36f);
    private final Btn run    = new Btn("RUN", -1, 0.36f);   // toggle, no key of its own
    private final Btn menu   = new Btn("\u2261", K_ESC, 0.90f);
    private final Btn w1 = new Btn("1", K_1, 0.70f);
    private final Btn w2 = new Btn("2", K_2, 0.70f);
    private final Btn w3 = new Btn("3", K_3, 0.70f);
    private final Btn w4 = new Btn("4", K_4, 0.70f);
    private final Btn[] buttons = { fire, use, run, menu, w1, w2, w3, w4 };

    // stick
    private int stickId = -1;
    private float stickX, stickY;     // origin (where the finger went down)
    private float knobX, knobY;       // current finger position
    private float idleX, idleY;       // where the faint idle ring is drawn
    private float stickR;             // max knob travel

    // camera swipe (right half)
    private int lookId = -1;
    private float lookLastX;
    private float lookRemainder;      // sub-unit leftovers so slow swipes still turn

    // camera turn by the finger that holds FIRE (aim while shooting)
    private float fireLastX;
    private float fireRemainder;

    // run toggle: while on, LSHIFT is held down permanently
    private boolean runOn = false;

    // last strafe direction sent to the engine
    private int strafeDir = 0;

    // insets (px) kept clear of screen edges / camera cutout
    private float insetL, insetR, insetT, insetB;

    private final Set<Integer> pressed = new HashSet<>();

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public TouchControls(Context ctx, KeySink sink) {
        super(ctx);
        this.sink = sink;
        this.dp = ctx.getResources().getDisplayMetrics().density;

        setFocusable(false);
        setFocusableInTouchMode(false);

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(1.5f * dp);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        fire.r = 36 * dp;
        use.r = 25 * dp;
        run.r = 22 * dp;
        menu.r = 17 * dp;
        w1.r = w2.r = w3.r = w4.r = 14 * dp;
        stickR = 52 * dp;
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        layoutControls();
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        float base = 22 * dp;
        float l = base, r = base, t = base * 0.6f, b = base;
        if (Build.VERSION.SDK_INT >= 28) {
            DisplayCutout cut = insets.getDisplayCutout();
            if (cut != null) {
                l = Math.max(l, cut.getSafeInsetLeft() + 8 * dp);
                r = Math.max(r, cut.getSafeInsetRight() + 8 * dp);
                t = Math.max(t, cut.getSafeInsetTop() + 4 * dp);
                b = Math.max(b, cut.getSafeInsetBottom() + 8 * dp);
            }
        }
        insetL = l; insetR = r; insetT = t; insetB = b;
        layoutControls();
        return super.onApplyWindowInsets(insets);
    }

    private void layoutControls() {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (insetL == 0) { insetL = insetR = insetB = 22 * dp; insetT = 14 * dp; }

        // right cluster, thumb reachable
        fire.cx = w - insetR - fire.r - 6 * dp;
        fire.cy = h - insetB - fire.r;
        use.cx = fire.cx - fire.r - use.r - 6 * dp;
        use.cy = fire.cy + 8 * dp;
        run.cx = use.cx;
        run.cy = use.cy - use.r - run.r - 10 * dp;

        // top right: menu + weapon keys
        menu.cx = w - insetR - menu.r;
        menu.cy = insetT + menu.r;
        float step = 2 * w1.r + 8 * dp;
        float x = menu.cx - menu.r - 14 * dp - w4.r;
        w4.cx = x;            w4.cy = menu.cy;
        w3.cx = x - step;     w3.cy = menu.cy;
        w2.cx = x - 2 * step; w2.cy = menu.cy;
        w1.cx = x - 3 * step; w1.cy = menu.cy;

        // idle stick ring (bottom left)
        idleX = insetL + stickR + 10 * dp;
        idleY = h - insetB - stickR;
        invalidate();
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        // stick
        if (stickId != -1) {
            fillPaint.setColor(0x22FFFFFF);
            c.drawCircle(stickX, stickY, stickR, fillPaint);
            ringPaint.setColor(0x66FFFFFF);
            c.drawCircle(stickX, stickY, stickR, ringPaint);
            fillPaint.setColor(0x77FFFFFF);
            c.drawCircle(knobX, knobY, stickR * 0.42f, fillPaint);
        } else {
            ringPaint.setColor(0x33FFFFFF);
            c.drawCircle(idleX, idleY, stickR, ringPaint);
            fillPaint.setColor(0x22FFFFFF);
            c.drawCircle(idleX, idleY, stickR * 0.42f, fillPaint);
        }

        // buttons
        for (Btn b : buttons) {
            boolean down = b.pointerId != -1;
            boolean blue = (b == run && runOn);
            fillPaint.setColor(blue ? 0x883D8BFF : (down ? 0x66FFFFFF : 0x1EFFFFFF));
            c.drawCircle(b.cx, b.cy, b.r, fillPaint);
            ringPaint.setColor(blue ? 0xFF5AA2FF : (down ? 0xAAFFFFFF : 0x55FFFFFF));
            c.drawCircle(b.cx, b.cy, b.r, ringPaint);
            textPaint.setColor(blue ? 0xFFFFFFFF : (down ? 0xFFFFFFFF : 0xB0FFFFFF));
            textPaint.setTextSize(b.r * b.textScale * 1.2f);
            float ty = b.cy - (textPaint.descent() + textPaint.ascent()) / 2f;
            c.drawText(b.label, b.cx, ty, textPaint);
        }
    }

    // ------------------------------------------------------------------
    // Touch handling
    // ------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        final int action = ev.getActionMasked();
        final int idx = ev.getActionIndex();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                pointerDown(ev.getPointerId(idx), ev.getX(idx), ev.getY(idx));
                break;

            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < ev.getPointerCount(); i++) {
                    int pid = ev.getPointerId(i);
                    if (pid == stickId) {
                        moveStick(ev.getX(i), ev.getY(i));
                    } else if (pid == lookId) {
                        moveLook(ev.getX(i));
                    } else if (pid == fire.pointerId) {
                        moveFireLook(ev.getX(i));
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                pointerUp(ev.getPointerId(idx));
                break;

            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                break;
        }
        return true;
    }

    private void pointerDown(int id, float x, float y) {
        // buttons first (slightly enlarged hit area)
        Btn hit = null;
        float best = Float.MAX_VALUE;
        for (Btn b : buttons) {
            if (b.pointerId != -1) continue;
            float d = dist(x, y, b.cx, b.cy);
            if (d <= b.r + 8 * dp && d < best) {
                best = d;
                hit = b;
            }
        }
        if (hit == run) {
            // toggle: one tap on, next tap off
            runOn = !runOn;
            setKey(K_LSHIFT, runOn);
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            invalidate();
            return;
        }
        if (hit != null) {
            hit.pointerId = id;
            if (hit == fire) {
                fireLastX = x;
                fireRemainder = 0f;
            }
            setKey(hit.key, true);
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            invalidate();
            return;
        }

        // otherwise: floating stick in the left half
        if (stickId == -1 && x < getWidth() * 0.5f) {
            stickId = id;
            // keep the whole ring on screen
            stickX = clamp(x, stickR + 4 * dp, getWidth() - stickR);
            stickY = clamp(y, stickR + 4 * dp, getHeight() - stickR - 4 * dp);
            knobX = x;
            knobY = y;
            moveStick(x, y);
            return;
        }

        // right half (and nothing else hit): swipe to turn the camera
        if (lookId == -1 && x >= getWidth() * 0.5f) {
            lookId = id;
            lookLastX = x;
            lookRemainder = 0f;
        }
    }

    private void moveLook(float x) {
        float v = (x - lookLastX) * LOOK_SENSITIVITY + lookRemainder;
        lookLastX = x;
        int units = (int) v;           // truncates toward zero
        lookRemainder = v - units;
        if (units != 0) sink.look(units);
    }

    /** Holding FIRE and sliding the same finger left/right turns the camera. */
    private void moveFireLook(float x) {
        float v = (x - fireLastX) * LOOK_SENSITIVITY + fireRemainder;
        fireLastX = x;
        int units = (int) v;
        fireRemainder = v - units;
        if (units != 0) sink.look(units);
    }

    private void setStrafe(int dir) {
        if (dir != strafeDir) {
            strafeDir = dir;
            sink.strafe(dir);
        }
    }

    private void pointerUp(int id) {
        for (Btn b : buttons) {
            if (b.pointerId == id) {
                b.pointerId = -1;
                setKey(b.key, false);
            }
        }
        if (id == stickId) {
            stickId = -1;
            setKey(K_UP, false);
            setKey(K_DOWN, false);
            setKey(K_LEFT, false);
            setKey(K_RIGHT, false);
            setStrafe(0);
        }
        if (id == lookId) {
            lookId = -1;
            lookRemainder = 0f;
        }
        invalidate();
    }

    private void moveStick(float x, float y) {
        float dx = x - stickX;
        float dy = y - stickY;
        float d = (float) Math.hypot(dx, dy);
        if (d > stickR) {
            dx = dx * stickR / d;
            dy = dy * stickR / d;
        }
        knobX = stickX + dx;
        knobY = stickY + dy;

        float nx = dx / stickR;   // -1..1
        float ny = dy / stickR;

        final float dead = 0.30f;
        if (sink.inGameplay()) {
            // gameplay: the stick only moves the player (forward/back + strafe)
            setKey(K_LEFT, false);
            setKey(K_RIGHT, false);
            setStrafe(nx < -dead ? -1 : (nx > dead ? 1 : 0));
        } else {
            // menus: left/right still work as arrow keys (sliders etc.)
            setStrafe(0);
            setKey(K_LEFT,  nx < -dead);
            setKey(K_RIGHT, nx >  dead);
        }
        setKey(K_UP,    ny < -dead);
        setKey(K_DOWN,  ny >  dead);
        invalidate();
    }

    /** Releases every key (call on pause / focus loss). */
    public void releaseAll() {
        for (Btn b : buttons) b.pointerId = -1;
        stickId = -1;
        lookId = -1;
        lookRemainder = 0f;
        setStrafe(0);
        for (Integer k : new HashSet<>(pressed)) {
            sink.key(k, false);
        }
        pressed.clear();
        if (runOn) setKey(K_LSHIFT, true);   // run toggle survives pause / cancel
        invalidate();
    }

    private void setKey(int key, boolean down) {
        if (down) {
            if (pressed.add(key)) sink.key(key, true);
        } else {
            if (pressed.remove(key)) sink.key(key, false);
        }
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        return (float) Math.hypot(x1 - x2, y1 - y2);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}

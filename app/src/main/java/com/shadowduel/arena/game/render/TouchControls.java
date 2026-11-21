package com.shadowduel.arena.game.render;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;

import com.shadowduel.arena.game.sim.Input;

import static com.shadowduel.arena.game.sim.Const.*;

/**
 * Multi-touch controls: floating joystick on the left, combat buttons on the right.
 * Touch events arrive on the UI thread; sample() is called from the game thread once
 * per tick, so button presses are latched and never lost between ticks.
 */
public final class TouchControls {

    public static final class Button {
        public final String label;
        public final int bit;
        public final boolean hold;
        public final int color;
        public float fx, fy, fr;     // position/radius as fractions of screen height (x of width)
        public float x, y, r;
        int pointer = -1;
        float pressAnim;

        Button(String label, int bit, boolean hold, int color, float fx, float fy, float fr) {
            this.label = label;
            this.bit = bit;
            this.hold = hold;
            this.color = color;
            this.fx = fx;
            this.fy = fy;
            this.fr = fr;
        }
    }

    public final Button[] buttons = {
            new Button("ATK", BTN_ATTACK, false, 0xFFFF3B5C, 0.885f, 0.76f, 0.105f),
            new Button("HEAVY", BTN_HEAVY, false, 0xFFFF8A1F, 0.772f, 0.80f, 0.072f),
            new Button("SKILL", BTN_SPECIAL, false, 0xFFB34BFF, 0.812f, 0.535f, 0.08f),
            new Button("JUMP", BTN_JUMP, false, 0xFF3BE08A, 0.952f, 0.52f, 0.075f),
            new Button("DASH", BTN_DASH, false, 0xFF3BD5FF, 0.715f, 0.585f, 0.065f),
            new Button("GUARD", BTN_BLOCK, true, 0xFF8FA3FF, 0.955f, 0.30f, 0.062f),
    };

    private int w, h;
    private float baseX, baseY, knobX, knobY, joyR;
    private int joyPointer = -1;
    private float restX, restY;
    private int latched;          // presses since last sample
    private float energyFrac = 1f;
    private boolean specialReady;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    public float opacity = 0.85f;

    public TouchControls() {
        text.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC));
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(0xFFFFFFFF);
    }

    public synchronized void layout(int width, int height) {
        w = width;
        h = height;
        for (Button b : buttons) {
            b.x = b.fx * w;
            b.y = b.fy * h;
            b.r = b.fr * h;
        }
        joyR = h * 0.12f;
        restX = w * 0.11f;
        restY = h * 0.72f;
        baseX = knobX = restX;
        baseY = knobY = restY;
    }

    public synchronized boolean onTouch(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int id = e.getPointerId(idx);
                float x = e.getX(idx), y = e.getY(idx);
                Button hit = buttonAt(x, y);
                if (hit != null) {
                    hit.pointer = id;
                    hit.pressAnim = 1f;
                    latched |= hit.bit;
                } else if (x < w * 0.45f && y > h * 0.25f && joyPointer == -1) {
                    joyPointer = id;
                    baseX = knobX = x;
                    baseY = knobY = y;
                }
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    if (e.getPointerId(i) == joyPointer) {
                        float dx = e.getX(i) - baseX, dy = e.getY(i) - baseY;
                        float d = (float) Math.hypot(dx, dy);
                        if (d > joyR) {
                            // drag the base along so direction changes stay responsive
                            baseX += dx / d * (d - joyR);
                            baseY += dy / d * (d - joyR);
                            dx = e.getX(i) - baseX;
                            dy = e.getY(i) - baseY;
                        }
                        knobX = baseX + dx;
                        knobY = baseY + dy;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                int id = e.getPointerId(idx);
                if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
                    release(-2);
                } else release(id);
                break;
            }
        }
        return true;
    }

    private void release(int id) {
        if (id == -2 || id == joyPointer) {
            joyPointer = -1;
            baseX = knobX = restX;
            baseY = knobY = restY;
        }
        for (Button b : buttons) if (id == -2 || b.pointer == id) b.pointer = -1;
    }

    private Button buttonAt(float x, float y) {
        Button best = null;
        float bd = Float.MAX_VALUE;
        for (Button b : buttons) {
            float d = (float) Math.hypot(x - b.x, y - b.y);
            if (d < b.r * 1.25f && d < bd) {
                bd = d;
                best = b;
            }
        }
        return best;
    }

    /** Called by the game thread once per tick. */
    public synchronized Input sample() {
        Input in = new Input();
        float dx = (knobX - baseX) / joyR, dy = (knobY - baseY) / joyR;
        if (Math.abs(dx) > 0.18f) in.mx = Math.round(Math.max(-1, Math.min(1, dx)) * 100);
        in.my = dy > 0.6f ? 1 : dy < -0.75f ? -1 : 0;
        int b = latched;
        for (Button bt : buttons) if (bt.hold && bt.pointer != -1) b |= bt.bit;
        // pushing the stick hard upward also jumps
        if (in.my < 0 && joyUpLatch) {
            b |= BTN_JUMP;
            joyUpLatch = false;
        }
        if (in.my >= 0) joyUpLatch = true;
        latched = 0;
        in.b = b;
        return in;
    }

    private boolean joyUpLatch = true;

    /** Keyboard / gamepad support for emulators and testing. */
    public synchronized void key(int bit, boolean down, int axis) {
        if (bit != 0) {
            if (down) latched |= bit;
            for (Button b : buttons) if (b.bit == bit && b.hold) b.pointer = down ? 99 : -1;
        }
        if (axis != Integer.MIN_VALUE) {
            knobX = baseX + axis * joyR;
            knobY = baseY;
        }
    }

    public void setEnergy(float frac, boolean ready) {
        energyFrac = frac;
        specialReady = ready;
    }

    public synchronized void draw(Canvas c, float dt) {
        // joystick
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(baseX, baseY, joyR * 1.2f, 0x55FFFFFF & alphaMask(), 0x00000000, Shader.TileMode.CLAMP));
        c.drawCircle(baseX, baseY, joyR * 1.2f, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(h * 0.006f);
        p.setColor(Palette.withAlpha(0xFFFFFFFF, 0.45f * opacity));
        c.drawCircle(baseX, baseY, joyR, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Palette.withAlpha(0xFF1A2040, 0.8f * opacity));
        c.drawCircle(knobX, knobY, joyR * 0.48f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(Palette.withAlpha(0xFF3BD5FF, 0.9f * opacity));
        c.drawCircle(knobX, knobY, joyR * 0.48f, p);
        p.setStyle(Paint.Style.FILL);

        for (Button b : buttons) {
            b.pressAnim = Math.max(0, b.pressAnim - dt * 5);
            boolean down = b.pointer != -1;
            float r = b.r * (down ? 0.92f : 1f);
            boolean dim = b.bit == BTN_SPECIAL && !specialReady;
            int col = dim ? 0xFF5A5A70 : b.color;
            p.setShader(new RadialGradient(b.x, b.y - r * 0.3f, r * 1.3f,
                    Palette.withAlpha(Palette.mix(col, 0xFFFFFFFF, 0.25f), (down ? 0.95f : 0.7f) * opacity),
                    Palette.withAlpha(Palette.mix(col, 0xFF000000, 0.55f), 0.75f * opacity), Shader.TileMode.CLAMP));
            c.drawCircle(b.x, b.y, r, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(h * 0.005f);
            p.setColor(Palette.withAlpha(0xFFFFFFFF, 0.6f * opacity + b.pressAnim * 0.4f));
            c.drawCircle(b.x, b.y, r, p);
            if (b.bit == BTN_SPECIAL) {
                p.setStrokeWidth(h * 0.012f);
                p.setColor(Palette.withAlpha(specialReady ? 0xFFFFC93B : 0xFFB34BFF, opacity));
                rect.set(b.x - r - h * 0.012f, b.y - r - h * 0.012f, b.x + r + h * 0.012f, b.y + r + h * 0.012f);
                c.drawArc(rect, -90, 360 * Math.min(1, energyFrac), false, p);
            }
            p.setStyle(Paint.Style.FILL);
            text.setTextSize(r * (b.label.length() > 4 ? 0.36f : 0.46f));
            text.setColor(Palette.withAlpha(0xFFFFFFFF, opacity));
            c.drawText(b.label, b.x, b.y + text.getTextSize() * 0.36f, text);
        }
    }

    private int alphaMask() {
        int a = (int) (255 * opacity);
        return (a << 24) | 0x00FFFFFF;
    }
}

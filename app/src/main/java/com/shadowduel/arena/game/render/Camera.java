package com.shadowduel.arena.game.render;

import android.graphics.Canvas;

import com.shadowduel.arena.game.sim.GameMap;

/**
 * Per-phone camera. It only changes how the world is drawn — world coordinates
 * (shared with the server) are never modified.
 */
public final class Camera {
    /** World units visible vertically at zoom 1. */
    public static final float BASE_VIEW_H = 440f;

    public float x, y;          // centre in world units
    public float zoom = 1f, targetZoom = 1f;
    public float scale;         // pixels per world unit
    public float viewW, viewH;  // visible world size
    public int screenW, screenH;
    private float shake, shakeX, shakeY;
    private boolean initialised;

    public void resize(int w, int h) {
        screenW = w;
        screenH = h;
    }

    public void addShake(float amount) {
        shake = Math.min(18f, shake + amount);
    }

    /** Smoothly follow (tx, ty) and clamp to the map. */
    public void update(float tx, float ty, GameMap map, float dt) {
        zoom += (targetZoom - zoom) * Math.min(1f, dt * 3f);
        scale = screenH / (BASE_VIEW_H / zoom);
        viewW = screenW / scale;
        viewH = screenH / scale;
        // look a bit above the feet so the fighter sits in the lower-middle of the screen
        float gx = tx, gy = ty - 70;
        if (!initialised) {
            x = gx;
            y = gy;
            initialised = true;
        } else {
            float k = 1f - (float) Math.exp(-dt * 7f);
            x += (gx - x) * k;
            y += (gy - y) * k;
        }
        float halfW = viewW / 2, halfH = viewH / 2;
        float mw = (float) map.pixelW, mh = (float) map.pixelH + 80;
        x = mw <= viewW ? mw / 2 : Math.max(halfW, Math.min(mw - halfW, x));
        y = mh <= viewH ? mh / 2 : Math.max(halfH - 200, Math.min(mh - halfH, y));
        if (shake > 0.1f) {
            shakeX = (float) (Math.random() * 2 - 1) * shake;
            shakeY = (float) (Math.random() * 2 - 1) * shake;
            shake *= (float) Math.exp(-dt * 10f);
        } else {
            shakeX = shakeY = 0;
            shake = 0;
        }
    }

    public void snap() {
        initialised = false;
    }

    /** Applies the world transform to the canvas. */
    public void apply(Canvas c) {
        c.translate(screenW / 2f + shakeX, screenH / 2f + shakeY);
        c.scale(scale, scale);
        c.translate(-x, -y);
    }

    public float left() { return x - viewW / 2; }
    public float right() { return x + viewW / 2; }
    public float top() { return y - viewH / 2; }
    public float bottom() { return y + viewH / 2; }

    public float toScreenX(double wx) { return (float) ((wx - x) * scale + screenW / 2f + shakeX); }
    public float toScreenY(double wy) { return (float) ((wy - y) * scale + screenH / 2f + shakeY); }

    public boolean visible(double wx, double wy, double margin) {
        return wx > left() - margin && wx < right() + margin && wy > top() - margin && wy < bottom() + margin;
    }
}

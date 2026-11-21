package com.shadowduel.arena.game.render;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import static com.shadowduel.arena.game.sim.Const.*;

/**
 * Procedural anime fighters (Kaze, Ryujin, Akira) drawn with a small skeleton.
 * Each animation state sets joint angles; everything is drawn in "local space"
 * (feet at 0,0, +x = facing direction, y down) with a dark cel outline.
 */
public final class CharacterRenderer {

    /** Joint angles in degrees: 0 = straight down, positive = rotated toward the facing side. */
    static final class Pose {
        float lean, bob, headTilt;
        float fThigh, fShin, bThigh, bShin;     // shin angles are absolute
        float fUpper, fFore, bUpper, bFore;
        boolean weaponOut, crouch;
        float scarf;                           // scarf wave phase
        float slash = -1;                      // 0..1 progress of a weapon swing, -1 none
        float glow;                            // energy aura 0..1
    }

    private static final int OUTLINE = 0xFF0B0A16;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final Pose pose = new Pose();
    private float flash;   // 0..1 white hit flash
    private float alpha = 1f;

    // skeleton (units)
    private static final float HIP_Y = -34, TORSO = 22, NECK = 9, HEAD_R = 15.5f;
    private static final float THIGH = 17, SHIN = 17, UPPER = 13, FORE = 13;

    public CharacterRenderer() {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
    }

    private int c(int color) {
        int col = flash > 0 ? Palette.mix(color, 0xFFFFFFFF, flash) : color;
        if (alpha < 1f) col = Palette.withAlpha(col, ((col >>> 24) / 255f) * alpha);
        return col;
    }

    // ------------------------------------------------------------------ poses
    private void computePose(String charId, int anim, float t, float p, float speedFrac) {
        Pose q = pose;
        q.lean = 0; q.bob = 0; q.headTilt = 0; q.weaponOut = false; q.crouch = false; q.slash = -1; q.glow = 0;
        q.scarf = t * 9f;
        switch (anim) {
            case ANIM_RUN: {
                float ph = t * 13f;
                float s = (float) Math.sin(ph), co = (float) Math.cos(ph);
                q.lean = 14;
                q.bob = -Math.abs(co) * 3f;
                q.fThigh = s * 42; q.bThigh = -s * 42;
                q.fShin = q.fThigh - 20 - Math.max(0, -co) * 55;
                q.bShin = q.bThigh - 20 - Math.max(0, co) * 55;
                q.fUpper = -s * 50 + 10; q.fFore = q.fUpper + 75;
                q.bUpper = s * 50 + 10; q.bFore = q.bUpper + 75;
                break;
            }
            case ANIM_JUMP:
                q.lean = 6; q.fThigh = 55; q.fShin = -10; q.bThigh = 10; q.bShin = -40;
                q.fUpper = 150; q.fFore = 175; q.bUpper = -40; q.bFore = 10;
                break;
            case ANIM_FALL: {
                float w = (float) Math.sin(t * 10) * 6;
                q.lean = -4; q.fThigh = 20 + w; q.fShin = 5; q.bThigh = -15 - w; q.bShin = -30;
                q.fUpper = 110 + w; q.fFore = 140; q.bUpper = -100 - w; q.bFore = -130;
                break;
            }
            case ANIM_ATTACK1: case ANIM_ATTACK2: case ANIM_ATTACK3: {
                int combo = anim - ANIM_ATTACK1;
                float wind = Math.min(1, p / 0.25f), swing = Math.max(0, Math.min(1, (p - 0.2f) / 0.35f));
                q.lean = 8 + swing * 14;
                q.fThigh = 30; q.fShin = 0; q.bThigh = -30; q.bShin = -45;
                q.weaponOut = true;
                q.slash = swing > 0 && swing < 1 ? swing : -1;
                if (combo == 1) { // rising slash
                    q.fUpper = 40 - wind * 30 + swing * 170; q.fFore = q.fUpper + 10;
                } else if (combo == 2) { // spinning finisher
                    q.lean = 20; q.fThigh = 45; q.bThigh = -45;
                    q.fUpper = 200 - wind * 60 - swing * 160; q.fFore = q.fUpper - 10;
                } else {
                    q.fUpper = 150 - wind * 20 - swing * 100; q.fFore = q.fUpper - 20;
                }
                q.bUpper = -30; q.bFore = 20;
                break;
            }
            case ANIM_HEAVY: {
                float wind = Math.min(1, p / 0.35f), hit = Math.max(0, Math.min(1, (p - 0.3f) / 0.25f));
                q.lean = -10 * wind + 28 * hit;
                q.fThigh = 35 + hit * 10; q.fShin = 5; q.bThigh = -40; q.bShin = -55;
                if ("ryujin".equals(charId)) { // dragon uppercut
                    q.fUpper = -20 + hit * 190; q.fFore = q.fUpper + (1 - hit) * 60;
                    q.bUpper = -30; q.bFore = 30; q.glow = hit;
                    q.crouch = wind > 0.5f && hit < 0.3f;
                } else if ("kaze".equals(charId)) { // throw
                    q.fUpper = -60 + hit * 160; q.fFore = q.fUpper + 20;
                    q.bUpper = 40; q.bFore = 80;
                } else { // volt knuckle
                    q.fUpper = -20 + hit * 110; q.fFore = q.fUpper - 0;
                    q.bUpper = -20; q.bFore = 40; q.glow = hit;
                }
                break;
            }
            case ANIM_SPECIAL: {
                q.glow = 1;
                if ("kaze".equals(charId)) {
                    q.lean = 38; q.fThigh = 60; q.fShin = 20; q.bThigh = -50; q.bShin = -70;
                    q.fUpper = 95; q.fFore = 95; q.bUpper = -60; q.bFore = -40; q.weaponOut = true;
                    q.slash = p > 0.15f && p < 0.6f ? (p - 0.15f) / 0.45f : -1;
                } else if ("ryujin".equals(charId)) {
                    float up = Math.min(1, p / 0.35f);
                    q.lean = -6; q.fThigh = 35; q.fShin = 10; q.bThigh = -35; q.bShin = -10;
                    q.fUpper = 30 + up * 130; q.fFore = q.fUpper + 10; q.bUpper = -30 - up * 120; q.bFore = q.bUpper - 10;
                    q.crouch = up < 0.6f;
                } else {
                    q.lean = 12; q.fThigh = 40; q.fShin = 10; q.bThigh = -40; q.bShin = -55;
                    q.fUpper = 92; q.fFore = 90; q.bUpper = 60; q.bFore = 100;
                }
                break;
            }
            case ANIM_DASH:
                q.lean = 34; q.fThigh = 50; q.fShin = 10; q.bThigh = -60; q.bShin = -80;
                q.fUpper = -40; q.fFore = -20; q.bUpper = -55; q.bFore = -35;
                break;
            case ANIM_BLOCK:
                q.lean = -4; q.crouch = true; q.fThigh = 30; q.fShin = -5; q.bThigh = -30; q.bShin = -40;
                q.fUpper = 60; q.fFore = 165; q.bUpper = 50; q.bFore = 150; q.glow = 0.6f;
                break;
            case ANIM_HIT:
                q.lean = -24; q.headTilt = -18; q.fThigh = 25; q.fShin = 0; q.bThigh = -10; q.bShin = -20;
                q.fUpper = -40; q.fFore = -10; q.bUpper = -70; q.bFore = -50;
                break;
            case ANIM_VICTORY: {
                float b = (float) Math.abs(Math.sin(t * 5)) * 3;
                q.bob = -b; q.fThigh = 12; q.fShin = 8; q.bThigh = -12; q.bShin = -8;
                q.fUpper = 175; q.fFore = 175; q.bUpper = -25; q.bFore = 40; q.glow = 0.5f; q.weaponOut = true;
                break;
            }
            case ANIM_SPAWN:
            case ANIM_IDLE:
            default: {
                float br = (float) Math.sin(t * 3.2f);
                q.bob = br * 1.2f; q.lean = 4;
                q.fThigh = 14; q.fShin = 4; q.bThigh = -14; q.bShin = -6;
                q.fUpper = 25 + br * 3; q.fFore = 95; q.bUpper = 15 - br * 3; q.bFore = 85;
                if (speedFrac > 0.05f) q.lean += 4;
                break;
            }
        }
        if (q.crouch) q.bob += 6;
    }

    // ------------------------------------------------------------------ drawing
    /**
     * Draw a fighter with feet at (x, y).
     * @param anim   Const.ANIM_* code
     * @param t      seconds spent in this animation (for loops)
     * @param p      0..1 action progress
     * @param flashA 0..1 white flash after taking a hit
     * @param alphaA overall opacity
     */
    private int[] skin;
    private int trail, blade;

    /** Cosmetic weapon colours for the next draw() (0 = default). */
    public void setWeapon(int trailColor, int bladeColor) {
        trail = trailColor;
        blade = bladeColor;
    }

    /** Cosmetic palette for the next draw() call (null = default look). */
    public void setSkin(int[] palette) {
        skin = palette;
    }

    public void draw(Canvas cv, String charId, float x, float y, int facing, int anim, float t, float p,
                     float speedFrac, float flashA, float alphaA, float scale) {
        Palette.Style s = Palette.skinned(charId, skin);
        flash = flashA;
        alpha = alphaA;
        computePose(charId, anim, t, p, speedFrac);
        Pose q = pose;
        cv.save();
        cv.translate(x, y);
        if (anim == ANIM_DEAD) {
            cv.translate(0, -6);
            cv.rotate(-facing * 84);
        }
        cv.scale(facing * scale, scale);

        // ground shadow
        if (anim != ANIM_DEAD) {
            fill.setShader(null);
            fill.setColor(Palette.withAlpha(0xFF000000, 0.35f * alpha));
            rect.set(-20, -4, 20, 4);
            cv.drawOval(rect, fill);
        }
        // aura
        if (q.glow > 0 && alpha > 0.2f) {
            int col = s.accent;
            glow.setShader(new RadialGradient(0, -40, 58, Palette.withAlpha(col, 0.45f * q.glow * alpha), 0x00000000, Shader.TileMode.CLAMP));
            cv.drawCircle(0, -40, 58, glow);
            glow.setShader(null);
        }

        float hipX = 0, hipY = HIP_Y + q.bob;
        double lr = Math.toRadians(q.lean);
        float neckX = hipX + (float) Math.sin(lr) * TORSO, neckY = hipY - (float) Math.cos(lr) * TORSO;
        double hr = Math.toRadians(q.lean + q.headTilt);
        float headX = neckX + (float) Math.sin(hr) * (NECK + 2), headY = neckY - (float) Math.cos(hr) * (NECK + 2);

        int darkMain = Palette.mix(s.main, 0xFF000000, 0.35f);
        int legCol = "akira".equals(charId) ? 0xFF22386E : s.dark;
        int legBack = Palette.mix(legCol, 0xFF000000, 0.35f);

        // scarf / hair tails behind (Kaze)
        if ("kaze".equals(charId)) drawScarf(cv, neckX, neckY, s, q);

        // back limbs
        drawLimb(cv, hipX, hipY, q.bThigh, THIGH, q.bShin, SHIN, 11, legBack, s.dark);
        drawArm(cv, neckX - 1, neckY + 3, q.bUpper, q.bFore, darkMain, s, charId, false);

        // torso
        drawTorso(cv, hipX, hipY, neckX, neckY, s, charId);

        // head
        drawHead(cv, headX, headY, s, charId, q.lean + q.headTilt, anim);

        // front limbs
        drawLimb(cv, hipX, hipY, q.fThigh, THIGH, q.fShin, SHIN, 11, legCol, s.dark);
        drawArm(cv, neckX + 1, neckY + 3, q.fUpper, q.fFore, s.main, s, charId, true);

        // weapon on the back when sheathed (Kaze katana)
        if ("kaze".equals(charId) && !q.weaponOut) {
            stroke.setColor(c(OUTLINE));
            stroke.setStrokeWidth(5);
            cv.drawLine(neckX - 14, neckY - 6, hipX + 12, hipY + 4, stroke);
            stroke.setColor(c(0xFF6B6F8A));
            stroke.setStrokeWidth(3);
            cv.drawLine(neckX - 14, neckY - 6, hipX + 12, hipY + 4, stroke);
        }
        cv.restore();
    }

    private void drawScarf(Canvas cv, float nx, float ny, Palette.Style s, Pose q) {
        path.reset();
        path.moveTo(nx, ny + 2);
        float len = 34;
        for (int i = 1; i <= 6; i++) {
            float k = i / 6f;
            float wx = nx - k * len - Math.abs(q.lean) * 0.25f * k;
            float wy = ny + 2 + (float) Math.sin(q.scarf - k * 4) * 5 * k + k * 6;
            path.lineTo(wx, wy);
        }
        stroke.setStrokeWidth(9);
        stroke.setColor(c(OUTLINE));
        cv.drawPath(path, stroke);
        stroke.setStrokeWidth(6);
        stroke.setColor(c(s.accent));
        cv.drawPath(path, stroke);
    }

    private void drawLimb(Canvas cv, float x0, float y0, float a1, float l1, float a2, float l2, float width,
                          int color, int footColor) {
        double r1 = Math.toRadians(a1), r2 = Math.toRadians(a2);
        float x1 = x0 + (float) Math.sin(r1) * l1, y1 = y0 + (float) Math.cos(r1) * l1;
        float x2 = x1 + (float) Math.sin(r2) * l2, y2 = y1 + (float) Math.cos(r2) * l2;
        stroke.setColor(c(OUTLINE));
        stroke.setStrokeWidth(width + 3.5f);
        cv.drawLine(x0, y0, x1, y1, stroke);
        cv.drawLine(x1, y1, x2, y2, stroke);
        stroke.setColor(c(color));
        stroke.setStrokeWidth(width);
        cv.drawLine(x0, y0, x1, y1, stroke);
        cv.drawLine(x1, y1, x2, y2, stroke);
        // boot
        fill.setColor(c(OUTLINE));
        rect.set(x2 - 6, y2 - 5, x2 + 9, y2 + 2.5f);
        cv.drawRoundRect(rect, 4, 4, fill);
        fill.setColor(c(footColor));
        rect.set(x2 - 4.5f, y2 - 3.8f, x2 + 7.5f, y2 + 1.2f);
        cv.drawRoundRect(rect, 3, 3, fill);
    }

    private void drawArm(Canvas cv, float x0, float y0, float a1, float a2, int sleeve, Palette.Style s, String id,
                         boolean front) {
        double r1 = Math.toRadians(a1), r2 = Math.toRadians(a2);
        float x1 = x0 + (float) Math.sin(r1) * UPPER, y1 = y0 + (float) Math.cos(r1) * UPPER;
        float x2 = x1 + (float) Math.sin(r2) * FORE, y2 = y1 + (float) Math.cos(r2) * FORE;
        // weapon in the front hand
        if (front && pose.weaponOut && "kaze".equals(id)) drawKatana(cv, x2, y2, a2, s);
        stroke.setColor(c(OUTLINE));
        stroke.setStrokeWidth(11.5f);
        cv.drawLine(x0, y0, x1, y1, stroke);
        cv.drawLine(x1, y1, x2, y2, stroke);
        stroke.setColor(c(sleeve));
        stroke.setStrokeWidth(8);
        cv.drawLine(x0, y0, x1, y1, stroke);
        stroke.setColor(c("akira".equals(id) ? sleeve : s.skin));
        cv.drawLine(x1, y1, x2, y2, stroke);
        // hand / gauntlet
        float hr = "ryujin".equals(id) ? 6.5f : 5f;
        fill.setColor(c(OUTLINE));
        cv.drawCircle(x2, y2, hr + 1.8f, fill);
        int handCol = trail != 0 && !"kaze".equals(id) ? trail : "ryujin".equals(id) ? s.accent : "akira".equals(id) ? 0xFF2A4C8F : s.dark;
        fill.setColor(c(handCol));
        cv.drawCircle(x2, y2, hr, fill);
        if (front && ("ryujin".equals(id) || "akira".equals(id)) && alpha > 0.3f) {
            float g = 0.35f + pose.glow * 0.5f;
            glow.setShader(new RadialGradient(x2, y2, 16, Palette.withAlpha(trail != 0 ? trail : s.accent, g * alpha), 0x00000000, Shader.TileMode.CLAMP));
            cv.drawCircle(x2, y2, 16, glow);
            glow.setShader(null);
        }
    }

    private void drawKatana(Canvas cv, float hx, float hy, float armAngle, Palette.Style s) {
        double r = Math.toRadians(armAngle + 75);
        float bx = hx + (float) Math.sin(r) * 40, by = hy + (float) Math.cos(r) * 40;
        float gx = hx - (float) Math.sin(r) * 7, gy = hy - (float) Math.cos(r) * 7;
        stroke.setColor(c(OUTLINE));
        stroke.setStrokeWidth(5.5f);
        cv.drawLine(gx, gy, bx, by, stroke);
        stroke.setColor(c(blade != 0 ? blade : 0xFFE8ECFF));
        stroke.setStrokeWidth(3f);
        cv.drawLine(hx, hy, bx, by, stroke);
        stroke.setColor(c(trail != 0 ? trail : s.accent));
        stroke.setStrokeWidth(1.4f);
        cv.drawLine(hx, hy, bx, by, stroke);
        stroke.setColor(c(0xFF3A2B1A));
        stroke.setStrokeWidth(4f);
        cv.drawLine(gx, gy, hx, hy, stroke);
    }

    private void drawTorso(Canvas cv, float hx, float hy, float nx, float ny, Palette.Style s, String id) {
        float dx = nx - hx, dy = ny - hy;
        float len = (float) Math.hypot(dx, dy);
        float px = -dy / len, py = dx / len; // perpendicular
        float sw = 11f, hw = 8.5f;
        path.reset();
        path.moveTo(hx + px * hw, hy + py * hw);
        path.lineTo(nx + px * sw, ny + py * sw);
        path.quadTo(nx, ny - 3, nx - px * sw, ny - py * sw);
        path.lineTo(hx - px * hw, hy - py * hw);
        path.close();
        stroke.setColor(c(OUTLINE));
        stroke.setStrokeWidth(3.5f);
        fill.setColor(c(s.main));
        cv.drawPath(path, fill);
        cv.drawPath(path, stroke);
        // chest details
        if ("ryujin".equals(id)) {
            fill.setColor(c(s.dark));
            cv.drawCircle(nx + px * 9, ny + py * 9 + 1, 6.5f, fill); // shoulder pad
            fill.setColor(c(s.accent2));
            cv.drawCircle(hx + dx * 0.55f, hy + dy * 0.55f, 3f, fill); // dragon gem
        } else if ("akira".equals(id)) {
            stroke.setColor(c(s.dark));
            stroke.setStrokeWidth(3.2f);
            cv.drawLine(hx + dx * 0.15f, hy + dy * 0.15f, nx - px * 2, ny - py * 2, stroke); // jacket zip
            stroke.setColor(c(s.accent2));
            stroke.setStrokeWidth(2f);
            float mx = hx + dx * 0.55f, my = hy + dy * 0.55f;
            cv.drawLine(mx + 4, my - 4, mx, my, stroke);
            cv.drawLine(mx, my, mx + 3, my + 1, stroke);
            cv.drawLine(mx + 3, my + 1, mx - 1, my + 6, stroke);
        } else {
            stroke.setColor(c(s.accent));
            stroke.setStrokeWidth(2.5f);
            cv.drawLine(nx + px * 8, ny + py * 8, hx - px * 6, hy - py * 6, stroke); // sash
        }
        // belt
        stroke.setColor(c("ryujin".equals(id) ? s.accent2 : "akira".equals(id) ? 0xFF1B2A55 : s.accent));
        stroke.setStrokeWidth(3.5f);
        cv.drawLine(hx + px * (hw + 0.5f) + dx * 0.12f, hy + py * (hw + 0.5f) + dy * 0.12f,
                hx - px * (hw + 0.5f) + dx * 0.12f, hy - py * (hw + 0.5f) + dy * 0.12f, stroke);
    }

    private void drawHead(Canvas cv, float x, float y, Palette.Style s, String id, float tilt, int anim) {
        cv.save();
        cv.translate(x, y);
        cv.rotate(tilt * 0.6f);
        float R = HEAD_R;
        // hair back mass
        path.reset();
        if ("ryujin".equals(id)) {
            // flame-like spikes pointing up/back
            path.moveTo(-R - 2, 2);
            path.lineTo(-R - 9, -8);
            path.lineTo(-R + 1, -9);
            path.lineTo(-R - 4, -21);
            path.lineTo(-6, -15);
            path.lineTo(-4, -30);
            path.lineTo(4, -17);
            path.lineTo(12, -27);
            path.lineTo(13, -13);
            path.lineTo(R + 4, -12);
            path.lineTo(R, 0);
            path.close();
        } else if ("akira".equals(id)) {
            path.moveTo(-R - 1, 4);
            path.lineTo(-R - 8, -4);
            path.lineTo(-R, -8);
            path.lineTo(-R - 5, -17);
            path.lineTo(-5, -18);
            path.lineTo(-2, -25);
            path.lineTo(6, -18);
            path.lineTo(15, -20);
            path.lineTo(R + 2, -8);
            path.lineTo(R + 1, 2);
            path.close();
        } else {
            path.moveTo(-R - 1, 6);
            path.lineTo(-R - 11, 0);
            path.lineTo(-R - 1, -5);
            path.lineTo(-R - 8, -14);
            path.lineTo(-6, -16);
            path.lineTo(-1, -23);
            path.lineTo(6, -16);
            path.lineTo(R + 3, -10);
            path.lineTo(R, 2);
            path.close();
        }
        stroke.setColor(c(OUTLINE));
        stroke.setStrokeWidth(3.5f);
        fill.setColor(c(s.hair));
        cv.drawPath(path, stroke);
        cv.drawPath(path, fill);

        // face
        fill.setColor(c(OUTLINE));
        cv.drawCircle(1, 1, R + 1.8f, fill);
        fill.setColor(c(s.skin));
        cv.drawCircle(1, 1, R, fill);

        // fringe over forehead
        path.reset();
        path.moveTo(-R + 1, -2);
        path.quadTo(-4, -R - 4, R + 1, -6);
        path.lineTo(R - 3, -1);
        path.lineTo(R - 7, -6);
        path.lineTo(R - 10, 0);
        path.lineTo(2, -7);
        path.lineTo(-3, 0);
        path.lineTo(-8, -5);
        path.close();
        fill.setColor(c(s.hair));
        cv.drawPath(path, fill);
        fill.setColor(c(s.hairDark));
        cv.drawCircle(-R + 4, 2, 4, fill);

        // headband
        if (!"ryujin".equals(id)) {
            stroke.setColor(c("akira".equals(id) ? s.accent2 : s.accent));
            stroke.setStrokeWidth(3.2f);
            cv.drawLine(-R + 1, -5, R - 1, -8, stroke);
            if ("kaze".equals(id)) {
                cv.drawLine(-R + 1, -5, -R - 10, -1 + (float) Math.sin(pose.scarf) * 3, stroke);
                cv.drawLine(-R + 1, -5, -R - 8, 4 + (float) Math.sin(pose.scarf + 1) * 3, stroke);
            }
        }

        // eyes
        boolean closed = anim == ANIM_HIT || anim == ANIM_DEAD;
        if (closed) {
            stroke.setColor(c(OUTLINE));
            stroke.setStrokeWidth(2f);
            cv.drawLine(4, 0, 9, 3, stroke);
            cv.drawLine(4, 4, 9, 1, stroke);
        } else {
            fill.setColor(c(OUTLINE));
            rect.set(3.2f, -4, 11.5f, 6);
            cv.drawOval(rect, fill);
            fill.setColor(c(0xFFFFFFFF));
            rect.set(4.2f, -3, 10.5f, 5);
            cv.drawOval(rect, fill);
            fill.setColor(c(s.eye));
            rect.set(6.2f, -2.2f, 10.4f, 4.6f);
            cv.drawOval(rect, fill);
            fill.setColor(c(OUTLINE));
            cv.drawCircle(8.6f, 1.4f, 1.5f, fill);
            fill.setColor(c(0xFFFFFFFF));
            cv.drawCircle(7.6f, -0.4f, 1.1f, fill);
            // brow
            stroke.setColor(c(OUTLINE));
            stroke.setStrokeWidth(1.8f);
            cv.drawLine(3.5f, -6.5f, 11.5f, -4.8f, stroke);
            // second eye peeking (3/4 view)
            fill.setColor(c(OUTLINE));
            rect.set(13.4f, -3, 16.2f, 5);
            cv.drawOval(rect, fill);
        }
        // mask (Kaze) / mouth
        if ("kaze".equals(id)) {
            path.reset();
            path.moveTo(-2, 6);
            path.lineTo(R + 1.5f, 4);
            path.quadTo(R, 13, 6, 15);
            path.quadTo(-1, 14, -2, 6);
            fill.setColor(c(s.dark));
            cv.drawPath(path, fill);
        } else {
            stroke.setColor(c(0xFF6B2A2A));
            stroke.setStrokeWidth(1.6f);
            cv.drawLine(9, 10, 12.5f, 9.5f, stroke);
        }
        cv.restore();
    }

    /** Portrait bust for the HUD panels (drawn into a circle of radius r at cx, cy). */
    public void drawPortraitSkin(Canvas cv, String charId, float cx, float cy, float r, int[] palette) {
        skin = palette;
        drawPortrait(cv, charId, cx, cy, r);
        skin = null;
    }

    public void drawPortrait(Canvas cv, String charId, float cx, float cy, float r) {
        Palette.Style s = Palette.skinned(charId, skin);
        flash = 0;
        alpha = 1;
        computePose(charId, ANIM_IDLE, 0.3f, 0, 0);
        pose.fUpper = 60;
        pose.fFore = 150;
        cv.save();
        // head (local y ≈ -67) lands slightly above the circle centre, torso fills the bottom
        float k = r / 26f;
        cv.translate(cx - r * 0.12f, cy - r * 0.02f + 67 * k);
        cv.scale(k, k);
        float neckX = 0, neckY = HIP_Y - TORSO;
        drawTorso(cv, 0, HIP_Y, neckX, neckY, s, charId);
        drawHead(cv, 1.5f, neckY - NECK - 2, s, charId, 0, ANIM_IDLE);
        drawArm(cv, neckX + 1, neckY + 3, pose.fUpper, pose.fFore, s.main, s, charId, true);
        cv.restore();
    }
}

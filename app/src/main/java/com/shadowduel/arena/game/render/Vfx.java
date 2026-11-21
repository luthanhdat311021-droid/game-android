package com.shadowduel.arena.game.render;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import com.shadowduel.arena.game.sim.Fighter;
import com.shadowduel.arena.game.sim.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.shadowduel.arena.game.sim.Const.*;

/**
 * Visual effects only — nothing here feeds back into game logic. Effects are spawned
 * from server/offline events (hits, kills, spawns) and from fighter states.
 */
public final class Vfx {

    private static final int GLOW = 0, SPARK = 1, EMBER = 2, SMOKE = 3, RING = 4, SHARD = 5;

    private static final class P {
        int type, color;
        float x, y, vx, vy, life, max, size, grav, rot, vr;
    }

    private static final class Text {
        String text;
        int color;
        float x, y, life, max, size;
        boolean ko;
    }

    private final List<P> parts = new ArrayList<>();
    private final List<P> pool = new ArrayList<>();
    private final List<Text> texts = new ArrayList<>();
    private final Paint add = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final Random rnd = new Random();
    public int maxParticles = 700;
    public float quality = 1f;

    public Vfx() {
        add.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        Typeface tf = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC);
        textPaint.setTypeface(tf);
        textPaint.setTextAlign(Paint.Align.CENTER);
        outline.setTypeface(tf);
        outline.setTextAlign(Paint.Align.CENTER);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeJoin(Paint.Join.ROUND);
        outline.setColor(0xFF120612);
    }

    private P spawn(int type, float x, float y, float vx, float vy, float life, float size, int color) {
        if (parts.size() >= maxParticles * quality) return null;
        P p = pool.isEmpty() ? new P() : pool.remove(pool.size() - 1);
        p.type = type; p.x = x; p.y = y; p.vx = vx; p.vy = vy; p.life = life; p.max = life; p.size = size;
        p.color = color; p.grav = 0; p.rot = rnd.nextFloat() * 360; p.vr = (rnd.nextFloat() - 0.5f) * 720;
        parts.add(p);
        return p;
    }

    private float rf(float a, float b) {
        return a + rnd.nextFloat() * (b - a);
    }

    // ------------------------------------------------------------------ event driven
    public void hit(float x, float y, int color, int dmg, boolean blocked, String kind) {
        int n = (int) ((blocked ? 8 : 18) * quality);
        int col = blocked ? 0xFF9FD8FF : color;
        spawn(GLOW, x, y, 0, 0, 0.22f, blocked ? 46 : 70, col);
        P ring = spawn(RING, x, y, 0, 0, 0.3f, blocked ? 34 : 44, col);
        for (int i = 0; i < n; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            float sp = rf(250, 700);
            P p = spawn(SPARK, x, y, (float) Math.cos(a) * sp, (float) Math.sin(a) * sp, rf(0.15f, 0.4f), rf(2, 4), i % 3 == 0 ? 0xFFFFFFFF : col);
            if (p != null) p.grav = 900;
        }
        if (kind != null && kind.startsWith("fire")) burst(x, y, 0xFFFF7A1F, 10, 220);
        if (!blocked || dmg > 0) {
            Text t = new Text();
            t.text = blocked ? "BLOCK" : String.valueOf(dmg);
            t.color = blocked ? 0xFF9FD8FF : dmg >= 120 ? 0xFFFFC21F : 0xFFFFFFFF;
            t.x = x + rf(-10, 10);
            t.y = y - 30;
            t.life = t.max = 0.8f;
            t.size = dmg >= 120 ? 30 : 22;
            texts.add(t);
        }
    }

    public void knockout(float x, float y, int color) {
        burst(x, y, color, 40, 650);
        spawn(GLOW, x, y, 0, 0, 0.5f, 180, color);
        spawn(RING, x, y, 0, 0, 0.5f, 120, 0xFFFFFFFF);
        for (int i = 0; i < 12 * quality; i++) {
            P p = spawn(SMOKE, x + rf(-20, 20), y + rf(-20, 20), rf(-60, 60), rf(-90, -20), rf(0.6f, 1.1f), rf(18, 34), 0xFF4A3A66);
            if (p != null) p.grav = -40;
        }
        Text t = new Text();
        t.text = "K.O.";
        t.color = 0xFFFF5A3B;
        t.x = x;
        t.y = y - 60;
        t.life = t.max = 1.6f;
        t.size = 54;
        t.ko = true;
        texts.add(t);
    }

    public void spawnBeam(float x, float y, int color) {
        spawn(GLOW, x, y - 40, 0, 0, 0.6f, 120, color);
        for (int i = 0; i < 26 * quality; i++) {
            P p = spawn(SPARK, x + rf(-22, 22), y - rf(0, 30), 0, rf(-500, -200), rf(0.3f, 0.7f), rf(2, 4), i % 2 == 0 ? 0xFFFFFFFF : color);
            if (p != null) p.grav = 0;
        }
        spawn(RING, x, y - 10, 0, 0, 0.45f, 70, color);
    }

    public void burst(float x, float y, int color, int n, float speed) {
        for (int i = 0; i < n * quality; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            float sp = rf(speed * 0.3f, speed);
            P p = spawn(i % 4 == 0 ? GLOW : SPARK, x, y, (float) Math.cos(a) * sp, (float) Math.sin(a) * sp,
                    rf(0.25f, 0.7f), i % 4 == 0 ? rf(16, 30) : rf(2, 5), color);
            if (p != null) p.grav = 300;
        }
    }

    // ------------------------------------------------------------------ state driven (per frame)
    /** Continuous effects from a fighter's current animation. */
    public void fighterFx(Fighter f, float dt) {
        if (!f.alive) return;
        Palette.Style s = Palette.style(f.charId);
        int anim = f.anim();
        float x = (float) f.rx, y = (float) f.ry;
        if (anim == ANIM_DASH || (anim == ANIM_SPECIAL && "kaze".equals(f.charId))) {
            // afterimage streak + shadow particles
            for (int i = 0; i < 3 * quality; i++) {
                spawn(GLOW, x - f.facing * rf(0, 40), y - rf(20, 60), -f.facing * 60, 0, 0.25f, rf(14, 24), s.accent);
            }
            if (anim == ANIM_SPECIAL) {
                for (int i = 0; i < 4 * quality; i++) {
                    P p = spawn(SHARD, x + rf(-30, 30), y - rf(10, 70), -f.facing * rf(100, 300), rf(-60, 60), rf(0.2f, 0.45f), rf(4, 8), 0xFFD6B8FF);
                    if (p != null) p.grav = 0;
                }
            }
        }
        if (anim == ANIM_SPECIAL && "ryujin".equals(f.charId)) {
            for (int i = 0; i < 4 * quality; i++) {
                P p = spawn(EMBER, x + rf(-26, 26), y - rf(0, 60), rf(-40, 40), rf(-260, -100), rf(0.3f, 0.6f), rf(3, 6), i % 2 == 0 ? 0xFFFFC23B : 0xFFFF5A12);
                if (p != null) p.grav = -100;
            }
        }
        if (anim == ANIM_SPECIAL && "akira".equals(f.charId) || anim == ANIM_HEAVY && "akira".equals(f.charId)) {
            if (rnd.nextFloat() < 0.6f) {
                P p = spawn(SPARK, x + f.facing * rf(10, 40), y - rf(30, 60), rf(-300, 300), rf(-300, 300), 0.12f, 3, 0xFFB8F2FF);
                if (p != null) p.grav = 0;
            }
        }
        if (anim == ANIM_HEAVY && "ryujin".equals(f.charId)) {
            for (int i = 0; i < 2 * quality; i++) spawn(EMBER, x + f.facing * 20, y - rf(30, 90), rf(-40, 40), rf(-220, -80), 0.4f, 4, 0xFFFF7A1F);
        }
        if (anim == ANIM_RUN && f.grounded && rnd.nextFloat() < 0.25f) {
            P p = spawn(SMOKE, x - f.facing * 12, y - 4, -f.facing * 30, -20, 0.35f, 8, 0xFF8A93B0);
            if (p != null) p.grav = -20;
        }
    }

    /** Slash crescent for melee swings (drawn in world space). */
    public void drawSlash(Canvas c, Fighter f) {
        if (!f.alive) return;
        int anim = f.anim();
        boolean melee = anim >= ANIM_ATTACK1 && anim <= ANIM_ATTACK3 || (anim == ANIM_HEAVY && !"kaze".equals(f.charId));
        boolean shadow = anim == ANIM_SPECIAL && "kaze".equals(f.charId);
        if (!melee && !shadow) return;
        float p = f.actionProgress();
        if (p < 0.15f || p > 0.7f) return;
        float k = (p - 0.15f) / 0.55f;
        Palette.Style s = Palette.style(f.charId);
        float x = (float) f.rx, y = (float) f.ry - 44;
        float rad = shadow ? 70 : anim == ANIM_ATTACK3 || anim == ANIM_HEAVY ? 58 : 46;
        float start = anim == ANIM_ATTACK2 ? 60 : -70, sweep = 150 * Math.min(1, k * 1.6f);
        if (anim == ANIM_ATTACK2) sweep = -sweep;
        c.save();
        c.translate(x + f.facing * 14, y);
        c.scale(f.facing, 1);
        rect.set(-rad, -rad, rad, rad);
        add.setStyle(Paint.Style.STROKE);
        add.setStrokeCap(Paint.Cap.ROUND);
        float fade = 1 - k;
        add.setStrokeWidth(shadow ? 22 : 14);
        add.setColor(Palette.withAlpha(s.accent, 0.45f * fade));
        c.drawArc(rect, start, sweep, false, add);
        add.setStrokeWidth(shadow ? 8 : 5);
        add.setColor(Palette.withAlpha(0xFFFFFFFF, 0.9f * fade));
        c.drawArc(rect, start, sweep, false, add);
        add.setStyle(Paint.Style.FILL);
        c.restore();
    }

    public void drawProjectile(Canvas c, World.Projectile pr, float time) {
        float x = (float) pr.x, y = (float) pr.y;
        int dir = pr.vx >= 0 ? 1 : -1;
        if ("bolt".equals(pr.kind)) {
            add.setShader(new RadialGradient(x, y, 90, 0x883BD5FF, 0x00000000, Shader.TileMode.CLAMP));
            c.drawCircle(x, y, 90, add);
            add.setShader(null);
            for (int pass = 0; pass < 2; pass++) {
                path.reset();
                float sx = x - dir * 70;
                path.moveTo(sx, y);
                for (int i = 1; i <= 8; i++) path.lineTo(sx + dir * i * 17, y + rf(-14, 14));
                add.setStyle(Paint.Style.STROKE);
                add.setStrokeJoin(Paint.Join.ROUND);
                add.setStrokeWidth(pass == 0 ? 12 : 4);
                add.setColor(pass == 0 ? 0x993BD5FF : 0xFFFFFFFF);
                c.drawPath(path, add);
            }
            add.setStyle(Paint.Style.FILL);
            if (rnd.nextFloat() < 0.7f) spawn(SPARK, x, y, rf(-200, 200), rf(-200, 200), 0.2f, 3, 0xFFB8F2FF);
        } else {
            add.setShader(new RadialGradient(x, y, 34, 0x66B34BFF, 0x00000000, Shader.TileMode.CLAMP));
            c.drawCircle(x, y, 34, add);
            add.setShader(null);
            c.save();
            c.translate(x, y);
            c.rotate(time * 1440 * dir);
            path.reset();
            for (int i = 0; i < 8; i++) {
                double a = Math.PI * i / 4;
                float rr = i % 2 == 0 ? 15 : 5;
                float px = (float) Math.cos(a) * rr, py = (float) Math.sin(a) * rr;
                if (i == 0) path.moveTo(px, py);
                else path.lineTo(px, py);
            }
            path.close();
            paint.setColor(0xFF1A1A2A);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3);
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFD8DEF0);
            c.drawPath(path, paint);
            c.restore();
            if (rnd.nextFloat() < 0.5f) spawn(GLOW, x, y, -dir * 40, 0, 0.2f, 10, 0xFFB34BFF);
        }
    }

    public void drawZone(Canvas c, World.Zone z, float time) {
        float x0 = (float) z.x, y0 = (float) z.y, w = (float) z.w, h = (float) z.h;
        float life = z.maxLife > 0 ? z.life / (float) z.maxLife : 1;
        float a = Math.min(1, life * 3);
        float cx = x0 + w / 2, base = y0 + h;
        add.setShader(new RadialGradient(cx, base - h * 0.3f, w * 0.7f, Palette.withAlpha(0xFFFF5A12, 0.55f * a), 0x00000000, Shader.TileMode.CLAMP));
        c.drawCircle(cx, base - h * 0.3f, w * 0.7f, add);
        add.setShader(null);
        // flame tongues
        for (int i = 0; i < 9; i++) {
            float fx = x0 + w * (i + 0.5f) / 9;
            float fh = h * (0.45f + 0.45f * (float) Math.abs(Math.sin(time * 9 + i * 1.7f))) * a;
            path.reset();
            path.moveTo(fx - 18, base);
            path.quadTo(fx - 14, base - fh * 0.5f, fx + (float) Math.sin(time * 12 + i) * 6, base - fh);
            path.quadTo(fx + 14, base - fh * 0.5f, fx + 18, base);
            path.close();
            add.setColor(Palette.withAlpha(0xFFFF5A12, 0.75f * a));
            c.drawPath(path, add);
            path.reset();
            path.moveTo(fx - 8, base);
            path.quadTo(fx - 6, base - fh * 0.3f, fx, base - fh * 0.6f);
            path.quadTo(fx + 6, base - fh * 0.3f, fx + 8, base);
            path.close();
            add.setColor(Palette.withAlpha(0xFFFFD23B, 0.8f * a));
            c.drawPath(path, add);
        }
        if (rnd.nextFloat() < 0.8f * quality) {
            P p = spawn(EMBER, x0 + rnd.nextFloat() * w, base - rnd.nextFloat() * h * 0.5f, rf(-30, 30), rf(-260, -120), rf(0.3f, 0.7f), rf(3, 6), 0xFFFFC23B);
            if (p != null) p.grav = -60;
        }
    }

    // ------------------------------------------------------------------ update + draw
    public void update(float dt) {
        for (int i = parts.size() - 1; i >= 0; i--) {
            P p = parts.get(i);
            p.life -= dt;
            if (p.life <= 0) {
                parts.remove(i);
                pool.add(p);
                continue;
            }
            p.vy += p.grav * dt;
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.vx *= 1 - Math.min(1, dt * 2.5f);
            p.rot += p.vr * dt;
        }
        for (int i = texts.size() - 1; i >= 0; i--) {
            Text t = texts.get(i);
            t.life -= dt;
            t.y -= dt * (t.ko ? 20 : 60);
            if (t.life <= 0) texts.remove(i);
        }
    }

    public void draw(Canvas c) {
        for (P p : parts) {
            float k = p.life / p.max;
            switch (p.type) {
                case GLOW: {
                    float r = p.size * (0.6f + 0.4f * k);
                    add.setShader(new RadialGradient(p.x, p.y, r, Palette.withAlpha(p.color, 0.8f * k), 0x00000000, Shader.TileMode.CLAMP));
                    c.drawCircle(p.x, p.y, r, add);
                    add.setShader(null);
                    break;
                }
                case RING: {
                    float r = p.size * (1.4f - k);
                    add.setStyle(Paint.Style.STROKE);
                    add.setStrokeWidth(6 * k + 1);
                    add.setColor(Palette.withAlpha(p.color, k));
                    c.drawCircle(p.x, p.y, r, add);
                    add.setStyle(Paint.Style.FILL);
                    break;
                }
                case SMOKE:
                    paint.setColor(Palette.withAlpha(p.color, 0.35f * k));
                    c.drawCircle(p.x, p.y, p.size * (1.6f - k * 0.6f), paint);
                    break;
                case SHARD:
                    c.save();
                    c.translate(p.x, p.y);
                    c.rotate(p.rot);
                    add.setColor(Palette.withAlpha(p.color, k));
                    c.drawRect(-p.size, -1.5f, p.size, 1.5f, add);
                    c.restore();
                    break;
                default: // SPARK, EMBER
                    add.setColor(Palette.withAlpha(p.color, Math.min(1, k * 1.5f)));
                    if (p.type == SPARK) {
                        add.setStyle(Paint.Style.STROKE);
                        add.setStrokeWidth(p.size);
                        add.setStrokeCap(Paint.Cap.ROUND);
                        c.drawLine(p.x, p.y, p.x - p.vx * 0.03f, p.y - p.vy * 0.03f, add);
                        add.setStyle(Paint.Style.FILL);
                    } else {
                        c.drawCircle(p.x, p.y, p.size * k + 1, add);
                    }
            }
        }
        for (Text t : texts) {
            float k = t.life / t.max;
            float pop = t.ko ? (k > 0.85f ? 1 + (k - 0.85f) * 4 : 1) : 1;
            float a = Math.min(1, k * 3);
            textPaint.setTextSize(t.size * pop);
            outline.setTextSize(t.size * pop);
            outline.setStrokeWidth(t.ko ? 9 : 5);
            outline.setColor(Palette.withAlpha(t.ko ? 0xFF3A0A0A : 0xFF120612, a));
            c.drawText(t.text, t.x, t.y, outline);
            if (t.ko) {
                textPaint.setShader(new android.graphics.LinearGradient(0, t.y - t.size, 0, t.y,
                        Palette.withAlpha(0xFFFFB37A, a), Palette.withAlpha(0xFFFF2E43, a), Shader.TileMode.CLAMP));
                textPaint.setColor(0xFFFFFFFF);
            } else {
                textPaint.setShader(null);
                textPaint.setColor(Palette.withAlpha(t.color, a));
            }
            c.drawText(t.text, t.x, t.y, textPaint);
            textPaint.setShader(null);
        }
    }

    public int count() {
        return parts.size();
    }
}

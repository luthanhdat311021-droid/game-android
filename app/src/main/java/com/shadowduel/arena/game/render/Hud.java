package com.shadowduel.arena.game.render;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import com.shadowduel.arena.game.GameSession;
import com.shadowduel.arena.game.sim.Fighter;
import com.shadowduel.arena.game.sim.GameMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Screen-space HUD in the style of a platform fighter:
 *  - bottom row of slanted player panels (portrait, P# badge, diagonal HP/energy/skill bars, kills)
 *    with the match timer panel in the middle,
 *  - "P1 ˅" tags with a small HP bar above every fighter,
 *  - kill feed, minimap, team scores, respawn countdown and the full scoreboard overlay.
 */
public final class Hud {

    private static final class Feed {
        String killer, victim, cause;
        int kColor, vColor;
        float life;
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint o = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final CharacterRenderer portraitRenderer = new CharacterRenderer();
    private final Map<String, Bitmap> portraits = new HashMap<>();
    private final List<Feed> feed = new ArrayList<>();
    private int w, h;
    public boolean showScoreboard;
    public float fightBanner = 1.6f;
    public String banner;
    public float bannerT;
    /** Screen rects of the two HUD buttons (pause, scoreboard), for touch handling. */
    public final RectF pauseBtn = new RectF(), scoreBtn = new RectF();

    public Hud() {
        Typeface tf = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC);
        t.setTypeface(tf);
        o.setTypeface(tf);
        o.setStyle(Paint.Style.STROKE);
        o.setStrokeJoin(Paint.Join.ROUND);
        o.setColor(0xFF0A0812);
    }

    public void layout(int width, int height) {
        w = width;
        h = height;
        portraits.clear();
        float s = h * 0.075f;
        pauseBtn.set(w - s * 1.25f, h * 0.02f, w - s * 0.25f, h * 0.02f + s);
        scoreBtn.set(w - s * 2.45f, h * 0.02f, w - s * 1.45f, h * 0.02f + s);
    }

    public void killFeed(String killer, int kc, String victim, int vc, String cause) {
        Feed f = new Feed();
        f.killer = killer;
        f.victim = victim;
        f.kColor = kc;
        f.vColor = vc;
        f.cause = cause;
        f.life = 5f;
        feed.add(0, f);
        if (feed.size() > 5) feed.remove(feed.size() - 1);
    }

    public void showBanner(String text) {
        banner = text;
        bannerT = 2f;
    }

    private void text(Canvas c, String s, float x, float y, float size, int color, Paint.Align align, float stroke) {
        t.setTextSize(size);
        t.setTextAlign(align);
        o.setTextSize(size);
        o.setTextAlign(align);
        if (stroke > 0) {
            o.setStrokeWidth(stroke);
            c.drawText(s, x, y, o);
        }
        t.setColor(color);
        c.drawText(s, x, y, t);
    }

    private Bitmap portrait(String charId, int size) {
        String key = charId + size;
        Bitmap b = portraits.get(key);
        if (b == null) {
            b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Path clip = new Path();
            clip.addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW);
            c.clipPath(clip);
            Palette.Style st = Palette.style(charId);
            p.setShader(new LinearGradient(0, 0, 0, size, Palette.mix(st.accent, 0xFFFFFFFF, 0.55f), Palette.mix(st.accent, 0xFF000000, 0.2f), Shader.TileMode.CLAMP));
            c.drawRect(0, 0, size, size, p);
            p.setShader(null);
            portraitRenderer.drawPortrait(c, charId, size / 2f, size * 0.42f, size * 0.42f);
            portraits.put(key, b);
        }
        return b;
    }

    // ------------------------------------------------------------------ main entry
    public void draw(Canvas c, GameSession s, Camera cam, float dt, float time) {
        int me = s.localId();
        Fighter self = s.fighter(me);
        for (Fighter f : s.fighters()) drawTag(c, f, cam, f.id == me);
        drawPanels(c, s, self, time);
        drawTop(c, s, self);
        drawMinimap(c, s, cam, me);
        drawFeed(c, dt);
        drawButtons(c);
        drawCenter(c, s, self, dt, time);
        if (showScoreboard) drawScoreboard(c, s, me);
    }

    /** "P1 ˅" tag with a short HP bar above a fighter (world → screen). */
    private void drawTag(Canvas c, Fighter f, Camera cam, boolean mine) {
        if (!f.alive || !f.known) return;
        float x = cam.toScreenX(f.rx), y = cam.toScreenY(f.ry - 96);
        if (x < -50 || x > w + 50 || y < -50 || y > h + 50) return;
        int col = Palette.forFighter(f.slot, f.team);
        float sz = h * 0.034f;
        String tag = mine ? "YOU" : "P" + (f.slot + 1);
        text(c, f.name.length() > 12 ? f.name.substring(0, 12) : f.name, x, y - sz * 1.15f, sz * 0.5f, 0xDDFFFFFF, Paint.Align.CENTER, sz * 0.14f);
        text(c, tag, x, y, sz, col, Paint.Align.CENTER, sz * 0.2f);
        // chevron
        path.reset();
        path.moveTo(x - sz * 0.22f, y + sz * 0.18f);
        path.lineTo(x, y + sz * 0.42f);
        path.lineTo(x + sz * 0.22f, y + sz * 0.18f);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(sz * 0.12f);
        p.setColor(col);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
        // HP bar
        float bw = sz * 2.1f, bh = sz * 0.2f, by = y + sz * 0.58f;
        if (f.displayHp < 0) f.displayHp = f.hp;
        f.displayHp += (f.hp - f.displayHp) * 0.15f;
        p.setColor(0xAA000000);
        c.drawRect(x - bw / 2 - 2, by - 2, x + bw / 2 + 2, by + bh + 2, p);
        p.setColor(0xFFFFFFFF);
        c.drawRect(x - bw / 2, by, x - bw / 2 + bw * Math.max(0, f.displayHp) / f.maxHp, by + bh, p);
        p.setColor(f.team == 2 ? 0xFF2F8BFF : 0xFFFF2E43);
        c.drawRect(x - bw / 2, by, x - bw / 2 + bw * Math.max(0, f.hp) / (float) f.maxHp, by + bh, p);
        if (f.protectT > 0) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2);
            p.setColor(0x993BD5FF);
            c.drawCircle(cam.toScreenX(f.rx), cam.toScreenY(f.ry - 40), cam.scale * 52, p);
            p.setStyle(Paint.Style.FILL);
        }
    }

    // ------------------------------------------------------------------ bottom panels
    private List<Fighter> panelFighters(GameSession s, Fighter self) {
        List<Fighter> list = new ArrayList<>(s.fighters());
        Collections.sort(list, (a, b) -> b.kills != a.kills ? b.kills - a.kills : a.slot - b.slot);
        List<Fighter> out = new ArrayList<>();
        if (self != null) out.add(self);
        for (Fighter f : list) {
            if (out.size() >= 4) break;
            if (f != self) out.add(f);
        }
        Collections.sort(out, (a, b) -> a.slot - b.slot);
        return out;
    }

    private void drawPanels(Canvas c, GameSession s, Fighter self, float time) {
        List<Fighter> list = panelFighters(s, self);
        float y0 = h * 0.855f, ph = h * 0.13f;
        float timerW = w * 0.065f, pw = w * 0.098f, gap = w * 0.006f;
        float total = pw * 4 + timerW + gap * 4;
        float x = w * 0.4325f - total / 2;
        int i = 0;
        for (int k = 0; k < 5; k++) {
            if (k == 2) {
                drawTimer(c, s, x, y0 + ph * 0.18f, timerW, ph * 0.62f);
                x += timerW + gap;
                continue;
            }
            if (i < list.size()) drawPanel(c, list.get(i), x, y0, pw, ph, self == list.get(i));
            i++;
            x += pw + gap;
        }
    }

    private void drawPanel(Canvas c, Fighter f, float x, float y, float pw, float ph, boolean mine) {
        int col = Palette.forFighter(f.slot, f.team);
        float slant = ph * 0.32f;
        // slanted panel body
        path.reset();
        path.moveTo(x + slant + ph * 0.25f, y + ph * 0.12f);
        path.lineTo(x + pw, y + ph * 0.12f);
        path.lineTo(x + pw - slant, y + ph);
        path.lineTo(x + ph * 0.25f, y + ph);
        path.close();
        p.setColor(0xE6101320);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(h * 0.004f);
        p.setColor(mine ? 0xFFFFFFFF : 0xAA8A93B0);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);

        // portrait circle overlapping the left edge
        float pr = ph * 0.42f;
        float pcx = x + pr * 1.02f, pcy = y + ph * 0.5f;
        p.setColor(0xFFFFFFFF);
        c.drawCircle(pcx, pcy, pr + h * 0.005f, p);
        Bitmap b = portrait(f.charId, (int) (pr * 2));
        if (!f.alive) p.setAlpha(110);
        c.drawBitmap(b, pcx - pr, pcy - pr, p);
        p.setAlpha(255);
        if (!f.alive) {
            p.setColor(0x88000000);
            c.drawCircle(pcx, pcy, pr, p);
            if (f.respawnT > 0) text(c, String.valueOf((f.respawnT + 29) / 30), pcx, pcy + pr * 0.35f, pr, 0xFFFFFFFF, Paint.Align.CENTER, pr * 0.12f);
            else text(c, "✕", pcx, pcy + pr * 0.35f, pr, 0xFFFF3B5C, Paint.Align.CENTER, pr * 0.12f);
        }

        // player number badge
        float bw = pr * 0.85f, bh = ph * 0.22f, bx = pcx + pr * 0.15f, by = y + ph - bh * 0.95f;
        rect.set(bx, by, bx + bw, by + bh);
        p.setColor(col);
        c.drawRoundRect(rect, bh * 0.2f, bh * 0.2f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(h * 0.003f);
        p.setColor(0xFFFFFFFF);
        c.drawRoundRect(rect, bh * 0.2f, bh * 0.2f, p);
        p.setStyle(Paint.Style.FILL);
        text(c, String.valueOf(f.slot + 1), bx + bw / 2, by + bh * 0.82f, bh * 0.95f, 0xFFFFFFFF, Paint.Align.CENTER, bh * 0.12f);

        // diagonal bars: HP (red), energy (yellow), skill (blue)
        float barsX = pcx + pr * 1.12f, barTop = y + ph * 0.16f, barBot = y + ph * 0.94f;
        float barW = pw * 0.115f, sl = (barBot - barTop) * 0.45f;
        float hp = Math.max(0, f.hp) / (float) f.maxHp;
        float en = (float) Math.min(1, f.energy / 100.0);
        boolean ready = f.energy >= f.def.special.cost && f.specialCd == 0;
        drawSlantBar(c, barsX, barTop, barBot, barW, sl, hp, hp < 0.3f ? 0xFFFF7A1F : 0xFFFF2233);
        drawSlantBar(c, barsX + barW * 1.25f, barTop, barBot, barW * 0.8f, sl, en, 0xFFFFD23B);
        drawSlantBar(c, barsX + barW * 2.35f, barTop + (barBot - barTop) * 0.2f, barBot, barW * 0.55f, sl * 0.8f, ready ? 1 : en * 0.5f, ready ? 0xFF3BD5FF : 0xFF2A4C8F);

        // kills
        float kx = x + pw - slant * 0.55f, ky = y + ph * 0.6f;
        text(c, String.valueOf(f.kills), kx, ky, ph * 0.34f, col, Paint.Align.RIGHT, ph * 0.05f);
        text(c, "KO", kx - ph * 0.02f, ky + ph * 0.22f, ph * 0.14f, 0xFFB8C0E0, Paint.Align.RIGHT, ph * 0.03f);
    }

    private void drawSlantBar(Canvas c, float x, float top, float bot, float bw, float sl, float frac, int color) {
        path.reset();
        path.moveTo(x + sl, top);
        path.lineTo(x + sl + bw, top);
        path.lineTo(x + bw, bot);
        path.lineTo(x, bot);
        path.close();
        p.setColor(0xFF2A2E40);
        c.drawPath(path, p);
        float ft = bot - (bot - top) * frac;
        float k = (bot - ft) / (bot - top);
        path.reset();
        path.moveTo(x + sl * k, ft);
        path.lineTo(x + sl * k + bw, ft);
        path.lineTo(x + bw, bot);
        path.lineTo(x, bot);
        path.close();
        p.setColor(color);
        c.drawPath(path, p);
        p.setColor(0x55FFFFFF);
        c.drawRect(x + sl * k, ft, x + sl * k + bw * 0.25f, ft + 2, p);
    }

    private void drawTimer(Canvas c, GameSession s, float x, float y, float tw, float th) {
        float sl = th * 0.3f;
        path.reset();
        path.moveTo(x + sl, y);
        path.lineTo(x + tw, y);
        path.lineTo(x + tw - sl, y + th);
        path.lineTo(x, y + th);
        path.close();
        p.setColor(0xF0202638);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(h * 0.004f);
        p.setColor(0xFFFFFFFF);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
        // cyan accent stripe like the reference
        path.reset();
        path.moveTo(x + sl * 0.9f + 6, y + 4);
        path.lineTo(x + sl * 0.9f + 16, y + 4);
        path.lineTo(x + sl * 0.2f + 16, y + th - 4);
        path.lineTo(x + sl * 0.2f + 6, y + th - 4);
        path.close();
        p.setColor(0xFF3BC8FF);
        c.drawPath(path, p);
        int left = s.timeLeftTicks();
        String txt = left < 0 ? "∞" : String.format(java.util.Locale.US, "%d:%02d", left / s.tickRate() / 60, (left / s.tickRate()) % 60);
        int col = left >= 0 && left < 30 * s.tickRate() ? 0xFFFF5A5A : 0xFFFFFFFF;
        text(c, txt, x + tw / 2 + sl * 0.15f, y + th * 0.7f, th * 0.52f, col, Paint.Align.CENTER, th * 0.06f);
    }

    // ------------------------------------------------------------------ top bar
    private void drawTop(Canvas c, GameSession s, Fighter self) {
        float cx = w / 2f, y = h * 0.075f;
        String mode = s.modeId();
        if ("tdm".equals(mode)) {
            int[] sc = s.teamScores();
            float bw = w * 0.07f, bh = h * 0.06f;
            rect.set(cx - bw - 6, y - bh * 0.8f, cx - 6, y + bh * 0.2f);
            p.setColor(0xDDFF3B4E);
            c.drawRoundRect(rect, 6, 6, p);
            rect.set(cx + 6, y - bh * 0.8f, cx + bw + 6, y + bh * 0.2f);
            p.setColor(0xDD2F8BFF);
            c.drawRoundRect(rect, 6, 6, p);
            text(c, String.valueOf(sc[0]), cx - bw / 2 - 6, y, bh * 0.75f, 0xFFFFFFFF, Paint.Align.CENTER, 4);
            text(c, String.valueOf(sc[1]), cx + bw / 2 + 6, y, bh * 0.75f, 0xFFFFFFFF, Paint.Align.CENTER, 4);
            text(c, "RED   ·   30   ·   BLUE", cx, y + bh * 0.75f, bh * 0.32f, 0xFFDDE3FF, Paint.Align.CENTER, 3);
        } else if (self != null) {
            List<GameSession.ScoreRow> rows = s.scoreboard();
            int rank = 1;
            for (int i = 0; i < rows.size(); i++) if (rows.get(i).id == self.id) rank = i + 1;
            String line;
            if ("survival".equals(mode)) {
                int alive = 0;
                for (Fighter f : s.fighters()) if (f.alive) alive++;
                line = "CÒN SỐNG " + alive + "/" + s.fighters().size();
            } else if ("training".equals(mode)) {
                line = "TRAINING · KO " + self.kills;
            } else {
                line = "#" + rank + "/" + rows.size() + "   ·   KO " + self.kills + "   ·   DEATH " + self.deaths;
            }
            text(c, line, cx, y, h * 0.04f, 0xFFFFFFFF, Paint.Align.CENTER, h * 0.008f);
        }
        String ns = s.netStatus();
        if (ns != null) {
            int ping = s.pingMs();
            int pc = ping < 0 ? 0xFFB8C0E0 : ping < 80 ? 0xFF3BE08A : ping < 160 ? 0xFFFFC93B : 0xFFFF3B5C;
            text(c, ns + (ping >= 0 ? "  " + ping + " ms" : ""), w * 0.012f, h * 0.255f, h * 0.026f, pc, Paint.Align.LEFT, h * 0.005f);
        }
    }

    private void drawMinimap(Canvas c, GameSession s, Camera cam, int me) {
        GameMap m = s.map();
        float mw = w * 0.17f, mh = (float) (mw * m.pixelH / m.pixelW);
        float x0 = w * 0.012f, y0 = h * 0.025f;
        rect.set(x0, y0, x0 + mw, y0 + mh);
        p.setColor(0xAA0A0D1C);
        c.drawRoundRect(rect, 8, 8, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2);
        p.setColor(0x885A6690);
        c.drawRoundRect(rect, 8, 8, p);
        p.setStyle(Paint.Style.FILL);
        float k = (float) (mw / m.pixelW);
        p.setColor(0x887A86A8);
        for (int r = 0; r < m.h; r += 1) {
            int cc = 0;
            while (cc < m.w) {
                if (!GameMap.isSolid(m.tiles[r][cc]) && !GameMap.isOneWay(m.tiles[r][cc])) { cc++; continue; }
                int st = cc;
                while (cc < m.w && (GameMap.isSolid(m.tiles[r][cc]) || GameMap.isOneWay(m.tiles[r][cc]))) cc++;
                c.drawRect(x0 + st * m.T * k, y0 + r * m.T * k, x0 + cc * m.T * k, y0 + (r + 1) * m.T * k, p);
            }
        }
        // camera view
        p.setStyle(Paint.Style.STROKE);
        p.setColor(0x66FFFFFF);
        p.setStrokeWidth(1.5f);
        c.drawRect(x0 + cam.left() * k, y0 + Math.max(0, cam.top()) * k, x0 + cam.right() * k, y0 + Math.min((float) m.pixelH, cam.bottom()) * k, p);
        p.setStyle(Paint.Style.FILL);
        double[] zone = s.safeZone();
        if (zone != null) {
            p.setColor(0x55FF3B5C);
            c.drawRect(x0, y0, x0 + (float) zone[0] * k, y0 + mh, p);
            c.drawRect(x0 + (float) zone[1] * k, y0, x0 + mw, y0 + mh, p);
        }
        for (Fighter f : s.fighters()) {
            if (!f.alive || !f.known) continue;
            float fx = x0 + (float) f.rx * k, fy = y0 + (float) Math.min(m.pixelH, f.ry - 30) * k;
            p.setColor(f.id == me ? 0xFFFFFFFF : Palette.forFighter(f.slot, f.team));
            c.drawCircle(fx, fy, f.id == me ? h * 0.009f : h * 0.0065f, p);
        }
    }

    private void drawFeed(Canvas c, float dt) {
        float y = h * 0.15f, size = h * 0.03f;
        for (int i = feed.size() - 1; i >= 0; i--) {
            feed.get(i).life -= dt;
            if (feed.get(i).life <= 0) feed.remove(i);
        }
        float x = w - w * 0.015f;
        for (Feed f : feed) {
            float a = Math.min(1, f.life);
            t.setTextSize(size);
            float vw = t.measureText(f.victim);
            String icon = f.cause.startsWith("fall") ? " ⤓ " : f.cause.startsWith("hazard") || f.cause.startsWith("zone") ? " ☠ " : " ⚔ ";
            float iw = t.measureText(icon);
            float kw = f.killer == null ? 0 : t.measureText(f.killer);
            rect.set(x - vw - iw - kw - size * 0.8f, y - size * 1.05f, x + size * 0.3f, y + size * 0.35f);
            p.setColor(Palette.withAlpha(0xFF0A0D1C, 0.7f * a));
            c.drawRoundRect(rect, 6, 6, p);
            text(c, f.victim, x, y, size, Palette.withAlpha(f.vColor, a), Paint.Align.RIGHT, 0);
            text(c, icon, x - vw, y, size, Palette.withAlpha(0xFFFFFFFF, a), Paint.Align.RIGHT, 0);
            if (f.killer != null) text(c, f.killer, x - vw - iw, y, size, Palette.withAlpha(f.kColor, a), Paint.Align.RIGHT, 0);
            y += size * 1.6f;
        }
    }

    private void drawButtons(Canvas c) {
        for (RectF r : new RectF[]{pauseBtn, scoreBtn}) {
            p.setColor(0xAA141A33);
            c.drawRoundRect(r, 10, 10, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2);
            p.setColor(0xAAFFFFFF);
            c.drawRoundRect(r, 10, 10, p);
            p.setStyle(Paint.Style.FILL);
        }
        p.setColor(0xFFFFFFFF);
        float bw = pauseBtn.width();
        c.drawRect(pauseBtn.left + bw * 0.32f, pauseBtn.top + bw * 0.25f, pauseBtn.left + bw * 0.44f, pauseBtn.bottom - bw * 0.25f, p);
        c.drawRect(pauseBtn.left + bw * 0.56f, pauseBtn.top + bw * 0.25f, pauseBtn.left + bw * 0.68f, pauseBtn.bottom - bw * 0.25f, p);
        for (int i = 0; i < 3; i++) {
            float yy = scoreBtn.top + bw * (0.3f + i * 0.2f);
            c.drawRect(scoreBtn.left + bw * 0.25f, yy, scoreBtn.right - bw * 0.25f, yy + bw * 0.08f, p);
        }
    }

    private void drawCenter(Canvas c, GameSession s, Fighter self, float dt, float time) {
        if (fightBanner > 0) {
            fightBanner -= dt;
            float k = Math.min(1, fightBanner);
            float pop = fightBanner > 1.3f ? 1 + (fightBanner - 1.3f) * 2 : 1;
            text(c, "FIGHT!", w / 2f, h * 0.45f, h * 0.16f * pop, Palette.withAlpha(0xFFFFC93B, k), Paint.Align.CENTER, h * 0.02f);
        }
        if (bannerT > 0 && banner != null) {
            bannerT -= dt;
            text(c, banner, w / 2f, h * 0.36f, h * 0.06f, Palette.withAlpha(0xFFFFFFFF, Math.min(1, bannerT)), Paint.Align.CENTER, h * 0.01f);
        }
        if (self != null && !self.alive && s.result() == null) {
            if (self.respawnT > 0) {
                text(c, "HỒI SINH SAU " + ((self.respawnT + 29) / 30), w / 2f, h * 0.42f, h * 0.07f, 0xFFFFFFFF, Paint.Align.CENTER, h * 0.012f);
            } else if ("survival".equals(s.modeId())) {
                text(c, "BẠN ĐÃ BỊ LOẠI · ĐANG XEM", w / 2f, h * 0.42f, h * 0.06f, 0xFFFF5A5A, Paint.Align.CENTER, h * 0.01f);
            }
        }
        double[] zone = s.safeZone();
        if (zone != null && self != null && self.alive && (self.x < zone[0] || self.x > zone[1])) {
            float k = 0.5f + 0.5f * (float) Math.sin(time * 8);
            text(c, "NGOÀI VÙNG AN TOÀN!", w / 2f, h * 0.2f, h * 0.05f, Palette.withAlpha(0xFFFF3B5C, 0.6f + 0.4f * k), Paint.Align.CENTER, h * 0.008f);
        }
    }

    private void drawScoreboard(Canvas c, GameSession s, int me) {
        List<GameSession.ScoreRow> rows = s.scoreboard();
        float bw = w * 0.56f, rh = h * 0.062f, bh = rh * (rows.size() + 2.2f);
        float x0 = (w - bw) / 2, y0 = (h - bh) / 2 - h * 0.05f;
        rect.set(x0, y0, x0 + bw, y0 + bh);
        p.setColor(0xEE0A0D1C);
        c.drawRoundRect(rect, 14, 14, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3);
        p.setColor(0xFF3BD5FF);
        c.drawRoundRect(rect, 14, 14, p);
        p.setStyle(Paint.Style.FILL);
        boolean surv = "survival".equals(s.modeId());
        float[] cols = {0.06f, 0.5f, 0.6f, 0.7f, 0.8f, 0.92f};
        String[] head = surv ? new String[]{"FIGHTER", "KO", "", "", "DMG", "STATUS"} : new String[]{"FIGHTER", "K", "D", "A", "DMG", "SCORE"};
        float y = y0 + rh;
        for (int i = 0; i < head.length; i++) {
            text(c, head[i], x0 + bw * cols[i], y, rh * 0.45f, 0xFFFFC93B, i == 0 ? Paint.Align.LEFT : Paint.Align.CENTER, 0);
        }
        y += rh * 0.3f;
        for (GameSession.ScoreRow r : rows) {
            y += rh;
            if (r.id == me) {
                p.setColor(0x333BD5FF);
                c.drawRect(x0 + 8, y - rh * 0.72f, x0 + bw - 8, y + rh * 0.25f, p);
            }
            int col = r.team == 1 ? 0xFFFF3B4E : r.team == 2 ? 0xFF2F8BFF : 0xFFFFFFFF;
            Fighter f = s.fighter(r.id);
            String name = (f != null ? "P" + (f.slot + 1) + "  " : "") + r.name;
            text(c, name, x0 + bw * cols[0], y, rh * 0.45f, col, Paint.Align.LEFT, 0);
            if (surv) {
                text(c, String.valueOf(r.kills), x0 + bw * cols[1], y, rh * 0.45f, 0xFFFFFFFF, Paint.Align.CENTER, 0);
                text(c, String.valueOf(r.damage), x0 + bw * cols[4], y, rh * 0.45f, 0xFFFFFFFF, Paint.Align.CENTER, 0);
                text(c, r.alive ? "SỐNG" : "LOẠI", x0 + bw * cols[5], y, rh * 0.42f, r.alive ? 0xFF3BE08A : 0xFFFF5A5A, Paint.Align.CENTER, 0);
            } else {
                int[] v = {r.kills, r.deaths, r.assists, r.damage, r.score};
                for (int i = 0; i < v.length; i++) {
                    text(c, String.valueOf(v[i]), x0 + bw * cols[i + 1], y, rh * 0.45f, 0xFFFFFFFF, Paint.Align.CENTER, 0);
                }
            }
        }
    }
}

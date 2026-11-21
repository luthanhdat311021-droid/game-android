package com.shadowduel.arena.game.render;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import com.shadowduel.arena.game.sim.GameMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Draws the arena: sky + parallax layers (screen space), textured tiles, props and
 * moving platforms (world space). Tile runs are precomputed once per map so a frame
 * only issues a few hundred draw calls.
 */
public final class StageRenderer {

    private static final int K_SOLID = 0, K_PAD = 1, K_NEON = 2, K_WALL = 3, K_PLANK = 4, K_ROOF = 5, K_SPIKE = 6, K_LAVA = 7;

    private static final class Run {
        int row, c0, c1, kind, depth;
    }

    private static final class Theme {
        int skyTop, skyMid, skyBottom, far, mid, near, nearDark, glow, stone, stoneLight, stoneDark, mortar;
        int grass, grassLight, plank, plankDark, roof, roofLight, window, fog;
    }

    private final GameMap map;
    private final Theme th = new Theme();
    private final List<Run> runs = new ArrayList<>();
    private final List<Run> tops = new ArrayList<>();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tex = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wallTex = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint add = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private final Path farLayer = new Path(), midLayer = new Path(), nearLayer = new Path();
    private final List<float[]> windows = new ArrayList<>();      // mid layer lit windows
    private final List<float[]> roofs = new ArrayList<>();        // mid layer cone roofs: x, y, w, h
    private final List<float[]> towers = new ArrayList<>();       // near layer big towers
    private final List<float[]> neonBars = new ArrayList<>();
    private final float[] stars;
    private final boolean temple, flame, cyber;
    private final Random rnd = new Random(7);
    public boolean highQuality = true;

    public StageRenderer(GameMap map) {
        this.map = map;
        temple = "temple".equals(map.theme);
        flame = "flame".equals(map.theme);
        cyber = "cyber".equals(map.theme);
        setupTheme();
        buildRuns();
        stars = new float[150 * 3];
        for (int i = 0; i < 150; i++) {
            stars[i * 3] = rnd.nextFloat();
            stars[i * 3 + 1] = rnd.nextFloat() * 0.6f;
            stars[i * 3 + 2] = 0.5f + rnd.nextFloat() * 1.6f;
        }
        buildLayers();
        tex.setShader(makeStoneShader());
        wallTex.setShader(makeWallShader());
        add.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD));
    }

    private void setupTheme() {
        Theme t = th;
        if (flame) {
            t.skyTop = 0xFF12030A; t.skyMid = 0xFF4A0E0C; t.skyBottom = 0xFF9A2A0E;
            t.far = 0xFF2A0A0C; t.mid = 0xFF1E0A0A; t.near = 0xFF140606; t.nearDark = 0xFF0C0303; t.glow = 0xFFFF6A1F;
            t.stone = 0xFF4B3A38; t.stoneLight = 0xFF7A5E55; t.stoneDark = 0xFF241A1A; t.mortar = 0xFF1A1010;
            t.grass = 0xFF8A2A10; t.grassLight = 0xFFFF7A2A; t.plank = 0xFF5A3A2A; t.plankDark = 0xFF2E1C12;
            t.roof = 0xFF6E1A12; t.roofLight = 0xFFB0321C; t.window = 0xFFFF9A3B; t.fog = 0x66FF4A10;
        } else if (cyber) {
            t.skyTop = 0xFF05031A; t.skyMid = 0xFF1A0B40; t.skyBottom = 0xFF3B1466;
            t.far = 0xFF140A33; t.mid = 0xFF0F0A2A; t.near = 0xFF0A0820; t.nearDark = 0xFF060414; t.glow = 0xFFFF3BD5;
            t.stone = 0xFF2C3352; t.stoneLight = 0xFF56628F; t.stoneDark = 0xFF161A2E; t.mortar = 0xFF0E1122;
            t.grass = 0xFF22D3EE; t.grassLight = 0xFFA5F3FC; t.plank = 0xFF3A3F5C; t.plankDark = 0xFF1E2238;
            t.roof = 0xFF2A1F5C; t.roofLight = 0xFFFF3BD5; t.window = 0xFF3BD5FF; t.fog = 0x55B34BFF;
        } else {
            t.skyTop = 0xFF050820; t.skyMid = 0xFF0D1438; t.skyBottom = 0xFF1C2A5C;
            t.far = 0xFF111A40; t.mid = 0xFF141C3E; t.near = 0xFF0E1A2A; t.nearDark = 0xFF09121E; t.glow = 0xFF6A8CFF;
            t.stone = 0xFF5E6880; t.stoneLight = 0xFF97A3BD; t.stoneDark = 0xFF2E3448; t.mortar = 0xFF232838;
            t.grass = 0xFF2F7A35; t.grassLight = 0xFF6CC24A; t.plank = 0xFF6E4A2C; t.plankDark = 0xFF3A2414;
            t.roof = 0xFF8E2333; t.roofLight = 0xFFC9404E; t.window = 0xFFFFC96B; t.fog = 0x443B5BFF;
        }
    }

    // ------------------------------------------------------------------ tiles
    private static int kind(char ch) {
        switch (ch) {
            case '#': return K_SOLID;
            case 'G': return K_PAD;
            case 'N': return K_NEON;
            case 'W': return K_WALL;
            case '=': return K_PLANK;
            case 'R': return K_ROOF;
            case '^': return K_SPIKE;
            case 'L': return K_LAVA;
            default: return -1;
        }
    }

    private void buildRuns() {
        int[][] depth = new int[map.h][map.w];
        for (int c = 0; c < map.w; c++) {
            int d = 0;
            for (int row = 0; row < map.h; row++) {
                char ch = map.tiles[row][c];
                if (GameMap.isSolid(ch)) d++;
                else d = 0;
                depth[row][c] = Math.min(d, 7);
            }
        }
        for (int row = 0; row < map.h; row++) {
            int c = 0;
            while (c < map.w) {
                int k = kind(map.tiles[row][c]);
                if (k < 0) { c++; continue; }
                int d = depth[row][c];
                int start = c;
                while (c < map.w && kind(map.tiles[row][c]) == k && depth[row][c] == d) c++;
                Run run = new Run();
                run.row = row; run.c0 = start; run.c1 = c - 1; run.kind = k; run.depth = d;
                runs.add(run);
                if (d == 1 && (k == K_SOLID || k == K_WALL)) tops.add(run);
            }
        }
    }

    private Shader makeStoneShader() {
        int S = 128;
        Bitmap b = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
        c.drawColor(th.mortar);
        Random rr = new Random(11);
        int rowH = 32;
        for (int y = 0; y < S; y += rowH) {
            int x = (y / rowH) % 2 == 0 ? 0 : -24;
            while (x < S) {
                int w = 40 + rr.nextInt(24);
                float shade = 0.85f + rr.nextFloat() * 0.3f;
                q.setColor(Palette.mix(th.stoneDark, th.stone, Math.min(1f, shade)));
                r.set(x + 2, y + 2, x + w - 2, y + rowH - 2);
                c.drawRoundRect(r, 5, 5, q);
                if (x + w > S) { r.offset(-S, 0); c.drawRoundRect(r, 5, 5, q); }
                q.setColor(Palette.withAlpha(th.stoneLight, 0.55f));
                r.set(x + 4, y + 3, x + w - 6, y + 7);
                c.drawRoundRect(r, 3, 3, q);
                if (x + w > S) { r.offset(-S, 0); c.drawRoundRect(r, 3, 3, q); }
                x += w;
            }
        }
        for (int i = 0; i < 260; i++) {
            q.setColor(Palette.withAlpha(rr.nextBoolean() ? 0xFF000000 : 0xFFFFFFFF, 0.06f + rr.nextFloat() * 0.06f));
            c.drawCircle(rr.nextInt(S), rr.nextInt(S), 0.8f + rr.nextFloat() * 2f, q);
        }
        if (flame) {
            q.setStyle(Paint.Style.STROKE);
            q.setStrokeWidth(1.6f);
            q.setColor(0xAAFF6A1F);
            for (int i = 0; i < 6; i++) {
                float x0 = rr.nextInt(S), y0 = rr.nextInt(S);
                c.drawLine(x0, y0, x0 + rr.nextInt(20) - 10, y0 + 12, q);
            }
        }
        return new BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
    }

    private Shader makeWallShader() {
        int S = 64;
        Bitmap b = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
        c.drawColor(0xFF161A30);
        q.setColor(0xFF1E2340);
        c.drawRect(0, 0, S, 4, q);
        Random rr = new Random(3);
        for (int y = 10; y < S; y += 18) {
            for (int x = 6; x < S; x += 16) {
                boolean lit = rr.nextFloat() < 0.45f;
                q.setColor(lit ? (rr.nextBoolean() ? 0xFF3BD5FF : 0xFFFF6BE0) : 0xFF0D0F1F);
                c.drawRect(x, y, x + 8, y + 10, q);
            }
        }
        return new BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
    }

    // ------------------------------------------------------------------ parallax layers
    private void buildLayers() {
        float W = (float) map.pixelW;
        float base = (float) (map.pixelH * 0.72);
        // far mountains / skyline
        farLayer.reset();
        farLayer.moveTo(-2000, base + 400);
        float x = -2000;
        while (x < W + 2000) {
            float peak = cyber ? base - 120 - rnd.nextFloat() * 260 : base - 80 - rnd.nextFloat() * 300;
            float w = cyber ? 60 + rnd.nextFloat() * 90 : 160 + rnd.nextFloat() * 260;
            if (cyber) {
                farLayer.lineTo(x, peak);
                farLayer.lineTo(x + w, peak);
            } else {
                farLayer.lineTo(x + w * 0.5f, peak);
            }
            x += w;
            if (!cyber) farLayer.lineTo(x, base - rnd.nextFloat() * 60);
        }
        farLayer.lineTo(W + 2000, base + 400);
        farLayer.close();

        // mid layer: castles (temple), fortress spires (flame), towers (cyber)
        midLayer.reset();
        x = -1200;
        while (x < W + 1200) {
            float cw = 260 + rnd.nextFloat() * 220;
            float ground = base + 40;
            if (temple || flame) {
                float wallH = 120 + rnd.nextFloat() * 80;
                midLayer.addRect(x, ground - wallH, x + cw, ground + 400, Path.Direction.CW);
                int towersN = 2 + rnd.nextInt(3);
                for (int i = 0; i < towersN; i++) {
                    float tx = x + cw * (i + 0.5f) / towersN - 18;
                    float th2 = wallH + 70 + rnd.nextFloat() * 140;
                    float tw = 30 + rnd.nextFloat() * 22;
                    midLayer.addRect(tx, ground - th2, tx + tw, ground, Path.Direction.CW);
                    roofs.add(new float[]{tx - 6, ground - th2, tw + 12, 46 + rnd.nextFloat() * 30});
                    for (int k = 0; k < 2; k++) {
                        if (rnd.nextFloat() < 0.75f) windows.add(new float[]{tx + tw / 2 - 4, ground - th2 + 30 + k * 34, 8, 13});
                    }
                }
                // crenellations
                for (float cx = x; cx < x + cw; cx += 22) midLayer.addRect(cx, ground - wallH - 12, cx + 12, ground - wallH, Path.Direction.CW);
                for (int k = 0; k < 4; k++) {
                    if (rnd.nextFloat() < 0.6f) windows.add(new float[]{x + 20 + rnd.nextFloat() * (cw - 40), ground - wallH + 20 + rnd.nextFloat() * 60, 7, 11});
                }
            } else {
                float h = 260 + rnd.nextFloat() * 380;
                midLayer.addRect(x, ground - h, x + cw * 0.6f, ground + 400, Path.Direction.CW);
                neonBars.add(new float[]{x, ground - h, cw * 0.6f, rnd.nextBoolean() ? 1 : 0});
                for (float wy = ground - h + 20; wy < ground; wy += 26) {
                    for (float wx = x + 10; wx < x + cw * 0.6f - 10; wx += 22) {
                        if (rnd.nextFloat() < 0.35f) windows.add(new float[]{wx, wy, 9, 12});
                    }
                }
            }
            x += cw + 200 + rnd.nextFloat() * 500;
        }

        // near layer: bushes / rocks, and the big round tower with a red scale roof
        nearLayer.reset();
        x = -800;
        float nb = base + 210;
        while (x < W + 800) {
            float rad = 50 + rnd.nextFloat() * 90;
            nearLayer.addCircle(x, nb - rad * 0.4f, rad, Path.Direction.CW);
            x += rad * 1.2f;
        }
        nearLayer.addRect(-800, nb - 20, W + 800, nb + 600, Path.Direction.CW);
        for (float tx = 700; tx < W; tx += 1700 + rnd.nextFloat() * 500) {
            towers.add(new float[]{tx, nb - 30, 120 + rnd.nextFloat() * 40, 300 + rnd.nextFloat() * 80});
        }
    }

    private void layer(Canvas c, Camera cam, float f) {
        float refY = (float) (map.pixelH * 0.6);
        c.save();
        c.translate(cam.screenW / 2f, cam.screenH / 2f);
        c.scale(cam.scale, cam.scale);
        c.translate(-cam.x * f, -(cam.y * f + refY * (1 - f)));
    }

    /** Sky and parallax: drawn before the world transform. */
    public void drawBackground(Canvas c, Camera cam, float time) {
        int w = cam.screenW, h = cam.screenH;
        p.setShader(new LinearGradient(0, 0, 0, h, new int[]{th.skyTop, th.skyMid, th.skyBottom}, new float[]{0, 0.55f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        // stars and moon (very slow parallax)
        float sx = -(cam.x * 0.02f) % w;
        for (int i = 0; i < stars.length; i += 3) {
            float tw = 0.5f + 0.5f * (float) Math.sin(time * 2 + i);
            p.setColor(Palette.withAlpha(0xFFFFFFFF, (flame ? 0.15f : 0.35f) + 0.4f * tw));
            float px = ((stars[i] * w + sx) % w + w) % w;
            c.drawCircle(px, stars[i + 1] * h, stars[i + 2] * (h / 1080f), p);
        }
        float mx = w * 0.78f - cam.x * 0.01f, my = h * 0.17f, mr = h * 0.06f;
        int moon = flame ? 0xFFFF8A3B : cyber ? 0xFFFF5BE0 : 0xFFE9EEFF;
        p.setShader(new RadialGradient(mx, my, mr * 5, Palette.withAlpha(moon, 0.35f), 0x00000000, Shader.TileMode.CLAMP));
        c.drawCircle(mx, my, mr * 5, p);
        p.setShader(null);
        p.setColor(moon);
        path.reset();
        path.addCircle(mx, my, mr, Path.Direction.CW);
        if (!flame) { // crescent: subtract an offset disc instead of painting over the sky
            Path cut = new Path();
            cut.addCircle(mx + mr * 0.45f, my - mr * 0.2f, mr * 0.9f, Path.Direction.CW);
            path.op(cut, Path.Op.DIFFERENCE);
        }
        c.drawPath(path, p);

        // far
        layer(c, cam, 0.15f);
        p.setColor(th.far);
        c.drawPath(farLayer, p);
        if (flame) {
            // glowing volcano rims
            p.setShader(new LinearGradient(0, (float) (map.pixelH * 0.72 - 380), 0, (float) (map.pixelH * 0.72), 0x00FF6A1F, 0x55FF3A0F, Shader.TileMode.CLAMP));
            c.drawPath(farLayer, p);
            p.setShader(null);
        }
        c.restore();

        // mid
        layer(c, cam, 0.32f);
        p.setColor(th.mid);
        c.drawPath(midLayer, p);
        for (float[] rf : roofs) {
            path.reset();
            path.moveTo(rf[0], rf[1]);
            path.lineTo(rf[0] + rf[2] / 2, rf[1] - rf[3]);
            path.lineTo(rf[0] + rf[2], rf[1]);
            path.close();
            p.setColor(th.roof);
            c.drawPath(path, p);
        }
        for (float[] wn : windows) {
            float flick = 0.75f + 0.25f * (float) Math.sin(time * 3 + wn[0]);
            p.setColor(Palette.withAlpha(th.window, flick));
            r.set(wn[0], wn[1], wn[0] + wn[2], wn[1] + wn[3]);
            c.drawRoundRect(r, wn[2] / 2, wn[2] / 2, p);
        }
        if (cyber) {
            for (float[] nb : neonBars) {
                p.setColor(nb[3] > 0 ? 0xFFFF3BD5 : 0xFF3BD5FF);
                c.drawRect(nb[0], nb[1], nb[0] + nb[2], nb[1] + 4, p);
            }
        }
        c.restore();

        // near
        layer(c, cam, 0.58f);
        p.setColor(th.near);
        c.drawPath(nearLayer, p);
        for (float[] t : towers) drawBigTower(c, t[0], t[1], t[2], t[3], time);
        c.restore();

        // atmospheric fog at the bottom
        p.setShader(new LinearGradient(0, h * 0.45f, 0, h, 0x00000000, th.fog, Shader.TileMode.CLAMP));
        c.drawRect(0, h * 0.45f, w, h, p);
        p.setShader(null);
    }

    /** Round stone tower with a red scaled cone roof, a lit arched window and a glowing ring. */
    private void drawBigTower(Canvas c, float cx, float ground, float tw, float th2, float time) {
        float top = ground - th2;
        float l = cx - tw / 2, rgt = cx + tw / 2;
        if (cyber) {
            p.setColor(0xFF111633);
            c.drawRect(l, top - 80, rgt, ground, p);
            p.setColor(0xFFFF3BD5);
            c.drawRect(l, top - 80, rgt, top - 72, p);
            p.setColor(0xFF3BD5FF);
            for (float y = top - 50; y < ground - 20; y += 40) c.drawRect(l + 14, y, rgt - 14, y + 6, p);
            return;
        }
        p.setShader(new LinearGradient(l, 0, rgt, 0, new int[]{0xFF1E2438, 0xFF3A4360, 0xFF1A2032}, null, Shader.TileMode.CLAMP));
        c.drawRect(l, top, rgt, ground, p);
        p.setShader(null);
        p.setColor(0x33000000);
        for (float y = top + 22; y < ground; y += 22) c.drawRect(l, y, rgt, y + 3, p);
        // cone roof with scales
        float rh = tw * 1.05f;
        path.reset();
        path.moveTo(l - 16, top + 8);
        path.quadTo(cx, top - rh * 0.2f, cx, top - rh);
        path.quadTo(cx, top - rh * 0.2f, rgt + 16, top + 8);
        path.close();
        p.setColor(th.roof);
        c.drawPath(path, p);
        c.save();
        c.clipPath(path);
        p.setColor(th.roofLight);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3);
        for (float y = top - rh + 16; y < top + 10; y += 13) {
            for (float x = l - 20; x < rgt + 20; x += 16) {
                r.set(x + ((y / 13) % 2 == 0 ? 0 : 8), y - 8, x + 16 + ((y / 13) % 2 == 0 ? 0 : 8), y + 8);
                c.drawArc(r, 0, 180, false, p);
            }
        }
        p.setStyle(Paint.Style.FILL);
        c.restore();
        p.setColor(0xFF2B2F44);
        c.drawRect(cx - 2, top - rh - 40, cx + 2, top - rh, p);
        // lit arched window
        float wx = cx + tw * 0.12f, wy = top + th2 * 0.28f;
        p.setShader(new RadialGradient(wx, wy, 60, Palette.withAlpha(th.window, 0.5f), 0x00000000, Shader.TileMode.CLAMP));
        c.drawCircle(wx, wy, 60, p);
        p.setShader(null);
        p.setColor(th.window);
        r.set(wx - 10, wy - 18, wx + 10, wy + 18);
        c.drawRoundRect(r, 10, 10, p);
        // glowing red ring (portal) beside the tower
        float rx = l - 30, ry = top + th2 * 0.45f, pulse = 0.7f + 0.3f * (float) Math.sin(time * 4);
        p.setShader(new RadialGradient(rx, ry, 70, Palette.withAlpha(0xFFFF2040, 0.55f * pulse), 0x00000000, Shader.TileMode.CLAMP));
        c.drawCircle(rx, ry, 70, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(7);
        p.setColor(0xFFFF3050);
        r.set(rx - 16, ry - 26, rx + 16, ry + 26);
        c.drawOval(r, p);
        p.setStyle(Paint.Style.FILL);
    }

    // ------------------------------------------------------------------ world space
    public void drawTiles(Canvas c, Camera cam, float time) {
        int T = map.T;
        int c0 = (int) Math.floor(cam.left() / T) - 1, c1 = (int) Math.ceil(cam.right() / T) + 1;
        int r0 = (int) Math.floor(cam.top() / T) - 1, r1 = (int) Math.ceil(cam.bottom() / T) + 1;
        for (Run run : runs) {
            if (run.row < r0 || run.row > r1 || run.c1 < c0 || run.c0 > c1) continue;
            float x0 = run.c0 * T, x1 = (run.c1 + 1) * T, y0 = run.row * T, y1 = y0 + T;
            switch (run.kind) {
                case K_SOLID:
                    c.drawRect(x0, y0, x1, y1 + 0.5f, tex);
                    if (run.depth > 1) {
                        // deeper rock fades into darkness so platforms read as a floating stage
                        float dk = run.depth == 2 ? 0.25f : run.depth == 3 ? 0.5f : run.depth == 4 ? 0.7f : 0.84f;
                        p.setColor(Palette.withAlpha(th.skyTop, dk));
                        c.drawRect(x0, y0, x1, y1 + 0.5f, p);
                    }
                    break;
                case K_WALL:
                    c.drawRect(x0, y0, x1, y1 + 0.5f, wallTex);
                    break;
                case K_PAD:
                    drawPad(c, x0, y0, x1, y1, time);
                    break;
                case K_NEON:
                    p.setColor(0xFF1A1F38);
                    c.drawRect(x0, y0, x1, y1, p);
                    add.setShader(new LinearGradient(0, y0 - 14, 0, y0 + 10, 0x00FF3BD5, 0xAAFF3BD5, Shader.TileMode.MIRROR));
                    c.drawRect(x0, y0 - 14, x1, y0 + 10, add);
                    add.setShader(null);
                    p.setColor(0xFFFF8BEA);
                    c.drawRect(x0, y0, x1, y0 + 4, p);
                    break;
                case K_PLANK:
                    drawPlank(c, x0, y0, x1);
                    break;
                case K_ROOF:
                    drawRoofPlatform(c, x0, y0, x1);
                    break;
                case K_SPIKE:
                    drawSpikes(c, x0, y0, x1, time);
                    break;
                case K_LAVA:
                    drawLava(c, x0, y0, x1, y1, run.row, time);
                    break;
            }
        }
        // surface lips + grass on exposed tops
        for (Run run : tops) {
            if (run.row < r0 || run.row > r1 || run.c1 < c0 || run.c0 > c1) continue;
            float x0 = run.c0 * T, x1 = (run.c1 + 1) * T, y0 = run.row * T;
            p.setColor(th.stoneLight);
            r.set(x0 - 2, y0 - 2, x1 + 2, y0 + 7);
            c.drawRoundRect(r, 4, 4, p);
            if (temple) {
                p.setColor(th.grass);
                c.drawRect(x0, y0 - 3, x1, y0 + 2, p);
                p.setColor(th.grassLight);
                for (float gx = x0 + 3; gx < x1 - 2; gx += 7) {
                    float hgt = 4 + ((gx * 13) % 7);
                    path.reset();
                    path.moveTo(gx - 2.5f, y0);
                    path.lineTo(gx + (float) Math.sin(time * 2 + gx) * 1.5f, y0 - hgt);
                    path.lineTo(gx + 2.5f, y0);
                    path.close();
                    c.drawPath(path, p);
                }
            } else if (flame) {
                p.setColor(0xCCFF6A1F);
                c.drawRect(x0, y0, x1, y0 + 2, p);
            } else {
                p.setColor(0xFF3BD5FF);
                c.drawRect(x0, y0 - 1, x1, y0 + 2, p);
            }
        }
    }

    /** Yellow glowing grid pads (like dance-floor panels). */
    private void drawPad(Canvas c, float x0, float y0, float x1, float y1, float time) {
        p.setColor(0xFF2A2410);
        c.drawRect(x0, y0, x1, y1, p);
        float pulse = 0.65f + 0.35f * (float) Math.sin(time * 3);
        add.setShader(new LinearGradient(0, y0 - 40, 0, y0, 0x00FFC21F, Palette.withAlpha(0xFFFFB81F, 0.45f * pulse), Shader.TileMode.CLAMP));
        c.drawRect(x0, y0 - 40, x1, y0, add);
        add.setShader(null);
        float cell = 10.5f;
        for (float x = x0 + 1.5f; x < x1 - 2; x += cell) {
            for (float y = y0 + 1.5f; y < y1 - 2; y += cell) {
                float k = 0.6f + 0.4f * (float) Math.sin(time * 5 + x * 0.07f + y * 0.11f);
                p.setColor(Palette.mix(0xFFB07A0A, 0xFFFFE066, k * pulse));
                c.drawRect(x, y, x + cell - 2, y + cell - 2, p);
            }
        }
    }

    private void drawPlank(Canvas c, float x0, float y0, float x1) {
        p.setColor(th.plankDark);
        c.drawRect(x0, y0, x1, y0 + 12, p);
        p.setColor(th.plank);
        c.drawRect(x0, y0, x1, y0 + 8, p);
        p.setColor(Palette.mix(th.plank, 0xFFFFFFFF, 0.25f));
        c.drawRect(x0, y0, x1, y0 + 2, p);
        p.setColor(th.plankDark);
        for (float x = x0 + 24; x < x1; x += 32) c.drawRect(x, y0, x + 2, y0 + 8, p);
        // supports
        p.setColor(th.plankDark);
        c.drawRect(x0 + 6, y0 + 12, x0 + 12, y0 + 28, p);
        c.drawRect(x1 - 12, y0 + 12, x1 - 6, y0 + 28, p);
    }

    private void drawRoofPlatform(Canvas c, float x0, float y0, float x1) {
        path.reset();
        path.moveTo(x0 - 22, y0 + 26);
        path.lineTo(x0 + 6, y0);
        path.lineTo(x1 - 6, y0);
        path.lineTo(x1 + 22, y0 + 26);
        path.close();
        p.setColor(cyber ? 0xFF2A1F5C : th.roof);
        c.drawPath(path, p);
        c.save();
        c.clipPath(path);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.5f);
        p.setColor(cyber ? 0xFFFF3BD5 : th.roofLight);
        for (float y = y0 + 6; y < y0 + 28; y += 9) {
            for (float x = x0 - 24; x < x1 + 24; x += 12) {
                float o = ((int) ((y - y0) / 9) % 2 == 0) ? 0 : 6;
                r.set(x + o, y - 6, x + o + 12, y + 6);
                c.drawArc(r, 0, 180, false, p);
            }
        }
        p.setStyle(Paint.Style.FILL);
        c.restore();
        p.setColor(cyber ? 0xFF3BD5FF : 0xFFE0B04A);
        c.drawRect(x0 + 6, y0 - 1, x1 - 6, y0 + 3, p);
        // upturned eaves
        p.setColor(cyber ? 0xFF3BD5FF : th.roofLight);
        c.drawCircle(x0 - 22, y0 + 24, 4, p);
        c.drawCircle(x1 + 22, y0 + 24, 4, p);
    }

    private void drawSpikes(Canvas c, float x0, float y0, float x1, float time) {
        float base = y0 + map.T;
        if (cyber) {
            float k = 0.5f + 0.5f * (float) Math.sin(time * 18);
            add.setColor(Palette.withAlpha(0xFF3BD5FF, 0.35f + 0.4f * k));
            c.drawRect(x0, base - 10, x1, base, add);
            p.setColor(0xFF3BD5FF);
            for (float x = x0; x < x1; x += 8) c.drawLine(x, base - 8, x + 6, base - 2, p);
            return;
        }
        for (float x = x0; x < x1; x += 10.6f) {
            path.reset();
            path.moveTo(x, base);
            path.lineTo(x + 5.3f, base - 18);
            path.lineTo(x + 10.6f, base);
            path.close();
            p.setColor(0xFF9AA3B8);
            c.drawPath(path, p);
            p.setColor(0xFF4A5266);
            c.drawRect(x, base - 3, x + 10.6f, base, p);
        }
    }

    private void drawLava(Canvas c, float x0, float y0, float x1, float y1, int row, float time) {
        boolean surface = row == 0 || map.tiles[row - 1][(int) (x0 / map.T)] != 'L';
        p.setColor(surface ? 0xFFFF5A12 : 0xFFD8360A);
        c.drawRect(x0, y0 + (surface ? 6 : 0), x1, y1 + 0.5f, p);
        if (surface) {
            p.setColor(0xFFFFC23B);
            path.reset();
            path.moveTo(x0, y0 + 10);
            for (float x = x0; x <= x1; x += 8) path.lineTo(x, y0 + 7 + (float) Math.sin(time * 3 + x * 0.05f) * 3);
            path.lineTo(x1, y0 + 14);
            path.lineTo(x0, y0 + 14);
            path.close();
            c.drawPath(path, p);
            add.setShader(new LinearGradient(0, y0 - 70, 0, y0 + 8, 0x00FF5A12, 0x88FF7A1F, Shader.TileMode.CLAMP));
            c.drawRect(x0, y0 - 70, x1, y0 + 8, add);
            add.setShader(null);
        }
    }

    public void drawMovers(Canvas c) {
        for (GameMap.Mover m : map.movers) {
            float x0 = (float) m.x, x1 = (float) (m.x + m.w), y0 = (float) m.y;
            p.setColor(cyber ? 0xFF2C3352 : th.stoneDark);
            r.set(x0, y0, x1, y0 + 16);
            c.drawRoundRect(r, 4, 4, p);
            p.setColor(cyber ? 0xFF3BD5FF : flame ? 0xFFFF7A2A : th.stoneLight);
            c.drawRect(x0 + 2, y0, x1 - 2, y0 + 4, p);
            add.setColor(Palette.withAlpha(cyber ? 0xFF3BD5FF : 0xFFFF6A1F, 0.35f));
            r.set(x0 + 10, y0 + 14, x1 - 10, y0 + 26);
            c.drawOval(r, add);
        }
    }

    /** Props behind fighters. */
    public void drawProps(Canvas c, Camera cam, float time) {
        for (GameMap.Prop pr : map.props) {
            if (!cam.visible(pr.x, pr.y, 220)) continue;
            float x = (float) pr.x, y = (float) pr.y;
            switch (pr.type) {
                case "torch":
                case "brazier":
                    p.setColor(0xFF3A3030);
                    c.drawRect(x - 3, y - 46, x + 3, y, p);
                    if ("brazier".equals(pr.type)) {
                        r.set(x - 14, y - 56, x + 14, y - 44);
                        c.drawRoundRect(r, 5, 5, p);
                    }
                    flame(c, x, y - 52, 1f, time);
                    break;
                case "lantern": {
                    p.setColor(th.plankDark);
                    c.drawRect(x - 1.5f, y - 40, x + 1.5f, y - 26, p);
                    float sw = (float) Math.sin(time * 2 + x) * 2;
                    add.setShader(new RadialGradient(x + sw, y - 18, 40, 0x66FF5A3A, 0x00000000, Shader.TileMode.CLAMP));
                    c.drawCircle(x + sw, y - 18, 40, add);
                    add.setShader(null);
                    p.setColor(0xFFE0402E);
                    r.set(x - 8 + sw, y - 28, x + 8 + sw, y - 8);
                    c.drawRoundRect(r, 7, 7, p);
                    p.setColor(0xFFFFC96B);
                    r.set(x - 4 + sw, y - 24, x + 4 + sw, y - 12);
                    c.drawRoundRect(r, 4, 4, p);
                    break;
                }
                case "shrine":
                    p.setColor(0xFF2A2F44);
                    c.drawRect(x - 34, y - 58, x + 34, y, p);
                    p.setColor(th.window);
                    r.set(x - 10, y - 46, x + 10, y - 14);
                    c.drawRoundRect(r, 10, 10, p);
                    add.setShader(new RadialGradient(x, y - 30, 70, Palette.withAlpha(th.window, 0.35f), 0x00000000, Shader.TileMode.CLAMP));
                    c.drawCircle(x, y - 30, 70, add);
                    add.setShader(null);
                    path.reset();
                    path.moveTo(x - 60, y - 52);
                    path.quadTo(x, y - 70, x, y - 96);
                    path.quadTo(x, y - 70, x + 60, y - 52);
                    path.close();
                    p.setColor(th.roof);
                    c.drawPath(path, p);
                    break;
                case "tree":
                    p.setColor(0xFF0E2A1C);
                    for (int i = 0; i < 4; i++) {
                        float ty = y - 30 - i * 34, tw = 70 - i * 13;
                        path.reset();
                        path.moveTo(x - tw, ty);
                        path.lineTo(x, ty - 56);
                        path.lineTo(x + tw, ty);
                        path.close();
                        c.drawPath(path, p);
                    }
                    p.setColor(0xFF2A1C12);
                    c.drawRect(x - 6, y - 34, x + 6, y, p);
                    break;
                case "banner": {
                    float wave = (float) Math.sin(time * 3 + x) * 4;
                    p.setColor(th.roof);
                    path.reset();
                    path.moveTo(x - 14, y - 128);
                    path.lineTo(x + 14, y - 128);
                    path.lineTo(x + 14 + wave, y - 60);
                    path.lineTo(x + wave, y - 70);
                    path.lineTo(x - 14 + wave, y - 60);
                    path.close();
                    c.drawPath(path, p);
                    p.setColor(0xFFE0B04A);
                    c.drawCircle(x + wave * 0.5f, y - 100, 6, p);
                    break;
                }
                case "grass":
                    p.setColor(0xFF1E5A2A);
                    for (int i = 0; i < 5; i++) c.drawCircle(x + i * 14 - 28, y - 6, 12 - Math.abs(i - 2) * 2, p);
                    break;
                case "rocks":
                    p.setColor(th.stoneDark);
                    r.set(x - 30, y - 22, x + 10, y);
                    c.drawOval(r, p);
                    p.setColor(th.stone);
                    r.set(x - 6, y - 30, x + 30, y);
                    c.drawOval(r, p);
                    break;
                case "neon_sign": {
                    float k = 0.7f + 0.3f * (float) Math.sin(time * 6 + x);
                    add.setShader(new RadialGradient(x, y - 110, 90, Palette.withAlpha(0xFFFF3BD5, 0.4f * k), 0x00000000, Shader.TileMode.CLAMP));
                    c.drawCircle(x, y - 110, 90, add);
                    add.setShader(null);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(5);
                    p.setColor(Palette.withAlpha(0xFFFF6BE0, k));
                    r.set(x - 60, y - 140, x + 60, y - 84);
                    c.drawRoundRect(r, 10, 10, p);
                    p.setColor(Palette.withAlpha(0xFF3BD5FF, k));
                    c.drawLine(x - 40, y - 112, x + 40, y - 112, p);
                    p.setStyle(Paint.Style.FILL);
                    break;
                }
                case "antenna":
                    p.setColor(0xFF2C3352);
                    c.drawRect(x - 3, y - 160, x + 3, y, p);
                    p.setColor((int) (time * 2) % 2 == 0 ? 0xFFFF2E43 : 0xFF551018);
                    c.drawCircle(x, y - 164, 6, p);
                    break;
                case "lamp":
                    p.setColor(0xFF2C3352);
                    c.drawRect(x - 3, y - 110, x + 3, y, p);
                    c.drawRect(x - 3, y - 110, x + 26, y - 104, p);
                    add.setShader(new RadialGradient(x + 22, y - 100, 80, 0x553BD5FF, 0x00000000, Shader.TileMode.CLAMP));
                    c.drawCircle(x + 22, y - 100, 80, add);
                    add.setShader(null);
                    break;
            }
        }
    }

    private void flame(Canvas c, float x, float y, float s, float time) {
        float f = (float) Math.sin(time * 14 + x) * 2;
        add.setShader(new RadialGradient(x, y - 8, 60 * s, 0x77FF7A1F, 0x00000000, Shader.TileMode.CLAMP));
        c.drawCircle(x, y - 8, 60 * s, add);
        add.setShader(null);
        path.reset();
        path.moveTo(x - 8 * s, y);
        path.quadTo(x - 6 * s, y - 14 * s, x + f, y - 26 * s);
        path.quadTo(x + 7 * s, y - 12 * s, x + 8 * s, y);
        path.close();
        p.setColor(0xFFFF7A1F);
        c.drawPath(path, p);
        path.reset();
        path.moveTo(x - 4 * s, y);
        path.quadTo(x - 3 * s, y - 8 * s, x + f * 0.5f, y - 15 * s);
        path.quadTo(x + 4 * s, y - 7 * s, x + 4 * s, y);
        path.close();
        p.setColor(0xFFFFE07A);
        c.drawPath(path, p);
    }

    /** Survival safe zone: darken and tint everything outside [l, r]. */
    public void drawSafeZone(Canvas c, Camera cam, double[] zone, float time) {
        if (zone == null) return;
        float l = (float) zone[0], rr = (float) zone[1];
        float top = cam.top() - 50, bot = cam.bottom() + 50;
        p.setColor(0x66B01030);
        if (l > cam.left()) c.drawRect(cam.left() - 50, top, l, bot, p);
        if (rr < cam.right()) c.drawRect(rr, top, cam.right() + 50, bot, p);
        float k = 0.6f + 0.4f * (float) Math.sin(time * 6);
        p.setColor(Palette.withAlpha(0xFFFF3B5C, k));
        c.drawRect(l - 3, top, l + 3, bot, p);
        c.drawRect(rr - 3, top, rr + 3, bot, p);
    }

    /** Small helper for Matrix-free reuse of the stone texture in other views. */
    public Shader stoneShader() {
        Shader s = tex.getShader();
        s.setLocalMatrix(new Matrix());
        return s;
    }
}

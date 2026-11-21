package com.shadowduel.arena.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import com.shadowduel.arena.data.Economy;
import com.shadowduel.arena.game.render.CharacterRenderer;
import com.shadowduel.arena.game.render.Palette;

import org.json.JSONObject;

/** Draws an item icon from its catalog row (category, rarity, palette) — no image assets needed. */
public class ItemIconView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private final CharacterRenderer chars = new CharacterRenderer();
    private JSONObject item;
    private String special; // "gold", "gems" for currency packs

    public ItemIconView(Context c) {
        this(c, null);
    }

    public ItemIconView(Context c, AttributeSet a) {
        super(c, a);
    }

    public void setItem(JSONObject item) {
        this.item = item;
        this.special = null;
        invalidate();
    }

    public void setCurrency(String type) {
        this.item = null;
        this.special = type;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, s = Math.min(w, h);
        String rarity = item == null ? (special != null && special.equals("gems") ? "epic" : "legendary") : item.optString("rarity");
        int rc = Economy.rarityColor(rarity);
        p.setShader(new RadialGradient(cx, cy, s * 0.7f, Palette.withAlpha(rc, 0.55f), 0x00000000, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, s * 0.7f, p);
        p.setShader(null);
        String cat = item == null ? special : item.optString("category");
        JSONObject fx = item == null ? null : item.optJSONObject("gameplay_effect");
        switch (cat == null ? "" : cat) {
            case "skin": {
                int[] pal = Economy.palette(fx == null ? null : fx.optJSONObject("palette"));
                chars.drawPortraitSkin(c, item.optString("character_id", "kaze"), cx, cy, s * 0.42f, pal);
                break;
            }
            case "weapon": {
                int col = parse(fx, "trail", rc);
                c.save();
                c.rotate(-40, cx, cy);
                p.setColor(0xFF1A1A2A);
                r.set(cx - s * 0.05f, cy - s * 0.38f, cx + s * 0.05f, cy + s * 0.2f);
                c.drawRoundRect(r, 6, 6, p);
                p.setColor(parse(fx, "blade", 0xFFE8ECFF));
                r.set(cx - s * 0.035f, cy - s * 0.36f, cx + s * 0.035f, cy + s * 0.18f);
                c.drawRoundRect(r, 6, 6, p);
                p.setColor(col);
                c.drawRect(cx - s * 0.12f, cy + s * 0.18f, cx + s * 0.12f, cy + s * 0.22f, p);
                p.setColor(0xFF3A2B1A);
                c.drawRect(cx - s * 0.03f, cy + s * 0.22f, cx + s * 0.03f, cy + s * 0.38f, p);
                c.restore();
                break;
            }
            case "equipment":
                path.reset();
                path.moveTo(cx, cy - s * 0.34f);
                path.lineTo(cx + s * 0.28f, cy - s * 0.2f);
                path.quadTo(cx + s * 0.26f, cy + s * 0.22f, cx, cy + s * 0.36f);
                path.quadTo(cx - s * 0.26f, cy + s * 0.22f, cx - s * 0.28f, cy - s * 0.2f);
                path.close();
                p.setColor(0xFF5E6880);
                c.drawPath(path, p);
                p.setColor(rc);
                c.drawCircle(cx, cy, s * 0.09f, p);
                break;
            case "consumable": {
                String id = item.optString("id");
                int liquid = id.contains("health") ? 0xFFFF3B5C : id.contains("energy") ? 0xFFFFD23B : id.contains("shield") ? 0xFF3BD5FF : 0xFF3BE08A;
                p.setColor(0xCCDDE3FF);
                c.drawCircle(cx, cy + s * 0.08f, s * 0.26f, p);
                c.drawRect(cx - s * 0.08f, cy - s * 0.32f, cx + s * 0.08f, cy - s * 0.1f, p);
                p.setColor(liquid);
                c.drawCircle(cx, cy + s * 0.1f, s * 0.21f, p);
                p.setColor(0xFF8A5A2C);
                c.drawRect(cx - s * 0.09f, cy - s * 0.38f, cx + s * 0.09f, cy - s * 0.3f, p);
                p.setColor(0x99FFFFFF);
                c.drawCircle(cx - s * 0.08f, cy + s * 0.02f, s * 0.05f, p);
                break;
            }
            case "chest":
                p.setColor(0xFF8A5A2C);
                r.set(cx - s * 0.32f, cy - s * 0.12f, cx + s * 0.32f, cy + s * 0.28f);
                c.drawRoundRect(r, 8, 8, p);
                p.setColor(0xFFB07A3C);
                r.set(cx - s * 0.32f, cy - s * 0.3f, cx + s * 0.32f, cy - s * 0.08f);
                c.drawRoundRect(r, 14, 14, p);
                p.setColor(0xFFFFC93B);
                c.drawRect(cx - s * 0.05f, cy - s * 0.14f, cx + s * 0.05f, cy + s * 0.04f, p);
                break;
            case "effect":
                p.setColor(rc);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * i / 4;
                    c.drawLine(cx, cy, cx + (float) Math.cos(a) * s * 0.36f, cy + (float) Math.sin(a) * s * 0.36f, strokeOf(rc, s * 0.05f));
                }
                p.setColor(0xFFFFFFFF);
                c.drawCircle(cx, cy, s * 0.1f, p);
                break;
            case "emote":
                p.setColor(0xFFF4F6FF);
                r.set(cx - s * 0.32f, cy - s * 0.24f, cx + s * 0.32f, cy + s * 0.16f);
                c.drawRoundRect(r, 18, 18, p);
                path.reset();
                path.moveTo(cx - s * 0.1f, cy + s * 0.14f);
                path.lineTo(cx - s * 0.2f, cy + s * 0.32f);
                path.lineTo(cx + s * 0.04f, cy + s * 0.14f);
                c.drawPath(path, p);
                text(c, "GG", cx, cy + s * 0.04f, s * 0.22f, 0xFF141A33);
                break;
            case "nameplate":
                p.setColor(parse(fx, "color", rc));
                r.set(cx - s * 0.38f, cy - s * 0.14f, cx + s * 0.38f, cy + s * 0.14f);
                c.drawRoundRect(r, 10, 10, p);
                text(c, "P1", cx, cy + s * 0.07f, s * 0.2f, 0xFF141A33);
                break;
            case "gold":
                p.setColor(0xFFB8860B);
                c.drawCircle(cx, cy, s * 0.3f, p);
                p.setColor(0xFFFFD23B);
                c.drawCircle(cx, cy, s * 0.25f, p);
                text(c, "G", cx, cy + s * 0.1f, s * 0.28f, 0xFFB8860B);
                break;
            case "gems":
                path.reset();
                path.moveTo(cx, cy - s * 0.32f);
                path.lineTo(cx + s * 0.3f, cy - s * 0.08f);
                path.lineTo(cx, cy + s * 0.34f);
                path.lineTo(cx - s * 0.3f, cy - s * 0.08f);
                path.close();
                p.setColor(0xFF3BD5FF);
                c.drawPath(path, p);
                p.setColor(0x88FFFFFF);
                c.drawRect(cx - s * 0.3f, cy - s * 0.1f, cx + s * 0.3f, cy - s * 0.06f, p);
                break;
            default:
                p.setColor(rc);
                c.drawCircle(cx, cy, s * 0.2f, p);
        }
    }

    private Paint strokeOf(int color, float width) {
        Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
        sp.setColor(color);
        sp.setStrokeWidth(width);
        sp.setStrokeCap(Paint.Cap.ROUND);
        return sp;
    }

    private void text(Canvas c, String s, float x, float y, float size, int color) {
        p.setColor(color);
        p.setTextSize(size);
        p.setTextAlign(Paint.Align.CENTER);
        p.setFakeBoldText(true);
        c.drawText(s, x, y, p);
    }

    private static int parse(JSONObject fx, String key, int fallback) {
        try {
            return fx != null && fx.has(key) ? android.graphics.Color.parseColor(fx.getString(key)) : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}

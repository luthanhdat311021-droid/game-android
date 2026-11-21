package com.shadowduel.arena.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import com.shadowduel.arena.game.render.CharacterRenderer;
import com.shadowduel.arena.game.render.Palette;
import com.shadowduel.arena.game.sim.Const;

/** Animated fighter preview used on menus: idle, then a short attack/special loop. */
public class CharacterPreviewView extends View {
    private final CharacterRenderer renderer = new CharacterRenderer();
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String charId = "kaze";
    private long start = System.nanoTime();
    private int facing = 1;
    public boolean showcase = true;

    public CharacterPreviewView(Context c) {
        this(c, null);
    }

    public CharacterPreviewView(Context c, AttributeSet a) {
        super(c, a);
    }

    public void setCharacter(String id) {
        charId = id;
        start = System.nanoTime();
        invalidate();
    }

    public void setSkin(int[] palette) {
        renderer.setSkin(palette);
        invalidate();
    }

    public void setFacing(int f) {
        facing = f;
    }

    @Override
    protected void onDraw(Canvas c) {
        float t = (System.nanoTime() - start) / 1e9f;
        float w = getWidth(), h = getHeight();
        float scale = h / 110f;
        int anim = Const.ANIM_IDLE;
        float p = 0, at = t;
        if (showcase) {
            float cyc = t % 4.2f;
            if (cyc > 2.2f && cyc < 2.8f) {
                anim = Const.ANIM_ATTACK1 + (int) ((cyc - 2.2f) / 0.2f);
                p = ((cyc - 2.2f) % 0.2f) / 0.2f;
            } else if (cyc >= 3.0f && cyc < 3.8f) {
                anim = Const.ANIM_SPECIAL;
                p = (cyc - 3.0f) / 0.8f;
            }
        }
        int accent = Palette.style(charId).accent;
        glow.setShader(new RadialGradient(w / 2, h * 0.6f, Math.min(w, h) * 0.55f,
                Palette.withAlpha(accent, 0.45f), 0x00000000, Shader.TileMode.CLAMP));
        c.drawCircle(w / 2, h * 0.6f, Math.min(w, h) * 0.55f, glow);
        renderer.draw(c, charId, w / 2, h * 0.93f, facing, anim, at, p, 0, 0, 1f, scale);
        if (isShown()) postInvalidateOnAnimation();
    }
}

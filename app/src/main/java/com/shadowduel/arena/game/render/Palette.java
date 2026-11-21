package com.shadowduel.arena.game.render;

import android.graphics.Color;

/** Player and character colours used by the stage, characters and HUD. */
public final class Palette {
    private Palette() {
    }

    /** P1 red, P2 blue, P3 yellow, P4 green, then purple, orange, cyan, pink. */
    public static final int[] PLAYER = {
            0xFFFF2E43, 0xFF2F6BFF, 0xFFFFC21F, 0xFF27D05E, 0xFFB34BFF, 0xFFFF7A1F, 0xFF22D3EE, 0xFFFF4FB3
    };
    public static final int TEAM_RED = 0xFFFF3B4E, TEAM_BLUE = 0xFF2F8BFF;

    public static int player(int slot) {
        return PLAYER[Math.floorMod(slot, PLAYER.length)];
    }

    public static int forFighter(int slot, int team) {
        if (team == 1) return TEAM_RED;
        if (team == 2) return TEAM_BLUE;
        return player(slot);
    }

    /** Character style: skin, hair, outfit main, outfit dark, accent (energy/vfx), accent 2. */
    public static final class Style {
        public final int skin, hair, hairDark, main, dark, accent, accent2, eye;

        Style(int skin, int hair, int hairDark, int main, int dark, int accent, int accent2, int eye) {
            this.skin = skin;
            this.hair = hair;
            this.hairDark = hairDark;
            this.main = main;
            this.dark = dark;
            this.accent = accent;
            this.accent2 = accent2;
            this.eye = eye;
        }
    }

    public static final Style KAZE = new Style(0xFFF6D2B8, 0xFF1C1A33, 0xFF0D0C1A, 0xFF2B2550, 0xFF15122B,
            0xFFB34BFF, 0xFF6A5CFF, 0xFFB34BFF);
    public static final Style RYUJIN = new Style(0xFFE9B48F, 0xFFFF5A1F, 0xFFB0280A, 0xFFB3261E, 0xFF5E120D,
            0xFFFF8A1F, 0xFFFFD23B, 0xFFFFB020);
    public static final Style AKIRA = new Style(0xFFF7D9C2, 0xFFFFE07A, 0xFFD9A93B, 0xFFEDF3FF, 0xFF2A4C8F,
            0xFF3BD5FF, 0xFFFFF27A, 0xFF2F8BFF);

    public static Style style(String charId) {
        if ("ryujin".equals(charId)) return RYUJIN;
        if ("akira".equals(charId)) return AKIRA;
        return KAZE;
    }

    private static final java.util.Map<String, Style> SKINNED = new java.util.HashMap<>();

    /** Base style recoloured with an equipped skin palette [main, dark, accent, hair] (cosmetic only). */
    public static Style skinned(String charId, int[] pal) {
        if (pal == null || pal.length < 4) return style(charId);
        String key = charId + pal[0] + ":" + pal[1] + ":" + pal[2] + ":" + pal[3];
        Style s = SKINNED.get(key);
        if (s == null) {
            Style b = style(charId);
            s = new Style(b.skin, pal[3], mix(pal[3], 0xFF000000, 0.35f), pal[0], pal[1], pal[2], mix(pal[2], 0xFFFFFFFF, 0.4f), pal[2]);
            SKINNED.put(key, s);
        }
        return s;
    }

    public static int withAlpha(int color, float a) {
        return Color.argb(Math.max(0, Math.min(255, (int) (a * 255))), Color.red(color), Color.green(color), Color.blue(color));
    }

    public static int mix(int a, int b, float t) {
        return Color.argb(
                (int) (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }
}

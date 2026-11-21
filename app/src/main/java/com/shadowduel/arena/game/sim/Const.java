package com.shadowduel.arena.game.sim;

/** Mirrors server/src/game/constants.js. */
public final class Const {
    private Const() {
    }

    public static final int BTN_BLOCK = 1, BTN_JUMP_HELD = 2, BTN_JUMP = 4, BTN_ATTACK = 8, BTN_HEAVY = 16,
            BTN_DASH = 32, BTN_SPECIAL = 64;
    public static final int BTN_HELD = BTN_BLOCK | BTN_JUMP_HELD;

    public static final int ACT_NONE = 0, ACT_BASIC = 1, ACT_HEAVY = 2, ACT_SPECIAL = 3, ACT_DASH = 4,
            ACT_BLOCK = 5, ACT_HIT = 6, ACT_DEAD = 7;

    public static final int ANIM_IDLE = 0, ANIM_RUN = 1, ANIM_JUMP = 2, ANIM_FALL = 3, ANIM_ATTACK1 = 4,
            ANIM_ATTACK2 = 5, ANIM_ATTACK3 = 6, ANIM_HEAVY = 7, ANIM_SPECIAL = 8, ANIM_DASH = 9, ANIM_BLOCK = 10,
            ANIM_HIT = 11, ANIM_DEAD = 12, ANIM_SPAWN = 13, ANIM_VICTORY = 14;

    public static final int TEAM_NONE = 0, TEAM_RED = 1, TEAM_BLUE = 2;

    public static final String[] CHARACTERS = {"kaze", "ryujin", "akira"};
    public static final String[] MAPS = {"shadow_temple", "flame_kingdom", "cyber_city"};
    public static final String[] MODES = {"ffa", "tdm", "survival"};
}

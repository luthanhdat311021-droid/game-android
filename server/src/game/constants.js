'use strict';
/**
 * Values shared with the Android client (app/.../game/sim/Const.java).
 * Keep both files in sync — the protocol version guards against mismatches.
 */
const PROTOCOL_VERSION = 1;

/** Input buttons. BLOCK and JUMP_HELD are "held" bits; the others are edge-triggered presses for that tick. */
const BTN = Object.freeze({ BLOCK: 1, JUMP_HELD: 2, JUMP: 4, ATTACK: 8, HEAVY: 16, DASH: 32, SPECIAL: 64 });
const BTN_MASK = 127;

/** Action state machine. */
const ACT = Object.freeze({ NONE: 0, BASIC: 1, HEAVY: 2, SPECIAL: 3, DASH: 4, BLOCK: 5, HIT: 6, DEAD: 7 });

/** Animation state codes sent to clients. */
const ANIM = Object.freeze({
  IDLE: 0, RUN: 1, JUMP: 2, FALL: 3, ATTACK1: 4, ATTACK2: 5, ATTACK3: 6, HEAVY: 7, SPECIAL: 8,
  DASH: 9, BLOCK: 10, HIT: 11, DEAD: 12, SPAWN: 13, VICTORY: 14
});

const TEAM = Object.freeze({ NONE: 0, RED: 1, BLUE: 2 });
const MODES = ['ffa', 'tdm', 'survival'];
const MAPS = ['shadow_temple', 'flame_kingdom', 'cyber_city'];
const CHARACTERS = ['kaze', 'ryujin', 'akira'];

module.exports = { PROTOCOL_VERSION, BTN, BTN_MASK, ACT, ANIM, TEAM, MODES, MAPS, CHARACTERS };

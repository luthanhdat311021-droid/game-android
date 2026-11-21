'use strict';
/**
 * Stat bonuses from equipped items (weapon of the chosen character + equipment that fits everyone).
 * Item rows carry gameplay_effect.stats = { attack_pct, defense_pct, hp_pct, speed_pct }.
 * The server applies them when a match starts; the client only mirrors them (HUD + prediction).
 * Each stat is capped so gear helps but never decides a fight on its own.
 */
const MAX_PCT = 30;
const KEYS = ['attack_pct', 'defense_pct', 'hp_pct', 'speed_pct'];

function computeStats(look) {
  const out = { attack_pct: 0, defense_pct: 0, hp_pct: 0, speed_pct: 0 };
  for (const slot of ['weapon', 'equipment']) {
    const s = look && look[slot] && look[slot].stats;
    if (!s) continue;
    for (const k of KEYS) if (Number.isFinite(s[k])) out[k] += s[k];
  }
  for (const k of KEYS) out[k] = Math.max(0, Math.min(MAX_PCT, Math.round(out[k])));
  return out;
}

module.exports = { computeStats, MAX_PCT };

'use strict';
/** Simple arena bot: chase nearest enemy, jump obstacles/gaps, combo, block, use specials. */
const { BTN, ACT } = require('./constants');
const { groundBelow } = require('./PhysicsSystem');

function rng(seed) {
  let s = seed >>> 0;
  return () => {
    s = (s + 0x6D2B79F5) >>> 0;
    let t = s;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

class BotBrain {
  constructor(fighter, seed, level = 0.6) {
    this.f = fighter;
    this.rand = rng(seed);
    this.level = level;             // 0 = sleepy, 1 = sharp
    this.target = null;
    this.retarget = 0;
    this.attackCd = 0;
    this.blockT = 0;
    this.jumpCd = 0;
  }

  enemies(world) {
    return world.fighters.filter(o => o !== this.f && o.alive && !(world.teams && o.team === this.f.team));
  }

  decide(world) {
    const f = this.f, map = world.map, r = this.rand;
    const inp = { mx: 0, my: 0, b: 0 };
    if (!f.alive || f.act === ACT.HIT) return inp;
    if (this.attackCd > 0) this.attackCd--;
    if (this.jumpCd > 0) this.jumpCd--;

    if (--this.retarget <= 0 || !this.target || !this.target.alive) {
      const list = this.enemies(world);
      let best = null, bd = 1e9;
      for (const o of list) {
        const d = Math.abs(o.x - f.x) + Math.abs(o.y - f.y) * 1.5 + (o.protectT > 0 ? 600 : 0);
        if (d < bd) { bd = d; best = o; }
      }
      this.target = best;
      this.retarget = 15 + Math.floor(r() * 15);
    }

    let goalX = map.pixelW / 2, goalY = f.y;
    const zone = world.mode.zone;
    const t = this.target;
    if (t) { goalX = t.x; goalY = t.y; }
    if (zone && (f.x < zone[0] + 60 || f.x > zone[1] - 60)) goalX = (zone[0] + zone[1]) / 2;

    const dx = goalX - f.x, dy = goalY - f.y, adx = Math.abs(dx);
    const reach = 72;

    if (this.blockT > 0) {
      this.blockT--;
      inp.b |= BTN.BLOCK;
      return inp;
    }

    // keep distance logic: approach unless in reach
    if (adx > reach - 10) inp.mx = Math.sign(dx) * 100;
    else if (t && Math.sign(dx) !== f.facing && adx > 4) inp.mx = Math.sign(dx) * 40;

    // navigation: walls, gaps, targets above
    const dir = inp.mx !== 0 ? Math.sign(inp.mx) : f.facing;
    const aheadCol = Math.floor((f.x + dir * (f.w / 2 + 14)) / map.T);
    const midRow = Math.floor((f.y - f.h / 2) / map.T);
    const lowRow = Math.floor((f.y - 8) / map.T);
    const wall = map.solid(aheadCol, midRow) || map.solid(aheadCol, lowRow);
    const gap = f.grounded && inp.mx !== 0 && !groundBelow(map, f.x + dir * 44, f.y, 10);
    const hazardAhead = map.hazard(aheadCol, lowRow) || map.hazard(aheadCol, lowRow + 1);
    if (this.jumpCd === 0) {
      if (f.grounded && (wall || gap || hazardAhead || (dy < -80 && adx < 280))) {
        inp.b |= BTN.JUMP; this.jumpCd = 8;
      } else if (!f.grounded && f.vy > 150 && f.jumps > 0 && !groundBelow(map, f.x, f.y, 12)) {
        inp.b |= BTN.JUMP; this.jumpCd = 10;                    // recover from pits
      } else if (f.grounded && f.groundOneWay && dy > 90 && adx < 200 && r() < 0.1) {
        inp.my = 1; inp.b |= BTN.JUMP; this.jumpCd = 10;         // drop through platform
      }
    }

    if (!t) return inp;
    const tdx = t.x - f.x, tdy = t.y - f.y, tadx = Math.abs(tdx);
    const sp = f.def.special;

    // defend against an attacker that is close and swinging
    if (tadx < 120 && Math.abs(tdy) < 60 && t.isAttacking() && r() < 0.18 * this.level) {
      this.blockT = 6 + Math.floor(r() * 6);
      inp.b |= BTN.BLOCK;
      return inp;
    }

    if (this.attackCd === 0 && f.act !== ACT.DASH) {
      const ranged = (f.def.heavy.type === 'projectile' || sp.type === 'projectile') && Math.abs(tdy) < 50 && tadx > 180 && tadx < 560;
      if (tadx <= reach + 12 && Math.abs(tdy) < 70) {
        inp.mx = Math.sign(tdx) * 10 || f.facing * 10;
        const roll = r();
        if (f.energy >= sp.cost && f.specialCd === 0 && roll < 0.3) inp.b |= BTN.SPECIAL;
        else if (roll < 0.45) inp.b |= BTN.HEAVY;
        else inp.b |= BTN.ATTACK;
        this.attackCd = 3 + Math.floor(r() * 12 * (1.2 - this.level));
      } else if (ranged && Math.sign(tdx) === f.facing && r() < 0.25) {
        if (sp.type === 'projectile' && f.energy >= sp.cost && f.specialCd === 0) inp.b |= BTN.SPECIAL;
        else if (f.def.heavy.type === 'projectile') inp.b |= BTN.HEAVY;
        this.attackCd = 12 + Math.floor(r() * 12);
      } else if (sp.type === 'dash_slash' && tadx < 320 && Math.abs(tdy) < 40 && Math.sign(tdx) === f.facing &&
                 f.energy >= sp.cost && f.specialCd === 0 && r() < 0.2) {
        inp.b |= BTN.SPECIAL;
        this.attackCd = 15;
      } else if (tadx > 420 && f.dashCd === 0 && f.grounded && r() < 0.03) {
        inp.b |= BTN.DASH;
      }
    }
    return inp;
  }
}

module.exports = { BotBrain, rng };

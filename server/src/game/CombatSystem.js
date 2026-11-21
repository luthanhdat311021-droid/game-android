'use strict';
/**
 * Server-side hit detection and damage. Supports any number of targets:
 * every attack instance keeps the set of fighters it already hit, so one swing,
 * projectile or zone pulse can never damage the same target twice.
 */
const { ACT } = require('./constants');
const { rectsOverlap } = require('./PhysicsSystem');

const PROJ_STEP = 16;

class CombatSystem {
  constructor(world) {
    this.world = world;
    this.P = world.P;
  }

  canHit(attacker, target, ownerTeam) {
    if (!target.alive || target.protectT > 0 || target.isIntangible()) return false;
    if (attacker && target.id === attacker.id) return false;
    if (!this.world.friendlyFire && ownerTeam && target.team === ownerTeam) return false;
    return true;
  }

  /** World-space hitbox of a move, mirrored by facing. */
  hitbox(f, box) {
    const [ox, oy, w, h] = box;
    const x = f.facing > 0 ? f.x + ox : f.x - ox - w;
    return [x, f.y + oy, w, h];
  }

  bodyOf(f) { return [f.x - f.w / 2, f.y - f.h, f.w, f.h]; }

  update() {
    const w = this.world;
    for (const f of w.fighters) {
      if (!f.alive || !f.isAttacking()) continue;
      const mv = f.move();
      const first = f.actT === mv.startup + 1;
      const active = f.actT > mv.startup && f.actT <= mv.startup + mv.active;
      const kind = f.act === ACT.SPECIAL ? 'special' : f.act === ACT.HEAVY ? 'heavy' : 'basic';
      if ((mv.type === undefined || mv.type === 'melee' || mv.type === 'dash_slash') && active) {
        const hb = this.hitbox(f, mv.box);
        for (const t of w.fighters) {
          if (f.hits.includes(t.id) || !this.canHit(f, t, f.team)) continue;
          if (rectsOverlap(...hb, ...this.bodyOf(t))) {
            f.hits.push(t.id);
            const dir = t.x === f.x ? f.facing : Math.sign(t.x - f.x);
            this.applyHit(f.id, t, mv, dir, kind + ':' + f.charId);
          }
        }
      }
      if (first && mv.type === 'projectile') this.spawnProjectile(f, mv);
      if (first && mv.type === 'zone') this.spawnZone(f, mv);
    }
    this.updateProjectiles();
    this.updateZones();
  }

  spawnProjectile(f, mv) {
    const p = mv.proj;
    this.world.projectiles.push({
      id: this.world.nextInst(), owner: f.id, team: f.team, kind: p.kind, move: mv,
      x: f.x + f.facing * (f.w / 2 + p.w / 2), y: f.y + p.offY, vx: f.facing * p.speed,
      w: p.w, h: p.h, life: p.life, pierce: p.pierce, hits: [], dead: false
    });
  }

  spawnZone(f, mv) {
    const z = mv.zone;
    const cx = f.x + f.facing * z.offX;
    this.world.zones.push({
      id: this.world.nextInst(), owner: f.id, team: f.team, kind: z.kind, move: mv,
      x: cx - z.w / 2, y: f.y - z.h, w: z.w, h: z.h, life: z.life, interval: z.interval, last: new Map()
    });
  }

  updateProjectiles() {
    const w = this.world;
    for (const p of w.projectiles) {
      const owner = w.byId.get(p.owner);
      const dist = Math.abs(p.vx * w.dt);
      const steps = Math.max(1, Math.ceil(dist / PROJ_STEP));
      for (let s = 0; s < steps && !p.dead; s++) {
        p.x += (p.vx * w.dt) / steps;
        const col = Math.floor(p.x / w.map.T), row = Math.floor(p.y / w.map.T);
        if (w.map.solid(col, row) || p.x < 0 || p.x > w.map.pixelW) { p.dead = true; break; }
        for (const t of w.fighters) {
          if (p.hits.includes(t.id) || !this.canHit(owner || { id: p.owner }, t, p.team)) continue;
          if (rectsOverlap(p.x - p.w / 2, p.y - p.h / 2, p.w, p.h, ...this.bodyOf(t))) {
            p.hits.push(t.id);
            this.applyHit(p.owner, t, p.move, Math.sign(p.vx), p.kind);
            if (!p.pierce) { p.dead = true; break; }
          }
        }
      }
      if (--p.life <= 0) p.dead = true;
    }
    w.projectiles = w.projectiles.filter(p => !p.dead);
  }

  updateZones() {
    const w = this.world;
    for (const z of w.zones) {
      const owner = w.byId.get(z.owner);
      for (const t of w.fighters) {
        if (!this.canHit(owner || { id: z.owner }, t, z.team)) continue;
        const last = z.last.get(t.id);
        if (last !== undefined && w.tick - last < z.interval) continue;
        if (rectsOverlap(z.x, z.y, z.w, z.h, ...this.bodyOf(t))) {
          z.last.set(t.id, w.tick);
          const dir = Math.sign(t.x - (z.x + z.w / 2)) || 1;
          this.applyHit(z.owner, t, z.move, dir, z.kind);
        }
      }
      z.life--;
    }
    w.zones = w.zones.filter(z => z.life > 0);
  }

  /** Apply one hit. attackerId may belong to a fighter that has since died or left. */
  applyHit(attackerId, t, mv, dir, kind) {
    const w = this.world, P = this.P;
    const a = w.byId.get(attackerId);
    const atk = a ? a.attack : 100;
    let dmg = Math.round((mv.dmg * atk / 100) * (100 / (100 + t.defense)));
    const blocked = t.act === ACT.BLOCK;
    if (blocked) dmg = Math.max(1, Math.round(dmg * P.blockDamage));
    dmg = Math.min(dmg, t.hp);
    t.hp -= dmg;
    t.dmgTaken += dmg;
    t.energy = Math.min(P.energyMax, t.energy + dmg * P.energyPerDamageTaken);
    if (a) {
      a.dmgDealt += dmg;
      a.energy = Math.min(P.energyMax, a.energy + dmg * P.energyPerDamageDealt);
    }
    t.dmgLog.set(attackerId, w.tick);
    t.lastHitBy = attackerId; t.lastHitTick = w.tick;
    w.emit({ e: 'hit', a: attackerId, v: t.id, d: dmg, x: Math.round(t.x), y: Math.round(t.y - t.h / 2), b: blocked ? 1 : 0, k: kind });
    if (t.hp <= 0) {
      w.kill(t, attackerId, kind);
      return;
    }
    if (blocked) {
      t.vx = mv.kb[0] * dir * P.blockKnockback;
    } else {
      t.act = ACT.HIT; t.stunT = mv.stun; t.combo = 0; t.queued = 0;
      t.vx = mv.kb[0] * dir; t.vy = mv.kb[1];
      if (mv.kb[1] < 0) { t.grounded = false; t.onMover = -1; }
      t.facing = -dir;
    }
  }
}

module.exports = { CombatSystem };

'use strict';
/** Chooses spawn points: initial spread by slot, respawn at the point farthest from living enemies. */
class SpawnSystem {
  constructor(world) { this.world = world; }

  initial(f, index, count) {
    const sp = this.world.map.spawns;
    const step = sp.length / Math.max(1, count);
    this.place(f, sp[Math.floor(index * step) % sp.length]);
  }

  respawnPoint(f) {
    const sp = this.world.map.spawns;
    let best = null, bestScore = -1;
    for (let i = 0; i < sp.length; i++) {
      const s = sp[(i + this.world.tick) % sp.length];
      let nearest = 1e9;
      for (const o of this.world.fighters) {
        if (o === f || !o.alive) continue;
        if (this.world.teams && o.team === f.team) continue;
        nearest = Math.min(nearest, Math.hypot(o.x - s.x, o.y - s.y));
      }
      if (nearest > bestScore) { bestScore = nearest; best = s; }
    }
    return best;
  }

  place(f, s) {
    f.x = s.x; f.y = s.y; f.vx = 0; f.vy = 0;
    f.grounded = false; f.onMover = -1; f.dropT = 0;
    f.facing = s.x < this.world.map.pixelW / 2 ? 1 : -1;
  }

  respawn(f) {
    const P = this.world.P;
    this.place(f, this.respawnPoint(f));
    f.alive = true; f.hp = f.maxHp; f.energy = Math.max(f.energy, 25);
    f.act = 0; f.actT = 0; f.combo = 0; f.stunT = 0; f.queued = 0;
    f.protectT = P.protectTicks; f.respawnT = 0; f.dmgLog.clear();
    f.lastHitBy = null; f.lastHitTick = -9999;
    this.world.emit({ e: 'spawn', p: f.id, x: Math.round(f.x), y: Math.round(f.y) });
  }
}

module.exports = { SpawnSystem };

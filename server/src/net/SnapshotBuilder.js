'use strict';
/**
 * Builds per-client world snapshots with:
 *  - Interest management: fighters near the viewer are sent every snapshot,
 *    far ones only every FAR_EVERY snapshots (enough for minimap/scoreboard).
 *    Projectiles, zones and hit effects are only sent when nearby. Kills, spawns
 *    and the scoreboard always go to everybody.
 *  - Delta compression: a field is only sent when it changed since the last
 *    snapshot delivered to that client (WebSocket is ordered + reliable).
 */
const FAR_EVERY = 6;

const r1 = v => Math.round(v * 10) / 10;

/** Full replicated state of one fighter (short keys keep packets small). */
function fighterState(f) {
  return {
    x: r1(f.x), y: r1(f.y), vx: r1(f.vx), vy: r1(f.vy), f: f.facing, hp: f.hp, en: r1(f.energy),
    st: f.anim(), a: f.act, at: f.actT, cb: f.combo, q: f.queued, sn: f.stunT, dc: f.dashCd, sc: f.specialCd,
    dr: f.dropT, g: f.grounded ? 1 : 0, go: f.groundOneWay ? 1 : 0, mv: f.onMover, j: f.jumps, ai: f.airT,
    al: f.alive ? 1 : 0, rs: f.respawnT, pr: f.protectT
  };
}

class ClientView {
  constructor() {
    this.known = new Map(); // fighter id -> last sent state
    this.count = 0;
    this.lastScoreboard = '';
    this.lastX = 0; this.lastY = 0;
  }
}

class SnapshotBuilder {
  constructor(cfg) {
    this.rangeX = cfg.interestRangeX;
    this.rangeY = cfg.interestRangeY;
  }

  near(view, x, y) {
    return Math.abs(x - view.lastX) <= this.rangeX && Math.abs(y - view.lastY) <= this.rangeY;
  }

  /**
   * @param world GameWorld
   * @param view ClientView for this recipient
   * @param self fighter controlled by the recipient (may be null for spectators)
   * @param ack last input sequence processed for this recipient
   * @param events events accumulated since the previous snapshot
   * @param scoreboardJson pre-serialised scoreboard
   */
  build(world, view, self, ack, events, scoreboardJson, gone) {
    view.count++;
    if (self && self.alive) { view.lastX = self.x; view.lastY = self.y; }
    const fullRate = view.count % FAR_EVERY === 0;
    const players = [];
    for (const f of world.fighters) {
      const isNear = f === self || this.near(view, f.x, f.y);
      if (!isNear && !fullRate && view.known.has(f.id)) continue;
      const cur = fighterState(f);
      const prev = view.known.get(f.id);
      const d = { i: f.id };
      let changed = false;
      for (const k in cur) {
        if (!prev || prev[k] !== cur[k]) { d[k] = cur[k]; changed = true; }
      }
      view.known.set(f.id, cur);
      if (changed || !prev) players.push(d);
    }
    const snap = {
      t: 'snap', tick: world.tick, ms: Date.now(), ack, tl: world.mode.timeLeft(), p: players
    };
    const pr = [];
    for (const p of world.projectiles) {
      if (this.near(view, p.x, p.y)) pr.push([p.id, p.kind, Math.round(p.x), Math.round(p.y), Math.round(p.vx), p.owner]);
    }
    if (pr.length) snap.pr = pr;
    const zn = [];
    for (const z of world.zones) {
      if (this.near(view, z.x + z.w / 2, z.y)) zn.push([z.id, z.kind, Math.round(z.x), Math.round(z.y), z.w, z.h, z.life, z.owner]);
    }
    if (zn.length) snap.z = zn;
    const ev = events.filter(e => e.e !== 'hit' || this.near(view, e.x, e.y));
    if (ev.length) snap.ev = ev;
    if (scoreboardJson !== view.lastScoreboard) {
      snap.sb = JSON.parse(scoreboardJson);
      view.lastScoreboard = scoreboardJson;
    }
    if (world.teams) snap.ts = [world.mode.teamScore[1], world.mode.teamScore[2]];
    if (world.mode.zone) snap.zn = world.mode.zone;
    if (gone && gone.length) {
      snap.gone = gone;
      for (const id of gone) view.known.delete(id);
    }
    return snap;
  }
}

module.exports = { SnapshotBuilder, ClientView, fighterState };

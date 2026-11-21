'use strict';
/**
 * Independent simulation of one match. One GameWorld per room; nothing is shared
 * between worlds except read-only map/character definitions.
 */
const { loadMap, loadDefs } = require('./MapLoader');
const { Fighter } = require('./Fighter');
const { CombatSystem } = require('./CombatSystem');
const { SpawnSystem } = require('./SpawnSystem');
const { GameModeSystem } = require('./GameModeSystem');
const { BotBrain } = require('./BotBrain');
const { inHazard } = require('./PhysicsSystem');
const { ACT } = require('./constants');

const NEUTRAL = Object.freeze({ mx: 0, my: 0, b: 0 });

class GameWorld {
  /**
   * @param {object} o { mapId, mode, players: [{id,name,charId,team,isBot,userId}], seed, durationSec, friendlyFire }
   */
  constructor(o) {
    this.defs = loadDefs();
    this.P = this.defs.physics;
    this.tickRate = this.P.tickRate;
    this.dt = 1 / this.tickRate;
    this.map = loadMap(o.mapId);
    this.modeId = o.mode;
    const modeCfg = this.defs.modes[o.mode];
    if (!modeCfg) throw new Error('unknown mode ' + o.mode);
    this.teams = !!modeCfg.teams;
    this.respawns = !!modeCfg.respawn;
    this.friendlyFire = o.friendlyFire === true || !this.teams;
    this.tick = 0;
    this.inst = 0;
    this.events = [];
    this.projectiles = [];
    this.zones = [];
    this.fighters = [];
    this.byId = new Map();
    this.bots = new Map();
    this.seed = o.seed || 1;
    this.combat = new CombatSystem(this);
    this.spawner = new SpawnSystem(this);
    this.mode = new GameModeSystem(this, o.mode, modeCfg, o.durationSec);
    o.players.forEach((p, i) => this.addFighter(p, i, o.players.length));
  }

  addFighter(p, index, count) {
    const f = new Fighter({ ...p, slot: p.slot != null ? p.slot : index }, this.defs);
    this.fighters.push(f);
    this.byId.set(f.id, f);
    if (f.isBot) this.bots.set(f.id, new BotBrain(f, this.seed * 31 + index * 7919 + 1, p.botLevel || 0.6));
    this.spawner.initial(f, index, count == null ? this.fighters.length : count);
    f.protectT = this.P.protectTicks;
    f.energy = 25;
    return f;
  }

  removeFighter(id) {
    const f = this.byId.get(id);
    if (!f) return;
    this.fighters = this.fighters.filter(x => x !== f);
    this.byId.delete(id);
    this.bots.delete(id);
  }

  nextInst() { return ++this.inst; }
  emit(ev) { ev.tk = this.tick; this.events.push(ev); }

  /**
   * Advance the simulation one fixed tick.
   * @param {Map<string,{mx,my,b}>} inputs input for each human fighter this tick
   * @returns match result when the match just ended, else null
   */
  step(inputs) {
    this.events = [];
    if (this.mode.result) return null;
    this.tick++;
    this.map.updateMovers(this.tick);

    for (const f of this.fighters) {
      if (!f.alive) {
        if (this.respawns && f.respawnT > 0 && --f.respawnT === 0) this.spawner.respawn(f);
        continue;
      }
      const bot = this.bots.get(f.id);
      const input = bot ? bot.decide(this) : (inputs && inputs.get(f.id)) || NEUTRAL;
      f.step(input, this);
    }

    this.combat.update();
    this.environment();
    return this.mode.update();
  }

  environment() {
    const P = this.P;
    for (const f of this.fighters) {
      if (!f.alive) continue;
      if (f.y > this.map.killY) {
        this.kill(f, this.creditFor(f), 'fall');
        continue;
      }
      if (inHazard(f, this.map)) {
        if (f.hazardT <= 0) {
          f.hazardT = P.hazardIntervalTicks;
          this.environmentDamage(f, Math.round(f.maxHp * P.hazardDamagePct), 'hazard');
          if (f.alive && f.act !== ACT.HIT) { f.vy = Math.min(f.vy, -420); f.grounded = false; f.onMover = -1; }
        } else f.hazardT--;
      } else if (f.hazardT > 0) f.hazardT--;
    }
  }

  creditFor(f) {
    return f.lastHitBy != null && this.tick - f.lastHitTick <= this.P.fallCreditTicks ? f.lastHitBy : null;
  }

  environmentDamage(f, dmg, cause) {
    if (f.protectT > 0 && cause !== 'zone') return;
    dmg = Math.min(dmg, f.hp);
    f.hp -= dmg;
    f.dmgTaken += dmg;
    this.emit({ e: 'hit', a: null, v: f.id, d: dmg, x: Math.round(f.x), y: Math.round(f.y - f.h / 2), b: 0, k: cause });
    if (f.hp <= 0) this.kill(f, this.creditFor(f), cause);
  }

  /** Death, kill credit and assist credit. */
  kill(victim, killerId, cause) {
    if (!victim.alive) return;
    const P = this.P;
    victim.alive = false; victim.hp = 0; victim.act = ACT.DEAD; victim.vx = 0; victim.vy = 0;
    victim.deaths++;
    victim.respawnT = this.respawns ? P.respawnTicks : 0;
    const killer = killerId != null && killerId !== victim.id ? this.byId.get(killerId) : null;
    if (killer) { killer.kills++; }
    const assists = [];
    for (const [id, t] of victim.dmgLog) {
      if (id === killerId || id === victim.id || this.tick - t > P.assistWindowTicks) continue;
      const a = this.byId.get(id);
      if (a && !(this.teams && a.team === victim.team)) { a.assists++; assists.push(id); }
    }
    victim.dmgLog.clear();
    this.mode.onKill(victim, killer);
    this.emit({ e: 'kill', k: killer ? killer.id : null, v: victim.id, as: assists, c: cause,
      x: Math.round(victim.x), y: Math.round(Math.min(victim.y, this.map.pixelH)) });
  }

  /** Public scoreboard rows: [id, kills, deaths, assists, damage, score]. */
  scoreboard() {
    return this.fighters.map(f => [f.id, f.kills, f.deaths, f.assists, f.dmgDealt, f.kills]);
  }
}

module.exports = { GameWorld, NEUTRAL };

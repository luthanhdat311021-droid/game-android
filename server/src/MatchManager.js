'use strict';
/**
 * Runs every active match on one fixed-timestep loop.
 * Each Match owns an independent GameWorld; snapshots only go to that room's members.
 */
const crypto = require('crypto');
const log = require('./log');
const { GameWorld } = require('./game/GameWorld');
const { BTN, BTN_MASK, PROTOCOL_VERSION } = require('./game/constants');
const { SnapshotBuilder, ClientView } = require('./net/SnapshotBuilder');
const { STATUS } = require('./RoomManager');

const HELD = BTN.BLOCK | BTN.JUMP_HELD;
const QUEUE_MAX = 8;

class Match {
  constructor(room, cfg) {
    this.room = room;
    this.id = crypto.randomUUID();
    this.startedAt = new Date();
    this.nextFighterId = 0;
    this.byPlayer = new Map(); // playerId -> { fid, queue, lastSeq, held, mx, my, view }
    this.leavers = [];
    const members = [...room.members.values()].sort((a, b) => a.slot - b.slot);
    const players = members.map(m => this.fighterSpec(m));
    this.world = new GameWorld({
      mapId: room.mapId, mode: room.mode, players,
      seed: crypto.randomInt(1, 1 << 30), friendlyFire: room.friendlyFire,
      durationSec: cfg.matchDurationOverrideSec
    });
    this.gone = [];
    this.result = null;
  }

  fighterSpec(m) {
    const fid = this.nextFighterId++;
    this.byPlayer.set(m.playerId, {
      fid, member: m, queue: [], lastSeq: 0, mx: 0, my: 0, held: 0, view: new ClientView(), pending: []
    });
    const look = m.cosmetics ? (m.cosmetics()[m.charId] || {}) : {};
    this.looks = this.looks || new Map();
    this.looks.set(fid, look);
    return { id: fid, name: m.name, charId: m.charId, team: m.team, isBot: m.isBot, userId: m.userId, slot: m.slot };
  }

  roster() {
    // cosmetics only change how a fighter looks; stats are always the standard character stats
    return this.world.fighters.map(f => ({ id: f.id, name: f.name, charId: f.charId, team: f.team, isBot: f.isBot, slot: f.slot,
      skin: (this.looks.get(f.id) || {}).skin || null, weapon: (this.looks.get(f.id) || {}).weapon || null }));
  }

  startMessage(playerId) {
    const me = this.byPlayer.get(playerId);
    return {
      t: 'match_start', v: PROTOCOL_VERSION, matchId: this.id, roomCode: this.room.code, mode: this.room.mode,
      map: this.room.mapId, tickRate: this.world.tickRate, tick: this.world.tick,
      duration: this.world.mode.durationTicks, you: me ? me.fid : -1, friendlyFire: this.world.friendlyFire,
      players: this.roster()
    };
  }

  /** Validated input from a client. */
  pushInput(playerId, msg) {
    const p = this.byPlayer.get(playerId);
    if (!p) return false;
    const seq = msg.s;
    if (!Number.isInteger(seq) || seq <= p.lastSeq || seq > p.lastSeq + 1000) return false;
    const mx = Number.isInteger(msg.mx) ? Math.max(-100, Math.min(100, msg.mx)) : 0;
    const my = msg.my === 1 ? 1 : msg.my === -1 ? -1 : 0;
    const b = Number.isInteger(msg.b) ? msg.b & BTN_MASK : 0;
    p.lastSeq = seq;
    if (p.queue.length >= QUEUE_MAX) {
      // never lose a button press: fold the oldest input into the next one
      const old = p.queue.shift();
      p.queue[0].b |= old.b & ~HELD;
    }
    p.queue.push({ s: seq, mx, my, b });
    return true;
  }

  collectInputs() {
    const inputs = new Map();
    for (const p of this.byPlayer.values()) {
      if (p.member.isBot) continue;
      let inp = p.queue.shift();
      if (inp && p.queue.length > 3) {
        // client is ahead (clock drift / burst after lag): consume one extra input
        const extra = p.queue.shift();
        extra.b |= inp.b & ~HELD;
        inp = extra;
      }
      if (inp) {
        p.mx = inp.mx; p.my = inp.my; p.held = inp.b & HELD; p.ack = inp.s;
        inputs.set(p.fid, inp);
      } else {
        // no packet this tick: keep holding the last direction but never repeat presses
        inputs.set(p.fid, { mx: p.mx, my: p.my, b: p.held });
      }
    }
    return inputs;
  }
}

class MatchManager {
  constructor(cfg, rooms, sessions, results) {
    this.cfg = cfg;
    this.rooms = rooms;
    this.sessions = sessions;
    this.results = results;
    this.snapshots = new SnapshotBuilder(cfg);
    this.matches = new Set();
    this.timer = null;
    this.acc = 0;
    this.last = 0;
    this.stats = { ticks: 0, maxTickMs: 0 };
  }

  begin(room) {
    room.status = STATUS.STARTING;
    this.rooms.changed(room);
    this.rooms.broadcast(room, { t: 'countdown', ms: this.cfg.countdownMs });
    setTimeout(() => {
      if (!this.rooms.rooms.has(room.code) || room.status !== STATUS.STARTING) return;
      if (room.members.size < 1) { room.status = STATUS.LOBBY; return; }
      const match = new Match(room, this.cfg);
      room.match = match;
      room.status = STATUS.IN_MATCH;
      this.matches.add(match);
      log.info('match start', room.code, match.id, room.mode, room.mapId, room.members.size + ' fighters');
      for (const m of room.members.values()) {
        if (!m.isBot) this.sessions.send(this.sessions.byPlayer.get(m.playerId), match.startMessage(m.playerId));
      }
      this.rooms.changed(room);
      this.results.matchStarted(match).catch(() => {});
      this.ensureLoop();
    }, this.cfg.countdownMs);
  }

  ensureLoop() {
    if (this.timer) return;
    this.last = performance.now();
    this.acc = 0;
    const dtMs = 1000 / 30;
    this.timer = setInterval(() => {
      const now = performance.now();
      this.acc += Math.min(250, now - this.last);
      this.last = now;
      while (this.acc >= dtMs) {
        this.acc -= dtMs;
        this.tickAll();
      }
      if (this.matches.size === 0) { clearInterval(this.timer); this.timer = null; }
    }, 1000 / 60);
  }

  tickAll() {
    const t0 = performance.now();
    for (const match of [...this.matches]) {
      try {
        this.tickMatch(match);
      } catch (e) {
        log.error('match tick crashed', match.room.code, e.stack);
        this.abort(match.room);
      }
    }
    this.stats.ticks++;
    this.stats.maxTickMs = Math.max(this.stats.maxTickMs, performance.now() - t0);
  }

  tickMatch(match) {
    const w = match.world;
    const result = w.step(match.collectInputs());
    for (const p of match.byPlayer.values()) if (w.events.length) p.pending.push(...w.events);
    const sendNow = result || w.tick % this.cfg.snapshotEvery === 0;
    if (sendNow) this.sendSnapshots(match);
    if (result) this.finish(match, result);
  }

  sendSnapshots(match) {
    const w = match.world;
    const sb = JSON.stringify(w.scoreboard());
    const gone = match.gone;
    match.gone = [];
    for (const [playerId, p] of match.byPlayer) {
      if (p.member.isBot) continue;
      const s = this.sessions.byPlayer.get(playerId);
      if (!s || !s.ws || s.ws.readyState !== 1) continue;
      // throttle a client whose socket is backed up; it gets a delta next time
      if (s.ws.bufferedAmount > 256 * 1024) {
        if (gone.length) p.pendingGone = (p.pendingGone || []).concat(gone);
        continue;
      }
      const g = (p.pendingGone || []).concat(gone);
      p.pendingGone = null;
      const snap = this.snapshots.build(w, p.view, w.byId.get(p.fid), p.ack || 0, p.pending, sb, g);
      p.pending = [];
      s.ws.send(JSON.stringify(snap));
    }
  }

  joinInProgress(room, member) {
    const match = room.match;
    const spec = match.fighterSpec(member);
    match.world.addFighter(spec, match.world.fighters.length, match.world.fighters.length + 1);
    const f = match.world.byId.get(spec.id);
    match.world.spawner.respawn(f);
    for (const m of room.members.values()) {
      if (m.isBot) continue;
      const s = this.sessions.byPlayer.get(m.playerId);
      if (m === member) this.sessions.send(s, match.startMessage(m.playerId));
      else this.sessions.send(s, { t: 'roster', players: match.roster() });
    }
  }

  playerLeft(room, playerId) {
    const match = room.match;
    const p = match.byPlayer.get(playerId);
    if (!p) return;
    const f = match.world.byId.get(p.fid);
    if (f) {
      match.leavers.push({
        id: f.id, userId: f.userId, name: f.name, isBot: false, slot: f.slot, team: f.team, charId: f.charId,
        kills: f.kills, deaths: f.deaths, assists: f.assists, damageDealt: f.dmgDealt, damageTaken: f.dmgTaken,
        score: f.kills, placement: 0, left: true
      });
      match.world.removeFighter(f.id);
      match.world.emit({ e: 'left', p: f.id });
      for (const q of match.byPlayer.values()) q.pending.push({ e: 'left', p: f.id, tk: match.world.tick });
      match.gone.push(f.id);
    }
    match.byPlayer.delete(playerId);
    // nobody left to fight → end the match early
    if (match.world.fighters.filter(x => !x.isBot).length === 0) this.abort(room);
  }

  /** Re-sends match state after a reconnect. */
  resync(room, session) {
    const match = room.match;
    if (!match) return;
    const p = match.byPlayer.get(session.playerId);
    if (!p) return;
    p.view = new ClientView(); // next snapshot is a full one
    p.queue = [];
    this.sessions.send(session, Object.assign(match.startMessage(session.playerId), { resumed: true, lastSeq: p.lastSeq }));
  }

  finish(match, result) {
    this.matches.delete(match);
    const room = match.room;
    const total = result.standings.length + match.leavers.length;
    for (const l of match.leavers) l.placement = total;
    result.standings.push(...match.leavers);
    match.result = result;
    room.status = STATUS.RESULTS;
    log.info('match end', room.code, match.id, result.reason, 'winners', result.winners.join(','));
    this.rooms.broadcast(room, { t: 'match_end', matchId: match.id, result, mode: room.mode, map: room.mapId });
    this.rooms.changed(room);
    this.results.save(match, result).then(changes => {
      if (changes) this.rooms.broadcast(room, { t: 'rating', matchId: match.id, changes });
    }).catch(e => log.error('result save failed permanently', match.id, e.message));
    setTimeout(() => this.backToLobby(room, match), this.cfg.resultsMs);
  }

  backToLobby(room, match) {
    if (room.match !== match || !this.rooms.rooms.has(room.code)) return;
    room.match = null;
    room.status = STATUS.LOBBY;
    for (const m of room.members.values()) if (!m.isBot) m.ready = false;
    this.rooms.changed(room);
  }

  abort(room) {
    const match = room.match;
    if (!match) return;
    this.matches.delete(match);
    room.match = null;
    room.status = STATUS.LOBBY;
    this.rooms.broadcast(room, { t: 'match_aborted', matchId: match.id });
    log.info('match aborted', room.code, match.id);
  }
}

module.exports = { MatchManager, Match };

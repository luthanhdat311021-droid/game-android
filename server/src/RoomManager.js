'use strict';
/**
 * Lobby rooms. Node runs room commands one at a time on a single thread, so the
 * capacity check and the insert in join() are atomic: two players racing for the
 * last slot can never push a room past maxPlayers.
 */
const crypto = require('crypto');
const log = require('./log');
const { MODES, MAPS, CHARACTERS, TEAM } = require('./game/constants');

const STATUS = { LOBBY: 'lobby', STARTING: 'starting', IN_MATCH: 'in_match', RESULTS: 'results' };

class GameError extends Error {
  constructor(code, message) { super(message || code); this.code = code; }
}

function cleanText(s, max, fallback) {
  if (typeof s !== 'string') return fallback;
  s = s.replace(/[\u0000-\u001f\u007f<>]/g, '').trim().slice(0, max);
  return s || fallback;
}

class Room {
  constructor(code, opts, host) {
    this.id = crypto.randomUUID();
    this.code = code;
    this.name = cleanText(opts.name, 32, 'SHADOW ROOM #' + code);
    this.mode = MODES.includes(opts.mode) ? opts.mode : 'ffa';
    this.mapId = MAPS.includes(opts.map) ? opts.map : 'shadow_temple';
    this.maxPlayers = clampInt(opts.maxPlayers, 2, 8, 8);
    this.minPlayers = 2;
    this.isPrivate = !!opts.isPrivate;
    this.password = this.isPrivate ? cleanText(opts.password, 32, '') : '';
    this.allowJoinInProgress = this.mode === 'ffa' && opts.allowJoinInProgress === true;
    this.ranked = false;
    this.friendlyFire = opts.friendlyFire === true;
    this.hostId = host.playerId;
    this.status = STATUS.LOBBY;
    this.members = new Map(); // playerId -> member
    this.match = null;
    this.createdAt = Date.now();
    this.botCounter = 0;
  }

  humans() { return [...this.members.values()].filter(m => !m.isBot); }
  isFull() { return this.members.size >= this.maxPlayers; }

  freeSlot() {
    const used = new Set([...this.members.values()].map(m => m.slot));
    for (let i = 0; i < this.maxPlayers; i++) if (!used.has(i)) return i;
    return -1;
  }

  teamCounts() {
    const c = { [TEAM.RED]: 0, [TEAM.BLUE]: 0 };
    for (const m of this.members.values()) if (m.team) c[m.team]++;
    return c;
  }

  smallerTeam() {
    const c = this.teamCounts();
    return c[TEAM.RED] <= c[TEAM.BLUE] ? TEAM.RED : TEAM.BLUE;
  }

  addMember(m) {
    m.slot = this.freeSlot();
    m.team = this.mode === 'tdm' ? this.smallerTeam() : TEAM.NONE;
    this.members.set(m.playerId, m);
    return m;
  }

  publicInfo() {
    return {
      id: this.id, code: this.code, name: this.name, mode: this.mode, map: this.mapId,
      players: this.members.size, maxPlayers: this.maxPlayers, status: this.status,
      isPrivate: this.isPrivate, ranked: this.ranked, hostName: (this.members.get(this.hostId) || {}).name || ''
    };
  }

  state() {
    return Object.assign(this.publicInfo(), {
      hostId: this.hostId,
      minPlayers: this.minPlayers,
      allowJoinInProgress: this.allowJoinInProgress,
      friendlyFire: this.friendlyFire,
      members: [...this.members.values()].sort((a, b) => a.slot - b.slot).map(m => ({
        playerId: m.playerId, name: m.name, slot: m.slot, charId: m.charId, team: m.team,
        ready: m.ready, isBot: m.isBot, isHost: m.playerId === this.hostId, connected: m.isBot || m.connected()
      }))
    });
  }
}

function clampInt(v, lo, hi, d) {
  const n = Number.isInteger(v) ? v : d;
  return Math.max(lo, Math.min(hi, n));
}

class RoomManager {
  constructor(cfg, sessions, supabase) {
    this.cfg = cfg;
    this.sessions = sessions;
    this.supabase = supabase;
    this.rooms = new Map(); // code -> Room
    this.syncTimers = new Map();
    this.syncChains = new Map();
    this.matchManager = null; // set by index.js
  }

  newCode() {
    const L = 'ABCDEFGHJKLMNPQRSTUVWXYZ';
    for (let i = 0; i < 1000; i++) {
      const code = L[crypto.randomInt(L.length)] + String(crypto.randomInt(1000)).padStart(3, '0');
      if (!this.rooms.has(code)) return code;
    }
    return crypto.randomBytes(3).toString('hex').toUpperCase();
  }

  member(session, charId) {
    return {
      playerId: session.playerId, userId: session.userId, name: session.name,
      charId: CHARACTERS.includes(charId) ? charId : 'kaze', team: 0, ready: false, isBot: false, slot: -1,
      connected: () => !!session.connected,
      cosmetics: () => session.cosmetics || {}
    };
  }

  create(session, opts) {
    if (this.cfg.maxRooms && this.rooms.size >= this.cfg.maxRooms) throw new GameError('SERVER_FULL', 'Server has reached its room limit');
    this.leave(session);
    const room = new Room(this.newCode(), opts || {}, session);
    room.addMember(this.member(session, opts && opts.charId));
    this.rooms.set(room.code, room);
    session.roomCode = room.code;
    log.info('room created', room.code, room.mode, room.mapId, 'by', session.name);
    this.changed(room);
    return room;
  }

  join(session, code, password, charId) {
    code = typeof code === 'string' ? code.trim().toUpperCase().replace(/^#/, '') : '';
    const room = this.rooms.get(code);
    if (!room) throw new GameError('ROOM_NOT_FOUND', 'Room not found');
    if (room.members.has(session.playerId)) { session.roomCode = room.code; return room; }
    if (room.isPrivate && room.password && password !== room.password) throw new GameError('BAD_PASSWORD', 'Wrong room password');
    if (room.isFull()) throw new GameError('ROOM_FULL', 'Room is full');
    if (room.status !== STATUS.LOBBY) {
      const canJoin = room.status === STATUS.IN_MATCH && room.allowJoinInProgress && room.match;
      if (!canJoin) throw new GameError('MATCH_IN_PROGRESS', 'Match already started');
    }
    this.leave(session);
    const m = room.addMember(this.member(session, charId));
    session.roomCode = room.code;
    if (room.status === STATUS.IN_MATCH) this.matchManager.joinInProgress(room, m);
    log.info('join', room.code, session.name, room.members.size + '/' + room.maxPlayers);
    this.changed(room);
    return room;
  }

  quickMatch(session, mode, charId) {
    mode = MODES.includes(mode) ? mode : 'ffa';
    const candidates = [...this.rooms.values()]
      .filter(r => !r.isPrivate && r.mode === mode && r.status === STATUS.LOBBY && !r.isFull())
      .sort((a, b) => b.members.size - a.members.size);
    if (candidates.length) return this.join(session, candidates[0].code, '', charId);
    return this.create(session, { mode, charId, name: 'Quick Match' });
  }

  leave(session) {
    const room = session.roomCode ? this.rooms.get(session.roomCode) : null;
    session.roomCode = null;
    if (!room || !room.members.has(session.playerId)) return;
    room.members.delete(session.playerId);
    if (room.match) this.matchManager.playerLeft(room, session.playerId);
    if (room.humans().length === 0) {
      this.close(room);
      return;
    }
    if (room.hostId === session.playerId) room.hostId = room.humans().sort((a, b) => a.slot - b.slot)[0].playerId;
    this.changed(room);
  }

  close(room) {
    if (room.match) this.matchManager.abort(room);
    this.rooms.delete(room.code);
    log.info('room closed', room.code);
    clearTimeout(this.syncTimers.get(room.code));
    this.syncTimers.delete(room.code);
    this.queueSync(room.code, () => this.supabase.deleteRoom(room.id));
  }

  get(session) {
    const room = session.roomCode ? this.rooms.get(session.roomCode) : null;
    if (!room) throw new GameError('NOT_IN_ROOM', 'You are not in a room');
    return room;
  }

  requireLobby(room) {
    if (room.status !== STATUS.LOBBY) throw new GameError('MATCH_IN_PROGRESS', 'Room is not in the lobby');
  }

  requireHost(room, session) {
    if (room.hostId !== session.playerId) throw new GameError('NOT_HOST', 'Only the host can do that');
  }

  selectCharacter(session, charId) {
    const room = this.get(session);
    this.requireLobby(room);
    if (!CHARACTERS.includes(charId)) throw new GameError('BAD_CHARACTER', 'Unknown character');
    room.members.get(session.playerId).charId = charId;
    this.changed(room);
  }

  selectTeam(session, team) {
    const room = this.get(session);
    this.requireLobby(room);
    if (room.mode !== 'tdm') throw new GameError('NO_TEAMS', 'This mode has no teams');
    if (team !== TEAM.RED && team !== TEAM.BLUE) throw new GameError('BAD_TEAM', 'Team must be 1 (red) or 2 (blue)');
    const m = room.members.get(session.playerId);
    if (m.team !== team && room.teamCounts()[team] >= Math.ceil(room.maxPlayers / 2)) throw new GameError('TEAM_FULL', 'That team is full');
    m.team = team;
    m.ready = false;
    this.changed(room);
  }

  setReady(session, ready) {
    const room = this.get(session);
    this.requireLobby(room);
    room.members.get(session.playerId).ready = !!ready;
    this.changed(room);
  }

  updateSettings(session, s) {
    const room = this.get(session);
    this.requireHost(room, session);
    this.requireLobby(room);
    if (MODES.includes(s.mode) && s.mode !== room.mode) {
      room.mode = s.mode;
      for (const m of room.members.values()) m.team = 0;
      if (room.mode === 'tdm') for (const m of [...room.members.values()].sort((a, b) => a.slot - b.slot)) m.team = room.smallerTeam();
      room.allowJoinInProgress = room.allowJoinInProgress && room.mode === 'ffa';
    }
    if (MAPS.includes(s.map)) room.mapId = s.map;
    if (Number.isInteger(s.maxPlayers)) {
      const max = clampInt(s.maxPlayers, 2, 8, room.maxPlayers);
      if (max < room.members.size) throw new GameError('TOO_MANY_PLAYERS', 'More players than the new limit');
      room.maxPlayers = max;
    }
    if (typeof s.name === 'string') room.name = cleanText(s.name, 32, room.name);
    if (typeof s.friendlyFire === 'boolean') room.friendlyFire = s.friendlyFire;
    if (typeof s.allowJoinInProgress === 'boolean') room.allowJoinInProgress = s.allowJoinInProgress && room.mode === 'ffa';
    for (const m of room.members.values()) if (!m.isBot) m.ready = false;
    this.changed(room);
  }

  addBot(session, charId) {
    const room = this.get(session);
    this.requireHost(room, session);
    this.requireLobby(room);
    if (room.isFull()) throw new GameError('ROOM_FULL', 'Room is full');
    const c = CHARACTERS.includes(charId) ? charId : CHARACTERS[room.botCounter % CHARACTERS.length];
    const id = 'bot-' + room.code + '-' + (++room.botCounter);
    room.addMember({
      playerId: id, userId: null, name: 'BOT ' + c.charAt(0).toUpperCase() + c.slice(1) + ' ' + room.botCounter,
      charId: c, team: 0, ready: true, isBot: true, slot: -1, connected: () => true
    });
    this.changed(room);
  }

  removeMember(session, playerId) {
    const room = this.get(session);
    this.requireHost(room, session);
    this.requireLobby(room);
    const m = room.members.get(playerId);
    if (!m || playerId === session.playerId) throw new GameError('BAD_TARGET', 'Cannot remove that player');
    room.members.delete(playerId);
    if (!m.isBot) {
      const s = this.sessions.byPlayer.get(playerId);
      if (s) { s.roomCode = null; this.sessions.send(s, { t: 'kicked', reason: 'REMOVED_BY_HOST' }); }
    }
    this.changed(room);
  }

  /** Validates the start rules and moves the room into its countdown. */
  start(session) {
    const room = this.get(session);
    this.requireHost(room, session);
    this.requireLobby(room);
    const all = [...room.members.values()];
    if (all.length < room.minPlayers) throw new GameError('NOT_ENOUGH_PLAYERS', `Need at least ${room.minPlayers} fighters`);
    const notReady = all.filter(m => !m.isBot && m.playerId !== room.hostId && !m.ready);
    if (notReady.length) throw new GameError('NOT_READY', 'Waiting for: ' + notReady.map(m => m.name).join(', '));
    if (all.some(m => !m.isBot && !m.connected())) throw new GameError('PLAYER_DISCONNECTED', 'A player is reconnecting');
    if (room.mode === 'tdm') {
      for (const m of all) if (!m.team) m.team = room.smallerTeam();
      const c = room.teamCounts();
      if (!c[TEAM.RED] || !c[TEAM.BLUE]) throw new GameError('TEAMS_UNBALANCED', 'Both teams need at least one fighter');
      if (Math.abs(c[TEAM.RED] - c[TEAM.BLUE]) > 1) throw new GameError('TEAMS_UNBALANCED', 'Teams differ by more than one fighter');
    }
    this.matchManager.begin(room);
  }

  list() {
    return [...this.rooms.values()].filter(r => !r.isPrivate).map(r => r.publicInfo())
      .sort((a, b) => (a.status === 'lobby' ? 0 : 1) - (b.status === 'lobby' ? 0 : 1) || b.players - a.players);
  }

  /** Broadcast lobby state to the room and mirror it to Supabase (debounced). */
  changed(room) {
    this.broadcast(room, { t: 'room', room: room.state() });
    if (!this.supabase.enabled) return;
    clearTimeout(this.syncTimers.get(room.code));
    this.syncTimers.set(room.code, setTimeout(() => {
      this.syncTimers.delete(room.code);
      if (!this.rooms.has(room.code)) return;
      const row = {
        id: room.id, code: room.code, name: room.name, mode: room.mode, map_id: room.mapId,
        max_players: room.maxPlayers, current_players: room.members.size, status: room.status,
        is_private: room.isPrivate, host_id: this.userIdOf(room, room.hostId),
        host_name: (room.members.get(room.hostId) || {}).name || '', server_id: this.cfg.serverId,
        updated_at: new Date().toISOString()
      };
      const players = [...room.members.values()].map(m => ({
        room_id: room.id, slot: m.slot, player_id: m.userId || null, display_name: m.name,
        character_id: m.charId, team_id: m.team, is_ready: m.ready, is_bot: m.isBot, is_host: m.playerId === room.hostId
      }));
      this.queueSync(room.code, () => this.supabase.upsertRoom(row, players));
    }, 250));
  }

  /** Serialises Supabase writes per room so they apply in order. */
  queueSync(code, job) {
    const prev = this.syncChains.get(code) || Promise.resolve();
    const next = prev.then(job).catch(e => log.warn('room sync failed', code, e.message));
    this.syncChains.set(code, next);
    next.then(() => { if (this.syncChains.get(code) === next) this.syncChains.delete(code); });
  }

  userIdOf(room, playerId) {
    const m = room.members.get(playerId);
    return m && m.userId ? m.userId : null;
  }

  broadcast(room, msg) {
    const data = JSON.stringify(msg);
    for (const m of room.members.values()) {
      if (m.isBot) continue;
      this.sessions.send(this.sessions.byPlayer.get(m.playerId), data);
    }
  }
}

module.exports = { RoomManager, Room, GameError, STATUS, cleanText };

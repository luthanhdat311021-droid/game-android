'use strict';
/**
 * Ranked matchmaking: a queue of signed-in players grouped by rating.
 *  - The allowed rating gap starts at BASE_WINDOW and widens the longer someone waits.
 *  - A full group (MAX_PLAYERS) starts at once; after FORM_AFTER_MS a group of ≥ MIN_PLAYERS starts.
 *  - Ranked rooms are private, Free For All, random map, no bots, start automatically.
 * Ratings are read from Supabase (never from the client).
 */
const log = require('./log');
const { MAPS } = require('./game/constants');
const { GameError } = require('./RoomManager');

const MIN_PLAYERS = 2, MAX_PLAYERS = 4;
const BASE_WINDOW = 100, WINDOW_PER_SEC = 15, MAX_WINDOW = 800;
const FORM_AFTER_MS = 20000;

class Matchmaker {
  constructor(cfg, rooms, sessions, supabase, matches) {
    this.cfg = cfg;
    this.rooms = rooms;
    this.sessions = sessions;
    this.supabase = supabase;
    this.matches = matches;
    this.queue = new Map(); // playerId -> { session, rating, charId, since }
    this.timer = null;
  }

  start() { this.timer = setInterval(() => this.tick(), 1000); }
  stop() { clearInterval(this.timer); }

  static window(waitMs) {
    return Math.min(MAX_WINDOW, BASE_WINDOW + Math.floor(waitMs / 1000) * WINDOW_PER_SEC);
  }

  async join(session, charId) {
    if (!session.userId) throw new GameError('RANKED_LOGIN_REQUIRED', 'Ranked needs a signed-in account');
    if (this.queue.has(session.playerId)) return;
    let rating = 1000;
    if (this.supabase.enabled) {
      const rows = await this.supabase.request('GET', `/rest/v1/rankings?select=rating&player_id=eq.${session.userId}`).catch(() => null);
      if (rows && rows[0]) rating = rows[0].rating;
    }
    this.rooms.leave(session);
    this.queue.set(session.playerId, { session, rating, charId, since: Date.now() });
    log.info('ranked queue +', session.name, rating, 'size', this.queue.size);
    this.status(session.playerId);
  }

  leave(session, notify = true) {
    if (!this.queue.delete(session.playerId)) return;
    if (notify) this.sessions.send(session, { t: 'ranked_cancelled' });
  }

  status(playerId) {
    const e = this.queue.get(playerId);
    if (!e) return;
    const wait = Date.now() - e.since;
    this.sessions.send(e.session, { t: 'ranked_queue', rating: e.rating, waitSec: Math.floor(wait / 1000),
      window: Matchmaker.window(wait), inQueue: this.queue.size });
  }

  tick() {
    for (const [id, e] of this.queue) {
      // players that disconnected for good drop out of the queue
      if (!e.session.connected && !this.sessions.byPlayer.has(id)) this.queue.delete(id);
    }
    const now = Date.now();
    const list = [...this.queue.values()].sort((a, b) => a.since - b.since); // oldest first gets priority
    const used = new Set();
    for (const anchor of list) {
      if (used.has(anchor.session.playerId)) continue;
      const win = Matchmaker.window(now - anchor.since);
      const group = [anchor];
      const near = list.filter(o => o !== anchor && !used.has(o.session.playerId)
        && Math.abs(o.rating - anchor.rating) <= Math.max(win, Matchmaker.window(now - o.since)))
        .sort((a, b) => Math.abs(a.rating - anchor.rating) - Math.abs(b.rating - anchor.rating));
      for (const o of near) { if (group.length < MAX_PLAYERS) group.push(o); }
      const ready = group.length >= MAX_PLAYERS || (group.length >= MIN_PLAYERS && now - anchor.since >= FORM_AFTER_MS);
      if (ready) {
        for (const g of group) used.add(g.session.playerId);
        this.launch(group);
      }
    }
    for (const id of this.queue.keys()) this.status(id);
  }

  launch(group) {
    for (const g of group) this.queue.delete(g.session.playerId);
    const host = group[0];
    try {
      const map = MAPS[Math.floor(Math.random() * MAPS.length)];
      const room = this.rooms.create(host.session, { mode: 'ffa', map, maxPlayers: group.length, isPrivate: true,
        name: 'RANKED', charId: host.charId });
      room.ranked = true;
      room.password = require('crypto').randomBytes(8).toString('hex'); // nobody can walk in by code
      for (const g of group.slice(1)) this.rooms.join(g.session, room.code, room.password, g.charId);
      for (const m of room.members.values()) m.ready = true;
      log.info('ranked match', room.code, group.map(g => `${g.session.name}(${g.rating})`).join(' vs '));
      this.rooms.broadcast(room, { t: 'ranked_found', code: room.code, players: group.map(g => ({ name: g.session.name, rating: g.rating })) });
      this.matches.begin(room);
    } catch (e) {
      log.error('ranked launch failed', e.message);
      for (const g of group) this.sessions.send(g.session, { t: 'error', code: 'RANKED_FAILED', message: e.message });
    }
  }
}

module.exports = { Matchmaker, MIN_PLAYERS, MAX_PLAYERS, FORM_AFTER_MS };

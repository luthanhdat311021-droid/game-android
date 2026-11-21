'use strict';
/**
 * Tracks connected players. A session survives a dropped socket for
 * reconnectGraceMs so a phone that loses signal can resume with its resume token
 * and keep controlling the same fighter.
 */
const crypto = require('crypto');
const log = require('./log');

class PlayerSessionManager {
  constructor(cfg, onExpire) {
    this.cfg = cfg;
    this.onExpire = onExpire;
    this.byPlayer = new Map();
    this.byResume = new Map();
  }

  /** identity: { playerId, userId, name, isGuest } */
  create(identity, ws) {
    const old = this.byPlayer.get(identity.playerId);
    if (old) {
      // same account connecting again: the new socket takes over the session
      this.takeOver(old, ws);
      old.name = identity.name;
      return old;
    }
    const s = {
      playerId: identity.playerId,
      userId: identity.userId || null,
      name: identity.name,
      isGuest: !!identity.isGuest,
      resumeToken: crypto.randomBytes(24).toString('hex'),
      ws: null,
      connected: false,
      roomCode: null,
      graceTimer: null,
      bucket: { tokens: this.cfg.inputRatePerSec, at: Date.now() },
      rtt: 0,
      violations: 0
    };
    this.byPlayer.set(s.playerId, s);
    this.byResume.set(s.resumeToken, s);
    this.attach(s, ws);
    return s;
  }

  resume(token, ws) {
    const s = typeof token === 'string' ? this.byResume.get(token) : null;
    if (!s) return null;
    this.takeOver(s, ws);
    return s;
  }

  takeOver(s, ws) {
    if (s.ws && s.ws !== ws) {
      try { s.ws.send(JSON.stringify({ t: 'kicked', reason: 'SESSION_TAKEN_OVER' })); s.ws.close(4001, 'session taken over'); } catch (e) { /* ignore */ }
    }
    this.attach(s, ws);
  }

  attach(s, ws) {
    if (s.graceTimer) { clearTimeout(s.graceTimer); s.graceTimer = null; }
    s.ws = ws; s.connected = true;
    ws.session = s;
  }

  detach(ws) {
    const s = ws.session;
    if (!s || s.ws !== ws) return;
    s.ws = null; s.connected = false;
    log.info('player disconnected', s.name, 'grace', this.cfg.reconnectGraceMs, 'ms');
    s.graceTimer = setTimeout(() => this.expire(s), this.cfg.reconnectGraceMs);
  }

  expire(s) {
    s.graceTimer = null;
    if (s.connected) return;
    this.byPlayer.delete(s.playerId);
    this.byResume.delete(s.resumeToken);
    this.onExpire(s);
  }

  /** Token bucket. Returns false when the player is sending too fast. */
  allowInput(s) {
    const now = Date.now(), b = s.bucket, rate = this.cfg.inputRatePerSec;
    b.tokens = Math.min(rate * 0.5, b.tokens + ((now - b.at) / 1000) * rate);
    b.at = now;
    if (b.tokens < 1) return false;
    b.tokens -= 1;
    return true;
  }

  send(s, msg) {
    if (!s || !s.ws || s.ws.readyState !== 1) return false;
    s.ws.send(typeof msg === 'string' ? msg : JSON.stringify(msg));
    return true;
  }

  count() { return this.byPlayer.size; }
}

module.exports = { PlayerSessionManager };

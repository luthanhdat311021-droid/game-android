'use strict';
/** Headless game client used by the integration tests and the load test. */
const WebSocket = require('ws');
const { PROTOCOL_VERSION } = require('../src/game/constants');

class TestClient {
  constructor(url, name) {
    this.url = url;
    this.name = name;
    this.msgs = [];
    this.waiters = [];
    this.seq = 0;
    this.snapshots = 0;
    this.lastSnap = null;
    this.state = new Map(); // fighter id -> merged state
    this.matchStart = null;
    this.errors = [];
    this.foreignIds = 0;
  }

  connect(extra = {}) {
    return new Promise((resolve, reject) => {
      this.ws = new WebSocket(this.url);
      this.ws.on('open', () => {
        this.send(Object.assign({ t: 'hello', v: PROTOCOL_VERSION, name: this.name }, extra));
      });
      this.ws.on('message', raw => this.onMessage(JSON.parse(raw.toString())));
      this.ws.on('error', reject);
      this.wait(m => m.t === 'welcome' || m.t === 'error', 5000).then(m => {
        if (m.t === 'error') reject(new Error(m.code));
        else { this.welcome = m; resolve(m); }
      }, reject);
    });
  }

  onMessage(m) {
    if (m.t === 'snap') {
      this.snapshots++;
      this.lastSnap = m;
      for (const d of m.p) {
        if (this.matchStart && !this.matchStart.players.some(p => p.id === d.i)) this.foreignIds++;
        this.state.set(d.i, Object.assign(this.state.get(d.i) || {}, d));
      }
      if (m.gone) for (const id of m.gone) this.state.delete(id);
    } else {
      if (m.t === 'match_start') { this.matchStart = m; this.state.clear(); }
      if (m.t === 'roster' && this.matchStart) this.matchStart.players = m.players;
      if (m.t === 'error') this.errors.push(m);
      this.msgs.push(m);
    }
    this.waiters = this.waiters.filter(w => {
      if (w.pred(m)) { clearTimeout(w.timer); w.resolve(m); return false; }
      return true;
    });
  }

  wait(pred, ms = 5000) {
    const found = this.msgs.find(pred);
    if (found) { this.msgs.splice(this.msgs.indexOf(found), 1); return Promise.resolve(found); }
    return new Promise((resolve, reject) => {
      const w = { pred, resolve: m => { const i = this.msgs.indexOf(m); if (i >= 0) this.msgs.splice(i, 1); resolve(m); } };
      w.timer = setTimeout(() => {
        this.waiters = this.waiters.filter(x => x !== w);
        reject(new Error(`${this.name}: timeout waiting`));
      }, ms);
      this.waiters.push(w);
    });
  }

  send(o) { if (this.ws.readyState === 1) this.ws.send(JSON.stringify(o)); }
  input(mx, b = 0, my = 0) { this.send({ t: 'in', s: ++this.seq, mx, my, b }); }
  me() { return this.matchStart ? this.state.get(this.matchStart.you) : null; }
  close() { try { this.ws.close(); } catch (e) { /* ignore */ } }
}

module.exports = { TestClient };

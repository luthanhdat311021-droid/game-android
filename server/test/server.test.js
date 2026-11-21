'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { createServer } = require('../src/index');
const { TestClient } = require('../tools/TestClient');
const baseConfig = require('../src/config');

process.env.LOG_LEVEL = 'warn';

function cfg(extra) {
  return Object.assign({}, baseConfig, {
    port: 0, host: '127.0.0.1', allowGuests: true, supabaseEnabled: false, supabaseUrl: '', supabaseServiceKey: '',
    countdownMs: 100, resultsMs: 300, matchDurationOverrideSec: 3, reconnectGraceMs: 2000
  }, extra || {});
}

async function boot(extra) {
  const server = createServer(cfg(extra));
  const port = await server.start();
  return { server, url: `ws://127.0.0.1:${port}` };
}

async function clients(url, n, prefix = 'C') {
  const list = Array.from({ length: n }, (_, i) => new TestClient(url, prefix + i));
  await Promise.all(list.map(c => c.connect()));
  return list;
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

test('9 players race for an 8-player room: exactly 8 get in', async () => {
  const { server, url } = await boot();
  try {
    const [host, ...others] = await clients(url, 9);
    host.send({ t: 'create_room', name: 'Race', mode: 'ffa', map: 'shadow_temple', maxPlayers: 8 });
    const room = (await host.wait(m => m.t === 'room')).room;
    // all 8 joiners fire at the same moment
    others.forEach(c => c.send({ t: 'join_room', code: room.code }));
    await sleep(400);
    const r = server.rooms.rooms.get(room.code);
    assert.equal(r.members.size, 8);
    const rejected = others.filter(c => c.errors.some(e => e.code === 'ROOM_FULL'));
    assert.equal(rejected.length, 1);
    [host, ...others].forEach(c => c.close());
  } finally { await server.stop(); }
});

test('two rooms play at the same time without leaking data', async () => {
  const { server, url } = await boot();
  try {
    const all = await clients(url, 8);
    const a = all.slice(0, 4), b = all.slice(4);
    a[0].send({ t: 'create_room', mode: 'ffa', map: 'shadow_temple' });
    b[0].send({ t: 'create_room', mode: 'ffa', map: 'cyber_city' });
    const ra = (await a[0].wait(m => m.t === 'room')).room;
    const rb = (await b[0].wait(m => m.t === 'room')).room;
    for (const c of a.slice(1)) c.send({ t: 'join_room', code: ra.code });
    for (const c of b.slice(1)) c.send({ t: 'join_room', code: rb.code });
    await sleep(200);
    for (const c of [...a.slice(1), ...b.slice(1)]) c.send({ t: 'ready', ready: true });
    await sleep(100);
    a[0].send({ t: 'start_match' });
    b[0].send({ t: 'start_match' });
    const sa = await Promise.all(a.map(c => c.wait(m => m.t === 'match_start')));
    const sb = await Promise.all(b.map(c => c.wait(m => m.t === 'match_start')));
    assert.notEqual(sa[0].matchId, sb[0].matchId);
    assert.ok(sa.every(m => m.map === 'shadow_temple' && m.players.length === 4));
    assert.ok(sb.every(m => m.map === 'cyber_city' && m.players.length === 4));
    // each client controls a distinct fighter
    assert.equal(new Set(sa.map(m => m.you)).size, 4);
    // move only client a[1] right; everyone else idles
    const startX = () => a[1].me() && a[1].me().x;
    await sleep(300);
    const x0 = startX();
    for (let i = 0; i < 30; i++) { a[1].input(100); await sleep(33); }
    await sleep(300);
    assert.ok(a[1].me().x !== x0, 'controlled fighter moved');
    // other clients see a[1]'s fighter at the same place (synchronised)
    const fid = sa[1].you;
    assert.ok(Math.abs(a[2].state.get(fid).x - a[1].me().x) < 1, 'state synchronised across clients');
    for (const c of all) assert.equal(c.foreignIds, 0, c.name + ' received a fighter from another room');
    const ends = await Promise.all(all.map(c => c.wait(m => m.t === 'match_end', 6000)));
    assert.ok(ends.every(e => e.result.standings.length === 4));
    assert.equal(server.results.saved.size, 2, 'both results recorded');
    all.forEach(c => c.close());
  } finally { await server.stop(); }
});

test('client cannot send damage or score; only inputs are accepted', async () => {
  const { server, url } = await boot({ matchDurationOverrideSec: 2 });
  try {
    const [h, g] = await clients(url, 2);
    h.send({ t: 'create_room' });
    const room = (await h.wait(m => m.t === 'room')).room;
    g.send({ t: 'join_room', code: room.code });
    await g.wait(m => m.t === 'room');
    g.send({ t: 'ready', ready: true });
    await sleep(50);
    h.send({ t: 'start_match' });
    await h.wait(m => m.t === 'match_start');
    h.send({ t: 'damage', target: 1, amount: 9999 });
    h.send({ t: 'score', kills: 50 });
    h.send({ t: 'in', s: 1, mx: 100000, b: 0xffff, x: 9999, hp: 99999 });
    const err = await h.wait(m => m.t === 'error' && m.code === 'UNKNOWN_TYPE');
    assert.ok(err);
    const end = await h.wait(m => m.t === 'match_end', 5000);
    assert.ok(end.result.standings.every(s => s.kills < 50));
    // a non-host cannot start or change settings
    g.send({ t: 'room_settings', map: 'cyber_city' });
    const e2 = await g.wait(m => m.t === 'error');
    assert.ok(['NOT_HOST', 'MATCH_IN_PROGRESS'].includes(e2.code));
    h.close(); g.close();
  } finally { await server.stop(); }
});

test('start rules: min players, ready check, joining a running match', async () => {
  const { server, url } = await boot({ matchDurationOverrideSec: 5 });
  try {
    const [h, g, late] = await clients(url, 3);
    h.send({ t: 'create_room' });
    const room = (await h.wait(m => m.t === 'room')).room;
    h.send({ t: 'start_match' });
    assert.equal((await h.wait(m => m.t === 'error')).code, 'NOT_ENOUGH_PLAYERS');
    g.send({ t: 'join_room', code: room.code });
    await sleep(100);
    h.send({ t: 'start_match' });
    assert.equal((await h.wait(m => m.t === 'error')).code, 'NOT_READY');
    g.send({ t: 'ready', ready: true });
    await sleep(50);
    h.send({ t: 'start_match' });
    await h.wait(m => m.t === 'match_start');
    late.send({ t: 'join_room', code: room.code });
    assert.equal((await late.wait(m => m.t === 'error')).code, 'MATCH_IN_PROGRESS');
    [h, g, late].forEach(c => c.close());
  } finally { await server.stop(); }
});

test('disconnect + resume keeps control of the same fighter', async () => {
  const { server, url } = await boot({ matchDurationOverrideSec: 6 });
  try {
    const [h, g] = await clients(url, 2);
    h.send({ t: 'create_room' });
    const room = (await h.wait(m => m.t === 'room')).room;
    g.send({ t: 'join_room', code: room.code });
    await sleep(100);
    g.send({ t: 'ready', ready: true });
    await sleep(50);
    h.send({ t: 'start_match' });
    const start = await g.wait(m => m.t === 'match_start');
    const resume = g.welcome.resume;
    g.ws.terminate();
    await sleep(300);
    const g2 = new TestClient(url, 'G-again');
    await g2.connect({ resume });
    const again = await g2.wait(m => m.t === 'match_start');
    assert.equal(again.resumed, true);
    assert.equal(again.you, start.you, 'same fighter after reconnect');
    await sleep(300);
    assert.ok(g2.snapshots > 0, 'receiving snapshots again');
    h.close(); g2.close();
  } finally { await server.stop(); }
});

test('player who never comes back is removed after the grace period', async () => {
  const { server, url } = await boot({ reconnectGraceMs: 300 });
  try {
    const [h, g] = await clients(url, 2);
    h.send({ t: 'create_room' });
    const room = (await h.wait(m => m.t === 'room')).room;
    g.send({ t: 'join_room', code: room.code });
    await sleep(100);
    g.ws.terminate();
    await sleep(700);
    assert.equal(server.rooms.rooms.get(room.code).members.size, 1);
    h.close();
  } finally { await server.stop(); }
});

test('a token that does not verify is refused even when guests are allowed', async () => {
  const { server, url } = await boot({ allowGuests: true });
  try {
    const c = new TestClient(url, 'X');
    await assert.rejects(c.connect({ token: 'forged.jwt.token-that-is-long-enough' }), /AUTH_FAILED/);
    const g = new TestClient(url, 'G');
    const w = await g.connect();
    assert.equal(w.guest, true, 'no token -> guest is fine');
    g.close();
  } finally { await server.stop(); }
});

test('without Supabase and without guest mode, login is refused', async () => {
  const { server, url } = await boot({ allowGuests: false });
  try {
    const c = new TestClient(url, 'X');
    await assert.rejects(c.connect({ token: 'fake.jwt.token-that-is-long-enough' }), /AUTH_FAILED/);
  } finally { await server.stop(); }
});

test('ranked: guests refused; signed-in players are grouped by rating and the match auto-starts', async () => {
  const { server, url } = await boot({ matchDurationOverrideSec: 2 });
  try {
    const cs = await clients(url, 5, 'R');
    cs[4].send({ t: 'ranked_join', charId: 'kaze' });
    assert.equal((await cs[4].wait(m => m.t === 'error')).code, 'RANKED_LOGIN_REQUIRED');
    // pretend the first four are signed in (no Supabase in tests → everyone rated 1000)
    cs.slice(0, 4).forEach((c, i) => { server.sessions.byPlayer.get(c.welcome.playerId).userId = 'user-' + i; });
    for (const c of cs.slice(0, 4)) c.send({ t: 'ranked_join', charId: 'akira' });
    const found = await Promise.all(cs.slice(0, 4).map(c => c.wait(m => m.t === 'ranked_found', 4000)));
    assert.equal(found[0].players.length, 4);
    const starts = await Promise.all(cs.slice(0, 4).map(c => c.wait(m => m.t === 'match_start', 4000)));
    assert.equal(new Set(starts.map(s => s.matchId)).size, 1, 'all four in the same match');
    const room = [...server.rooms.rooms.values()][0];
    assert.equal(room.ranked, true);
    assert.equal(room.isPrivate, true);
    const end = await cs[0].wait(m => m.t === 'match_end', 6000);
    assert.ok(end);
    assert.equal(server.results.saved.get(end.matchId).ranked, true, 'result flagged ranked');
    cs.forEach(c => c.close());
  } finally { await server.stop(); }
});

test('ranked: the rating window widens with waiting time', () => {
  const { Matchmaker } = require('../src/Matchmaker');
  assert.equal(Matchmaker.window(0), 100);
  assert.equal(Matchmaker.window(10000), 250);
  assert.equal(Matchmaker.window(600000), 800);
});

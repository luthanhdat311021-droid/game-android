#!/usr/bin/env node
'use strict';
/**
 * Automated multiplayer test: N simulated players per room, R rooms, random inputs at 30 Hz.
 *   node tools/load-test.js [url] [playersPerRoom=8] [rooms=2]
 * Needs a server running with ALLOW_GUESTS=true (and a short MATCH_DURATION_SEC for quick runs).
 * NOTE: this simulates clients on one machine — it checks server logic, sync and bandwidth,
 * not real mobile networks (latency, packet loss, NAT). See docs/TESTING.md.
 */
const { TestClient } = require('./TestClient');
const { BTN } = require('../src/game/constants');

const url = process.argv[2] || 'ws://127.0.0.1:8080';
const perRoom = Number(process.argv[3] || 8);
const roomCount = Number(process.argv[4] || 2);
const sleep = ms => new Promise(r => setTimeout(r, ms));

async function runRoom(r) {
  const cs = Array.from({ length: perRoom }, (_, i) => new TestClient(url, `R${r}P${i}`));
  await Promise.all(cs.map(c => c.connect()));
  cs[0].send({ t: 'create_room', name: 'Load ' + r, mode: 'ffa', map: 'shadow_temple', maxPlayers: 8 });
  const room = (await cs[0].wait(m => m.t === 'room')).room;
  for (const c of cs.slice(1)) {
    c.send({ t: 'join_room', code: room.code, charId: ['kaze', 'ryujin', 'akira'][Math.floor(Math.random() * 3)] });
  }
  await sleep(300);
  for (const c of cs.slice(1)) c.send({ t: 'ready', ready: true });
  await sleep(200);
  cs[0].send({ t: 'start_match' });
  await Promise.all(cs.map(c => c.wait(m => m.t === 'match_start', 8000)));
  const t0 = Date.now();
  let bytes = 0;
  cs.forEach(c => c.ws.on('message', d => { bytes += d.length; }));
  const pings = [];
  cs.forEach(c => c.ws.on('message', d => {
    const m = JSON.parse(d.toString());
    if (m.t === 'pong') pings.push(Date.now() - m.c);
  }));
  const timer = setInterval(() => {
    for (const c of cs) {
      const presses = [BTN.ATTACK, BTN.HEAVY, BTN.JUMP, BTN.DASH, BTN.SPECIAL, 0, 0, 0];
      const b = Math.random() < 0.2 ? presses[Math.floor(Math.random() * presses.length)] : 0;
      c.input(Math.round((Math.random() * 2 - 1) * 100), b);
    }
  }, 33);
  const pinger = setInterval(() => cs.forEach(c => c.send({ t: 'ping', c: Date.now() })), 1000);
  const ends = await Promise.all(cs.map(c => c.wait(m => m.t === 'match_end', 600000)));
  clearInterval(timer); clearInterval(pinger);
  const secs = (Date.now() - t0) / 1000;
  const res = ends[0].result;
  const foreign = cs.reduce((s, c) => s + c.foreignIds, 0);
  const snaps = cs.map(c => c.snapshots);
  cs.forEach(c => c.close());
  return {
    room: room.code, players: perRoom, seconds: secs.toFixed(1),
    snapshotsPerClientPerSec: (snaps.reduce((a, b) => a + b, 0) / perRoom / secs).toFixed(1),
    kbPerClientPerSec: (bytes / perRoom / secs / 1024).toFixed(2),
    avgPingMs: pings.length ? (pings.reduce((a, b) => a + b, 0) / pings.length).toFixed(1) : '-',
    kills: res.standings.reduce((s, x) => s + x.kills, 0),
    deaths: res.standings.reduce((s, x) => s + x.deaths, 0),
    winners: res.winners.join(','), draw: res.draw, foreignFighters: foreign,
    consistentStandings: ends.every(e => JSON.stringify(e.result.standings) === JSON.stringify(res.standings))
  };
}

(async () => {
  console.log(`Load test: ${roomCount} room(s) x ${perRoom} players -> ${url}`);
  const results = await Promise.all(Array.from({ length: roomCount }, (_, r) => runRoom(r)));
  console.table(results);
  const ok = results.every(r => r.foreignFighters === 0 && r.consistentStandings);
  const health = await fetch(url.replace('ws', 'http') + '/health').then(r => r.json()).catch(() => null);
  if (health) console.log('server health:', health);
  console.log(ok ? 'PASS' : 'FAIL');
  process.exit(ok ? 0 : 1);
})().catch(e => { console.error(e); process.exit(1); });

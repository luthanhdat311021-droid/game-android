'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { GameWorld } = require('../src/game/GameWorld');
const { BTN, ACT } = require('../src/game/constants');

function world(players, mode = 'ffa', extra = {}) {
  return new GameWorld(Object.assign({ mapId: 'shadow_temple', mode, players, seed: 7 }, extra));
}
const P = (id, charId = 'kaze', team = 0, isBot = false) => ({ id, name: 'P' + id, charId, team, isBot });
function run(w, n, inputs) {
  let res = null;
  for (let i = 0; i < n && !res; i++) res = w.step(typeof inputs === 'function' ? inputs(w.tick) : inputs);
  return res;
}
/** Put fighters on the main floor next to each other, out of spawn protection. */
function lineUp(w, xs) {
  const floorY = 26 * 32;
  w.fighters.forEach((f, i) => { f.x = xs[i]; f.y = floorY; f.vx = 0; f.vy = 0; f.protectT = 0; f.facing = 1; f.energy = 100; });
  run(w, 2, new Map());
}

test('fighter falls, lands and runs on the floor', () => {
  const w = world([P(0)]);
  const f = w.fighters[0];
  f.x = 20 * 32; f.y = 20 * 32;
  run(w, 40, new Map());
  assert.equal(f.y, 26 * 32, 'landed on floor top');
  assert.ok(f.grounded);
  const x0 = f.x;
  run(w, 15, new Map([[0, { mx: 100, my: 0, b: 0 }]]));
  assert.ok(f.x > x0 + 100, 'moved right');
});

test('jump and one-way platform landing', () => {
  const w = world([P(0)]);
  const f = w.fighters[0];
  lineUp(w, [25 * 32]);
  run(w, 1, new Map([[0, { mx: 0, b: BTN.JUMP }]]));
  run(w, 1, new Map());
  assert.ok(f.vy < 0 && !f.grounded, 'airborne');
  // second (double) jump at apex reaches the platform at row 19
  run(w, 12, new Map());
  run(w, 1, new Map([[0, { mx: 0, b: BTN.JUMP }]]));
  run(w, 40, new Map());
  assert.equal(f.y, 19 * 32, 'stands on one-way platform');
  // drop through
  run(w, 1, new Map([[0, { mx: 0, my: 1, b: BTN.JUMP }]]));
  run(w, 30, new Map());
  assert.equal(f.y, 26 * 32, 'dropped back to floor');
});

test('basic attack damages a target exactly once per swing', () => {
  const w = world([P(0), P(1)]);
  lineUp(w, [30 * 32 - 200, 30 * 32 - 150]);
  const [a, t] = w.fighters;
  a.x = 600; t.x = 650; a.facing = 1;
  run(w, 1, new Map([[0, { mx: 0, b: BTN.ATTACK }]]));
  run(w, 12, new Map());
  const hits = t.maxHp - t.hp;
  const expected = Math.round(55 * 100 / 100 * (100 / (100 + 35)));
  assert.equal(hits, expected, 'single hit damage');
  assert.equal(a.dmgDealt, expected);
});

test('Flame Burst zone hits every enemy inside it, pulses on interval', () => {
  const w = world([P(0, 'ryujin'), P(1), P(2), P(3)]);
  lineUp(w, [600, 630, 660, 700]);
  const victims = w.fighters.slice(1);
  run(w, 1, new Map([[0, { mx: 0, b: BTN.SPECIAL }]]));
  run(w, 10, new Map());
  assert.equal(w.zones.length, 1, 'zone spawned');
  for (const v of victims) assert.ok(v.hp < v.maxHp, 'victim ' + v.id + ' damaged');
  const first = victims.map(v => v.maxHp - v.hp);
  // no double damage inside one pulse interval
  run(w, 5, new Map());
  victims.forEach((v, i) => assert.equal(v.maxHp - v.hp, first[i]));
});

test('Thunder Strike pierces several targets', () => {
  const w = world([P(0, 'akira'), P(1), P(2), P(3)]);
  lineUp(w, [500, 650, 800, 950]);
  run(w, 1, new Map([[0, { mx: 0, b: BTN.SPECIAL }]]));
  run(w, 25, new Map());
  const hit = w.fighters.slice(1).filter(v => v.hp < v.maxHp).length;
  assert.equal(hit, 3);
});

test('friendly fire off in TDM: teammates are not damaged', () => {
  const w = world([P(0, 'akira', 1), P(1, 'kaze', 1), P(2, 'kaze', 2)], 'tdm');
  lineUp(w, [500, 650, 800]);
  run(w, 1, new Map([[0, { mx: 0, b: BTN.SPECIAL }]]));
  run(w, 25, new Map());
  assert.equal(w.fighters[1].hp, w.fighters[1].maxHp, 'teammate untouched');
  assert.ok(w.fighters[2].hp < w.fighters[2].maxHp, 'enemy hit');
});

test('kill credit, assist credit, death and respawn with protection', () => {
  const w = world([P(0), P(1), P(2)]);
  lineUp(w, [600, 650, 2400]);
  const [k, v, helper] = w.fighters;
  v.dmgLog.set(helper.id, w.tick); // helper damaged the victim recently
  v.hp = 5;
  run(w, 1, new Map([[0, { mx: 0, b: BTN.ATTACK }]]));
  run(w, 6, new Map());
  assert.equal(v.alive, false);
  assert.equal(v.deaths, 1);
  assert.equal(k.kills, 1);
  assert.equal(helper.assists, 1);
  const kill = { k: k.id, v: v.id };
  assert.ok(kill);
  run(w, 151, new Map());
  assert.equal(v.alive, true, 'respawned after 5 s');
  assert.equal(v.hp, v.maxHp);
  assert.ok(v.protectT > 0, 'spawn protection');
  assert.ok(w.map.spawns.some(s => s.x === v.x), 'at a spawn point');
});

test('falling into a pit kills and credits the last attacker', () => {
  const w = world([P(0), P(1)]);
  lineUp(w, [1000, 12 * 32]);
  const [k, v] = w.fighters;
  v.x = 12 * 32; v.y = 20 * 32; v.lastHitBy = k.id; v.lastHitTick = w.tick;
  run(w, 60, new Map());
  assert.equal(v.alive, false);
  assert.equal(k.kills, 1);
});

test('spawn protection blocks damage', () => {
  const w = world([P(0), P(1)]);
  lineUp(w, [600, 650]);
  w.fighters[1].protectT = 60;
  run(w, 1, new Map([[0, { mx: 0, b: BTN.ATTACK }]]));
  run(w, 10, new Map());
  assert.equal(w.fighters[1].hp, w.fighters[1].maxHp);
});

test('FFA time-up ranks by kills, then deaths, and reports draws', () => {
  const w = world([P(0), P(1), P(2)], 'ffa', { durationSec: 1 });
  const [a, b, c] = w.fighters;
  a.kills = 3; b.kills = 3; c.kills = 1; a.deaths = 1; b.deaths = 2;
  const res = run(w, 40, new Map());
  assert.ok(res, 'match ended');
  assert.equal(res.reason, 'time_up');
  assert.deepEqual(res.winners, [0]);
  assert.equal(res.draw, false);
  assert.deepEqual(res.standings.map(s => s.placement), [1, 2, 3]);

  const w2 = world([P(0), P(1)], 'ffa', { durationSec: 1 });
  w2.fighters[0].kills = 2; w2.fighters[1].kills = 2;
  const r2 = run(w2, 40, new Map());
  assert.equal(r2.draw, true);
  assert.deepEqual(r2.winners.sort(), [0, 1]);
});

test('TDM score limit ends the match', () => {
  const w = world([P(0, 'kaze', 1), P(1, 'kaze', 2)], 'tdm');
  w.mode.teamScore[1] = 30;
  const res = run(w, 2, new Map());
  assert.equal(res.reason, 'score_limit');
  assert.equal(res.winnerTeam, 1);
});

test('Survival: no respawn, last standing wins, placements by death order', () => {
  const w = world([P(0), P(1), P(2)], 'survival');
  lineUp(w, [1000, 1500, 2000]);
  w.kill(w.fighters[2], null, 'test');
  run(w, 200, new Map());
  assert.equal(w.fighters[2].alive, false, 'stays dead');
  assert.equal(w.fighters[2].placement, 3);
  w.kill(w.fighters[1], 0, 'test');
  const res = run(w, 2, new Map());
  assert.equal(res.reason, 'last_standing');
  assert.deepEqual(res.winners, [0]);
  assert.equal(w.fighters[1].placement, 2);
});

test('8 bots fight a full 5-minute FFA without errors and score kills', () => {
  const players = Array.from({ length: 8 }, (_, i) => P(i, ['kaze', 'ryujin', 'akira'][i % 3], 0, true));
  const w = world(players);
  const t0 = Date.now();
  const res = run(w, 9001, new Map());
  const ms = Date.now() - t0;
  assert.ok(res, 'match ended by time');
  const kills = w.fighters.reduce((s, f) => s + f.kills, 0);
  const deaths = w.fighters.reduce((s, f) => s + f.deaths, 0);
  assert.ok(deaths > 5, 'fights happened: deaths=' + deaths);
  assert.ok(kills <= deaths, 'kills never exceed deaths');
  for (const f of w.fighters) assert.ok(Number.isFinite(f.x) && Number.isFinite(f.y));
  assert.ok(ms < 5000, '9000 ticks simulated in ' + ms + ' ms');
});

test('all three maps load and bots survive on them', () => {
  for (const mapId of ['shadow_temple', 'flame_kingdom', 'cyber_city']) {
    const players = Array.from({ length: 6 }, (_, i) => P(i, ['kaze', 'ryujin', 'akira'][i % 3], 0, true));
    const w = new GameWorld({ mapId, mode: 'ffa', players, seed: 3, durationSec: 30 });
    const res = run(w, 1000, new Map());
    assert.ok(res, mapId + ' finished');
    for (const f of w.fighters) assert.ok(Number.isFinite(f.x), mapId);
  }
});

test('dash and attack states run their course', () => {
  const w = world([P(0)]);
  lineUp(w, [500]);
  const f = w.fighters[0];
  run(w, 1, new Map([[0, { mx: 100, b: BTN.DASH }]]));
  assert.equal(f.act, ACT.DASH);
  run(w, 10, new Map());
  assert.equal(f.act, ACT.NONE);
  assert.ok(f.x > 650, 'dash moved far');
});


test('equipment stat bonuses are applied by the server and capped at +30%', () => {
  const { computeStats } = require('../src/game/ItemStats');
  const stats = computeStats({ weapon: { stats: { attack_pct: 12 } }, equipment: { stats: { defense_pct: 15, hp_pct: 10 } } });
  assert.deepEqual(stats, { attack_pct: 12, defense_pct: 15, hp_pct: 10, speed_pct: 0 });
  assert.equal(computeStats({ weapon: { stats: { attack_pct: 90 } } }).attack_pct, 30, 'capped');
  const w = world([Object.assign(P(0, 'ryujin'), { stats }), P(1, 'ryujin')]);
  const [geared, plain] = w.fighters;
  assert.equal(geared.maxHp, Math.round(1100 * 1.1));
  assert.equal(geared.hp, geared.maxHp);
  assert.equal(geared.attack, Math.round(115 * 1.12));
  assert.equal(plain.attack, 115);
  // a geared hit deals more damage than a plain one
  lineUp(w, [600, 650]);
  w.fighters[1].x = 650;
  run(w, 1, new Map([[0, { mx: 0, b: BTN.ATTACK }]]));
  run(w, 10, new Map());
  const dmg = plain.maxHp - plain.hp;
  assert.equal(dmg, Math.round((65 * geared.attack / 100) * (100 / (100 + plain.defense))));
});

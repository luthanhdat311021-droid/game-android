#!/usr/bin/env node
'use strict';
/**
 * Records a deterministic movement trace from the authoritative JS simulation.
 * app/src/test/.../SimParityTest replays the same inputs through the Java port and
 * requires identical positions — that is what keeps client prediction in sync.
 */
const fs = require('fs');
const path = require('path');
const { GameWorld } = require('../src/game/GameWorld');
const { BTN } = require('../src/game/constants');

function script(t) {
  const s = t % 240;
  let mx = 0, my = 0, b = 0;
  if (s < 40) mx = 100;
  else if (s < 50) { mx = 100; if (s === 41) b |= BTN.JUMP; if (s === 49) b |= BTN.JUMP; }
  else if (s < 70) mx = -60;
  else if (s === 72) b |= BTN.DASH;
  else if (s === 85 || s === 88 || s === 95 || s === 103) b |= BTN.ATTACK;
  else if (s === 120) b |= BTN.HEAVY;
  else if (s === 140) b |= BTN.SPECIAL;
  else if (s > 160 && s < 200) { mx = -100; if (s === 170) b |= BTN.JUMP; }
  else if (s === 205) { my = 1; b |= BTN.JUMP; }
  else if (s > 210 && s < 230) b |= BTN.BLOCK;
  return { mx, my, b };
}

const traces = [];
for (const [mapId, charId, x, y] of [['shadow_temple', 'kaze', 600, 832], ['flame_kingdom', 'akira', 1220, 600], ['cyber_city', 'ryujin', 1700, 900]]) {
  const w = new GameWorld({ mapId, mode: 'training', players: [{ id: 0, name: 'T', charId }], seed: 1 });
  const f = w.fighters[0];
  f.x = x; f.y = y; f.energy = 100;
  const frames = [];
  const inputs = [];
  for (let t = 0; t < 720; t++) {
    const inp = script(t);
    inputs.push([inp.mx, inp.my, inp.b]);
    w.step(new Map([[0, inp]]));
    frames.push([f.x, f.y, f.vx, f.vy, f.act, f.actT, f.facing, f.grounded ? 1 : 0]);
  }
  traces.push({ mapId, charId, x, y, inputs, frames });
}
const out = path.join(__dirname, '..', '..', 'app', 'src', 'test', 'resources', 'sim-trace.json');
fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, JSON.stringify(traces));
console.log('wrote', out);

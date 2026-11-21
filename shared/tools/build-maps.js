#!/usr/bin/env node
/*
 * Generates the tile maps in shared/maps/*.json.
 * Both the Node.js game server and the Android client load these files,
 * so collision is identical on both sides.
 *
 * Tile legend:
 *   .  empty            #  solid stone        =  one-way platform (wood)
 *   R  one-way roof     G  solid glowing pad  L  lava (hazard, not solid)
 *   ^  spikes (hazard)  N  solid neon block   W  solid wall/building
 *
 * Run:  node shared/tools/build-maps.js
 */
const fs = require('fs');
const path = require('path');

const TILE = 32;

function grid(w, h) {
  return Array.from({ length: h }, () => Array(w).fill('.'));
}
function fill(g, x0, y0, x1, y1, ch) {
  for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) {
    if (y >= 0 && y < g.length && x >= 0 && x < g[0].length) g[y][x] = ch;
  }
}
/** spawn standing on the surface whose top row is `row`, at tile column `col` */
function spawn(col, row) {
  return [col * TILE + TILE / 2, row * TILE];
}
function out(map) {
  map.rows = map.grid.map(r => r.join(''));
  delete map.grid;
  const file = path.join(__dirname, '..', 'maps', map.id + '.json');
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(map, null, 1));
  console.log('wrote', file, map.width + 'x' + map.height);
}

// ---------------------------------------------------------------- SHADOW TEMPLE
{
  const W = 112, H = 34;
  const g = grid(W, H);
  // outer cliffs (high ground), pits between cliffs and the main floor
  fill(g, 0, 22, 8, H - 1, '#');
  fill(g, 103, 22, W - 1, H - 1, '#');
  // main floor
  fill(g, 15, 26, 96, H - 1, '#');
  // central temple dais with glowing pads
  fill(g, 45, 23, 66, 25, '#');
  fill(g, 49, 23, 53, 23, 'G');
  fill(g, 58, 23, 62, 23, 'G');
  // stone pillars that split the floor into lanes
  fill(g, 33, 22, 34, 25, '#');
  fill(g, 77, 22, 78, 25, '#');
  // one-way platforms at several heights
  fill(g, 20, 19, 29, 19, '=');
  fill(g, 82, 19, 91, 19, '=');
  fill(g, 36, 15, 44, 15, '=');
  fill(g, 67, 15, 75, 15, '=');
  fill(g, 47, 11, 64, 11, 'R');      // temple roof
  fill(g, 24, 8, 31, 8, '=');
  fill(g, 80, 8, 87, 8, '=');
  fill(g, 1, 16, 7, 16, '=');
  fill(g, 104, 16, 110, 16, '=');
  // spikes in the floor gutters
  fill(g, 40, 25, 42, 25, '^');
  fill(g, 69, 25, 71, 25, '^');
  out({
    id: 'shadow_temple', name: 'Shadow Temple', theme: 'temple', tileSize: TILE,
    width: W, height: H, grid: g,
    killY: H * TILE + 160,
    spawns: [spawn(4, 22), spawn(107, 22), spawn(19, 26), spawn(92, 26), spawn(28, 26),
      spawn(83, 26), spawn(50, 23), spawn(61, 23), spawn(24, 19), spawn(87, 19), spawn(40, 15), spawn(71, 15)],
    movers: [],
    props: [
      { type: 'torch', x: 45 * TILE, y: 23 * TILE }, { type: 'torch', x: 67 * TILE - 4, y: 23 * TILE },
      { type: 'lantern', x: 25 * TILE, y: 19 * TILE }, { type: 'lantern', x: 87 * TILE, y: 19 * TILE },
      { type: 'shrine', x: 55.5 * TILE, y: 11 * TILE },
      { type: 'tree', x: 4 * TILE, y: 22 * TILE }, { type: 'tree', x: 108 * TILE, y: 22 * TILE },
      { type: 'banner', x: 33.5 * TILE, y: 22 * TILE }, { type: 'banner', x: 77.5 * TILE, y: 22 * TILE },
      { type: 'grass', x: 18 * TILE, y: 26 * TILE }, { type: 'grass', x: 60 * TILE, y: 26 * TILE },
      { type: 'grass', x: 90 * TILE, y: 26 * TILE }, { type: 'rocks', x: 11 * TILE, y: 22 * TILE },
      { type: 'rocks', x: 100 * TILE, y: 22 * TILE }
    ]
  });
}

// ---------------------------------------------------------------- FLAME KINGDOM
{
  const W = 120, H = 34;
  const g = grid(W, H);
  // lava sea
  fill(g, 0, 31, W - 1, H - 1, 'L');
  // fortress bases and stone bridges
  fill(g, 0, 20, 12, 30, '#');
  fill(g, 107, 20, W - 1, 30, '#');
  fill(g, 13, 24, 36, 25, '#');                 // left bridge
  fill(g, 83, 24, 106, 25, '#');                // right bridge
  fill(g, 46, 26, 73, 30, '#');                 // central keep
  fill(g, 52, 20, 67, 21, '#');                 // keep upper floor
  fill(g, 52, 22, 53, 25, '#');
  fill(g, 66, 22, 67, 25, '#');
  fill(g, 56, 20, 63, 20, 'G');
  // bridge pillars standing in lava
  fill(g, 20, 26, 21, 30, '#');
  fill(g, 98, 26, 99, 30, '#');
  // upper approaches
  fill(g, 16, 17, 26, 17, '=');
  fill(g, 93, 17, 103, 17, '=');
  fill(g, 30, 12, 40, 12, '=');
  fill(g, 79, 12, 89, 12, '=');
  fill(g, 54, 13, 65, 13, 'R');
  fill(g, 2, 14, 9, 14, '=');
  fill(g, 110, 14, 117, 14, '=');
  // lava gutter on the keep
  fill(g, 58, 26, 61, 26, 'L');
  out({
    id: 'flame_kingdom', name: 'Flame Kingdom', theme: 'flame', tileSize: TILE,
    width: W, height: H, grid: g,
    killY: H * TILE + 160,
    spawns: [spawn(5, 20), spawn(114, 20), spawn(18, 24), spawn(101, 24), spawn(30, 24), spawn(89, 24),
      spawn(49, 26), spawn(70, 26), spawn(57, 20), spawn(62, 20), spawn(21, 17), spawn(98, 17)],
    // one-way moving platforms over the lava gaps: base x/y in pixels, width in tiles,
    // offset = amp * sin(2*pi*tick/period)
    movers: [
      { x: 37 * TILE, y: 22 * TILE, w: 4, ax: 4 * TILE, ay: 0, period: 150 },
      { x: 78 * TILE, y: 22 * TILE, w: 4, ax: -4 * TILE, ay: 0, period: 150 },
      { x: 58 * TILE, y: 8 * TILE, w: 4, ax: 0, ay: 2 * TILE, period: 120 }
    ],
    props: [
      { type: 'brazier', x: 14 * TILE, y: 24 * TILE }, { type: 'brazier', x: 105 * TILE, y: 24 * TILE },
      { type: 'brazier', x: 47 * TILE, y: 26 * TILE }, { type: 'brazier', x: 72 * TILE, y: 26 * TILE },
      { type: 'banner', x: 52.5 * TILE, y: 22 * TILE }, { type: 'banner', x: 66.5 * TILE, y: 22 * TILE },
      { type: 'rocks', x: 6 * TILE, y: 20 * TILE }, { type: 'rocks', x: 112 * TILE, y: 20 * TILE }
    ]
  });
}

// ---------------------------------------------------------------- CYBER CITY
{
  const W = 124, H = 36;
  const g = grid(W, H);
  // lower street level spans the whole map
  fill(g, 0, 32, W - 1, H - 1, '#');
  // buildings
  fill(g, 0, 18, 9, 31, 'W');
  fill(g, 114, 18, W - 1, 31, 'W');
  fill(g, 18, 24, 29, 31, 'W');
  fill(g, 38, 20, 47, 31, 'W');
  fill(g, 76, 20, 85, 31, 'W');
  fill(g, 94, 24, 105, 31, 'W');
  fill(g, 56, 26, 67, 31, 'W');
  // neon trims on rooftops (solid)
  fill(g, 18, 24, 29, 24, 'N');
  fill(g, 38, 20, 47, 20, 'N');
  fill(g, 76, 20, 85, 20, 'N');
  fill(g, 94, 24, 105, 24, 'N');
  fill(g, 56, 26, 67, 26, 'G');
  // sky bridges and signs (one-way)
  fill(g, 10, 22, 17, 22, '=');
  fill(g, 106, 22, 113, 22, '=');
  fill(g, 48, 16, 75, 16, 'R');
  fill(g, 30, 14, 37, 14, '=');
  fill(g, 86, 14, 93, 14, '=');
  fill(g, 58, 9, 65, 9, '=');
  fill(g, 2, 12, 8, 12, '=');
  fill(g, 115, 12, 121, 12, '=');
  // electrified grates in the street
  fill(g, 33, 32, 35, 32, '^');
  fill(g, 88, 32, 90, 32, '^');
  out({
    id: 'cyber_city', name: 'Cyber City', theme: 'cyber', tileSize: TILE,
    width: W, height: H, grid: g,
    killY: H * TILE + 160,
    spawns: [spawn(4, 18), spawn(119, 18), spawn(12, 32), spawn(111, 32), spawn(23, 24), spawn(100, 24),
      spawn(42, 20), spawn(80, 20), spawn(52, 32), spawn(71, 32), spawn(60, 26), spawn(63, 26)],
    movers: [
      { x: 50 * TILE, y: 27 * TILE, w: 3, ax: 0, ay: 3 * TILE, period: 140 },
      { x: 70 * TILE, y: 27 * TILE, w: 3, ax: 0, ay: -3 * TILE, period: 140 }
    ],
    props: [
      { type: 'neon_sign', x: 42.5 * TILE, y: 20 * TILE }, { type: 'neon_sign', x: 80.5 * TILE, y: 20 * TILE },
      { type: 'antenna', x: 5 * TILE, y: 18 * TILE }, { type: 'antenna', x: 118 * TILE, y: 18 * TILE },
      { type: 'lamp', x: 15 * TILE, y: 32 * TILE }, { type: 'lamp', x: 50 * TILE, y: 32 * TILE },
      { type: 'lamp', x: 73 * TILE, y: 32 * TILE }, { type: 'lamp', x: 109 * TILE, y: 32 * TILE }
    ]
  });
}

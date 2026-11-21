'use strict';
const fs = require('fs');
const path = require('path');

const SHARED = process.env.SHARED_DIR || path.join(__dirname, '..', '..', '..', 'shared');

const SOLID = new Set(['#', 'G', 'N', 'W']);
const ONE_WAY = new Set(['=', 'R']);
const HAZARD = new Set(['L', '^']);

/** Tile map + moving platforms. World units: x right, y down, (0,0) = top-left. */
class GameMap {
  constructor(json) {
    this.id = json.id;
    this.name = json.name;
    this.theme = json.theme;
    this.T = json.tileSize;
    this.w = json.width;
    this.h = json.height;
    this.rows = json.rows;
    this.pixelW = this.w * this.T;
    this.pixelH = this.h * this.T;
    this.killY = json.killY;
    this.spawns = json.spawns.map(([x, y]) => ({ x, y }));
    this.moverDefs = json.movers || [];
    this.movers = this.moverDefs.map(() => ({ x: 0, y: 0, w: 0, dx: 0, dy: 0 }));
    this.updateMovers(0);
  }

  tile(col, row) {
    if (col < 0 || col >= this.w) return '#';         // side walls
    if (row < 0 || row >= this.h) return '.';         // open sky / bottomless pit
    return this.rows[row][col];
  }
  solid(col, row) { return SOLID.has(this.tile(col, row)); }
  oneWay(col, row) { return ONE_WAY.has(this.tile(col, row)); }
  hazard(col, row) { return HAZARD.has(this.tile(col, row)); }

  /** Moving platform positions are a pure function of the tick, so clients can reproduce them. */
  updateMovers(tick) {
    for (let i = 0; i < this.moverDefs.length; i++) {
      const d = this.moverDefs[i];
      const s = Math.sin((2 * Math.PI * tick) / d.period);
      const m = this.movers[i];
      const nx = d.x + d.ax * s, ny = d.y + d.ay * s;
      m.dx = tick === 0 ? 0 : nx - m.x;
      m.dy = tick === 0 ? 0 : ny - m.y;
      m.x = nx; m.y = ny; m.w = d.w * this.T;
    }
  }
}

const cache = new Map();
function loadMap(id) {
  if (!/^[a-z_]+$/.test(id)) throw new Error('bad map id');
  if (!cache.has(id)) {
    cache.set(id, JSON.parse(fs.readFileSync(path.join(SHARED, 'maps', id + '.json'), 'utf8')));
  }
  return new GameMap(cache.get(id));
}

let defs = null;
function loadDefs() {
  if (!defs) defs = JSON.parse(fs.readFileSync(path.join(SHARED, 'characters.json'), 'utf8'));
  return defs;
}

module.exports = { GameMap, loadMap, loadDefs, SOLID, ONE_WAY, HAZARD };

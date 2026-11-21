'use strict';
/**
 * Tile collision for fighters. Mirrored 1:1 in app/.../game/sim/Physics.java so the
 * client can predict its own movement. Movement is split in sub-steps of at most
 * STEP units so fast dashes cannot tunnel through one-tile walls.
 */
const STEP = 12;
const EPS = 0.001;

function rowsOf(f, map) {
  return [Math.floor((f.y - f.h) / map.T), Math.floor((f.y - EPS) / map.T)];
}

function solidColumn(map, col, r0, r1) {
  for (let r = r0; r <= r1; r++) if (map.solid(col, r)) return true;
  return false;
}

function moveX(f, map, dx) {
  if (dx === 0) return;
  const steps = Math.ceil(Math.abs(dx) / STEP);
  const step = dx / steps;
  const hw = f.w / 2;
  for (let i = 0; i < steps; i++) {
    f.x += step;
    const [r0, r1] = rowsOf(f, map);
    if (step > 0) {
      const col = Math.floor((f.x + hw - EPS) / map.T);
      if (solidColumn(map, col, r0, r1)) { f.x = col * map.T - hw; f.vx = 0; return; }
    } else {
      const col = Math.floor((f.x - hw) / map.T);
      if (solidColumn(map, col, r0, r1)) { f.x = (col + 1) * map.T + hw; f.vx = 0; return; }
    }
  }
}

/** Returns true when the fighter landed this call. */
function moveY(f, map, dy) {
  if (dy === 0) return false;
  const steps = Math.ceil(Math.abs(dy) / STEP);
  const step = dy / steps;
  const hw = f.w / 2;
  for (let i = 0; i < steps; i++) {
    const prevBottom = f.y;
    f.y += step;
    const c0 = Math.floor((f.x - hw) / map.T), c1 = Math.floor((f.x + hw - EPS) / map.T);
    if (step > 0) {
      const row = Math.floor((f.y - EPS) / map.T);
      const top = row * map.T;
      for (let c = c0; c <= c1; c++) {
        const solid = map.solid(c, row);
        const oneWay = !solid && f.dropT === 0 && map.oneWay(c, row) && prevBottom <= top + EPS;
        if (solid || oneWay) {
          f.y = top; f.vy = 0; f.onMover = -1; f.groundOneWay = oneWay;
          return true;
        }
      }
      if (f.dropT === 0) {
        for (let m = 0; m < map.movers.length; m++) {
          const mv = map.movers[m];
          if (f.x + hw <= mv.x || f.x - hw >= mv.x + mv.w) continue;
          if (prevBottom <= mv.y + Math.abs(mv.dy) + 1 && f.y >= mv.y) {
            f.y = mv.y; f.vy = 0; f.onMover = m; f.groundOneWay = true;
            return true;
          }
        }
      }
    } else {
      const row = Math.floor((f.y - f.h) / map.T);
      for (let c = c0; c <= c1; c++) {
        if (map.solid(c, row)) { f.y = (row + 1) * map.T + f.h; f.vy = 0; return false; }
      }
    }
  }
  return false;
}

/** Integrate velocity and resolve collisions. Updates f.grounded. */
function integrate(f, map, dt) {
  moveX(f, map, f.vx * dt);
  const landed = moveY(f, map, f.vy * dt);
  f.grounded = landed;
  if (!landed) f.onMover = -1;
}

/** Does the fighter's body overlap a hazard tile (lava, spikes)? */
function inHazard(f, map) {
  const hw = f.w / 2;
  const c0 = Math.floor((f.x - hw) / map.T), c1 = Math.floor((f.x + hw - EPS) / map.T);
  const [r0, r1] = rowsOf(f, map);
  for (let r = r0; r <= r1; r++) for (let c = c0; c <= c1; c++) if (map.hazard(c, r)) return true;
  return false;
}

/** Is there solid ground under the point (x, y + 4)? Used by bots. */
function groundBelow(map, x, y, depth) {
  const col = Math.floor(x / map.T);
  const r0 = Math.floor((y + 2) / map.T);
  for (let r = r0; r < r0 + depth; r++) if (map.solid(col, r) || map.oneWay(col, r)) return true;
  return false;
}

function rectsOverlap(ax, ay, aw, ah, bx, by, bw, bh) {
  return ax < bx + bw && ax + aw > bx && ay < by + bh && ay + ah > by;
}

module.exports = { integrate, inHazard, groundBelow, rectsOverlap, moveX, moveY };

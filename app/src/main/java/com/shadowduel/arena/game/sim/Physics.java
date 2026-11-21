package com.shadowduel.arena.game.sim;

/** Port of server/src/game/PhysicsSystem.js — keep the two identical (client prediction depends on it). */
public final class Physics {
    private Physics() {
    }

    static final double STEP = 12;
    static final double EPS = 0.001;

    private static int row0(Fighter f, GameMap m) {
        return (int) Math.floor((f.y - f.h) / m.T);
    }

    private static int row1(Fighter f, GameMap m) {
        return (int) Math.floor((f.y - EPS) / m.T);
    }

    private static boolean solidColumn(GameMap map, int col, int r0, int r1) {
        for (int r = r0; r <= r1; r++) if (map.solid(col, r)) return true;
        return false;
    }

    static void moveX(Fighter f, GameMap map, double dx) {
        if (dx == 0) return;
        int steps = (int) Math.ceil(Math.abs(dx) / STEP);
        double step = dx / steps;
        double hw = f.w / 2;
        for (int i = 0; i < steps; i++) {
            f.x += step;
            int r0 = row0(f, map), r1 = row1(f, map);
            if (step > 0) {
                int col = (int) Math.floor((f.x + hw - EPS) / map.T);
                if (solidColumn(map, col, r0, r1)) {
                    f.x = col * map.T - hw;
                    f.vx = 0;
                    return;
                }
            } else {
                int col = (int) Math.floor((f.x - hw) / map.T);
                if (solidColumn(map, col, r0, r1)) {
                    f.x = (col + 1) * map.T + hw;
                    f.vx = 0;
                    return;
                }
            }
        }
    }

    static boolean moveY(Fighter f, GameMap map, double dy) {
        if (dy == 0) return false;
        int steps = (int) Math.ceil(Math.abs(dy) / STEP);
        double step = dy / steps;
        double hw = f.w / 2;
        for (int i = 0; i < steps; i++) {
            double prevBottom = f.y;
            f.y += step;
            int c0 = (int) Math.floor((f.x - hw) / map.T), c1 = (int) Math.floor((f.x + hw - EPS) / map.T);
            if (step > 0) {
                int row = (int) Math.floor((f.y - EPS) / map.T);
                double top = row * map.T;
                for (int c = c0; c <= c1; c++) {
                    boolean solid = map.solid(c, row);
                    boolean oneWay = !solid && f.dropT == 0 && map.oneWay(c, row) && prevBottom <= top + EPS;
                    if (solid || oneWay) {
                        f.y = top;
                        f.vy = 0;
                        f.onMover = -1;
                        f.groundOneWay = oneWay;
                        return true;
                    }
                }
                if (f.dropT == 0) {
                    for (int m = 0; m < map.movers.length; m++) {
                        GameMap.Mover mv = map.movers[m];
                        if (f.x + hw <= mv.x || f.x - hw >= mv.x + mv.w) continue;
                        if (prevBottom <= mv.y + Math.abs(mv.dy) + 1 && f.y >= mv.y) {
                            f.y = mv.y;
                            f.vy = 0;
                            f.onMover = m;
                            f.groundOneWay = true;
                            return true;
                        }
                    }
                }
            } else {
                int row = (int) Math.floor((f.y - f.h) / map.T);
                for (int c = c0; c <= c1; c++) {
                    if (map.solid(c, row)) {
                        f.y = (row + 1) * map.T + f.h;
                        f.vy = 0;
                        return false;
                    }
                }
            }
        }
        return false;
    }

    static void integrate(Fighter f, GameMap map, double dt) {
        moveX(f, map, f.vx * dt);
        boolean landed = moveY(f, map, f.vy * dt);
        f.grounded = landed;
        if (!landed) f.onMover = -1;
    }

    public static boolean inHazard(Fighter f, GameMap map) {
        double hw = f.w / 2;
        int c0 = (int) Math.floor((f.x - hw) / map.T), c1 = (int) Math.floor((f.x + hw - EPS) / map.T);
        int r0 = row0(f, map), r1 = row1(f, map);
        for (int r = r0; r <= r1; r++) for (int c = c0; c <= c1; c++) if (map.hazard(c, r)) return true;
        return false;
    }

    public static boolean groundBelow(GameMap map, double x, double y, int depth) {
        int col = (int) Math.floor(x / map.T);
        int r0 = (int) Math.floor((y + 2) / map.T);
        for (int r = r0; r < r0 + depth; r++) if (map.solid(col, r) || map.oneWay(col, r)) return true;
        return false;
    }

    public static boolean overlap(double ax, double ay, double aw, double ah, double bx, double by, double bw, double bh) {
        return ax < bx + bw && ax + aw > bx && ay < by + bh && ay + ah > by;
    }
}

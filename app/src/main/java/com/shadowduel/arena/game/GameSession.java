package com.shadowduel.arena.game;

import com.shadowduel.arena.game.sim.Fighter;
import com.shadowduel.arena.game.sim.GameMap;
import com.shadowduel.arena.game.sim.Input;
import com.shadowduel.arena.game.sim.World;

import java.util.List;

/**
 * What the renderer and HUD need from a match, offline (local World with bots)
 * or online (server snapshots + local prediction).
 */
public interface GameSession {

    final class ScoreRow {
        public int id, kills, deaths, assists, damage, score;
        public String name;
        public String charId;
        public int team;
        public boolean alive;
    }

    final class FinalResult {
        public String reason = "";
        public boolean draw;
        public int[] winners = new int[0];
        public int winnerTeam, redScore, blueScore, mvp = -1, topKills = -1, mostDamage = -1;
        public List<ScoreRow> standings;
        public int[] placements;
        public String matchId;
    }

    GameMap map();

    String modeId();

    int tickRate();

    /** Fighter controlled on this phone, or -1. */
    int localId();

    List<Fighter> fighters();

    Fighter fighter(int id);

    List<World.Projectile> projectiles();

    List<World.Zone> zones();

    /** Events produced since the last call (hits, kills, spawns). */
    List<World.Event> drainEvents();

    /** Advance one fixed tick with the local player's input. */
    void tick(Input local);

    /** Update draw positions (rx, ry) for this frame. alpha = fraction of the current tick elapsed. */
    void interpolate(float alpha, long nowMs);

    int timeLeftTicks();

    int[] teamScores();

    double[] safeZone();

    List<ScoreRow> scoreboard();

    /** Non-null once the match is over. */
    FinalResult result();

    /** Connection status text for the HUD, or null when offline. */
    String netStatus();

    int pingMs();

    void close();
}

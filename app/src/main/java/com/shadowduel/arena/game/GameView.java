package com.shadowduel.arena.game;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.shadowduel.arena.game.render.Camera;
import com.shadowduel.arena.game.render.CharacterRenderer;
import com.shadowduel.arena.game.render.Hud;
import com.shadowduel.arena.game.render.Palette;
import com.shadowduel.arena.game.render.StageRenderer;
import com.shadowduel.arena.game.render.TouchControls;
import com.shadowduel.arena.game.render.Vfx;
import com.shadowduel.arena.game.sim.Const;
import com.shadowduel.arena.game.sim.Fighter;
import com.shadowduel.arena.game.sim.Input;
import com.shadowduel.arena.game.sim.World;

import java.util.List;

/**
 * Game screen: a render thread runs the fixed 30 Hz simulation step and draws
 * interpolated frames (~60 fps) on a hardware-accelerated SurfaceView canvas.
 */
public final class GameView extends SurfaceView implements SurfaceHolder.Callback, Runnable {

    public interface Listener {
        void onMatchOver(GameSession.FinalResult result);

        void onPauseRequested();

        /** Local player was hit / scored a KO (for vibration). */
        void onFeedback(boolean gotHit, boolean scoredKo);
    }

    private GameSession session;
    private Listener listener;
    private Thread thread;
    private volatile boolean running, paused;
    private final Camera camera = new Camera();
    private final CharacterRenderer chars = new CharacterRenderer();
    private final Vfx vfx = new Vfx();
    private final Hud hud = new Hud();
    private final TouchControls controls = new TouchControls();
    private StageRenderer stage;
    private final Paint debug = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean resultSent;
    private float time;
    private float fps;
    public boolean showFps;
    private int keyAxis;

    public GameView(Context c) {
        this(c, null);
    }

    public GameView(Context c, AttributeSet a) {
        super(c, a);
        getHolder().addCallback(this);
        setFocusable(true);
        setFocusableInTouchMode(true);
        debug.setColor(0xFFB8C0E0);
        debug.setTextSize(28);
    }

    public void start(GameSession s, Listener l) {
        session = s;
        listener = l;
        stage = new StageRenderer(s.map());
        resultSent = false;
        camera.snap();
    }

    public GameSession session() {
        return session;
    }

    public Hud hud() {
        return hud;
    }

    public void setQuality(int level) { // 0 low, 1 medium, 2 high
        vfx.quality = level == 0 ? 0.35f : level == 1 ? 0.7f : 1f;
        if (stage != null) stage.highQuality = level > 0;
    }

    public void setControlsOpacity(float a) {
        controls.opacity = a;
    }

    public void setPaused(boolean p) {
        paused = p;
    }

    // ------------------------------------------------------------------ surface
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        running = true;
        thread = new Thread(this, "game-loop");
        thread.start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        camera.resize(w, h);
        controls.layout(w, h);
        hud.layout(w, h);
        debug.setTextSize(h * 0.025f);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        running = false;
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ loop
    @Override
    public void run() {
        if (session == null) return;
        final double tickDt = 1.0 / session.tickRate();
        long last = System.nanoTime();
        double acc = 0;
        while (running) {
            long now = System.nanoTime();
            double frame = Math.min(0.25, (now - last) / 1e9);
            last = now;
            fps = fps * 0.9f + (float) (1 / Math.max(1e-4, frame)) * 0.1f;
            if (!paused || session.netStatus() != null) { // online matches keep running while paused
                acc += frame;
                while (acc >= tickDt) {
                    Input in = paused ? new Input() : controls.sample();
                    session.tick(in);
                    acc -= tickDt;
                }
            }
            session.interpolate((float) (acc / tickDt), SystemClock.uptimeMillis());
            handleEvents();
            render((float) frame);
            GameSession.FinalResult r = session.result();
            if (r != null && !resultSent) {
                resultSent = true;
                postDelayed(() -> listener.onMatchOver(r), 1800);
            }
            long spent = System.nanoTime() - now;
            long target = 16_000_000L;
            if (spent < target) SystemClock.sleep((target - spent) / 1_000_000L);
        }
    }

    private void handleEvents() {
        List<World.Event> evs = session.drainEvents();
        int me = session.localId();
        for (World.Event e : evs) {
            Fighter v = session.fighter(e.v);
            switch (e.type) {
                case World.Event.HIT: {
                    Fighter a = e.a >= 0 ? session.fighter(e.a) : null;
                    int col = a != null ? Palette.style(a.charId).accent : 0xFFFF7A1F;
                    vfx.hit((float) e.x, (float) e.y, col, e.d, e.blocked, e.kind);
                    if (v != null) v.hitFlash = 1f;
                    if (e.v == me) {
                        camera.addShake(e.d > 100 ? 9 : 5);
                        listener.onFeedback(true, false);
                    } else if (e.a == me) camera.addShake(2.5f);
                    break;
                }
                case World.Event.KILL: {
                    Fighter k = e.a >= 0 ? session.fighter(e.a) : null;
                    int vc = v != null ? Palette.forFighter(v.slot, v.team) : 0xFFFFFFFF;
                    vfx.knockout((float) e.x, (float) e.y - 40, v != null ? Palette.style(v.charId).accent : 0xFFFF3B5C);
                    if (k != null && "fireworks".equals(k.killFx)) vfx.fireworks((float) e.x, (float) e.y - 60);
                    hud.killFeed(k != null ? k.name : null, k != null ? Palette.forFighter(k.slot, k.team) : 0,
                            v != null ? v.name : "?", vc, e.kind == null ? "" : e.kind);
                    if (e.a == me && e.v != me) {
                        hud.showBanner(e.assists.length > 0 ? "K.O.!  +1" : "K.O.!");
                        listener.onFeedback(false, true);
                    }
                    if (e.v == me) camera.addShake(14);
                    break;
                }
                case World.Event.SPAWN:
                    if (v != null) vfx.spawnBeam((float) e.x, (float) e.y, Palette.forFighter(v.slot, v.team));
                    if (v != null && "lightning".equals(v.spawnFx)) vfx.lightningStrike((float) e.x, (float) e.y);
                    break;
                case World.Event.LEFT:
                    if (v != null) hud.showBanner(v.name + " đã rời trận");
                    break;
            }
        }
    }

    private Fighter cameraTarget() {
        Fighter me = session.fighter(session.localId());
        if (me != null && (me.alive || me.respawnT > 0)) return me;
        if ("survival".equals(session.modeId()) || me == null) {
            for (Fighter f : session.fighters()) if (f.alive) return f;
        }
        return me;
    }

    private void render(float dt) {
        SurfaceHolder holder = getHolder();
        Canvas c;
        try {
            c = holder.lockHardwareCanvas();
        } catch (Exception e) {
            c = holder.lockCanvas();
        }
        if (c == null) return;
        try {
            time += dt;
            vfx.update(dt);
            Fighter target = cameraTarget();
            if (target != null) camera.update((float) target.rx, (float) target.ry, session.map(), dt);
            else camera.update((float) session.map().pixelW / 2, (float) session.map().pixelH / 2, session.map(), dt);

            stage.drawBackground(c, camera, time);
            c.save();
            camera.apply(c);
            stage.drawProps(c, camera, time);
            stage.drawTiles(c, camera, time);
            stage.drawMovers(c);
            for (World.Zone z : session.zones()) vfx.drawZone(c, z, time);
            Fighter self = session.fighter(session.localId());
            for (Fighter f : session.fighters()) {
                if (f != self) drawFighter(c, f, dt);
            }
            if (self != null) drawFighter(c, self, dt);
            for (World.Projectile p : session.projectiles()) vfx.drawProjectile(c, p, time);
            vfx.draw(c);
            stage.drawSafeZone(c, camera, session.safeZone(), time);
            c.restore();

            if (self != null) {
                controls.setEnergy((float) (self.energy / self.def.special.cost),
                        self.energy >= self.def.special.cost && self.specialCd == 0);
            }
            hud.draw(c, session, camera, dt, time);
            controls.draw(c, dt);
            if (showFps) {
                c.drawText(String.format(java.util.Locale.US, "%.0f FPS · %d vfx", fps, vfx.count()),
                        camera.screenW * 0.012f, camera.screenH * 0.29f, debug);
            }
        } finally {
            holder.unlockCanvasAndPost(c);
        }
    }

    private void drawFighter(Canvas c, Fighter f, float dt) {
        if (!f.known) return;
        if (!camera.visible(f.rx, f.ry, 160)) return;
        int anim = f.anim();
        if (anim != f.lastAnim) {
            f.lastAnim = anim;
            f.animTime = 0;
        }
        f.animTime += dt;
        if (anim == Const.ANIM_DEAD && f.animTime > 1.2f) return;
        f.hitFlash = Math.max(0, f.hitFlash - dt * 6);
        float alpha = 1f;
        if (anim == Const.ANIM_DEAD) alpha = Math.max(0, 1 - f.animTime / 1.2f);
        else if (f.protectT > 0) alpha = 0.55f + 0.45f * (float) Math.abs(Math.sin(time * 14));
        vfx.fighterFx(f, dt);
        vfx.drawSlash(c, f);
        float speedFrac = (float) Math.min(1, Math.abs(f.vx) / Math.max(1, f.speed));
        chars.setSkin(f.skin);
        chars.setWeapon(f.trail, f.blade);
        chars.draw(c, f.charId, (float) f.rx, (float) f.ry, f.facing, anim, f.animTime, f.actionProgress(),
                speedFrac, f.hitFlash, alpha, 1f);
    }

    // ------------------------------------------------------------------ input
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            float x = e.getX(), y = e.getY();
            if (hud.pauseBtn.contains(x, y)) {
                post(() -> listener.onPauseRequested());
                return true;
            }
            if (hud.scoreBtn.contains(x, y)) {
                hud.showScoreboard = !hud.showScoreboard;
                return true;
            }
        }
        return controls.onTouch(e);
    }

    /** Keyboard support (emulator / Chromebook): A/D move, W/Space jump, J attack, K heavy, L special, Shift dash, S block. */
    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        return key(code, true) || super.onKeyDown(code, e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        return key(code, false) || super.onKeyUp(code, e);
    }

    private boolean key(int code, boolean down) {
        switch (code) {
            case KeyEvent.KEYCODE_A: case KeyEvent.KEYCODE_DPAD_LEFT:
                keyAxis = down ? -1 : (keyAxis == -1 ? 0 : keyAxis);
                controls.key(0, down, keyAxis);
                return true;
            case KeyEvent.KEYCODE_D: case KeyEvent.KEYCODE_DPAD_RIGHT:
                keyAxis = down ? 1 : (keyAxis == 1 ? 0 : keyAxis);
                controls.key(0, down, keyAxis);
                return true;
            case KeyEvent.KEYCODE_W: case KeyEvent.KEYCODE_SPACE: case KeyEvent.KEYCODE_DPAD_UP:
                controls.key(Const.BTN_JUMP, down, Integer.MIN_VALUE);
                return true;
            case KeyEvent.KEYCODE_J: controls.key(Const.BTN_ATTACK, down, Integer.MIN_VALUE); return true;
            case KeyEvent.KEYCODE_K: controls.key(Const.BTN_HEAVY, down, Integer.MIN_VALUE); return true;
            case KeyEvent.KEYCODE_L: controls.key(Const.BTN_SPECIAL, down, Integer.MIN_VALUE); return true;
            case KeyEvent.KEYCODE_SHIFT_LEFT: controls.key(Const.BTN_DASH, down, Integer.MIN_VALUE); return true;
            case KeyEvent.KEYCODE_S: controls.key(Const.BTN_BLOCK, down, Integer.MIN_VALUE); return true;
            case KeyEvent.KEYCODE_TAB:
                if (down) hud.showScoreboard = !hud.showScoreboard;
                return true;
        }
        return false;
    }
}

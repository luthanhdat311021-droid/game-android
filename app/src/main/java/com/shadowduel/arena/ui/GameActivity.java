package com.shadowduel.arena.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shadowduel.arena.App;
import com.shadowduel.arena.data.Prefs;
import com.shadowduel.arena.data.Supa;
import com.shadowduel.arena.databinding.ActivityGameBinding;
import com.shadowduel.arena.game.GameAssets;
import com.shadowduel.arena.game.GameSession;
import com.shadowduel.arena.game.GameView;
import com.shadowduel.arena.game.OfflineSession;
import com.shadowduel.arena.net.GameClient;
import com.shadowduel.arena.net.OnlineSession;

import org.json.JSONObject;

/** Multiplayer Game Screen (online) and offline/Training matches. */
public class GameActivity extends AppCompatActivity implements GameView.Listener, GameClient.Listener {
    public static volatile boolean running;
    public static String lastMatchId;

    /** Result handed to MatchResultActivity. */
    public static GameSession.FinalResult lastResult;
    public static String lastMode, lastMap;
    public static boolean lastOnline;
    public static int lastLocalId;
    public static Intent lastOfflineIntent;

    private ActivityGameBinding v;
    private GameSession session;
    private boolean online;
    private Vibrator vibrator;
    private Prefs prefs;

    public static Intent offline(Context c, String mode, String map, int bots) {
        return new Intent(c, GameActivity.class).putExtra("mode", mode).putExtra("map", map).putExtra("bots", bots);
    }

    public static void startOnline(Activity a) {
        if (running) return;
        running = true;
        a.startActivity(new Intent(a, GameActivity.class).putExtra("online", true));
        a.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        running = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        v = ActivityGameBinding.inflate(getLayoutInflater());
        setContentView(v.getRoot());
        hideSystemBars();
        prefs = App.get().prefs();
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        online = getIntent().getBooleanExtra("online", false);
        try {
            if (online) {
                GameClient client = App.get().client();
                JSONObject start = client.matchStart;
                if (start == null) throw new IllegalStateException("no match");
                OnlineSession os = new OnlineSession(this, client, start);
                lastMatchId = os.matchId();
                lastMap = start.optString("map");
                session = os;
            } else {
                String mode = getIntent().getStringExtra("mode");
                String map = getIntent().getStringExtra("map");
                String name = Supa.signedIn(this) ? Supa.username(this) : prefs.guestName();
                session = new OfflineSession(GameAssets.defs(this), GameAssets.map(this, map), mode, name,
                        prefs.character(), getIntent().getIntExtra("bots", 3), prefs.botLevel() / 100f, false);
                lastMap = map;
                lastOfflineIntent = getIntent();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Không bắt đầu được trận: " + e.getMessage(), Toast.LENGTH_LONG).show();
            running = false;
            finish();
            return;
        }
        GameView gv = v.gameView;
        gv.start(session, this);
        gv.setQuality(prefs.quality());
        gv.setControlsOpacity(prefs.controlsOpacity() / 100f);
        gv.showFps = prefs.showFps();
        gv.requestFocus();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                onPauseRequested();
            }
        });
    }

    private void hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        c.hide(WindowInsetsCompat.Type.systemBars());
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (online) App.get().client().addListener(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (online) App.get().client().removeListener(this);
        if (!online && session != null && session.result() == null) v.gameView.setPaused(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        running = false;
        if (session != null) session.close();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    // ------------------------------------------------------------ GameView.Listener
    @Override
    public void onMatchOver(GameSession.FinalResult result) {
        if (isFinishing()) return;
        lastResult = result;
        lastMode = session.modeId();
        lastOnline = online;
        lastLocalId = session.localId();
        startActivity(new Intent(this, MatchResultActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    @Override
    public void onPauseRequested() {
        if (isFinishing()) return;
        boolean training = session instanceof OfflineSession && ((OfflineSession) session).training;
        if (!online) v.gameView.setPaused(true);
        java.util.List<String> items = new java.util.ArrayList<>();
        items.add("Tiếp tục");
        items.add("Bảng điểm");
        if (training) {
            items.add("Hồi đầy nhân vật (reset)");
            items.add("Dùng vật phẩm từ Kho đồ");
            items.add("BOT tấn công: bật / tắt");
        }
        items.add(online ? "Rời trận (về sảnh)" : training ? "Kết thúc luyện tập" : "Thoát trận");
        boolean[] botsAggressive = {false};
        new MaterialAlertDialogBuilder(this)
                .setTitle(online ? "Menu (trận vẫn tiếp diễn)" : "Tạm dừng")
                .setItems(items.toArray(new String[0]), (d, w) -> {
                    String it = items.get(w);
                    if (it.startsWith("Bảng")) v.gameView.hud().showScoreboard = true;
                    else if (it.startsWith("Hồi")) ((OfflineSession) session).resetTraining();
                    else if (it.startsWith("Dùng")) {
                        useConsumable();
                        return;
                    }
                    else if (it.startsWith("BOT")) {
                        botsAggressive[0] = !botsAggressive[0];
                        ((OfflineSession) session).setBotsPassive(!botsAggressive[0]);
                        Toast.makeText(this, botsAggressive[0] ? "BOT sẽ tấn công" : "BOT đứng yên", Toast.LENGTH_SHORT).show();
                    } else if (it.startsWith("Rời")) {
                        App.get().client().send(GameClient.json("t", "leave_room"));
                        App.get().client().room = null;
                        finish();
                        return;
                    } else if (it.startsWith("Kết thúc") || it.startsWith("Thoát")) {
                        ((OfflineSession) session).finishNow();
                        return;
                    }
                    v.gameView.setPaused(false);
                })
                .setOnCancelListener(d -> v.gameView.setPaused(false))
                .show();
    }

    /** Training only: consume an owned item (server decrements it), then apply its training effect locally. */
    private void useConsumable() {
        if (!Supa.signedIn(this)) {
            Toast.makeText(this, "Cần đăng nhập để dùng vật phẩm", Toast.LENGTH_SHORT).show();
            v.gameView.setPaused(false);
            return;
        }
        Supa.select(this, "player_inventory?select=item_id,quantity,items(name,category,gameplay_effect)&player_id=eq." + Supa.userId(this), (rows, err) -> {
            java.util.List<org.json.JSONObject> list = new java.util.ArrayList<>();
            for (int i = 0; rows != null && i < rows.length(); i++) {
                org.json.JSONObject r = rows.optJSONObject(i), it = r.optJSONObject("items");
                if (it != null && "consumable".equals(it.optString("category"))) list.add(r);
            }
            if (list.isEmpty()) {
                Toast.makeText(this, err != null ? err : "Kho đồ không có vật phẩm tiêu hao", Toast.LENGTH_SHORT).show();
                v.gameView.setPaused(false);
                return;
            }
            String[] names = new String[list.size()];
            for (int i = 0; i < names.length; i++) names[i] = list.get(i).optJSONObject("items").optString("name") + "  ×" + list.get(i).optInt("quantity");
            new MaterialAlertDialogBuilder(this).setTitle("Dùng vật phẩm (Training)").setItems(names, (d, w) -> {
                org.json.JSONObject r = list.get(w);
                Supa.rpc(this, "use_item", GameClient.json("p_item", r.optString("item_id"), "p_request_id", java.util.UUID.randomUUID().toString()), (res, e2) -> {
                    if (e2 != null) Toast.makeText(this, e2, Toast.LENGTH_SHORT).show();
                    else Toast.makeText(this, ((OfflineSession) session).applyTrainingEffect(((org.json.JSONObject) res).optJSONObject("effect")), Toast.LENGTH_SHORT).show();
                    v.gameView.setPaused(false);
                });
            }).setOnCancelListener(d -> v.gameView.setPaused(false)).show();
        });
    }

    @Override
    public void onFeedback(boolean gotHit, boolean scoredKo) {
        if (!prefs.vibration() || vibrator == null) return;
        long ms = scoredKo ? 60 : 25;
        if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(ms, scoredKo ? 200 : 90));
    }

    // ------------------------------------------------------------ GameClient.Listener (online)
    @Override
    public void onMessage(String type, JSONObject msg) {
        if ("match_aborted".equals(type)) {
            Toast.makeText(this, "Trận đấu bị hủy (không còn đủ người chơi)", Toast.LENGTH_LONG).show();
            finish();
        } else if ("kicked".equals(type)) {
            Toast.makeText(this, "Phiên đăng nhập đã chuyển sang thiết bị khác", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    public void onConnection(GameClient.State state, String error) {
        if (state == GameClient.State.OFFLINE) {
            runOnUiThread(() -> {
                Toast.makeText(this, error != null ? error : "Mất kết nối với game server", Toast.LENGTH_LONG).show();
                finish();
            });
        }
    }
}

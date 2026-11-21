package com.shadowduel.arena.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shadowduel.arena.App;
import com.shadowduel.arena.R;
import com.shadowduel.arena.data.LobbyRealtime;
import com.shadowduel.arena.data.Net;
import com.shadowduel.arena.data.Prefs;
import com.shadowduel.arena.data.Supa;
import com.shadowduel.arena.databinding.ActivityMainBinding;
import com.shadowduel.arena.net.GameClient;

import org.json.JSONObject;

import java.util.Map;

/**
 * Home shell: toolbar with Options Menu (+ Graphics SubMenu), bottom Navigation Bar with
 * four fragments, Supabase Realtime lobby presence and the game-server connection.
 */
public class MainActivity extends AppCompatActivity implements GameClient.Listener, LobbyRealtime.Listener {
    private ActivityMainBinding v;
    private LobbyRealtime realtime;
    private int onlineCount = -1;
    private Fragment home, rooms, ranking, profile;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        v = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(v.getRoot());
        home = new HomeFragment();
        rooms = new RoomsFragment();
        ranking = new LeaderboardFragment();
        profile = new ProfileFragment();
        // compact floating navigation (no toolbar / bottom bar)
        v.navHome.setOnClickListener(x -> select(home, v.navHome));
        v.navRooms.setOnClickListener(x -> select(rooms, v.navRooms));
        v.navRanking.setOnClickListener(x -> select(ranking, v.navRanking));
        v.navProfile.setOnClickListener(x -> select(profile, v.navProfile));
        v.navSettings.setOnClickListener(x -> startActivity(new Intent(this, SettingsActivity.class)));
        v.navMenu.setOnClickListener(x -> {
            // Options Menu (with Graphics SubMenu) shown as a popup from the ⋮ button
            androidx.appcompat.widget.PopupMenu pm = new androidx.appcompat.widget.PopupMenu(this, v.navMenu);
            onCreateOptionsMenu(pm.getMenu());
            pm.setOnMenuItemClickListener(this::onOptionsItemSelected);
            pm.show();
        });
        realtime = new LobbyRealtime(this, this);
        if (getIntent().getBooleanExtra("rooms", false)) select(rooms, v.navRooms);
        else if (b == null) select(home, v.navHome);
    }

    private void select(Fragment f, android.widget.ImageButton btn) {
        for (android.widget.ImageButton x : new android.widget.ImageButton[]{v.navHome, v.navRooms, v.navRanking, v.navProfile}) {
            x.setColorFilter(x == btn ? getColor(R.color.accent_yellow) : 0xFF8A93B0);
        }
        show(f);
    }

    private void show(Fragment f) {
        getSupportFragmentManager().beginTransaction()
                .setCustomAnimations(R.anim.fade_in, R.anim.fade_out)
                .replace(R.id.fragmentHost, f)
                .commit();
    }

    public void openRooms() {
        select(rooms, v.navRooms);
    }

    @Override
    protected void onStart() {
        super.onStart();
        GameClient c = App.get().client();
        c.addListener(this);
        if (!"offline".equals(App.get().prefs().loginMode())) c.connect(App.get().prefs().guestName());
        realtime.start();
        realtime.setStatus("Đang ở sảnh");
        updateSubtitle();
    }

    @Override
    protected void onStop() {
        super.onStop();
        App.get().client().removeListener(this);
        realtime.stop();
    }

    void updateSubtitle() {
        GameClient c = App.get().client();
        String who = Supa.signedIn(this) ? Supa.username(this) : c.name() != null ? c.name() : App.get().prefs().guestName();
        String server;
        switch (c.state()) {
            case ONLINE: server = "Server: online"; break;
            case CONNECTING: server = "Server: đang kết nối…"; break;
            case RECONNECTING: server = "Server: đang kết nối lại…"; break;
            default: server = "offline".equals(App.get().prefs().loginMode()) ? "Chế độ offline" : "Server: offline";
        }
        String online = onlineCount >= 0 ? " · " + onlineCount + " người online" : "";
        v.statusLine.setText(who + " · " + server + online);
    }

    // ------------------------------------------------------------ Options Menu + SubMenu
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_options, menu);
        int q = App.get().prefs().quality();
        menu.findItem(q == 0 ? R.id.gfx_low : q == 1 ? R.id.gfx_medium : R.id.gfx_high).setChecked(true);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        Prefs p = App.get().prefs();
        int id = item.getItemId();
        if (id == R.id.menu_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
        } else if (id == R.id.menu_history) {
            startActivity(new Intent(this, MatchHistoryActivity.class));
        } else if (id == R.id.gfx_low || id == R.id.gfx_medium || id == R.id.gfx_high) {
            p.setQuality(id == R.id.gfx_low ? 0 : id == R.id.gfx_medium ? 1 : 2);
            item.setChecked(true);
        } else if (id == R.id.menu_rules) {
            Dialogs.rules(this);
        } else if (id == R.id.menu_server) {
            checkServer();
        } else if (id == R.id.menu_about) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.app_title)
                    .setMessage("Anime 2D Multiplayer Battle Arena\nAndroid Java/XML · Canvas SurfaceView\nNode.js authoritative WebSocket server\nSupabase Auth + PostgreSQL + Realtime\n\nPhiên bản " + com.shadowduel.arena.BuildConfig.VERSION_NAME)
                    .setPositiveButton("OK", null).show();
        } else if (id == R.id.menu_logout) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.logout)
                    .setMessage("Đăng xuất khỏi tài khoản này?")
                    .setPositiveButton(R.string.logout, (d, w) -> {
                        Supa.signOut(this);
                        App.get().client().disconnect();
                        p.setLoginMode("");
                        Intent i = new Intent(this, LoginActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(i);
                    })
                    .setNegativeButton("Hủy", null).show();
        } else return super.onOptionsItemSelected(item);
        return true;
    }

    private void checkServer() {
        String ws = App.get().prefs().serverUrl();
        String http = ws.replaceFirst("^ws", "http").replaceAll("/+$", "") + "/health";
        Net.run(() -> {
            String msg;
            try {
                Net.Response r = Net.call("GET", http, new java.util.HashMap<>(), null);
                JSONObject o = new JSONObject(r.body);
                msg = "Địa chỉ: " + ws + "\nNgười chơi: " + o.optInt("players") + "\nPhòng: " + o.optInt("rooms")
                        + "\nTrận đang đấu: " + o.optInt("matches") + "\nSupabase: " + (o.optBoolean("supabase") ? "đã kết nối" : "chưa cấu hình")
                        + "\nKhách: " + (o.optBoolean("guests") ? "cho phép" : "không") + "\nPing: " + App.get().client().rtt() + " ms";
            } catch (Exception e) {
                msg = "Không liên lạc được " + http + "\n" + e.getClass().getSimpleName();
            }
            String m = msg;
            Net.ui(() -> new MaterialAlertDialogBuilder(this).setTitle(R.string.server_status).setMessage(m)
                    .setPositiveButton("OK", null).show());
        });
    }

    // ------------------------------------------------------------ listeners
    @Override
    public void onMessage(String type, JSONObject msg) {
        if ("room".equals(type) && msg.optJSONObject("room") != null && !(isFinishing())) {
            // we just created/joined a room → open the lobby
            if (!LobbyActivity.open) startActivity(new Intent(this, LobbyActivity.class));
        } else if ("error".equals(type)) {
            Dialogs.error(this, msg);
        }
    }

    @Override
    public void onConnection(GameClient.State state, String error) {
        runOnUiThread(() -> {
            updateSubtitle();
            if (error != null && state == GameClient.State.OFFLINE) {
                com.google.android.material.snackbar.Snackbar.make(v.getRoot(), error, com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void onOnline(Map<String, String> players) {
        onlineCount = players.size();
        updateSubtitle();
        if (home instanceof HomeFragment) ((HomeFragment) home).setOnline(players);
    }

    @Override
    public void onRoomsChanged() {
        if (rooms instanceof RoomsFragment && rooms.isResumed()) ((RoomsFragment) rooms).refresh();
    }

    @Override
    public void onConnected(boolean on) {
        if (!on) onlineCount = -1;
        updateSubtitle();
    }
}

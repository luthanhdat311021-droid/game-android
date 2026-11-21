package com.shadowduel.arena.ui;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.shadowduel.arena.App;
import com.shadowduel.arena.R;
import com.shadowduel.arena.databinding.ActivityLobbyBinding;
import com.shadowduel.arena.game.GameAssets;
import com.shadowduel.arena.game.render.Palette;
import com.shadowduel.arena.net.GameClient;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Multiplayer lobby: room info, player list (rows built in Java, long-press Context Menu),
 * character & team selection, ready / start, bots, room settings (host), Options Menu.
 */
public class LobbyActivity extends AppCompatActivity implements GameClient.Listener {
    public static boolean open;
    private ActivityLobbyBinding v;
    private JSONObject room;
    private JSONObject contextMember;
    private boolean ready;

    private final ActivityResultLauncher<Intent> pickCharacter = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> {
                String id = App.get().prefs().character();
                App.get().client().send(GameClient.json("t", "select_character", "charId", id));
            });

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        open = true;
        v = ActivityLobbyBinding.inflate(getLayoutInflater());
        setContentView(v.getRoot());
        setSupportActionBar(v.lobbyToolbar);
        v.lobbyCharacter.setOnClickListener(x -> pickCharacter.launch(new Intent(this, CharacterSelectActivity.class)));
        v.lobbyHero.setOnClickListener(x -> pickCharacter.launch(new Intent(this, CharacterSelectActivity.class)));
        v.readyButton.setOnClickListener(x -> App.get().client().send(GameClient.json("t", "ready", "ready", !ready)));
        v.startButton.setOnClickListener(x -> App.get().client().send(GameClient.json("t", "start_match")));
        v.addBot.setOnClickListener(x -> App.get().client().send(GameClient.json("t", "add_bot")));
        v.teamRed.setOnClickListener(x -> App.get().client().send(GameClient.json("t", "select_team", "team", 1)));
        v.teamBlue.setOnClickListener(x -> App.get().client().send(GameClient.json("t", "select_team", "team", 2)));
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                confirmLeave();
            }
        });
        room = App.get().client().room;
        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        App.get().client().addListener(this);
        room = App.get().client().room;
        if (room == null) {
            finish();
            return;
        }
        render();
        // a match may have started while another screen (e.g. character select) was on top
        JSONObject start = App.get().client().matchStart;
        if ("in_match".equals(room.optString("status")) && start != null && !GameActivity.running
                && !start.optString("matchId").equals(GameActivity.lastMatchId)
                && room.optString("code").equals(start.optString("roomCode"))) {
            GameActivity.startOnline(this);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        App.get().client().removeListener(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        open = false;
    }

    private boolean isHost() {
        return room != null && room.optString("hostId").equals(App.get().client().playerId());
    }

    private void render() {
        if (room == null) return;
        String code = room.optString("code");
        String mode = room.optString("mode");
        v.lobbyToolbar.setTitle(room.optString("name"));
        v.lobbyToolbar.setSubtitle("#" + code + " · " + RoomsFragment.status(room.optString("status")));
        v.roomTitle.setText("SHADOW ROOM #" + code);
        v.roomMeta.setText("Mode: " + GameAssets.modeName(mode) + "\nMap: " + GameAssets.mapName(room.optString("map"))
                + "\nPlayers: " + room.optInt("players") + "/" + room.optInt("maxPlayers")
                + (room.optBoolean("isPrivate") ? "  · Riêng tư" : "")
                + ("tdm".equals(mode) ? (room.optBoolean("friendlyFire") ? "  · FF bật" : "  · FF tắt") : "")
                + (room.optBoolean("allowJoinInProgress") ? "  · Vào giữa trận" : ""));
        v.lobbyMap.setMap(room.optString("map"));
        v.teamButtons.setVisibility("tdm".equals(mode) ? View.VISIBLE : View.GONE);
        v.playersTitle.setText("Người chơi " + room.optInt("players") + "/" + room.optInt("maxPlayers") + " · giữ lâu để xem thêm");

        JSONArray ms = room.optJSONArray("members");
        v.playersList.removeAllViews();
        String me = App.get().client().playerId();
        int readyCount = 0, humans = 0;
        for (int i = 0; ms != null && i < ms.length(); i++) {
            JSONObject m = ms.optJSONObject(i);
            if (me != null && me.equals(m.optString("playerId"))) {
                ready = m.optBoolean("ready");
                v.lobbyHero.setCharacter(m.optString("charId"));
            }
            if (!m.optBoolean("isBot")) {
                humans++;
                if (m.optBoolean("ready") || m.optBoolean("isHost")) readyCount++;
            }
            View row = memberRow(m, me);
            v.playersList.addView(row);
        }
        boolean host = isHost();
        boolean lobby = "lobby".equals(room.optString("status"));
        v.startButton.setVisibility(host ? View.VISIBLE : View.GONE);
        v.addBot.setVisibility(host ? View.VISIBLE : View.GONE);
        v.readyButton.setVisibility(host ? View.GONE : View.VISIBLE);
        v.readyButton.setText(ready ? R.string.not_ready : R.string.ready);
        v.startButton.setEnabled(lobby);
        v.startButton.setText(getString(R.string.start_match) + "  (" + readyCount + "/" + humans + ")");
        v.readyButton.setEnabled(lobby);
        invalidateOptionsMenu();
    }

    private View memberRow(JSONObject m, String me) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_row);
        int p = Rows.dp(this, 8);
        row.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Rows.dp(this, 5);
        row.setLayoutParams(lp);

        int slot = m.optInt("slot");
        int team = m.optInt("team");
        TextView badge = new TextView(this);
        badge.setText("P" + (slot + 1));
        badge.setTextColor(0xFFFFFFFF);
        badge.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC));
        badge.setGravity(Gravity.CENTER);
        badge.setBackgroundColor(Palette.forFighter(slot, team));
        row.addView(badge, new LinearLayout.LayoutParams(Rows.dp(this, 40), Rows.dp(this, 32)));

        TextView name = new TextView(this);
        boolean mine = m.optString("playerId").equals(me);
        name.setText((m.optBoolean("isHost") ? "★ " : "") + m.optString("name") + (mine ? "  (bạn)" : "")
                + (m.optBoolean("connected", true) ? "" : "  · mất kết nối"));
        name.setTextColor(mine ? getColor(R.color.accent_cyan) : 0xFFF4F6FF);
        name.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC));
        name.setTextSize(15);
        name.setPadding(Rows.dp(this, 10), 0, 0, 0);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1.6f));

        TextView ch = new TextView(this);
        String charId = m.optString("charId");
        ch.setText(GameAssets.defs(this).chars.get(charId).name);
        ch.setTextColor(Palette.style(charId).accent);
        ch.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(ch, new LinearLayout.LayoutParams(0, -2, 0.8f));

        TextView tm = new TextView(this);
        tm.setText(team == 1 ? "RED" : team == 2 ? "BLUE" : "—");
        tm.setTextColor(team == 1 ? getColor(R.color.team_red) : team == 2 ? getColor(R.color.team_blue) : 0xFF8A93B0);
        tm.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(tm, new LinearLayout.LayoutParams(0, -2, 0.5f));

        TextView st = new TextView(this);
        boolean rd = m.optBoolean("ready") || m.optBoolean("isHost");
        st.setText(m.optBoolean("isBot") ? "BOT" : m.optBoolean("isHost") ? "CHỦ PHÒNG" : rd ? "SẴN SÀNG" : "ĐANG CHỜ");
        st.setTextColor(rd ? getColor(R.color.accent_green) : getColor(R.color.accent_yellow));
        st.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC));
        st.setGravity(Gravity.END);
        row.addView(st, new LinearLayout.LayoutParams(0, -2, 0.8f));

        row.setOnLongClickListener(x -> {
            contextMember = m;
            return false;
        });
        registerForContextMenu(row);
        return row;
    }

    // ------------------------------------------------------------ Context Menu (players)
    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenu.ContextMenuInfo info) {
        super.onCreateContextMenu(menu, view, info);
        getMenuInflater().inflate(R.menu.player_context, menu);
        if (contextMember != null) menu.setHeaderTitle(contextMember.optString("name"));
        boolean self = contextMember != null && contextMember.optString("playerId").equals(App.get().client().playerId());
        menu.findItem(R.id.ctx_kick).setVisible(isHost() && !self);
    }

    @Override
    public boolean onContextItemSelected(@NonNull MenuItem item) {
        if (contextMember == null) return super.onContextItemSelected(item);
        if (item.getItemId() == R.id.ctx_view) {
            String charId = contextMember.optString("charId");
            com.shadowduel.arena.game.sim.Defs.Char c = GameAssets.defs(this).chars.get(charId);
            new MaterialAlertDialogBuilder(this).setTitle(contextMember.optString("name"))
                    .setMessage("Slot: P" + (contextMember.optInt("slot") + 1) + "\nNhân vật: " + c.name + " · " + c.title
                            + "\nĐội: " + (contextMember.optInt("team") == 1 ? "Red" : contextMember.optInt("team") == 2 ? "Blue" : "—")
                            + "\n" + (contextMember.optBoolean("isBot") ? "Máy (BOT)" : "Người chơi"))
                    .setPositiveButton("OK", null).show();
        } else if (item.getItemId() == R.id.ctx_kick) {
            App.get().client().send(GameClient.json("t", "remove_member", "playerId", contextMember.optString("playerId")));
        } else return super.onContextItemSelected(item);
        return true;
    }

    // ------------------------------------------------------------ Options Menu
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.lobby_options, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.lobby_settings).setVisible(isHost());
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.lobby_share && room != null) {
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "Vào phòng SHADOW DUEL \"" + room.optString("name") + "\" — mã phòng: " + room.optString("code"));
            startActivity(Intent.createChooser(send, "Chia sẻ mã phòng"));
        } else if (id == R.id.lobby_settings) {
            roomSettings();
        } else if (id == R.id.lobby_rules) {
            Dialogs.rules(this);
        } else if (id == R.id.lobby_leave) {
            confirmLeave();
        } else return super.onOptionsItemSelected(item);
        return true;
    }

    private void roomSettings() {
        String[] maps = {"Shadow Temple", "Flame Kingdom", "Cyber City"};
        String[] mapIds = {"shadow_temple", "flame_kingdom", "cyber_city"};
        String[] modes = {"Free For All", "Team Deathmatch", "Survival Arena"};
        String[] modeIds = {"ffa", "tdm", "survival"};
        new MaterialAlertDialogBuilder(this).setTitle("Cài đặt phòng")
                .setItems(new String[]{"Đổi bản đồ", "Đổi chế độ", "Số người tối đa"}, (d, which) -> {
                    if (which == 0) new MaterialAlertDialogBuilder(this).setTitle("Bản đồ").setItems(maps, (d2, w) ->
                            App.get().client().send(GameClient.json("t", "room_settings", "map", mapIds[w]))).show();
                    else if (which == 1) new MaterialAlertDialogBuilder(this).setTitle("Chế độ").setItems(modes, (d2, w) ->
                            App.get().client().send(GameClient.json("t", "room_settings", "mode", modeIds[w]))).show();
                    else {
                        String[] sizes = {"2", "4", "6", "8"};
                        new MaterialAlertDialogBuilder(this).setTitle("Số người tối đa").setItems(sizes, (d2, w) ->
                                App.get().client().send(GameClient.json("t", "room_settings", "maxPlayers", Integer.parseInt(sizes[w])))).show();
                    }
                }).show();
    }

    private void confirmLeave() {
        new MaterialAlertDialogBuilder(this).setTitle(R.string.leave_room).setMessage("Rời khỏi phòng này?")
                .setPositiveButton(R.string.leave_room, (d, w) -> {
                    App.get().client().send(GameClient.json("t", "leave_room"));
                    App.get().client().room = null;
                    finish();
                })
                .setNegativeButton("Ở lại", null).show();
    }

    // ------------------------------------------------------------ server messages
    @Override
    public void onMessage(String type, JSONObject msg) {
        switch (type) {
            case "room":
                room = msg.optJSONObject("room");
                render();
                break;
            case "countdown":
                startActivity(new Intent(this, MatchLoadingActivity.class).putExtra("ms", msg.optInt("ms", 3000)));
                overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
                break;
            case "match_start":
                if (!MatchLoadingActivity.open) GameActivity.startOnline(this);
                break;
            case "error":
                Snackbar.make(v.getRoot(), Dialogs.errorText(msg), Snackbar.LENGTH_LONG).show();
                break;
            case "kicked":
                Toast.makeText(this, "SESSION_TAKEN_OVER".equals(msg.optString("reason"))
                        ? "Tài khoản đã đăng nhập ở thiết bị khác" : "Bạn đã bị mời ra khỏi phòng", Toast.LENGTH_LONG).show();
                finish();
                break;
            case "left_room":
                finish();
                break;
        }
    }

    @Override
    public void onConnection(GameClient.State state, String error) {
        runOnUiThread(() -> {
            if (state == GameClient.State.RECONNECTING) v.lobbyToolbar.setSubtitle("Đang kết nối lại…");
            else if (state == GameClient.State.OFFLINE) {
                Toast.makeText(this, error != null ? error : "Mất kết nối", Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }
}

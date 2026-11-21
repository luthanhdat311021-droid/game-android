package com.shadowduel.arena.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.EditText;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.shadowduel.arena.App;
import com.shadowduel.arena.R;
import com.shadowduel.arena.databinding.FragmentHomeBinding;
import com.shadowduel.arena.game.GameAssets;
import com.shadowduel.arena.net.GameClient;

import java.util.Map;

/** Home tab: fighter preview, Quick Match by mode, create / join room, Training, offline vs bots. */
public class HomeFragment extends Fragment {
    private FragmentHomeBinding v;
    private Map<String, String> pendingOnline;

    private final ActivityResultLauncher<Intent> pickCharacter = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> showHero());

    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup parent, @Nullable Bundle b) {
        v = FragmentHomeBinding.inflate(inf, parent, false);
        return v.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle b) {
        v.heroPanel.startAnimation(AnimationUtils.loadAnimation(requireContext(), R.anim.slide_in_left));
        v.playPanel.startAnimation(AnimationUtils.loadAnimation(requireContext(), R.anim.slide_up));
        v.quickMatch.startAnimation(AnimationUtils.loadAnimation(requireContext(), R.anim.pulse));
        showHero();
        v.changeHero.setOnClickListener(x -> pickCharacter.launch(new Intent(requireContext(), CharacterSelectActivity.class)));
        v.heroPreview.setOnClickListener(x -> pickCharacter.launch(new Intent(requireContext(), CharacterSelectActivity.class)));
        v.quickMatch.setOnClickListener(x -> {
            if (!online()) return;
            App.get().client().send(GameClient.json("t", "quick_match", "mode", mode(), "charId", App.get().prefs().character()));
        });
        v.rankedMatch.setOnClickListener(x -> {
            if (online()) startRanked();
        });
        v.createRoom.setOnClickListener(x -> {
            if (online()) startActivity(new Intent(requireContext(), CreateRoomActivity.class).putExtra("mode", mode()));
        });
        v.joinRoom.setOnClickListener(x -> {
            if (online()) askCode();
        });
        v.training.setOnClickListener(x -> startActivity(GameActivity.offline(requireContext(), "training", "shadow_temple", 2)));
        v.offlineBots.setOnClickListener(x -> askOffline());
        v.questsButton.setOnClickListener(x -> startActivity(new Intent(requireContext(), QuestActivity.class)));
        v.inventoryButton.setOnClickListener(x -> startActivity(new Intent(requireContext(), InventoryActivity.class)));
        v.shopButton.setOnClickListener(x -> startActivity(new Intent(requireContext(), ShopActivity.class)));
        if (pendingOnline != null) setOnline(pendingOnline);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!com.shadowduel.arena.data.Supa.signedIn(requireContext())) {
            v.walletLine.setText("Đăng nhập để nhận Gold, làm nhiệm vụ và mở Cửa hàng");
            return;
        }
        // idempotent: creates wallet + starter kit once (also for Google accounts), then daily login
        com.shadowduel.arena.data.Supa.rpc(requireContext(), "init_my_account", null, (r, err) -> {
            if (v == null) return;
            com.shadowduel.arena.data.Supa.rpc(requireContext(), "claim_daily_login", null, (d, e2) -> {
                if (v == null) return;
                if (d instanceof org.json.JSONObject && ((org.json.JSONObject) d).optBoolean("claimed")) {
                    Snackbar.make(v.getRoot(), "Quà đăng nhập hôm nay: +100 Gold", Snackbar.LENGTH_LONG).show();
                }
                com.shadowduel.arena.data.Economy.loadWallet(requireContext(), e3 -> {
                    if (v != null && com.shadowduel.arena.data.Economy.gold >= 0) {
                        v.walletLine.setText(String.format(java.util.Locale.US, "GOLD %,d   ·   GEMS %,d",
                                com.shadowduel.arena.data.Economy.gold, com.shadowduel.arena.data.Economy.gems));
                    } else if (v != null && err != null) v.walletLine.setText(err);
                });
            });
        });
        com.shadowduel.arena.data.Economy.loadCatalog(requireContext(), e -> { });
        com.shadowduel.arena.data.Economy.loadEquipment(requireContext(), e -> showHero());
    }

    private void showHero() {
        String id = App.get().prefs().character();
        if (v == null) return;
        v.heroPreview.setCharacter(id);
        v.heroPreview.setSkin(com.shadowduel.arena.data.Economy.palette(com.shadowduel.arena.data.Economy.skinPalette.get(id)));
        com.shadowduel.arena.game.sim.Defs.Char c = GameAssets.defs(requireContext()).chars.get(id);
        v.heroName.setText(c.name + " · " + c.title);
    }

    private String mode() {
        int id = v.modeChips.getCheckedChipId();
        return id == R.id.chipTdm ? "tdm" : id == R.id.chipSurvival ? "survival" : "ffa";
    }

    private boolean online() {
        GameClient c = App.get().client();
        if ("offline".equals(App.get().prefs().loginMode())) {
            Snackbar.make(v.getRoot(), "Bạn đang ở chế độ offline. Đăng nhập hoặc chơi khách để chơi online.", Snackbar.LENGTH_LONG).show();
            return false;
        }
        if (c.state() != GameClient.State.ONLINE) {
            c.connect(App.get().prefs().guestName());
            Snackbar.make(v.getRoot(), "Chưa kết nối game server (" + App.get().prefs().serverUrl() + "). Kiểm tra Cài đặt.", Snackbar.LENGTH_LONG)
                    .setAction(R.string.settings, x -> startActivity(new Intent(requireContext(), SettingsActivity.class))).show();
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------ ranked matchmaking
    private androidx.appcompat.app.AlertDialog rankedDialog;
    private android.widget.TextView rankedText;
    private final GameClient.Listener rankedListener = new GameClient.Listener() {
        @Override
        public void onMessage(String type, org.json.JSONObject m) {
            if (rankedDialog == null) return;
            switch (type) {
                case "ranked_queue":
                    int w = m.optInt("waitSec");
                    rankedText.setText(String.format(java.util.Locale.US,
                            "Rating của bạn: %d\nĐang tìm đối thủ trong khoảng ±%d rating\nThời gian chờ: %d:%02d\nNgười đang tìm trận: %d\n\nTrận rank: Free For All 2–4 người, tự bắt đầu. Thắng/thua sẽ thay đổi rating.",
                            m.optInt("rating"), m.optInt("window"), w / 60, w % 60, m.optInt("inQueue")));
                    break;
                case "ranked_found":
                    rankedText.setText("ĐÃ TÌM THẤY TRẬN! Đang vào…");
                    closeRanked(false);
                    break;
                case "ranked_cancelled":
                    closeRanked(false);
                    break;
                case "error":
                    closeRanked(false);
                    Snackbar.make(v.getRoot(), "RANKED_LOGIN_REQUIRED".equals(m.optString("code"))
                            ? "Đấu rank cần đăng nhập tài khoản (email hoặc Google)" : Dialogs.errorText(m), Snackbar.LENGTH_LONG).show();
                    break;
            }
        }

        @Override
        public void onConnection(GameClient.State state, String error) {
            if (state == GameClient.State.OFFLINE) closeRanked(false);
        }
    };

    private void startRanked() {
        if (!com.shadowduel.arena.data.Supa.signedIn(requireContext())) {
            Snackbar.make(v.getRoot(), "Đấu rank cần đăng nhập tài khoản (email hoặc Google)", Snackbar.LENGTH_LONG).show();
            return;
        }
        rankedText = new android.widget.TextView(requireContext());
        int pad = (int) (22 * getResources().getDisplayMetrics().density);
        rankedText.setPadding(pad, pad / 2, pad, 0);
        rankedText.setText("Đang vào hàng chờ…");
        rankedText.setTextSize(15);
        rankedDialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle("ĐANG TÌM TRẬN RANK")
                .setView(rankedText)
                .setCancelable(false)
                .setNegativeButton("HỦY TÌM", (d, w) -> closeRanked(true))
                .show();
        App.get().client().addListener(rankedListener);
        App.get().client().send(GameClient.json("t", "ranked_join", "charId", App.get().prefs().character()));
    }

    private void closeRanked(boolean cancel) {
        if (cancel) App.get().client().send(GameClient.json("t", "ranked_cancel"));
        App.get().client().removeListener(rankedListener);
        if (rankedDialog != null) rankedDialog.dismiss();
        rankedDialog = null;
    }

    private void askCode() {
        EditText input = new EditText(requireContext());
        input.setHint("VD: A102");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8), new InputFilter.AllCaps()});
        EditText pass = new EditText(requireContext());
        pass.setHint("Mật khẩu (nếu là phòng riêng)");
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        android.widget.LinearLayout box = new android.widget.LinearLayout(requireContext());
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 3, pad, 0);
        box.addView(input);
        box.addView(pass);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.join_room)
                .setView(box)
                .setPositiveButton("VÀO", (d, w) -> App.get().client().send(GameClient.json("t", "join_room",
                        "code", input.getText().toString().trim(), "password", pass.getText().toString(),
                        "charId", App.get().prefs().character())))
                .setNegativeButton("Hủy", null).show();
    }

    private void askOffline() {
        String[] maps = {"Shadow Temple", "Flame Kingdom", "Cyber City"};
        String[] ids = {"shadow_temple", "flame_kingdom", "cyber_city"};
        int[] choice = {0};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Offline vs BOT (" + GameAssets.modeName(mode()) + ")")
                .setSingleChoiceItems(maps, 0, (d, w) -> choice[0] = w)
                .setPositiveButton("8 NGƯỜI", (d, w) -> startActivity(GameActivity.offline(requireContext(), mode(), ids[choice[0]], 7)))
                .setNeutralButton("4 NGƯỜI", (d, w) -> startActivity(GameActivity.offline(requireContext(), mode(), ids[choice[0]], 3)))
                .setNegativeButton("Hủy", null).show();
    }

    /** Supabase Realtime presence list. */
    public void setOnline(Map<String, String> players) {
        pendingOnline = players;
        if (v == null) return;
        StringBuilder sb = new StringBuilder("● ").append(players.size()).append(" người đang online\n");
        int n = 0;
        for (String s : players.values()) {
            if (n++ >= 8) {
                sb.append("…");
                break;
            }
            sb.append("  ").append(s).append('\n');
        }
        v.onlineList.setText(sb.toString());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        v = null;
    }
}

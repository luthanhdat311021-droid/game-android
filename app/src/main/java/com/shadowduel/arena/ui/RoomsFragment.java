package com.shadowduel.arena.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shadowduel.arena.App;
import com.shadowduel.arena.R;
import com.shadowduel.arena.databinding.FragmentRoomsBinding;
import com.shadowduel.arena.databinding.ItemRoomBinding;
import com.shadowduel.arena.game.GameAssets;
import com.shadowduel.arena.net.GameClient;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Room browser. Updates when the game server sends the list, when Supabase Realtime reports
 * a change in public.rooms, and every few seconds while visible. Long-press = Context Menu.
 */
public class RoomsFragment extends Fragment implements GameClient.Listener {
    private FragmentRoomsBinding v;
    private JSONArray rooms = new JSONArray();
    private JSONObject selected;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 5000);
        }
    };

    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup parent, @Nullable Bundle b) {
        v = FragmentRoomsBinding.inflate(inf, parent, false);
        return v.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle b) {
        v.roomsList.setLayoutManager(new LinearLayoutManager(requireContext()));
        v.roomsList.setAdapter(adapter);
        v.roomsRefresh.setOnClickListener(x -> refresh());
        v.roomsCreate.setOnClickListener(x -> startActivity(new Intent(requireContext(), CreateRoomActivity.class)));
    }

    @Override
    public void onResume() {
        super.onResume();
        App.get().client().addListener(this);
        handler.post(poll);
    }

    @Override
    public void onPause() {
        super.onPause();
        App.get().client().removeListener(this);
        handler.removeCallbacks(poll);
    }

    public void refresh() {
        App.get().client().send(GameClient.json("t", "list_rooms"));
    }

    @Override
    public void onMessage(String type, JSONObject msg) {
        if (!"rooms".equals(type) || v == null) return;
        JSONArray list = msg.optJSONArray("rooms");
        boolean first = rooms.length() == 0;
        rooms = list == null ? new JSONArray() : list;
        adapter.notifyDataSetChanged();
        if (first && rooms.length() > 0) v.roomsList.scheduleLayoutAnimation();
        v.roomsEmpty.setVisibility(rooms.length() == 0 ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onConnection(GameClient.State state, String error) {
        if (state == GameClient.State.ONLINE) refresh();
    }

    private void join(JSONObject r) {
        App.get().client().send(GameClient.json("t", "join_room", "code", r.optString("code"),
                "charId", App.get().prefs().character()));
    }

    // ------------------------------------------------------------ Context Menu
    @Override
    public void onCreateContextMenu(@NonNull ContextMenu menu, @NonNull View view, @Nullable ContextMenu.ContextMenuInfo info) {
        super.onCreateContextMenu(menu, view, info);
        requireActivity().getMenuInflater().inflate(R.menu.room_context, menu);
        if (selected != null) menu.setHeaderTitle(selected.optString("name") + " #" + selected.optString("code"));
    }

    @Override
    public boolean onContextItemSelected(@NonNull MenuItem item) {
        if (selected == null) return super.onContextItemSelected(item);
        String code = selected.optString("code");
        int id = item.getItemId();
        if (id == R.id.ctx_join) {
            join(selected);
        } else if (id == R.id.ctx_copy) {
            ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("room", code));
            Toast.makeText(requireContext(), "Đã sao chép " + code, Toast.LENGTH_SHORT).show();
        } else if (id == R.id.ctx_share) {
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "Vào phòng SHADOW DUEL của mình nhé! Mã phòng: " + code), "Chia sẻ mã phòng"));
        } else if (id == R.id.ctx_details) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(selected.optString("name"))
                    .setMessage("Mã: #" + code + "\nChế độ: " + GameAssets.modeName(selected.optString("mode"))
                            + "\nBản đồ: " + GameAssets.mapName(selected.optString("map"))
                            + "\nNgười chơi: " + selected.optInt("players") + "/" + selected.optInt("maxPlayers")
                            + "\nChủ phòng: " + selected.optString("hostName")
                            + "\nTrạng thái: " + status(selected.optString("status")))
                    .setPositiveButton(R.string.join_room, (d, w) -> join(selected))
                    .setNegativeButton("Đóng", null).show();
        } else return super.onContextItemSelected(item);
        return true;
    }

    static String status(String s) {
        switch (s) {
            case "in_match": return "Đang đấu";
            case "starting": return "Sắp bắt đầu";
            case "results": return "Đang xem kết quả";
            default: return "Đang chờ";
        }
    }

    // ------------------------------------------------------------ adapter
    private final RecyclerView.Adapter<Holder> adapter = new RecyclerView.Adapter<Holder>() {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new Holder(ItemRoomBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int pos) {
            JSONObject r = rooms.optJSONObject(pos);
            h.b.roomName.setText(r.optString("name") + "  #" + r.optString("code"));
            h.b.roomInfo.setText(GameAssets.modeName(r.optString("mode")) + " · " + GameAssets.mapName(r.optString("map"))
                    + " · " + status(r.optString("status")));
            h.b.roomPlayers.setText(r.optInt("players") + "/" + r.optInt("maxPlayers"));
            h.b.roomMap.setMap(r.optString("map"));
            boolean open = "lobby".equals(r.optString("status")) && r.optInt("players") < r.optInt("maxPlayers");
            h.b.roomJoin.setEnabled(open);
            h.b.roomJoin.setOnClickListener(x -> join(r));
            h.itemView.setOnClickListener(x -> {
                selected = r;
                h.itemView.showContextMenu();
            });
            h.itemView.setOnLongClickListener(x -> {
                selected = r;
                return false; // let the context menu open
            });
            registerForContextMenu(h.itemView);
        }

        @Override
        public int getItemCount() {
            return rooms.length();
        }
    };

    static final class Holder extends RecyclerView.ViewHolder {
        final ItemRoomBinding b;

        Holder(ItemRoomBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        v = null;
    }
}

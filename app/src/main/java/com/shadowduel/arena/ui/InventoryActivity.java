package com.shadowduel.arena.ui;

import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shadowduel.arena.App;
import com.shadowduel.arena.R;
import com.shadowduel.arena.data.Economy;
import com.shadowduel.arena.data.Supa;
import com.shadowduel.arena.databinding.ItemCardBinding;
import com.shadowduel.arena.net.GameClient;
import com.shadowduel.arena.ui.widget.CharacterPreviewView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Kho đồ: owned items from player_inventory (RLS: own rows), equip / unequip / open chests. */
public class InventoryActivity extends EconomyActivity {
    private static final String[][] TAB_CATS = {
            {}, {"weapon"}, {"equipment"}, {"consumable", "chest"}, {"skin"}, {"effect", "emote", "nameplate"}};
    private final Set<String> equipped = new HashSet<>();
    private JSONObject contextRow;

    @Override protected String title() { return "KHO ĐỒ"; }
    @Override protected String[] tabs() { return new String[]{"Tất cả", "Vũ khí", "Trang bị", "Tiêu hao", "Skin", "Hiệu ứng"}; }

    @Override
    protected void load(int tab) {
        String me = Supa.userId(this);
        Supa.select(this, "player_equipment?select=item_id&player_id=eq." + me, (eq, e0) -> {
            equipped.clear();
            for (int i = 0; eq != null && i < eq.length(); i++) equipped.add(eq.optJSONObject(i).optString("item_id"));
            Supa.select(this, "player_inventory?select=item_id,quantity,source,expires_at,acquired_at&player_id=eq." + me + "&order=acquired_at.desc", (rows, err) -> {
                List<JSONObject> out = new ArrayList<>();
                for (int i = 0; rows != null && i < rows.length(); i++) {
                    JSONObject r = rows.optJSONObject(i);
                    JSONObject def = Economy.items.get(r.optString("item_id"));
                    if (def == null || !matches(def.optString("category"), TAB_CATS[tab])) continue;
                    try {
                        r.put("_def", def);
                    } catch (Exception ignored) {
                    }
                    out.add(r);
                }
                show(out, err, "Chưa có vật phẩm. Làm nhiệm vụ hoặc ghé Cửa hàng nhé!");
                Economy.loadEquipment(this, e -> { });
            });
        });
    }

    private static boolean matches(String cat, String[] cats) {
        if (cats.length == 0) return true;
        for (String c : cats) if (c.equals(cat)) return true;
        return false;
    }

    private static boolean expired(JSONObject r) {
        return !r.isNull("expires_at") && Economy.parseTime(r.optString("expires_at")) < System.currentTimeMillis();
    }

    @Override
    protected void bind(ItemCardBinding b, JSONObject r, int pos) {
        JSONObject def = r.optJSONObject("_def");
        String cat = def.optString("category"), id = def.optString("id");
        style(b, def.optString("rarity"));
        b.cardIcon.setItem(def);
        b.cardTitle.setText(def.optString("name") + (def.optBoolean("stackable") ? "  ×" + r.optInt("quantity") : ""));
        String exp = r.isNull("expires_at") ? "" : expired(r) ? "  ·  ĐÃ HẾT HẠN" : "  ·  hết hạn " + r.optString("expires_at").substring(0, 10);
        b.cardSub.setText(def.optString("description") + "\nNguồn: " + r.optString("source") + exp);
        boolean isEq = equipped.contains(id);
        boolean equippable = cat.equals("skin") || cat.equals("weapon") || cat.equals("equipment") || cat.equals("effect")
                || cat.equals("emote") || cat.equals("nameplate");
        if (expired(r)) {
            b.cardButton.setText("HẾT HẠN");
            b.cardButton.setEnabled(false);
        } else if (equippable) {
            b.cardButton.setText(isEq ? "THÁO" : "TRANG BỊ");
            b.cardButton.setOnClickListener(x -> equip(def, !isEq));
        } else if (cat.equals("chest")) {
            b.cardButton.setText("MỞ");
            b.cardButton.setOnClickListener(x -> open(def, b.getRoot()));
        } else {
            b.cardButton.setText("DÙNG Ở TRAINING");
            b.cardButton.setEnabled(false);
        }
        b.getRoot().setStrokeWidth(isEq ? 5 : 2);
        b.getRoot().setOnClickListener(x -> details(r));
        b.getRoot().setOnLongClickListener(x -> {
            contextRow = r;
            return false;
        });
        registerForContextMenu(b.getRoot());
    }

    private void equip(JSONObject def, boolean on) {
        String character = def.isNull("character_id") ? App.get().prefs().character() : def.optString("character_id");
        String slot = def.optString("category");
        JSONObject args = on ? GameClient.json("p_item", def.optString("id"), "p_character", character)
                : GameClient.json("p_slot", slot, "p_character", character);
        Supa.rpc(this, on ? "equip_item" : "unequip_item", args, (res, err) -> {
            if (err != null) toast(err);
            else toast(on ? "Đã trang bị " + def.optString("name") : "Đã tháo " + def.optString("name"));
            reload();
        });
    }

    private void open(JSONObject def, View card) {
        Supa.rpc(this, "use_item", GameClient.json("p_item", def.optString("id"), "p_request_id", UUID.randomUUID().toString()), (res, err) -> {
            if (err != null) {
                toast(err);
                return;
            }
            celebrate(card);
            JSONObject contents = def.optJSONObject("gameplay_effect") == null ? null : def.optJSONObject("gameplay_effect").optJSONObject("contents");
            toast("Mở rương: " + Economy.rewardText(contents));
            reload();
        });
    }

    private void details(JSONObject r) {
        JSONObject def = r.optJSONObject("_def");
        new MaterialAlertDialogBuilder(this)
                .setTitle(def.optString("name"))
                .setMessage(def.optString("description") + "\n\nĐộ hiếm: " + Economy.rarityName(def.optString("rarity"))
                        + "\nLoại: " + def.optString("category") + "\nSố lượng: " + r.optInt("quantity")
                        + "\nNguồn: " + r.optString("source") + "\nNhận lúc: " + r.optString("acquired_at").replace('T', ' ').substring(0, 16)
                        + (r.isNull("expires_at") ? "\nThời hạn: vĩnh viễn" : "\nHết hạn: " + r.optString("expires_at").replace('T', ' ').substring(0, 16))
                        + (def.optString("category").equals("equipment") || def.optString("category").equals("consumable")
                        ? "\n\nChỉ có tác dụng trong Training/PvE. Ranked PvP luôn dùng chỉ số chuẩn." : ""))
                .setPositiveButton("OK", null)
                .setNeutralButton("XEM TRƯỚC", (d, w) -> preview(def))
                .show();
    }

    /** Preview a skin on the character before equipping it. */
    private void preview(JSONObject def) {
        CharacterPreviewView pv = new CharacterPreviewView(this);
        String ch = def.isNull("character_id") ? App.get().prefs().character() : def.optString("character_id");
        pv.setCharacter(ch);
        JSONObject fx = def.optJSONObject("gameplay_effect");
        pv.setSkin(Economy.palette(fx == null ? null : fx.optJSONObject("palette")));
        pv.setMinimumHeight(Rows.dp(this, 220));
        new MaterialAlertDialogBuilder(this).setTitle(def.optString("name")).setView(pv).setPositiveButton("OK", null).show();
    }

    // ------------------------------------------------------------ Context Menu
    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenu.ContextMenuInfo info) {
        super.onCreateContextMenu(menu, view, info);
        getMenuInflater().inflate(R.menu.item_context, menu);
        if (contextRow != null) menu.setHeaderTitle(contextRow.optJSONObject("_def").optString("name"));
    }

    @Override
    public boolean onContextItemSelected(@NonNull MenuItem item) {
        if (contextRow == null) return super.onContextItemSelected(item);
        JSONObject def = contextRow.optJSONObject("_def");
        int id = item.getItemId();
        if (id == R.id.ctx_item_details) details(contextRow);
        else if (id == R.id.ctx_item_equip) equip(def, !equipped.contains(def.optString("id")));
        else if (id == R.id.ctx_item_preview) preview(def);
        else return super.onContextItemSelected(item);
        return true;
    }
}

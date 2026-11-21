package com.shadowduel.arena.ui;

import android.content.Intent;
import android.os.Bundle;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.shadowduel.arena.R;
import com.shadowduel.arena.data.Economy;
import com.shadowduel.arena.data.Supa;
import com.shadowduel.arena.databinding.ItemCardBinding;
import com.shadowduel.arena.net.GameClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Cửa hàng. Prices are read from public.shop_products (editable by admins). Gold/Gems purchases
 * run in public.buy_product() (balance check + deduction + item grant in one transaction).
 * Gems packs open PaymentActivity, where the server creates the order and verifies payment.
 */
public class ShopActivity extends EconomyActivity {
    private static final String[] SECTIONS = {"featured", "skins", "weapons", "effects", "consumables", "currency", "limited"};

    @Override protected String title() { return "CỬA HÀNG"; }
    @Override protected String[] tabs() { return new String[]{"Nổi bật", "Skin", "Vũ khí", "Hiệu ứng", "Tiêu hao", "Nạp Gems", "Giới hạn"}; }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        v.ecoAction.setVisibility(android.view.View.VISIBLE);
        v.ecoAction.setText("LỊCH SỬ");
        v.ecoAction.setOnClickListener(x -> startActivity(new Intent(this, WalletHistoryActivity.class)));
        String tab = getIntent().getStringExtra("tab");
        for (int i = 0; tab != null && i < SECTIONS.length; i++) {
            if (SECTIONS[i].equals(tab) && v.ecoTabs.getTabAt(i) != null) v.ecoTabs.getTabAt(i).select();
        }
    }

    @Override
    protected void load(int tab) {
        Supa.select(this, "shop_products?select=*&enabled=eq.true&section=eq." + SECTIONS[tab] + "&order=sort", (rows, err) -> {
            List<JSONObject> out = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (int i = 0; rows != null && i < rows.length(); i++) {
                JSONObject r = rows.optJSONObject(i);
                if (!r.isNull("available_until") && Economy.parseTime(r.optString("available_until")) < now) continue;
                out.add(r);
            }
            show(out, err, "Chưa có sản phẩm trong mục này.");
        });
    }

    private static String price(JSONObject p) {
        String cur = p.optString("currency");
        if ("vnd".equals(cur)) return String.format(Locale.US, "%,d đ", p.optLong("price")).replace(',', '.');
        return String.format(Locale.US, "%,d %s", p.optLong("price"), "gold".equals(cur) ? "Gold" : "Gems");
    }

    @Override
    protected void bind(ItemCardBinding b, JSONObject p, int pos) {
        boolean pack = "gems_pack".equals(p.optString("kind"));
        JSONObject def = pack ? null : Economy.items.get(p.optString("item_id"));
        style(b, pack ? (p.optLong("gems") >= 700 ? "legendary" : "epic") : def == null ? "common" : def.optString("rarity"));
        if (pack) {
            b.cardIcon.setCurrency("gems");
            b.cardTitle.setText(p.optString("title"));
            b.cardSub.setText(p.optLong("gems") + " Gems · thanh toán QR chuyển khoản, xác minh tự động");
        } else {
            b.cardIcon.setItem(def);
            b.cardTitle.setText(p.optString("title") + (p.optInt("quantity", 1) > 1 ? " ×" + p.optInt("quantity") : ""));
            b.cardSub.setText(def == null ? "" : def.optString("description"));
        }
        b.cardButton.setText(price(p));
        b.cardButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(
                "gems".equals(p.optString("currency")) || pack ? R.color.accent_blue : R.color.accent_red)));
        b.cardButton.setOnClickListener(x -> {
            if (pack) startActivity(new Intent(this, PaymentActivity.class).putExtra("productId", p.optString("id"))
                    .putExtra("title", p.optString("title")));
            else confirmBuy(p, b);
        });
        if (def != null) b.getRoot().setOnClickListener(x -> new MaterialAlertDialogBuilder(this).setTitle(def.optString("name"))
                .setMessage(def.optString("description") + "\n\nĐộ hiếm: " + Economy.rarityName(def.optString("rarity"))
                        + ("skin".equals(def.optString("category")) || "weapon".equals(def.optString("category")) || "effect".equals(def.optString("category"))
                        ? "\nVật phẩm trang trí — không thay đổi chỉ số chiến đấu." : ""))
                .setPositiveButton("OK", null).show());
    }

    private void confirmBuy(JSONObject p, ItemCardBinding b) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Mua " + p.optString("title") + "?")
                .setMessage("Giá: " + price(p))
                .setPositiveButton("MUA", (d, w) -> {
                    b.cardButton.setEnabled(false);
                    // request id makes a retried tap idempotent on the server
                    Supa.rpc(this, "buy_product", GameClient.json("p_product", p.optString("id"), "p_request_id", UUID.randomUUID().toString()), (res, err) -> {
                        b.cardButton.setEnabled(true);
                        if (err != null) {
                            toast(err);
                            return;
                        }
                        celebrate(b.getRoot());
                        toast("Đã mua " + p.optString("title") + " — xem trong Kho đồ");
                        refreshWallet();
                    });
                })
                .setNegativeButton("Hủy", null).show();
    }
}

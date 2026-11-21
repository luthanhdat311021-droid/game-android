package com.shadowduel.arena.ui;

import android.content.Intent;

import com.shadowduel.arena.data.Economy;
import com.shadowduel.arena.data.Supa;
import com.shadowduel.arena.databinding.ItemCardBinding;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Lịch sử: currency ledger, top-up orders (re-open pending ones) and purchases. */
public class WalletHistoryActivity extends EconomyActivity {
    @Override protected String title() { return "LỊCH SỬ GIAO DỊCH"; }
    @Override protected String[] tabs() { return new String[]{"Biến động Gold/Gems", "Đơn nạp", "Mua hàng"}; }

    @Override
    protected void load(int tab) {
        String me = Supa.userId(this);
        String q = tab == 0 ? "currency_ledger?select=*&player_id=eq." + me + "&order=id.desc&limit=100"
                : tab == 1 ? "payment_orders?select=*&player_id=eq." + me + "&order=created_at.desc&limit=50"
                : "purchase_history?select=*&player_id=eq." + me + "&order=id.desc&limit=100";
        Supa.select(this, q, (rows, err) -> {
            List<JSONObject> out = new ArrayList<>();
            for (int i = 0; rows != null && i < rows.length(); i++) {
                JSONObject r = rows.optJSONObject(i);
                try {
                    r.put("_tab", tab);
                } catch (Exception ignored) {
                }
                out.add(r);
            }
            show(out, err, "Chưa có giao dịch.");
        });
    }

    private static String when(JSONObject r) {
        String t = r.optString("created_at").replace('T', ' ');
        return t.length() > 16 ? t.substring(0, 16) : t;
    }

    @Override
    protected void bind(ItemCardBinding b, JSONObject r, int pos) {
        int tab = r.optInt("_tab");
        if (tab == 0) {
            long d = r.optLong("delta");
            String cur = r.optString("currency");
            style(b, "gems".equals(cur) ? "epic" : "legendary");
            b.cardIcon.setCurrency(cur);
            b.cardTitle.setText(String.format(Locale.US, "%s%,d %s", d > 0 ? "+" : "", d, "gems".equals(cur) ? "Gems" : "Gold"));
            b.cardSub.setText(r.optString("reason") + "\n" + when(r) + " · số dư " + r.optLong("balance_after"));
            b.cardButton.setVisibility(android.view.View.GONE);
        } else if (tab == 1) {
            String st = r.optString("status");
            style(b, "PAID".equals(st) ? "rare" : "NEEDS_REVIEW".equals(st) ? "legendary" : "common");
            b.cardRarity.setText(st);
            b.cardIcon.setCurrency("gems");
            b.cardTitle.setText(String.format(Locale.US, "%s · %,d đ → %d Gems", r.optString("code"), r.optLong("amount_vnd"), r.optInt("gems")).replace(',', '.'));
            b.cardSub.setText(when(r) + " · " + r.optString("provider") + (r.optString("status_note").isEmpty() ? "" : "\n" + r.optString("status_note")));
            b.cardButton.setText("PENDING".equals(st) ? "TIẾP TỤC" : "XEM");
            b.cardButton.setOnClickListener(x -> startActivity(new Intent(this, PaymentActivity.class).putExtra("orderId", r.optString("id"))));
        } else {
            JSONObject def = Economy.items.get(r.optString("item_id"));
            style(b, def == null ? "common" : def.optString("rarity"));
            b.cardIcon.setItem(def);
            b.cardTitle.setText(def == null ? r.optString("item_id") : def.optString("name"));
            b.cardSub.setText(String.format(Locale.US, "-%,d %s · %s", r.optLong("price"), "gold".equals(r.optString("currency")) ? "Gold" : "Gems", when(r)));
            b.cardButton.setVisibility(android.view.View.GONE);
        }
    }
}

package com.shadowduel.arena.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.tabs.TabLayout;
import com.shadowduel.arena.R;
import com.shadowduel.arena.data.Economy;
import com.shadowduel.arena.data.Supa;
import com.shadowduel.arena.databinding.ActivityEconomyBinding;
import com.shadowduel.arena.databinding.ItemCardBinding;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared screen: title, Gold/Gems balance, tabs and a grid of cards. Subclasses fill the cards. */
public abstract class EconomyActivity extends AppCompatActivity {
    protected ActivityEconomyBinding v;
    protected final List<JSONObject> shown = new ArrayList<>();

    protected abstract String title();

    protected abstract String[] tabs();

    /** Load data for the selected tab; call show(list) when ready. */
    protected abstract void load(int tab);

    protected abstract void bind(ItemCardBinding b, JSONObject row, int position);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        v = ActivityEconomyBinding.inflate(getLayoutInflater());
        setContentView(v.getRoot());
        v.ecoTitle.setText(title());
        v.ecoBack.setOnClickListener(x -> finish());
        v.ecoGems.setOnClickListener(x -> startActivity(new Intent(this, ShopActivity.class).putExtra("tab", "currency")));
        for (String t : tabs()) v.ecoTabs.addTab(v.ecoTabs.newTab().setText(t));
        v.ecoTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) { reload(); }
            @Override public void onTabUnselected(TabLayout.Tab tab) { }
            @Override public void onTabReselected(TabLayout.Tab tab) { reload(); }
        });
        int cols = getResources().getDisplayMetrics().widthPixels > 1800 ? 3 : 2;
        v.ecoList.setLayoutManager(new GridLayoutManager(this, cols));
        v.ecoList.setAdapter(adapter);
        if (!Supa.signedIn(this)) {
            v.ecoProgress.setVisibility(View.GONE);
            empty("Cần đăng nhập tài khoản (email hoặc Google) để dùng Nhiệm vụ, Kho đồ và Cửa hàng.");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!Supa.signedIn(this)) return;
        if (Economy.items.isEmpty()) Economy.loadCatalog(this, err -> reload());
        else reload();
    }

    protected int tab() {
        return Math.max(0, v.ecoTabs.getSelectedTabPosition());
    }

    protected void reload() {
        v.ecoProgress.setVisibility(View.VISIBLE);
        refreshWallet();
        load(tab());
    }

    protected void refreshWallet() {
        Economy.loadWallet(this, err -> {
            v.ecoGold.setText(String.format(Locale.US, "GOLD  %,d", Math.max(0, Economy.gold)));
            v.ecoGems.setText(String.format(Locale.US, "GEMS  %,d  +", Math.max(0, Economy.gems)));
        });
    }

    protected void show(List<JSONObject> rows, String error, String emptyText) {
        v.ecoProgress.setVisibility(View.GONE);
        boolean first = shown.isEmpty();
        shown.clear();
        if (rows != null) shown.addAll(rows);
        adapter.notifyDataSetChanged();
        if (first && !shown.isEmpty()) v.ecoList.scheduleLayoutAnimation();
        if (error != null) empty(error);
        else if (shown.isEmpty()) empty(emptyText);
        else v.ecoEmpty.setVisibility(View.GONE);
    }

    protected void empty(String text) {
        v.ecoEmpty.setText(text);
        v.ecoEmpty.setVisibility(View.VISIBLE);
    }

    protected void toast(String msg) {
        Snackbar.make(v.getRoot(), msg, Snackbar.LENGTH_LONG).show();
    }

    /** Reward pop animation on a card after a successful claim / purchase. */
    protected void celebrate(View card) {
        card.animate().scaleX(1.08f).scaleY(1.08f).setDuration(140).withEndAction(() ->
                card.animate().scaleX(1f).scaleY(1f).setDuration(220).start()).start();
    }

    protected void style(ItemCardBinding b, String rarity) {
        int c = Economy.rarityColor(rarity);
        b.getRoot().setStrokeColor(c);
        b.cardRarity.setText(Economy.rarityName(rarity).toUpperCase(Locale.ROOT));
        b.cardRarity.setTextColor(c);
    }

    private final RecyclerView.Adapter<Holder> adapter = new RecyclerView.Adapter<Holder>() {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new Holder(ItemCardBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int pos) {
            h.b.cardProgress.setVisibility(View.GONE);
            h.b.cardButton.setVisibility(View.VISIBLE);
            h.b.cardButton.setEnabled(true);
            h.b.getRoot().setOnClickListener(null);
            h.b.getRoot().setOnLongClickListener(null);
            bind(h.b, shown.get(pos), pos);
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }
    };

    static final class Holder extends RecyclerView.ViewHolder {
        final ItemCardBinding b;

        Holder(ItemCardBinding b) {
            super(b.getRoot());
            this.b = b;
        }
    }
}

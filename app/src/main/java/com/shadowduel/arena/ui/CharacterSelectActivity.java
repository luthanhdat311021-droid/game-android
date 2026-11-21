package com.shadowduel.arena.ui;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.shadowduel.arena.App;
import com.shadowduel.arena.R;
import com.shadowduel.arena.databinding.ActivityCharacterSelectBinding;
import com.shadowduel.arena.game.GameAssets;
import com.shadowduel.arena.game.render.Palette;
import com.shadowduel.arena.game.sim.Const;
import com.shadowduel.arena.game.sim.Defs;
import com.shadowduel.arena.ui.widget.CharacterPreviewView;

/** Character cards are built in Java from shared/characters.json (stats shown as bars). */
public class CharacterSelectActivity extends AppCompatActivity {
    public static final String EXTRA_CHARACTER = "character";
    private ActivityCharacterSelectBinding v;
    private String selected;
    private final MaterialCardView[] cards = new MaterialCardView[3];

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        v = ActivityCharacterSelectBinding.inflate(getLayoutInflater());
        setContentView(v.getRoot());
        selected = App.get().prefs().character();
        Defs defs = GameAssets.defs(this);
        for (int i = 0; i < Const.CHARACTERS.length; i++) {
            String id = Const.CHARACTERS[i];
            cards[i] = card(defs.chars.get(id));
            v.characterCards.addView(cards[i]);
            cards[i].startAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_up));
        }
        highlight();
        v.confirmCharacter.setOnClickListener(x -> {
            App.get().prefs().setCharacter(selected);
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_CHARACTER, selected));
            finish();
        });
    }

    private MaterialCardView card(Defs.Char c) {
        int accent = Palette.style(c.id).accent;
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(Rows.dp(this, 6), 0, Rows.dp(this, 6), 0);
        card.setLayoutParams(lp);
        card.setCardBackgroundColor(0xE6141A33);
        card.setRadius(Rows.dp(this, 14));
        card.setStrokeWidth(Rows.dp(this, 2));
        card.setTag(c.id);
        card.setOnClickListener(x -> {
            selected = c.id;
            highlight();
        });

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Rows.dp(this, 10);
        col.setPadding(p, p, p, p);
        card.addView(col);

        CharacterPreviewView preview = new CharacterPreviewView(this);
        preview.setCharacter(c.id);
        col.addView(preview, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        TextView name = new TextView(this);
        name.setText(c.name);
        name.setTextSize(22);
        name.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC));
        name.setTextColor(accent);
        name.setGravity(Gravity.CENTER);
        col.addView(name);

        TextView title = new TextView(this);
        title.setText(c.title + " · " + c.special.name);
        title.setTextColor(getColor(R.color.text_secondary));
        title.setGravity(Gravity.CENTER);
        title.setTextSize(12);
        col.addView(title);

        stat(col, "HP", c.hp, 1200, accent);
        stat(col, "ATK", c.attack, 130, accent);
        stat(col, "DEF", c.defense, 80, accent);
        stat(col, "SPD", (int) c.speed, 380, accent);
        return card;
    }

    private void stat(LinearLayout col, String label, int value, int max, int color) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(label + " " + value);
        t.setTextSize(11);
        t.setTextColor(0xFFDDE3FF);
        row.addView(t, new LinearLayout.LayoutParams(Rows.dp(this, 64), LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearProgressIndicator bar = new LinearProgressIndicator(this);
        bar.setMax(max);
        bar.setProgress(value);
        bar.setIndicatorColor(color);
        bar.setTrackColor(0x33FFFFFF);
        row.addView(bar, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        col.addView(row);
    }

    private void highlight() {
        for (MaterialCardView c : cards) {
            boolean on = selected.equals(c.getTag());
            c.setStrokeColor(on ? Palette.style((String) c.getTag()).accent : 0xFF2E3A6B);
            c.animate().scaleX(on ? 1.03f : 0.97f).scaleY(on ? 1.03f : 0.97f).setDuration(200).start();
            c.setAlpha(on ? 1f : 0.75f);
        }
    }
}

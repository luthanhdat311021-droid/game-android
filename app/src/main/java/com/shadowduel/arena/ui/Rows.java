package com.shadowduel.arena.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.shadowduel.arena.R;

/** Java Dynamic UI helpers: table rows built in code (leaderboard, history, results, lobby). */
public final class Rows {
    private Rows() {
    }

    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    /** A row of cells; weights define the column widths. */
    public static LinearLayout row(Context c, String[] cells, float[] weights, int color, boolean header) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(c, 8);
        row.setPadding(p, p, p, p);
        if (!header) row.setBackgroundResource(R.drawable.bg_row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 4);
        row.setLayoutParams(lp);
        for (int i = 0; i < cells.length; i++) {
            TextView t = new TextView(c);
            t.setText(cells[i]);
            t.setTextColor(header ? c.getColor(R.color.accent_yellow) : color);
            t.setTextSize(header ? 12 : 15);
            t.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, i == 0 || header ? Typeface.BOLD_ITALIC : Typeface.BOLD));
            t.setGravity(i == 0 ? Gravity.START : Gravity.CENTER);
            t.setSingleLine(true);
            row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weights[i]));
        }
        return row;
    }

    public static TextView message(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(c.getColor(R.color.text_secondary));
        t.setPadding(dp(c, 8), dp(c, 24), dp(c, 8), dp(c, 24));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    public static void animateIn(View v, int index) {
        android.view.animation.Animation a = AnimationUtils.loadAnimation(v.getContext(), R.anim.slide_in_left);
        a.setStartOffset(Math.min(index, 12) * 40L);
        v.startAnimation(a);
    }
}

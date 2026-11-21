package com.shadowduel.arena.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.shadowduel.arena.App;
import com.shadowduel.arena.R;
import com.shadowduel.arena.data.Prefs;
import com.shadowduel.arena.data.Supa;
import com.shadowduel.arena.databinding.ActivityLoginBinding;

/** Email/password login (Supabase Auth), guest login for LAN servers, or offline play. */
public class LoginActivity extends AppCompatActivity {
    protected ActivityLoginBinding v;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        v = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(v.getRoot());
        v.loginForm.startAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_up));
        v.loginHero.setCharacter(new String[]{"kaze", "ryujin", "akira"}[(int) (Math.random() * 3)]);
        if (!Supa.configured()) v.loginWarning.setVisibility(View.VISIBLE);
        setupForm();
        v.googleButton.setOnClickListener(x -> google());
        v.forgotPassword.setOnClickListener(x -> forgot());
        v.guestButton.setOnClickListener(x -> askGuestName());
        v.offlineButton.setOnClickListener(x -> {
            App.get().prefs().setLoginMode("offline");
            goHome();
        });
    }

    protected void setupForm() {
        v.loginButton.setOnClickListener(x -> submit());
        v.switchMode.setOnClickListener(x -> {
            startActivity(new Intent(this, RegisterActivity.class));
            overridePendingTransition(R.anim.slide_up, R.anim.fade_out);
        });
    }

    protected String text(EditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    private void submit() {
        String email = text(v.emailInput), pass = text(v.passwordInput);
        v.emailLayout.setError(null);
        v.passwordLayout.setError(null);
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            v.emailLayout.setError("Email không hợp lệ");
            return;
        }
        if (pass.length() < 6) {
            v.passwordLayout.setError("Ít nhất 6 ký tự");
            return;
        }
        busy(true);
        Supa.signIn(this, email, pass, err -> {
            busy(false);
            if (err != null) {
                Snackbar.make(v.getRoot(), err, Snackbar.LENGTH_LONG).show();
                return;
            }
            App.get().prefs().setLoginMode("supabase");
            goHome();
        });
    }

    protected void busy(boolean on) {
        v.loginProgress.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        v.loginButton.setEnabled(!on);
    }

    /** Opens Google sign-in in the browser (Supabase OAuth + PKCE); AuthCallbackActivity finishes it. */
    private void google() {
        if (!Supa.configured()) {
            Snackbar.make(v.getRoot(), R.string.supabase_missing, Snackbar.LENGTH_LONG).show();
            return;
        }
        try {
            v.googleButton.setEnabled(false);
            v.googleButton.setText("Đang mở Google…");
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Supa.googleAuthorizeUrl(this))));
        } catch (Exception e) {
            Snackbar.make(v.getRoot(), "Không mở được trình duyệt", Snackbar.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (v != null && v.googleButton != null) {
            v.googleButton.setEnabled(true);
            v.googleButton.setText("Continue with Google");
        }
    }

    private void forgot() {
        String email = text(v.emailInput);
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            v.emailLayout.setError("Nhập email để nhận link đặt lại mật khẩu");
            return;
        }
        Supa.resetPassword(this, email, err -> Snackbar.make(v.getRoot(),
                err == null ? "Đã gửi email đặt lại mật khẩu tới " + email : err, Snackbar.LENGTH_LONG).show());
    }

    private void askGuestName() {
        Prefs prefs = App.get().prefs();
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(prefs.guestName());
        input.setSelectAllOnFocus(true);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Tên hiển thị (khách)")
                .setMessage("Chế độ khách chỉ dùng được khi game server bật ALLOW_GUESTS. Kết quả của khách không được lưu lên bảng xếp hạng.")
                .setView(input)
                .setPositiveButton("Vào game", (d, w) -> {
                    String n = input.getText().toString().trim();
                    prefs.setGuestName(TextUtils.isEmpty(n) ? "Guest" : n);
                    prefs.setLoginMode("guest");
                    goHome();
                })
                .setNegativeButton("Hủy", null)
                .show();
    }

    protected void goHome() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        finish();
    }
}

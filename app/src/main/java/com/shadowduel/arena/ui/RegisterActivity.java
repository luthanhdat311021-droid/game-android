package com.shadowduel.arena.ui;

import android.view.View;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.shadowduel.arena.App;
import com.shadowduel.arena.R;
import com.shadowduel.arena.data.Supa;

/** Registration reuses the login layout with the username field shown. */
public class RegisterActivity extends LoginActivity {

    @Override
    protected void setupForm() {
        v.loginTitle.setText(R.string.register);
        v.loginButton.setText(R.string.register);
        v.usernameLayout.setVisibility(View.VISIBLE);
        v.forgotPassword.setVisibility(View.GONE);
        v.switchMode.setText(R.string.have_account);
        v.switchMode.setOnClickListener(x -> finish());
        v.loginButton.setOnClickListener(x -> submit());
    }

    private void submit() {
        String name = text(v.usernameInput), email = text(v.emailInput), pass = text(v.passwordInput);
        v.usernameLayout.setError(null);
        v.emailLayout.setError(null);
        v.passwordLayout.setError(null);
        if (!name.matches("[A-Za-z0-9_.-]{3,20}")) {
            v.usernameLayout.setError("3–20 ký tự: chữ, số, _ . -");
            return;
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            v.emailLayout.setError("Email không hợp lệ");
            return;
        }
        if (pass.length() < 6) {
            v.passwordLayout.setError("Ít nhất 6 ký tự");
            return;
        }
        busy(true);
        Supa.signUp(this, name, email, pass, err -> {
            busy(false);
            if (Supa.NEED_CONFIRM.equals(err)) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Kiểm tra email")
                        .setMessage("Supabase đã gửi thư xác nhận tới " + email + ". Xác nhận xong hãy đăng nhập.")
                        .setPositiveButton("OK", (d, w) -> finish())
                        .show();
                return;
            }
            if (err != null) {
                Snackbar.make(v.getRoot(), err, Snackbar.LENGTH_LONG).show();
                return;
            }
            App.get().prefs().setLoginMode("supabase");
            goHome();
        });
    }
}

package com.shadowduel.arena.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.shadowduel.arena.App;
import com.shadowduel.arena.data.Supa;

/**
 * Receives shadowduel://auth-callback?code=… after Google sign-in and exchanges the code
 * (PKCE: the code is useless without the verifier stored on this device).
 */
public class AuthCallbackActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Uri data = getIntent().getData();
        String code = data == null ? null : data.getQueryParameter("code");
        String error = data == null ? "no data" : data.getQueryParameter("error_description");
        if (code == null) {
            Toast.makeText(this, error != null ? "Đăng nhập Google bị hủy: " + error : "Đăng nhập Google bị hủy", Toast.LENGTH_LONG).show();
            back(false);
            return;
        }
        Supa.finishGoogle(this, code, err -> {
            if (err != null) {
                Toast.makeText(this, err, Toast.LENGTH_LONG).show();
                back(false);
                return;
            }
            App.get().prefs().setLoginMode("supabase");
            App.get().client().disconnect(); // reconnect to the game server with the new identity
            back(true);
        });
    }

    private void back(boolean ok) {
        Intent i = new Intent(this, ok ? MainActivity.class : LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }
}

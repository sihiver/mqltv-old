package com.mqltv;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AccountActivity extends FragmentActivity {

    private TextView letter;
    private TextView name;
    private TextView username;
    private TextView packages;
    private TextView expires;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account);

        if (!AuthPrefs.isLoggedIn(this)) {
            // If opened without a session, bounce to Login.
            LoginGuard.ensureLoggedIn(this);
            finish();
            return;
        }

        letter = findViewById(R.id.account_profile_letter);
        name = findViewById(R.id.account_name);
        username = findViewById(R.id.account_username);
        packages = findViewById(R.id.account_packages);
        expires = findViewById(R.id.account_expires);
        status = findViewById(R.id.account_status);
        Button btnClose = findViewById(R.id.account_close);
        Button btnLogout = findViewById(R.id.account_logout);

        bind();

        // Refresh from server so renewed subscriptions reflect immediately.
        AccountStatusRefresher.refresh(this, this::bind);

        if (btnClose != null) {
            btnClose.setOnClickListener(v -> finish());
        }

        if (btnLogout != null) {
            btnLogout.setOnClickListener(v -> {
                AuthPrefs.clear(getApplicationContext());
                // Return to launcher/home.
                Intent i = new Intent(AccountActivity.this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i);
                finish();
            });
        }

        // Make D-pad focus start on close button.
        View focus = btnClose != null ? btnClose : btnLogout;
        if (focus != null) focus.requestFocus();
    }

    private void bind() {
        String displayName = AuthPrefs.getDisplayName(this);
        String u = AuthPrefs.getUsername(this);
        String shownName = !TextUtils.isEmpty(displayName) ? displayName : u;

        if (letter != null) {
            String l = "?";
            if (!TextUtils.isEmpty(shownName)) {
                l = String.valueOf(Character.toUpperCase(shownName.trim().charAt(0)));
            }
            letter.setText(l);
        }
        if (name != null) name.setText(!TextUtils.isEmpty(shownName) ? shownName : "-");
        if (username != null) username.setText(!TextUtils.isEmpty(u) ? ("@" + u) : "-");

        if (packages != null) {
            String plan = AuthPrefs.getPlan(this);
            plan = plan.trim();
            if (!plan.isEmpty()) {
                packages.setText(plan);
            } else {
                packages.setText(AuthPrefs.getPackagesDisplay(this));
            }
        }

        String expiresAt = AuthPrefs.getExpiresAt(this);
        String expiresDisplay = formatExpires(expiresAt);
        if (expires != null) expires.setText(expiresDisplay);

        boolean isExpired = SubscriptionGuard.isExpired(this);
        if (status != null) {
            status.setText(isExpired ? "EXPIRED" : "AKTIF");
            int color = ContextCompat.getColor(this, isExpired ? android.R.color.holo_red_light : android.R.color.holo_green_light);
            status.setBackgroundColor(color);
        }
    }

    private static String formatExpires(String expiresAt) {
        if (expiresAt == null) return "-";
        String s = expiresAt.trim();
        if (s.isEmpty()) return "-";

        // Best-effort RFC3339 parsing (re-using SubscriptionGuard logic would require making it public).
        long ms = parseRfc3339ToMillis(s);
        if (ms <= 0) return s;

        SimpleDateFormat fmt = new SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US);
        return fmt.format(new Date(ms));
    }

    private static long parseRfc3339ToMillis(String input) {
        if (input == null) return -1;
        String s = input.trim();
        if (s.isEmpty()) return -1;

        // Normalize timezone: Z -> +0000, +07:00 -> +0700
        if (s.endsWith("Z")) {
            s = s.substring(0, s.length() - 1) + "+0000";
        } else {
            int len = s.length();
            if (len >= 6) {
                char sign = s.charAt(len - 6);
                if ((sign == '+' || sign == '-') && s.charAt(len - 3) == ':') {
                    s = s.substring(0, len - 3) + s.substring(len - 2);
                }
            }
        }

        java.util.Date d = tryParse(s, "yyyy-MM-dd'T'HH:mm:ss.SSSZ");
        if (d == null) d = tryParse(s, "yyyy-MM-dd'T'HH:mm:ssZ");
        return d != null ? d.getTime() : -1;
    }

    private static java.util.Date tryParse(String s, String pattern) {
        try {
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(pattern, java.util.Locale.US);
            fmt.setLenient(false);
            fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return fmt.parse(s);
        } catch (Throwable ignored) {
            return null;
        }
    }
}

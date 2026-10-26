package com.vault.app.ui;


import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.vault.app.R;
import com.vault.app.crypto.AppLock;
import com.vault.app.data.Entry;
import com.vault.app.data.VaultRepository;
import com.vault.app.tools.ChangePasswordLink;
import com.vault.app.tools.TotpGenerator;

import java.util.Locale;

/** A quiet, read-only page for one login. Editing happens in EntrySheet, behind "Edit". */
public class DetailActivity extends AppCompatActivity implements EntrySheet.Listener {
    public static final String EXTRA_ID = "entryId";

    private static final int[] AVATAR_COLORS = {
            0xFF0A84FF, 0xFFFF9F0A, 0xFF30D158, 0xFFBF5AF2, 0xFFFF375F, 0xFF40C8E0};

    private String entryId;
    private Entry entry;
    private String user, pass;
    private boolean passVisible;
    private TotpGenerator.Config totp;

    private TextView tvName, tvModified, tvLetter, tvUser, tvPass, tvSite, tvCode,
            tvSecTitle, tvSecBody, btnFix;
    private ImageView ivIcon, ivShield;
    private View avatar, rowTotp, divTotp, divFix;
    private CircularProgressIndicator ring;

    private final Handler tick = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (totp == null) return;
            long now = System.currentTimeMillis();
            String code = TotpGenerator.code(totp, now);
            tvCode.setText(code.length() == 6 ? code.substring(0, 3) + " " + code.substring(3) : code);
            int left = TotpGenerator.secondsLeft(totp, now);
            ring.setIndicatorColor(color(left <= 5 ? R.color.danger : R.color.ok));
            ring.setProgressCompat(left * 100 / totp.period, false);
            tick.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_detail);

        View root = findViewById(R.id.detailRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        entryId = getIntent().getStringExtra(EXTRA_ID);

        tvName = findViewById(R.id.tvName);
        tvModified = findViewById(R.id.tvModified);
        tvLetter = findViewById(R.id.tvLetter);
        tvUser = findViewById(R.id.tvUser);
        tvPass = findViewById(R.id.tvPass);
        tvSite = findViewById(R.id.tvSite);
        tvCode = findViewById(R.id.tvCode);
        tvSecTitle = findViewById(R.id.tvSecTitle);
        tvSecBody = findViewById(R.id.tvSecBody);
        btnFix = findViewById(R.id.btnFix);
        ivIcon = findViewById(R.id.ivIcon);
        ivShield = findViewById(R.id.ivShield);
        avatar = findViewById(R.id.avatar);
        rowTotp = findViewById(R.id.rowTotp);
        divTotp = findViewById(R.id.divTotp);
        divFix = findViewById(R.id.divFix);
        ring = findViewById(R.id.ring);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnEdit).setOnClickListener(v ->
                EntrySheet.newInstance(entryId).show(getSupportFragmentManager(), "entry"));

        findViewById(R.id.rowUser).setOnClickListener(v -> showMenu(v, user, false, null, false));
        findViewById(R.id.rowPass).setOnClickListener(v -> showMenu(v, pass, true, null, true));
        findViewById(R.id.rowTotp).setOnClickListener(v -> {
            if (totp != null) {
                showMenu(v, TotpGenerator.code(totp, System.currentTimeMillis()), false, null, true);
            }
        });
        findViewById(R.id.rowSite).setOnClickListener(v -> {
            if (entry != null && entry.domain != null && !entry.domain.isEmpty()) {
                showMenu(v, entry.domain, false, "https://" + entry.domain, false);
            }
        });
        btnFix.setOnClickListener(v -> openChangePage());
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (AppLock.isLocked()) {
            startActivity(new Intent(this, UnlockActivity.class));
            finish();
            return;
        }
        load();
    }

    @Override
    protected void onStop() {
        super.onStop();
        AppLock.markLeft();
        // Don't keep secrets around while the screen is not in front
        tick.removeCallbacksAndMessages(null);
        user = null;
        pass = null;
        totp = null;
        passVisible = false;
        tvUser.setText("");
        tvPass.setText("");
        tvCode.setText("");
    }

    @Override
    public void onEntriesChanged() {
        load(); // after Edit: show the new values (or close if the login was deleted)
    }

    // ---------- Loading ----------

    private void load() {
        AppLock.requireFresh(this, () -> {
            VaultRepository repo = VaultRepository.get(this);
            repo.getById(entryId, (e, err) -> {
                if (err != null || e == null) {
                    finish();
                    return;
                }
                entry = e;
                bindHeader(e);
                tvSite.setText(e.domain != null && !e.domain.isEmpty() ? e.domain : "None");
                bindSecurity();

                repo.reveal(e, (creds, err2) -> {
                    if (err2 != null) {
                        toast("Couldn't open this login");
                        finish();
                        return;
                    }
                    user = creds[0];
                    pass = creds[1];
                    tvUser.setText(user.isEmpty() ? "None" : user);
                    renderPass();

                    repo.revealTotp(e, (config, err3) -> {
                        tick.removeCallbacksAndMessages(null);
                        if (err3 != null || config == null) {
                            totp = null;
                            rowTotp.setVisibility(View.GONE);
                            divTotp.setVisibility(View.GONE);
                        } else {
                            totp = TotpGenerator.fromStored(config);
                            rowTotp.setVisibility(View.VISIBLE);
                            divTotp.setVisibility(View.VISIBLE);
                            tick.post(ticker);
                        }
                        bindSecurity();
                    });
                });
            });
        }, this::finish);
    }

    private void bindHeader(Entry e) {
        tvName.setText(e.title);
        CharSequence when = DateUtils.getRelativeTimeSpanString(
                e.updatedAt, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS);
        tvModified.setText("Last modified " + when.toString().toLowerCase(Locale.ROOT));

        Drawable icon = null;
        if (e.packageName != null && !e.packageName.isEmpty()) {
            try {
                icon = getPackageManager().getApplicationIcon(e.packageName);
            } catch (Exception ignored) {
                // app not installed or not visible: use the letter below
            }
        }
        if (icon != null) {
            ivIcon.setImageDrawable(icon);
            ivIcon.setVisibility(View.VISIBLE);
            tvLetter.setVisibility(View.GONE);
            avatar.setBackground(null);
        } else {
            ivIcon.setVisibility(View.GONE);
            tvLetter.setVisibility(View.VISIBLE);
            tvLetter.setText(e.title.isEmpty() ? "?" : e.title.substring(0, 1).toUpperCase(Locale.ROOT));
            avatar.setBackgroundResource(R.drawable.avatar_bg);
            int pick = (e.title.hashCode() & 0x7fffffff) % AVATAR_COLORS.length;
            avatar.getBackground().mutate().setTint(AVATAR_COLORS[pick]);
        }
    }

    /** The verdict, from the stored strength and breach information. Nothing is decrypted for it. */
    private void bindSecurity() {
        if (entry == null) return;
        String title;
        String body;
        int colorRes;
        boolean flagged;
        if (entry.breachCount > 0) {
            title = "Breached Password";
            body = "This password appeared in a known data breach. Change it as soon as possible.";
            colorRes = R.color.danger;
            flagged = true;
        } else if (entry.score <= 1) {
            title = "Weak Password";
            body = "This password is easy to guess. Choose a longer, more random one.";
            colorRes = R.color.danger;
            flagged = true;
        } else if (entry.score == 2) {
            title = "Fair Password";
            body = "This password could be stronger. A longer password is harder to guess.";
            colorRes = R.color.warn;
            flagged = true;
        } else {
            title = "Strong Password";
            body = "This password is long and difficult to guess.";
            colorRes = R.color.ok;
            flagged = false;
        }
        if (totp != null) {
            title += " & Verification Code";
            body += " Your verification code protects your account in case your password is stolen.";
        }
        tvSecTitle.setText(title);
        tvSecBody.setText(body);
        ivShield.setImageTintList(ColorStateList.valueOf(color(colorRes)));

        boolean hasSite = entry.domain != null && !entry.domain.isEmpty();
        int fixVisibility = (flagged && hasSite) ? View.VISIBLE : View.GONE;
        btnFix.setVisibility(fixVisibility);
        divFix.setVisibility(fixVisibility);
    }

    private void renderPass() {
        if (pass == null) return;
        if (passVisible) {
            tvPass.setText(pass);
        } else {
            StringBuilder dots = new StringBuilder();
            for (int i = 0; i < Math.min(pass.length(), 24); i++) dots.append('•');
            tvPass.setText(dots.length() == 0 ? "None" : dots.toString());
        }
    }

    // ---------- Row menus ----------

    private void showMenu(View anchor, String value, boolean canReveal, String openUrl, boolean sensitive) {
        if (value == null || value.isEmpty()) return;
        PopupMenu menu = new PopupMenu(this, anchor, Gravity.END);
        menu.getMenu().add(0, 1, 0, "Copy");
        if (canReveal) menu.getMenu().add(0, 2, 1, passVisible ? "Hide" : "Show");
        if (openUrl != null) menu.getMenu().add(0, 3, 1, "Open in browser");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    copy(value, sensitive);
                    break;
                case 2:
                    passVisible = !passVisible;
                    renderPass();
                    break;
                case 3:
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(openUrl)));
                    } catch (Exception e) {
                        toast("No browser found");
                    }
                    break;
            }
            return true;
        });
        menu.show();
    }

    private void copy(String text, boolean sensitive) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("vault", text);
        if (sensitive) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(extras);
        }
        cm.setPrimaryClip(clip);
        tvName.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        if (Build.VERSION.SDK_INT < 33) toast("Copied");
        if (sensitive) {
            new Handler(Looper.getMainLooper()).postDelayed(cm::clearPrimaryClip, 30_000);
        }
    }

    // ---------- Change password on the website ----------

    private void openChangePage() {
        if (entry == null || entry.domain == null || entry.domain.isEmpty()) return;
        String domain = entry.domain;
        btnFix.setEnabled(false);
        ChangePasswordLink.resolve(domain, (result, err) -> {
            btnFix.setEnabled(true);
            boolean specific = result != null && result.specific;
            String url = result != null ? result.url : "https://" + domain;
            String intro = specific
                    ? "Vault will open this site's password change page."
                    : "This site has no direct link, so Vault will open its homepage. Sign in and look under Settings, then Security, for \"Change password\".";
            new AlertDialog.Builder(this)
                    .setTitle("Change password on " + domain)
                    .setMessage(intro + "\n\nOn the new password box, choose \"Use a strong password\" "
                            + "from Vault. After you submit, tap Save when Android asks, so Vault "
                            + "keeps the new password.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Open", (d, w) -> {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                        } catch (Exception e) {
                            toast("No browser found");
                        }
                    })
                    .show();
        });
    }

    // ---------- Helpers ----------

    private int color(int res) {
        return ContextCompat.getColor(this, res);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
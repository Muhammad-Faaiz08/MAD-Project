package com.vault.app.autofill;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.service.autofill.Dataset;
import android.view.WindowManager;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillValue;
import android.widget.RemoteViews;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.vault.app.R;
import com.vault.app.crypto.BiometricHelper;
import com.vault.app.data.Entry;
import com.vault.app.data.VaultRepository;
import com.vault.app.tools.PasswordTools;

import java.util.ArrayList;
import java.util.List;

/**
 * Opens on top of the app being filled. In normal mode it asks for fingerprint or PIN, decrypts
 * ONLY the chosen login and gives it back to Android. In generate mode it creates a new random
 * password. It deliberately does not unlock the Vault app itself.
 */
public class AutofillAuthActivity extends AppCompatActivity {
    public static final String EXTRA_ENTRY_ID = "entryId";
    public static final String EXTRA_USER_ID = "userId";
    public static final String EXTRA_PASS_ID = "passId";
    public static final String EXTRA_WEB_DOMAIN = "webDomain";
    public static final String EXTRA_PACKAGE = "pkg";
    public static final String EXTRA_MODE = "mode";
    public static final String MODE_GENERATE = "generate";
    public static final String EXTRA_TARGET_IDS = "targetIds";

    private String entryId, webDomain, pkg, mode;
    private AutofillId userId, passId;
    private ArrayList<AutofillId> targetIds;

    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);

        Intent in = getIntent();
        entryId = in.getStringExtra(EXTRA_ENTRY_ID);
        webDomain = in.getStringExtra(EXTRA_WEB_DOMAIN);
        pkg = in.getStringExtra(EXTRA_PACKAGE);
        mode = in.getStringExtra(EXTRA_MODE);
        userId = in.getParcelableExtra(EXTRA_USER_ID);
        passId = in.getParcelableExtra(EXTRA_PASS_ID);
        targetIds = in.getParcelableArrayListExtra(EXTRA_TARGET_IDS);

        if (savedInstanceState != null) return; // rotation: don't do it twice

        if (MODE_GENERATE.equals(mode)) {
            generateStrong(); // nothing secret is revealed here, so no fingerprint is needed
            return;
        }

        BiometricHelper.authenticate(this, "Fill with Vault", new BiometricHelper.Callback() {
            @Override
            public void onSuccess() {
                proceed();
            }

            @Override
            public void onFailure(String message) {
                cancel();
            }
        });
    }

    // ---------- Generate mode ----------

    private void generateStrong() {
        if (targetIds == null || targetIds.isEmpty()) {
            cancel();
            return;
        }
        String password = PasswordTools.generate(20);
        copyToClipboard(password);

        RemoteViews row = new RemoteViews(getPackageName(), R.layout.autofill_item);
        Dataset.Builder b = new Dataset.Builder(row);
        for (AutofillId id : targetIds) b.setValue(id, AutofillValue.forText(password));

        setResult(RESULT_OK,
                new Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, b.build()));
        Toast.makeText(getApplicationContext(),
                "Strong password filled and copied for 30 seconds", Toast.LENGTH_SHORT).show();
        finish();
    }

    /** Safety net: if saving ever fails, the new password is still on the clipboard for a while. */
    private void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("password", text);
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
        clip.getDescription().setExtras(extras);
        cm.setPrimaryClip(clip);
        new Handler(Looper.getMainLooper()).postDelayed(cm::clearPrimaryClip, 30_000);
    }

    // ---------- Normal mode ----------

    private void proceed() {
        VaultRepository repo = VaultRepository.get(this);
        if (entryId != null) {
            fill(repo, entryId);
            return;
        }
        // "Choose from Vault…": let the user pick any login
        repo.list((all, err) -> {
            if (err != null || all.isEmpty()) {
                cancel();
                return;
            }
            showPicker(repo, all);
        });
    }

    private void showPicker(VaultRepository repo, List<Entry> all) {
        String[] labels = new String[all.size()];
        for (int i = 0; i < labels.length; i++) {
            Entry e = all.get(i);
            String name = e.label != null ? e.label : e.title;
            labels[i] = (e.domain == null || e.domain.isEmpty()) ? name : name + "  ·  " + e.domain;
        }
        new AlertDialog.Builder(this)
                .setTitle("Choose a login")
                .setItems(labels, (dialog, which) -> {
                    Entry chosen = all.get(which);
                    repo.associate(chosen.id, webDomain, pkg, (changed, err) -> { });
                    fill(repo, chosen.id);
                })
                .setOnCancelListener(d -> cancel())
                .show();
    }

    private void fill(VaultRepository repo, String id) {
        repo.getById(id, (entry, err) -> {
            if (err != null || entry == null) {
                cancel();
                return;
            }
            repo.reveal(entry, (creds, err2) -> {
                if (err2 != null) {
                    cancel();
                    return;
                }
                finishWith(creds[0], creds[1]);
            });
        });
    }

    private void finishWith(String username, String password) {
        RemoteViews row = new RemoteViews(getPackageName(), R.layout.autofill_item);
        Dataset.Builder b = new Dataset.Builder(row);
        if (userId != null && username != null && !username.isEmpty()) {
            b.setValue(userId, AutofillValue.forText(username));
        }
        if (passId != null) b.setValue(passId, AutofillValue.forText(password));

        Intent result = new Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, b.build());
        setResult(RESULT_OK, result);
        finish();
    }

    private void cancel() {
        setResult(RESULT_CANCELED);
        finish();
    }
}
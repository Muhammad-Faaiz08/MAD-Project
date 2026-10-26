package com.vault.app.autofill;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.vault.app.crypto.BiometricHelper;
import com.vault.app.data.VaultRepository;

/**
 * Opens after you tap "Save" in Android's save prompt. If the username is unknown it asks for it,
 * then it saves (asking for fingerprint or PIN only if the key is locked). It deliberately does
 * not unlock the Vault app itself.
 */
public class SaveActivity extends AppCompatActivity {
    public static final String EXTRA_USERNAME = "username";
    public static final String EXTRA_PASSWORD = "password";
    public static final String EXTRA_WEB_DOMAIN = "webDomain";
    public static final String EXTRA_PACKAGE = "pkg";
    public static final String EXTRA_CHANGE_FORM = "changeForm";

    private String username, password, domain, pkg;
    private boolean changeForm;
    private boolean allowEmpty; // the user was asked and chose to leave the username empty

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);

        Intent in = getIntent();
        username = in.getStringExtra(EXTRA_USERNAME);
        password = in.getStringExtra(EXTRA_PASSWORD);
        domain = in.getStringExtra(EXTRA_WEB_DOMAIN);
        pkg = in.getStringExtra(EXTRA_PACKAGE);
        changeForm = in.getBooleanExtra(EXTRA_CHANGE_FORM, false);

        if (savedInstanceState != null) return; // rotation: don't do it twice
        if (password == null) {
            finish();
            return;
        }

        boolean noUsername = username == null || username.isEmpty();
        if (noUsername && !changeForm) {
            askUsername();
        } else {
            attempt(true);
        }
    }

    /** Vault did not see which account this is: ask, so each account becomes its own login. */
    private void askUsername() {
        EditText input = new EditText(this);
        input.setHint("Username or email");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(pad, 8, pad, 0);
        box.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("Which account is this for?")
                .setMessage("Vault didn't see the username on this page. Type it so the right "
                        + "login is saved, or leave it empty.")
                .setView(box)
                .setNegativeButton("Cancel", (d, w) -> finish())
                .setPositiveButton("Save", (d, w) -> {
                    username = input.getText().toString().trim();
                    allowEmpty = true;
                    attempt(true);
                })
                .setOnCancelListener(d -> finish())
                .show();
    }

    /** First try without asking. If the key is locked, ask once and try again. */
    private void attempt(boolean mayAskForFingerprint) {
        String title = domain != null ? domain : appLabel(pkg);
        VaultRepository.get(this).upsertCredential(title, domain, pkg,
                username == null ? "" : username, password, changeForm, allowEmpty,
                (status, err) -> {
                    if (err != null) {
                        if (mayAskForFingerprint) {
                            askThenRetry();
                        } else {
                            toast("Couldn't save: " + err.getClass().getSimpleName()
                                    + " " + err.getMessage());
                            finish();
                        }
                        return;
                    }
                    if ("needs_username".equals(status)) {
                        askUsername();
                        return;
                    }
                    if ("updated".equals(status)) {
                        toast("Password updated in Vault");
                    } else if ("unchanged".equals(status)) {
                        toast("Already saved in Vault");
                    } else {
                        toast("Saved to Vault as \"" + title + "\"");
                        VaultRepository.get(this).enrichDomains(null);
                    }
                    finish();
                });
    }

    private void askThenRetry() {
        BiometricHelper.authenticate(this, "Save to Vault", new BiometricHelper.Callback() {
            @Override
            public void onSuccess() {
                attempt(false);
            }

            @Override
            public void onFailure(String message) {
                toast("Not saved");
                finish();
            }
        });
    }

    private String appLabel(String packageName) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
        } catch (Exception e) {
            return packageName != null ? packageName : "Login";
        }
    }

    private void toast(String s) {
        Toast.makeText(getApplicationContext(), s, Toast.LENGTH_LONG).show();
    }
}
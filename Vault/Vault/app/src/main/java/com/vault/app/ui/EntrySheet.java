package com.vault.app.ui;

import android.content.Intent;
import android.net.Uri;
import com.vault.app.tools.ChangePasswordLink;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.vault.app.tools.TotpGenerator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.vault.app.R;
import com.vault.app.crypto.AppLock;
import com.vault.app.data.Entry;
import com.vault.app.data.VaultRepository;
import com.vault.app.tools.PasswordTools;

import java.util.Locale;
import com.vault.app.tools.WebsiteFinder;

/** Add or edit one login. Pass null to add, or an entry id to edit. */
public class EntrySheet extends BottomSheetDialogFragment {

    public interface Listener {
        void onEntriesChanged();
    }

    private static final String ARG_ID = "id";

    public static EntrySheet newInstance(@Nullable String entryId) {
        EntrySheet s = new EntrySheet();
        Bundle b = new Bundle();
        b.putString(ARG_ID, entryId);
        s.setArguments(b);
        return s;
    }

    private String entryId;
    private Entry existing;
    private EditText etTitle, etDomain, etUser, etPass;
    private TextView tvShow, tvGenerate, tvStrength, tvBreach, btnCopy, btnDelete, btnFix;
    private LinearProgressIndicator bar;
    private MaterialButton btnSave;
    private View totpBox;
    private TextView tvTotpCode, tvTotpTime, btnTotp, btnTotpCopy, btnTotpRemove;
    private LinearProgressIndicator totpBar;
    private TotpGenerator.Config totp;   // the secret is only kept in memory while this sheet is open
    private final Handler tickHandler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!isAdded() || totp == null) return;
            showTotp();
            tickHandler.postDelayed(this, 1000);
        }
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int seq = 0;              // bumps on every password edit so old results are ignored
    private String breachFor = null;  // the password our last breach result belongs to
    private int breachCount = -1;

    // ---------- Window setup ----------

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog d = super.onCreateDialog(savedInstanceState);
        Window w = d.getWindow();
        if (w != null) {
            // dialogs are separate windows, so they need their own screenshot block
            w.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        return d;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_entry, container, false);
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog d = getDialog();
        if (d instanceof BottomSheetDialog) {
            BottomSheetBehavior<FrameLayout> b = ((BottomSheetDialog) d).getBehavior();
            b.setState(BottomSheetBehavior.STATE_EXPANDED);
            b.setSkipCollapsed(true);
        }
        ((View) requireView().getParent()).setBackgroundColor(Color.TRANSPARENT);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        seq++;
        handler.removeCallbacksAndMessages(null);
        tickHandler.removeCallbacksAndMessages(null);
        totp = null;
    }

    // ---------- Setup ----------

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        entryId = requireArguments().getString(ARG_ID);

        TextView tvSheetTitle = v.findViewById(R.id.tvSheetTitle);
        etTitle = v.findViewById(R.id.etTitle);
        etDomain = v.findViewById(R.id.etDomain);
        etUser = v.findViewById(R.id.etUser);
        etPass = v.findViewById(R.id.etPass);
        tvShow = v.findViewById(R.id.tvShow);
        tvGenerate = v.findViewById(R.id.tvGenerate);
        tvStrength = v.findViewById(R.id.tvStrength);
        tvBreach = v.findViewById(R.id.tvBreach);
        bar = v.findViewById(R.id.bar);
        btnSave = v.findViewById(R.id.btnSave);
        btnCopy = v.findViewById(R.id.btnCopy);
        btnDelete = v.findViewById(R.id.btnDelete);
        btnFix = v.findViewById(R.id.btnFix);

        totpBox = v.findViewById(R.id.totpBox);
        tvTotpCode = v.findViewById(R.id.tvTotpCode);
        tvTotpTime = v.findViewById(R.id.tvTotpTime);
        totpBar = v.findViewById(R.id.totpBar);
        btnTotp = v.findViewById(R.id.btnTotp);
        btnTotpCopy = v.findViewById(R.id.btnTotpCopy);
        btnTotpRemove = v.findViewById(R.id.btnTotpRemove);

        SpringTouch.attach(btnSave);
        SpringTouch.attach(tvGenerate);
        SpringTouch.attach(btnCopy);
        SpringTouch.attach(btnDelete);
        SpringTouch.attach(btnFix);

        tvShow.setOnClickListener(x -> setPasswordVisible(
                etPass.getTransformationMethod() instanceof PasswordTransformationMethod));
        tvGenerate.setOnClickListener(x -> {
            etPass.setText(PasswordTools.generate(20));
            setPasswordVisible(true);
            x.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        });
        etPass.addTextChangedListener(new Watcher() {
            @Override
            public void afterTextChanged(Editable e) {
                onPasswordChanged();
            }
        });
        btnSave.setOnClickListener(x -> save());
        btnCopy.setOnClickListener(x -> copyPassword());
        btnDelete.setOnClickListener(x -> confirmDelete());
        btnFix.setOnClickListener(x -> openChangePage());

        SpringTouch.attach(btnTotp);
        SpringTouch.attach(btnTotpCopy);
        SpringTouch.attach(btnTotpRemove);
        btnTotp.setOnClickListener(x -> askTotpKey());
        btnTotpCopy.setOnClickListener(x -> copyCode());
        tvTotpCode.setOnClickListener(x -> copyCode());
        btnTotpRemove.setOnClickListener(x -> removeTotp());

        if (entryId == null) {
            tvSheetTitle.setText("New login");
        } else {
            tvSheetTitle.setText("Edit login");
            btnCopy.setVisibility(View.VISIBLE);
            btnDelete.setVisibility(View.VISIBLE);

            btnTotp.setVisibility(View.VISIBLE);

            btnSave.setEnabled(false);
            btnCopy.setEnabled(false);
            loadExisting();
        }
    }

    private void loadExisting() {
        AppLock.requireFresh(requireActivity(), () -> {
            if (!isAdded()) return;
            VaultRepository repo = VaultRepository.get(requireContext());
            repo.getById(entryId, (e, err) -> {
                if (!isAdded()) return;
                if (err != null || e == null) { fail("Couldn't open this login"); return; }
                existing = e;
                etTitle.setText(e.title);
                etDomain.setText(e.domain);
                if (e.domain != null && !e.domain.isEmpty()) btnFix.setVisibility(View.VISIBLE);
                if ((e.domain == null || e.domain.isEmpty())
                        && e.packageName != null && !e.packageName.isEmpty()) {
                    suggestWebsite(e.packageName);
                }
                repo.reveal(e, (creds, err2) -> {
                    if (!isAdded()) return;
                    if (err2 != null) { fail("Couldn't decrypt this login"); return; }
                    etUser.setText(creds[0]);
                    etPass.setText(creds[1]);
                    breachFor = creds[1];
                    breachCount = e.breachCount;
                    btnSave.setEnabled(true);
                    btnCopy.setEnabled(true);

                    loadTotp(e);
                });
            });
        }, this::dismiss);
    }

    // ---------- Live strength + breach check ----------

    private void onPasswordChanged() {
        String pw = etPass.getText().toString();
        int mine = ++seq;
        handler.removeCallbacksAndMessages(null);
        if (!pw.equals(breachFor)) {
            breachFor = null;
            breachCount = -1;
        }
        tvBreach.setText("");

        if (pw.isEmpty()) {
            bar.setProgressCompat(0, false);
            tvStrength.setText("");
            return;
        }

        handler.postDelayed(() -> PasswordTools.analyzeAsync(pw, (r, e) -> {
            if (e != null || mine != seq || !isAdded()) return;
            showRating(r);
        }), 300);

        handler.postDelayed(() -> {
            if (mine != seq || !isAdded()) return;
            tvBreach.setTextColor(color(R.color.text_secondary));
            tvBreach.setText("Checking breaches…");
            PasswordTools.pwnedAsync(pw, (n, e) -> {
                if (mine != seq || !isAdded()) return;
                showBreach(pw, n, e);
            });
        }, 800);
    }

    private void showRating(PasswordTools.Rating r) {
        int[] colors = {R.color.danger, R.color.danger, R.color.warn, R.color.ok, R.color.ok};
        int c = color(colors[r.score]);
        bar.setIndicatorColor(c);
        bar.setProgressCompat((r.score + 1) * 20, true);
        tvStrength.setTextColor(c);
        tvStrength.setText(r.hint.isEmpty() ? r.label : r.label + " · " + r.hint);
    }

    private void showBreach(String pw, Integer n, Exception e) {
        if (e != null) {
            breachFor = null;
            breachCount = -1;
            tvBreach.setTextColor(color(R.color.text_secondary));
            tvBreach.setText("Couldn't check breaches (offline?)");
            return;
        }
        breachFor = pw;
        breachCount = n;
        if (n > 0) {
            tvBreach.setTextColor(color(R.color.danger));
            tvBreach.setText("Found in " + String.format(Locale.US, "%,d", n)
                    + " known breaches. Choose a different password.");
        } else {
            tvBreach.setTextColor(color(R.color.ok));
            tvBreach.setText("Not found in known breaches.");
        }
    }

    private void setPasswordVisible(boolean show) {
        int sel = etPass.getSelectionEnd();
        etPass.setTransformationMethod(show
                ? HideReturnsTransformationMethod.getInstance()
                : PasswordTransformationMethod.getInstance());
        etPass.setSelection(Math.max(0, Math.min(sel, etPass.length())));
        tvShow.setText(show ? "Hide" : "Show");
    }

    // ---------- Save / copy / delete ----------

    private void save() {
        String title = etTitle.getText().toString().trim();
        String domain = normalizeDomain(etDomain.getText().toString());
        String user = etUser.getText().toString().trim();
        String pass = etPass.getText().toString();
        if (title.isEmpty()) { etTitle.setError("Enter a name"); return; }
        if (pass.isEmpty()) { etPass.setError("Enter a password"); return; }

        btnSave.setEnabled(false);
        AppLock.requireFresh(requireActivity(), () -> {
            if (!isAdded()) return;
            PasswordTools.analyzeAsync(pass, (rating, e1) -> {
                if (!isAdded()) return;
                int score = e1 != null ? 0 : rating.score;
                int breach = pass.equals(breachFor) ? breachCount : -1;
                String pkg = existing != null ? existing.packageName : null;
                VaultRepository.get(requireContext()).save(entryId, title, domain, pkg,
                        user, pass, score, breach, (saved, e2) -> {
                            if (!isAdded()) return;
                            if (e2 != null) {
                                btnSave.setEnabled(true);
                                toast("Couldn't save: " + e2);
                                return;
                            }
                            notifyChanged();
                            dismiss();
                        });
            });
        }, () -> btnSave.setEnabled(true));
    }

    private void copyPassword() {
        String pw = etPass.getText().toString();
        if (pw.isEmpty()) return;
        Context app = requireContext().getApplicationContext();
        ClipboardManager cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);

        ClipData clip = ClipData.newPlainText("password", pw);
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true); // hides it from clipboard previews
        clip.getDescription().setExtras(extras);
        cm.setPrimaryClip(clip);

        btnCopy.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        if (android.os.Build.VERSION.SDK_INT < 33) toast("Password copied");

        // best effort: clear the clipboard after 30 seconds while the app is alive
        new Handler(Looper.getMainLooper()).postDelayed(cm::clearPrimaryClip, 30_000);
    }

    private void confirmDelete() {
        if (existing == null) return;
        new AlertDialog.Builder(requireContext())
                .setTitle("Delete this login?")
                .setMessage("This can't be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dlg, which) ->
                        VaultRepository.get(requireContext()).delete(existing, (ok, err) -> {
                            if (!isAdded()) return;
                            if (err != null) { toast("Couldn't delete: " + err); return; }
                            notifyChanged();
                            dismiss();
                        }))
                .show();
    }
    private void openChangePage() {
        String domain = normalizeDomain(etDomain.getText().toString());
        if (domain.isEmpty()) {
            toast("Add a website to this login first");
            return;
        }
        btnFix.setEnabled(false);
        ChangePasswordLink.resolve(domain, (result, err) -> {
            if (!isAdded()) return;
            btnFix.setEnabled(true);
            boolean specific = result != null && result.specific;
            String url = result != null ? result.url : "https://" + domain;
            String intro = specific
                    ? "Vault will open this site's password change page."
                    : "This site has no direct link, so Vault will open its homepage. Sign in and look under Settings, then Security, for \"Change password\".";
            new AlertDialog.Builder(requireContext())
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

    /** For app logins without a website: look one up and pre-fill it. The user confirms with Save. */
    private void suggestWebsite(String packageName) {
        WebsiteFinder.suggest(requireContext(), packageName, (result, err) -> {
            if (!isAdded() || err != null || result == null) return;
            if (!etDomain.getText().toString().trim().isEmpty()) return;
            etDomain.setText(result.domain);
            toast("Suggested website: " + result.domain + ". Check it, then tap Save.");
        });
    }

    // ---------- Verification codes ----------

    private void loadTotp(Entry e) {
        VaultRepository.get(requireContext()).revealTotp(e, (config, err) -> {
            if (!isAdded() || err != null || config == null) return;
            startTotp(TotpGenerator.fromStored(config));
        });
    }

    private void startTotp(TotpGenerator.Config c) {
        totp = c;
        totpBox.setVisibility(View.VISIBLE);
        btnTotp.setVisibility(View.GONE);
        tickHandler.removeCallbacksAndMessages(null);
        tickHandler.post(ticker);
    }

    private void showTotp() {
        long now = System.currentTimeMillis();
        String code = TotpGenerator.code(totp, now);
        int left = TotpGenerator.secondsLeft(totp, now);
        tvTotpCode.setText(code.length() == 6 ? code.substring(0, 3) + " " + code.substring(3) : code);
        tvTotpTime.setText("New code in " + left + " s");
        totpBar.setIndicatorColor(color(left <= 5 ? R.color.danger : R.color.accent));
        totpBar.setProgressCompat(left * 100 / totp.period, false);
    }

    private void askTotpKey() {
        EditText input = new EditText(requireContext());
        input.setHint("Setup key or otpauth:// link");
        input.setSingleLine(true);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        FrameLayout box = new FrameLayout(requireContext());
        box.setPadding(pad, 8, pad, 0);
        box.addView(input);

        new AlertDialog.Builder(requireContext())
                .setTitle("Set up verification code")
                .setMessage("In the app or website, choose to set up an authenticator app, then copy "
                        + "the setup key (not the QR code) and paste it here.")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Add", (d, w) -> saveTotpKey(input.getText().toString()))
                .show();
    }

    private void saveTotpKey(String raw) {
        TotpGenerator.Config c = TotpGenerator.parse(raw);
        if (c == null) {
            toast("That doesn't look like a valid setup key");
            return;
        }
        if (existing == null) return;
        AppLock.requireFresh(requireActivity(), () ->
                VaultRepository.get(requireContext()).saveTotp(existing.id, c.serialize(), (ok, err) -> {
                    if (!isAdded()) return;
                    if (err != null) {
                        toast("Couldn't save: " + err.getMessage());
                        return;
                    }
                    startTotp(c);
                }));
    }

    private void removeTotp() {
        if (existing == null) return;
        new AlertDialog.Builder(requireContext())
                .setTitle("Remove verification code?")
                .setMessage("Make sure you can still sign in without it, or have another way to get codes.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (d, w) ->
                        AppLock.requireFresh(requireActivity(), () ->
                                VaultRepository.get(requireContext()).saveTotp(existing.id, null, (ok, err) -> {
                                    if (!isAdded()) return;
                                    if (err != null) {
                                        toast("Couldn't remove: " + err.getMessage());
                                        return;
                                    }
                                    totp = null;
                                    tickHandler.removeCallbacksAndMessages(null);
                                    totpBox.setVisibility(View.GONE);
                                    btnTotp.setVisibility(View.VISIBLE);
                                })))
                .show();
    }

    private void copyCode() {
        if (totp == null) return;
        Context app = requireContext().getApplicationContext();
        ClipboardManager cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("code", TotpGenerator.code(totp, System.currentTimeMillis()));
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
        clip.getDescription().setExtras(extras);
        cm.setPrimaryClip(clip);
        if (android.os.Build.VERSION.SDK_INT < 33) toast("Code copied");
        new Handler(Looper.getMainLooper()).postDelayed(cm::clearPrimaryClip, 60_000);
    }
    // ---------- Helpers ----------

    private void notifyChanged() {
        if (getActivity() instanceof Listener) ((Listener) getActivity()).onEntriesChanged();
    }

    private void fail(String message) {
        toast(message);
        dismiss();
    }

    private void toast(String s) {
        Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show();
    }

    private int color(int res) {
        return ContextCompat.getColor(requireContext(), res);
    }

    /** "https://www.GitHub.com/login" becomes "github.com" so autofill can match it later. */
    private static String normalizeDomain(String s) {
        s = s.trim().toLowerCase(Locale.ROOT);
        s = s.replaceFirst("^[a-z]+://", "");
        if (s.startsWith("www.")) s = s.substring(4);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        return s;
    }

    private abstract static class Watcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

        @Override
        public void onTextChanged(CharSequence s, int a, int b, int c) {}
    }
}

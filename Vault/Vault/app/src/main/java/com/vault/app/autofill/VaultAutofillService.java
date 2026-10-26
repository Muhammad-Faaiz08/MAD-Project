package com.vault.app.autofill;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.CancellationSignal;
import android.service.autofill.AutofillService;
import android.service.autofill.Dataset;
import android.service.autofill.FillCallback;
import android.service.autofill.FillContext;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.SaveCallback;
import android.service.autofill.SaveInfo;
import android.service.autofill.SaveRequest;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;

import com.vault.app.R;
import com.vault.app.crypto.CryptoManager;
import com.vault.app.data.Entry;
import com.vault.app.data.VaultRepository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Android calls this when you tap a login box in another app, and when you submit a login. */
public class VaultAutofillService extends AutofillService {
    /** Screens where Vault must never appear: lock screen, PIN screens, permission dialogs. */
    private static final Set<String> NEVER = new HashSet<>(Arrays.asList(
            "android", "com.android.settings", "com.android.systemui", "com.android.keyguard",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller"));

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    // ---------- Filling ----------

    @Override
    public void onFillRequest(@NonNull FillRequest request, @NonNull CancellationSignal signal,
                              @NonNull FillCallback callback) {
        List<FillContext> contexts = request.getFillContexts();
        FieldParser f = FieldParser.parse(contexts.get(contexts.size() - 1).getStructure());

        boolean nothingToFill = f.passwordId == null && f.usernameId == null;
        boolean neverHere = f.packageName != null && NEVER.contains(f.packageName);
        if (nothingToFill || neverHere || getPackageName().equals(f.packageName)) {
            callback.onSuccess(null);
            return;
        }

        // Quiet mode: system apps' own screens and PIN-style screens only get logins that were
        // deliberately linked to them, never the "Choose from Vault…" or strong-password offers.
        boolean quiet = f.webDomain == null && (f.pinLike || isSystemApp(f.packageName));

        io.execute(() -> {
            try {
                List<Entry> matches = new ArrayList<>();
                for (Entry e : VaultRepository.get(this).allSync()) {
                    if (matches(e, f)) matches.add(e);
                }
                if (quiet && matches.isEmpty()) {
                    callback.onSuccess(null);
                    return;
                }

                List<AutofillId> newTargets = quiet
                        ? Collections.<AutofillId>emptyList()
                        : f.newPasswordTargets();

                FillResponse.Builder response = new FillResponse.Builder();
                boolean any = false;

                boolean canFillExisting = f.usernameId != null || f.currentPasswordId() != null;
                if (!matches.isEmpty()) {
                    if (canFillExisting) {
                        for (Entry e : matches) response.addDataset(dataset(f, e.id, labelFor(e)));
                        any = true;
                    }
                } else if (f.passwordId != null && newTargets.isEmpty()) {
                    response.addDataset(dataset(f, null, "Choose from Vault…"));
                    any = true;
                }

                // Sign-up and change-password forms: offer a freshly generated password
                if (!newTargets.isEmpty()) {
                    response.addDataset(strongDataset(newTargets));
                    any = true;
                }

                // Tell Android which boxes to watch so it can offer "Save to Vault?" later
                AutofillId saveId = f.savePasswordId();
                if (saveId != null) {
                    SaveInfo.Builder save;
                    if (f.usernameId != null) {
                        save = new SaveInfo.Builder(
                                SaveInfo.SAVE_DATA_TYPE_PASSWORD | SaveInfo.SAVE_DATA_TYPE_USERNAME,
                                new AutofillId[]{saveId});
                        save.setOptionalIds(new AutofillId[]{f.usernameId});
                    } else {
                        save = new SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_PASSWORD,
                                new AutofillId[]{saveId});
                    }
                    save.setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE);
                    response.setSaveInfo(save.build());
                    any = true;
                } else if (f.usernameId != null) {
                    // First screen of a two-step login (username, then password on the next
                    // screen): remember the username and wait for the password screen.
                    SaveInfo.Builder save = new SaveInfo.Builder(
                            SaveInfo.SAVE_DATA_TYPE_USERNAME, new AutofillId[]{f.usernameId});
                    save.setFlags(SaveInfo.FLAG_DELAY_SAVE);
                    response.setSaveInfo(save.build());
                    any = true;
                }

                if (!any) {
                    callback.onSuccess(null);
                    return;
                }
                callback.onSuccess(response.build());
            } catch (Exception ex) {
                callback.onFailure("Vault: " + ex.getMessage());
            }
        });
    }

    private boolean isSystemApp(String pkg) {
        if (pkg == null) return false;
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            return (ai.flags & (ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** The text on a suggestion: the saved username, or the login's name if there is none yet. */
    private static String labelFor(Entry e) {
        try {
            if (e.usernameHintEnc != null) {
                String user = CryptoManager.decryptLabel(e.usernameHintEnc);
                if (!user.isEmpty()) return user;
            }
        } catch (Exception ignored) {
            // fall back to the name below
        }
        return e.title;
    }

    /** Websites match by domain (subdomains too); apps match by package name. */
    private static boolean matches(Entry e, FieldParser f) {
        if (f.webDomain != null) return e.matchesSite(f.webDomain);
        return f.packageName != null && f.packageName.equals(e.packageName);
    }

    /** One suggestion. Its values are empty on purpose: they only arrive after the user authenticates. */
    private Dataset dataset(FieldParser f, String entryId, String label) {
        RemoteViews row = new RemoteViews(getPackageName(), R.layout.autofill_item);
        row.setTextViewText(R.id.afTitle, label);

        Intent i = new Intent(this, AutofillAuthActivity.class);
        i.putExtra(AutofillAuthActivity.EXTRA_ENTRY_ID, entryId);
        i.putExtra(AutofillAuthActivity.EXTRA_USER_ID, f.usernameId);
        i.putExtra(AutofillAuthActivity.EXTRA_PASS_ID, f.currentPasswordId());
        i.putExtra(AutofillAuthActivity.EXTRA_WEB_DOMAIN, f.webDomain);
        i.putExtra(AutofillAuthActivity.EXTRA_PACKAGE, f.packageName);

        int requestCode = entryId != null ? entryId.hashCode() : 1;
        PendingIntent pi = PendingIntent.getActivity(this, requestCode, i,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_MUTABLE);

        Dataset.Builder b = new Dataset.Builder(row);
        if (f.usernameId != null) b.setValue(f.usernameId, (AutofillValue) null);
        if (f.currentPasswordId() != null) b.setValue(f.currentPasswordId(), (AutofillValue) null);
        b.setAuthentication(pi.getIntentSender());
        return b.build();
    }

    /** "Use a strong password": fills a new random password into the new-password boxes. */
    private Dataset strongDataset(List<AutofillId> targets) {
        RemoteViews row = new RemoteViews(getPackageName(), R.layout.autofill_item);
        row.setTextViewText(R.id.afTitle, "Use a strong password");

        Intent i = new Intent(this, AutofillAuthActivity.class);
        i.putExtra(AutofillAuthActivity.EXTRA_MODE, AutofillAuthActivity.MODE_GENERATE);
        i.putParcelableArrayListExtra(AutofillAuthActivity.EXTRA_TARGET_IDS, new ArrayList<>(targets));
        PendingIntent pi = PendingIntent.getActivity(this, 2, i,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_MUTABLE);

        Dataset.Builder b = new Dataset.Builder(row);
        for (AutofillId id : targets) b.setValue(id, (AutofillValue) null);
        b.setAuthentication(pi.getIntentSender());
        return b.build();
    }

    // ---------- Saving ----------

    @Override
    public void onSaveRequest(@NonNull SaveRequest request, @NonNull SaveCallback callback) {
        String user = null, pass = null, domain = null, pkg = null;
        boolean changeForm = false;

        // Multi-step logins spread the boxes over several screens, so look at all of them
        List<FillContext> contexts = request.getFillContexts();
        for (int i = contexts.size() - 1; i >= 0; i--) {
            FieldParser f = FieldParser.parse(contexts.get(i).getStructure());
            if (pass == null && notEmpty(f.savePasswordValue())) {
                pass = f.savePasswordValue();
                changeForm = f.isPasswordChangeForm();
            }
            if (user == null && notEmpty(f.usernameValue)) user = f.usernameValue;
            if (domain == null) domain = f.webDomain;
            if (pkg == null) pkg = f.packageName;
        }

        if (pass == null || getPackageName().equals(pkg)) {
            callback.onSuccess();
            return;
        }

        // We can't ask for a fingerprint from here, so hand Android a screen to open instead
        Intent i = new Intent(this, SaveActivity.class);
        i.putExtra(SaveActivity.EXTRA_USERNAME, user);
        i.putExtra(SaveActivity.EXTRA_PASSWORD, pass);
        i.putExtra(SaveActivity.EXTRA_WEB_DOMAIN, domain);
        i.putExtra(SaveActivity.EXTRA_PACKAGE, pkg);
        i.putExtra(SaveActivity.EXTRA_CHANGE_FORM, changeForm);
        PendingIntent pi = PendingIntent.getActivity(this, (int) System.nanoTime(), i,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        callback.onSuccess(pi.getIntentSender());
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }
}
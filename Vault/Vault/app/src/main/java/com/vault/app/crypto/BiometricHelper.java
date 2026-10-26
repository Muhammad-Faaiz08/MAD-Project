package com.vault.app.crypto;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

/** Shows the system fingerprint/PIN prompt. Reused by the unlock screen and by autofill later. */
public final class BiometricHelper {
    public interface Callback {
        void onSuccess();
        void onFailure(String message);
    }

    private static final int AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_STRONG
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL;

    private BiometricHelper() {}

    public static boolean canAuthenticate(Context ctx) {
        return BiometricManager.from(ctx).canAuthenticate(AUTHENTICATORS)
                == BiometricManager.BIOMETRIC_SUCCESS;
    }

    public static void authenticate(FragmentActivity activity, String title, Callback cb) {
        BiometricPrompt prompt = new BiometricPrompt(activity,
                ContextCompat.getMainExecutor(activity),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult r) {
                        cb.onSuccess();
                    }

                    @Override
                    public void onAuthenticationError(int code, @NonNull CharSequence msg) {
                        cb.onFailure(msg.toString());
                    }
                });
        prompt.authenticate(new BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build());
    }
}

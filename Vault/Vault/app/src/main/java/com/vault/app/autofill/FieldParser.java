package com.vault.app.autofill;

import android.app.assist.AssistStructure;
import android.text.InputType;
import android.util.Pair;
import android.view.View;
import android.view.ViewStructure;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Looks through the screen another app is showing and finds its username and password boxes. */
final class FieldParser {
    AutofillId usernameId;
    AutofillId passwordId;     // first password box (on change forms this is the CURRENT password)
    String usernameValue;      // what is typed in the box right now (used when saving)
    String webDomain;          // set when the screen is a web page, e.g. github.com
    String packageName;        // the app on screen (for web pages that is the browser)
    boolean pinLike;           // a lone number-only password box: looks like a PIN screen

    private final List<AutofillId> passwordIds = new ArrayList<>();
    private final List<String> passwordValues = new ArrayList<>();
    private final List<AutofillId> newHintIds = new ArrayList<>();
    private int newHintIndex = -1;
    private int numericPasswordCount;
    private AutofillId lastTextId;
    private String lastTextValue;

    private FieldParser() {}

    static FieldParser parse(AssistStructure structure) {
        FieldParser p = new FieldParser();
        if (structure.getActivityComponent() != null) {
            p.packageName = structure.getActivityComponent().getPackageName();
        }
        for (int i = 0; i < structure.getWindowNodeCount(); i++) {
            p.walk(structure.getWindowNodeAt(i).getRootViewNode());
        }
        p.pinLike = p.webDomain == null && p.passwordIds.size() == 1
                && p.numericPasswordCount == 1 && p.usernameId == null;

        // No clearly labelled username box? Use the text box just before the password box.
        if (p.usernameId == null && p.passwordId != null) {
            p.usernameId = p.lastTextId;
            p.usernameValue = p.lastTextValue;
        }
        return p;
    }

    /**
     * The boxes where a NEW password goes: everything from the first box marked "new password"
     * to the end (new + confirm), or on forms with three password boxes the 2nd and 3rd
     * (current / new / confirm), or on forms with two boxes both of them (sign-up).
     */
    List<AutofillId> newPasswordTargets() {
        if (newHintIndex >= 0) return passwordIds.subList(newHintIndex, passwordIds.size());
        if (passwordIds.size() == 3) return passwordIds.subList(1, 3);
        if (passwordIds.size() == 2) return passwordIds;
        return Collections.emptyList();
    }

    /** The box for a password you already have (the old one on a change form), or null. */
    AutofillId currentPasswordId() {
        List<AutofillId> targets = newPasswordTargets();
        for (AutofillId id : passwordIds) {
            if (!targets.contains(id)) return id;
        }
        return null;
    }

    /** The password box to watch when saving: the new-password box if there is one. */
    private int saveIndex() {
        if (passwordIds.isEmpty()) return -1;
        if (newHintIndex >= 0) return newHintIndex;
        return passwordIds.size() == 3 ? 1 : 0;
    }

    AutofillId savePasswordId() {
        int i = saveIndex();
        return i < 0 ? null : passwordIds.get(i);
    }

    String savePasswordValue() {
        int i = saveIndex();
        return i < 0 ? null : passwordValues.get(i);
    }

    /** "https://www.GitHub.com/login" becomes "github.com". */
    static String normalizeDomain(String s) {
        s = s.trim().toLowerCase(Locale.ROOT);
        s = s.replaceFirst("^[a-z]+://", "");
        if (s.startsWith("www.")) s = s.substring(4);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        return s;
    }

    private void walk(AssistStructure.ViewNode node) {
        if (webDomain == null && node.getWebDomain() != null) {
            webDomain = normalizeDomain(node.getWebDomain());
        }

        AutofillId id = node.getAutofillId();
        if (id != null && node.getAutofillType() == View.AUTOFILL_TYPE_TEXT) {
            String value = textOf(node);
            if (isPassword(node)) {
                if (passwordId == null) passwordId = id;
                passwordIds.add(id);
                passwordValues.add(value);
                if (isNumericPassword(node)) numericPasswordCount++;
                if (isNewPassword(node)) {
                    if (newHintIndex < 0) newHintIndex = passwordIds.size() - 1;
                    newHintIds.add(id);
                }
            } else if (isUsername(node)) {
                if (usernameId == null) {
                    usernameId = id;
                    usernameValue = value;
                }
            } else if (passwordId == null) {
                lastTextId = id;
                lastTextValue = value;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) walk(node.getChildAt(i));
    }
    private static boolean isNewPassword(AssistStructure.ViewNode n) {
        if (hintContains(n, "newpassword", "new-password")) return true;
        String id = lower(n.getIdEntry());
        if (id.contains("new") && (id.contains("pass") || id.contains("pwd"))) return true;
        String label = lower(n.getHint()); // the grey placeholder text
        return label.contains("new password") || label.contains("create password")
                || label.contains("choose a password");
    }
    /** A change- or reset-password page: new-password boxes but no username box. */
    boolean isPasswordChangeForm() {
        return usernameId == null && !newPasswordTargets().isEmpty();
    }
    private static String textOf(AssistStructure.ViewNode n) {
        AutofillValue v = n.getAutofillValue();
        return (v != null && v.isText()) ? v.getTextValue().toString() : null;
    }

    private static boolean isNumericPassword(AssistStructure.ViewNode n) {
        int type = n.getInputType();
        return (type & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER
                && (type & InputType.TYPE_MASK_VARIATION) == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    }

    private static boolean isPassword(AssistStructure.ViewNode n) {
        if (hintContains(n, "password")) return true;
        int type = n.getInputType();
        int cls = type & InputType.TYPE_MASK_CLASS;
        int variation = type & InputType.TYPE_MASK_VARIATION;
        if (cls == InputType.TYPE_CLASS_TEXT
                && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) return true;
        if (isNumericPassword(n)) return true;
        if ("password".equals(htmlType(n))) return true;
        String id = lower(n.getIdEntry());
        return id.contains("password") || id.contains("passwd");
    }

    private static boolean isUsername(AssistStructure.ViewNode n) {
        if (hintContains(n, "user", "email", "login")) return true;
        if ("email".equals(htmlType(n))) return true;
        String id = lower(n.getIdEntry());
        return id.contains("user") || id.contains("email") || id.contains("login")
                || id.contains("identifier");
    }

    private static boolean hintContains(AssistStructure.ViewNode n, String... words) {
        String[] hints = n.getAutofillHints();
        if (hints == null) return false;
        for (String h : hints) {
            String l = lower(h);
            for (String w : words) if (l.contains(w)) return true;
        }
        return false;
    }

    /** For web pages: the type="..." of the HTML input, e.g. "password" or "email". */
    private static String htmlType(AssistStructure.ViewNode n) {
        ViewStructure.HtmlInfo html = n.getHtmlInfo();
        if (html == null || html.getAttributes() == null) return "";
        for (Pair<String, String> a : html.getAttributes()) {
            if ("type".equalsIgnoreCase(a.first) && a.second != null) {
                return a.second.toLowerCase(Locale.ROOT);
            }
        }
        return "";
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
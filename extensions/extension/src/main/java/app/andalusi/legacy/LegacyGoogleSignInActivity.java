package app.andalusi.legacy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/** Uses classic GOOGLE_SIGN_IN directly: R8 stripped the public facade and Builder. */
public final class LegacyGoogleSignInActivity extends Activity {
    private static final String LOG_TAG = "AndalusiLegacyLogin";
    private static final int RC_SIGN_IN = 9482;
    private static final String GMS_PACKAGE = "app.revanced.android.gms";
    private static final String SIGN_IN_ACTION = GMS_PACKAGE + ".auth.GOOGLE_SIGN_IN";
    // 7.0.0 is the first RE release verified to forward the classic sign-in nonce.
    private static final long MIN_MICROG_VERSION_CODE = 255070000L;
    private static final String SERVER_CLIENT_ID =
            "752406979491-m39pf3vd9p88r5buiidqtirjt45e2k0i.apps.googleusercontent.com";
    private long requestId;
    private String nonce;
    private String stage = "Preparing Google sign-in";
    private String microgVersion = "not detected";
    private String diagnostic;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestId = getIntent().getLongExtra(LegacyGoogleBridge.REQUEST_ID, -1);
        if (!LegacyGoogleBridge.isPending(requestId)) {
            // The original coroutine no longer exists after process death.
            finish();
            return;
        }
        if (state != null) {
            nonce = state.getString("nonce");
            microgVersion = state.getString("microgVersion", "unknown");
            diagnostic = state.getString("diagnostic");
            if (diagnostic != null) {
                showDiagnostic();
                return;
            }
            if (nonce == null) {
                showFailure(new SignInProblem("The sign-in request could not be restored. Please retry."));
            }
            return;
        }
        try {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            nonce = Base64.encodeToString(random, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            startLegacyGoogleSignIn();
        } catch (Exception error) {
            showFailure(error);
        }
    }

    private void startLegacyGoogleSignIn() throws Exception {
        stage = "Checking MicroG RE";
        PackageInfo info = getPackageManager().getPackageInfo(GMS_PACKAGE, 0);
        microgVersion = info.versionName;
        if (info.getLongVersionCode() < MIN_MICROG_VERSION_CODE) {
            throw new SignInProblem("Update MicroG RE to 7.0.0 or newer. This version cannot " +
                    "preserve the nonce required for this Google sign-in request.");
        }
        stage = "Preparing the account request";
        // Verified GoogleSignInOptions.b(String) is the retained JSON factory.
        JSONObject json = new JSONObject();
        json.put("scopes", new JSONArray().put("openid").put("email").put("profile"));
        json.put("idTokenRequested", true);
        json.put("serverAuthRequested", false);
        json.put("forceCodeForRefreshToken", false);
        json.put("serverClientId", SERVER_CLIENT_ID);
        Class<?> optionsClass = Class.forName("com.google.android.gms.auth.api.signin.GoogleSignInOptions");
        Object options = optionsClass.getMethod("b", String.class).invoke(null, json.toString());
        Class<?> configClass = Class.forName("com.google.android.gms.auth.api.signin.internal.SignInConfiguration");
        Parcelable config = (Parcelable) configClass.getConstructor(String.class, optionsClass)
                .newInstance(getPackageName(), options);
        Intent intent = new Intent(SIGN_IN_ACTION);
        intent.setPackage(GMS_PACKAGE);
        intent.putExtra("config", config);
        // MicroG RE supports this extra; the app's SignInHubActivity would drop it.
        intent.putExtra("nonce", nonce);
        stage = "Opening MicroG RE";
        if (getPackageManager().resolveActivity(intent, 0) == null) {
            throw new SignInProblem("MicroG RE has no activity for the classic Google sign-in request.");
        }
        startActivityForResult(intent, RC_SIGN_IN);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("nonce", nonce);
        state.putString("microgVersion", microgVersion);
        state.putString("diagnostic", diagnostic);
        super.onSaveInstanceState(state);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != RC_SIGN_IN) return;
        try {
            if (!LegacyGoogleBridge.isPending(requestId)) {
                finish();
                return;
            }
            if (resultCode != RESULT_OK || data == null) {
                LegacyGoogleBridge.cancel(requestId);
                finish();
                return;
            }
            stage = "Reading the MicroG result";
            data.setExtrasClassLoader(getClassLoader());
            Object status = data.getParcelableExtra("googleSignInStatus");
            if (status == null) throw new SignInProblem("MicroG returned no sign-in status.");
            int code = status.getClass().getField("a").getInt(status);
            if (code == 13 || code == 16 || code == 12501) {
                LegacyGoogleBridge.cancel(requestId);
                finish();
                return;
            }
            if (code > 0) throw new SignInProblem("MicroG Google sign-in failed (status " + code + ").");
            Object account = data.getParcelableExtra("googleSignInAccount");
            if (account == null) throw new SignInProblem("MicroG returned no Google account.");
            // Verified retained fields: b = ID token, c = email.
            String token = (String) account.getClass().getField("b").get(account);
            String email = (String) account.getClass().getField("c").get(account);
            stage = "Checking the Google token";
            validateToken(token);
            if (email == null || email.isEmpty()) throw new SignInProblem("Google account returned no email.");
            stage = "Converting the Google credential";
            Bundle credential = new Bundle();
            credential.putString("com.google.android.libraries.identity.googleid.BUNDLE_KEY_ID", email);
            credential.putString("com.google.android.libraries.identity.googleid.BUNDLE_KEY_ID_TOKEN", token);
            Object parsed = Class.forName("f5").getMethod("a", Bundle.class).invoke(null, credential);
            String parsedToken = (String) Class.forName("yt5").getField("b").get(parsed);
            stage = "Returning the token to Andalusi";
            Toast.makeText(this, "Google token received. Signing in to Andalusi...", Toast.LENGTH_LONG).show();
            LegacyGoogleBridge.complete(requestId, parsedToken);
            finish();
        } catch (Exception error) {
            showFailure(error);
        }
    }

    private void validateToken(String token) throws Exception {
        if (token == null || token.isEmpty()) throw new SignInProblem("Google returned no ID token.");
        String[] parts = token.split("\\.");
        if (parts.length != 3) throw new SignInProblem("Google returned an invalid ID token.");
        JSONObject header = new JSONObject(new String(
                Base64.decode(parts[0], Base64.URL_SAFE), StandardCharsets.UTF_8));
        JSONObject claims = new JSONObject(new String(
                Base64.decode(parts[1], Base64.URL_SAFE), StandardCharsets.UTF_8));
        logSafeClaims(header, claims);
        if (!SERVER_CLIENT_ID.equals(claims.optString("aud"))) {
            throw new SignInProblem("Google ID token has the wrong audience.");
        }
        if (nonce == null || !nonce.equals(claims.optString("nonce"))) {
            throw new SignInProblem("MicroG did not preserve the sign-in nonce. Update MicroG RE.");
        }
        // Request binding only. Andalusi's existing backend verifies the signature.
    }

    private void logSafeClaims(JSONObject header, JSONObject claims) {
        String algorithm = header.optString("alg");
        algorithm = "RS256".equals(algorithm) ? "RS256" : algorithm.isEmpty() ? "missing" : "other";
        String issuer = claims.optString("iss");
        issuer = ("accounts.google.com".equals(issuer) || "https://accounts.google.com".equals(issuer))
                ? "google" : issuer.isEmpty() ? "missing" : "other";
        String authorizedParty = claims.optString("azp");
        authorizedParty = authorizedParty.isEmpty() ? "missing" :
                SERVER_CLIENT_ID.equals(authorizedParty) ? "matches" : "other";
        long issuedAt = claims.optLong("iat", 0);
        long expiresAt = claims.optLong("exp", 0);
        long now = System.currentTimeMillis() / 1000;
        String age = issuedAt > 0 ? Long.toString(now - issuedAt) : "unknown";
        String lifetime = issuedAt > 0 && expiresAt >= issuedAt ?
                Long.toString(expiresAt - issuedAt) : "unknown";
        Log.e(LOG_TAG, "Google credential claims: alg=" + algorithm +
                " keyId=" + header.has("kid") + " issuer=" + issuer +
                " audienceMatch=" + SERVER_CLIENT_ID.equals(claims.optString("aud")) +
                " authorizedParty=" + authorizedParty +
                " nonceMatch=" + (nonce != null && nonce.equals(claims.optString("nonce"))) +
                " emailVerified=" + claims.optBoolean("email_verified", false) +
                " subjectPresent=" + !claims.optString("sub").isEmpty() +
                " ageSeconds=" + age + " lifetimeSeconds=" + lifetime);
    }

    private static final class SignInProblem extends Exception {
        SignInProblem(String message) { super(message); }
    }

    private void showFailure(Exception error) {
        Throwable cause = LegacyGoogleBridge.unwrap(error);
        // Only our fixed messages are shown. External exception messages can contain account data.
        String detail = cause instanceof SignInProblem ? cause.getMessage() : cause.getClass().getSimpleName();
        diagnostic = "Build: dev.8 (Andalusi 10.2.0)\nStep: " + stage + "\nMicroG RE: " + microgVersion +
                "\n\n" + detail;
        showDiagnostic();
    }

    private void showDiagnostic() {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("Google sign-in could not finish")
                .setMessage(diagnostic)
                .setCancelable(false)
                .setNeutralButton("Copy details", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("Sign-in details", diagnostic));
                    finishFailure();
                })
                .setPositiveButton("Close", (dialog, which) -> finishFailure())
                .show();
    }

    private void finishFailure() {
        LegacyGoogleBridge.fail(requestId, new IllegalStateException(diagnostic));
        finish();
    }

    @Override
    public void onBackPressed() {
        LegacyGoogleBridge.cancel(requestId);
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) LegacyGoogleBridge.cancel(requestId);
        super.onDestroy();
    }
}

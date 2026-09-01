package app.andalusi.legacy;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/** Uses classic GOOGLE_SIGN_IN directly: R8 stripped the public facade and Builder. */
public final class LegacyGoogleSignInActivity extends Activity {
    private static final int RC_SIGN_IN = 9482;
    private static final String GMS_PACKAGE = "app.revanced.android.gms";
    private static final String SERVER_CLIENT_ID =
            "752406979491-m39pf3vd9p88r5buiidqtirjt45e2k0i.apps.googleusercontent.com";
    private long requestId;
    private String nonce;

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
            if (nonce == null) {
                LegacyGoogleBridge.fail(requestId, new IllegalStateException("Missing sign-in nonce"));
                finish();
            }
            return;
        }
        try {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            nonce = Base64.encodeToString(random, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            startLegacyGoogleSignIn();
        } catch (Exception error) {
            LegacyGoogleBridge.fail(requestId, error);
            finish();
        }
    }

    private void startLegacyGoogleSignIn() throws Exception {
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
        Intent intent = new Intent("com.google.android.gms.auth.GOOGLE_SIGN_IN");
        intent.setPackage(GMS_PACKAGE);
        intent.putExtra("config", config);
        // MicroG RE supports this extra; the app's SignInHubActivity would drop it.
        intent.putExtra("nonce", nonce);
        startActivityForResult(intent, RC_SIGN_IN);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putString("nonce", nonce);
        super.onSaveInstanceState(state);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != RC_SIGN_IN) return;
        try {
            if (!LegacyGoogleBridge.isPending(requestId)) return;
            if (resultCode != RESULT_OK || data == null) {
                LegacyGoogleBridge.cancel(requestId);
                return;
            }
            data.setExtrasClassLoader(getClassLoader());
            Object status = data.getParcelableExtra("googleSignInStatus");
            if (status == null) throw new IllegalStateException("MicroG returned no sign-in status");
            int code = status.getClass().getField("a").getInt(status);
            if (code == 13 || code == 16 || code == 12501) {
                LegacyGoogleBridge.cancel(requestId);
                return;
            }
            if (code > 0) throw new IllegalStateException("MicroG Google sign-in failed (status " + code + ")");
            Object account = data.getParcelableExtra("googleSignInAccount");
            if (account == null) throw new IllegalStateException("MicroG returned no Google account");
            // Verified retained fields: b = ID token, c = email.
            String token = (String) account.getClass().getField("b").get(account);
            String email = (String) account.getClass().getField("c").get(account);
            validateToken(token);
            if (email == null || email.isEmpty()) throw new IllegalStateException("Google account returned no email");
            Bundle credential = new Bundle();
            credential.putString("com.google.android.libraries.identity.googleid.BUNDLE_KEY_ID", email);
            credential.putString("com.google.android.libraries.identity.googleid.BUNDLE_KEY_ID_TOKEN", token);
            Object parsed = Class.forName("f5").getMethod("a", Bundle.class).invoke(null, credential);
            String parsedToken = (String) Class.forName("al5").getField("b").get(parsed);
            LegacyGoogleBridge.complete(requestId, parsedToken);
        } catch (Exception error) {
            LegacyGoogleBridge.fail(requestId, error);
        } finally {
            finish();
        }
    }

    private void validateToken(String token) throws Exception {
        if (token == null || token.isEmpty()) throw new IllegalStateException("Google returned no ID token");
        String[] parts = token.split("\\.");
        if (parts.length != 3) throw new IllegalStateException("Google returned an invalid ID token");
        JSONObject claims = new JSONObject(new String(
                Base64.decode(parts[1], Base64.URL_SAFE), StandardCharsets.UTF_8));
        if (!SERVER_CLIENT_ID.equals(claims.optString("aud"))) {
            throw new IllegalStateException("Google ID token has the wrong audience");
        }
        if (nonce == null || !nonce.equals(claims.optString("nonce"))) {
            throw new IllegalStateException("MicroG did not preserve the sign-in nonce; update MicroG RE");
        }
        // Request binding only. Andalusi's existing backend verifies the signature.
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

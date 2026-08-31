package app.andalusi.legacy;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Transparent one-shot Activity that invokes the classic GoogleSignIn API already bundled
 * in Andalusi, receives the result, converts it to the exact Bundle expected by Andalusi's
 * existing Google ID credential parser, then resumes the original login coroutine.
 */
public final class LegacyGoogleSignInActivity extends Activity {
    private static final int RC_SIGN_IN = 9482;

    // Exact web/server OAuth client ID embedded in Andalusi 10.0.3.
    private static final String SERVER_CLIENT_ID =
            "752406979491-m39pf3vd9p88r5buiidqtirjt45e2k0i.apps.googleusercontent.com";

    private static final String KEY_ID =
            "com.google.android.libraries.identity.googleid.BUNDLE_KEY_ID";
    private static final String KEY_ID_TOKEN =
            "com.google.android.libraries.identity.googleid.BUNDLE_KEY_ID_TOKEN";
    private static final String KEY_DISPLAY_NAME =
            "com.google.android.libraries.identity.googleid.BUNDLE_KEY_DISPLAY_NAME";
    private static final String KEY_FAMILY_NAME =
            "com.google.android.libraries.identity.googleid.BUNDLE_KEY_FAMILY_NAME";
    private static final String KEY_GIVEN_NAME =
            "com.google.android.libraries.identity.googleid.BUNDLE_KEY_GIVEN_NAME";
    private static final String KEY_PROFILE_PICTURE_URI =
            "com.google.android.libraries.identity.googleid.BUNDLE_KEY_PROFILE_PICTURE_URI";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState == null) {
            try {
                startLegacyGoogleSignIn();
            } catch (Throwable t) {
                LegacyGoogleBridge.fail(t);
                finish();
            }
        }
    }

    /**
     * Reflection is intentional: the extension module does not need to compile against Google's
     * libraries. Andalusi already contains these classic GoogleSignIn classes at runtime.
     */
    private void startLegacyGoogleSignIn() throws Exception {
        Class<?> optionsClass = Class.forName(
                "com.google.android.gms.auth.api.signin.GoogleSignInOptions"
        );
        Class<?> builderClass = Class.forName(
                "com.google.android.gms.auth.api.signin.GoogleSignInOptions$Builder"
        );

        Object defaultSignIn = optionsClass.getField("DEFAULT_SIGN_IN").get(null);
        Constructor<?> builderConstructor = builderClass.getConstructor(optionsClass);
        Object builder = builderConstructor.newInstance(defaultSignIn);

        builderClass.getMethod("requestIdToken", String.class)
                .invoke(builder, SERVER_CLIENT_ID);
        builderClass.getMethod("requestEmail").invoke(builder);
        Object options = builderClass.getMethod("build").invoke(builder);

        Class<?> googleSignInClass = Class.forName(
                "com.google.android.gms.auth.api.signin.GoogleSignIn"
        );
        Object client = googleSignInClass
                .getMethod("getClient", Activity.class, optionsClass)
                .invoke(null, this, options);

        Intent signInIntent = (Intent) client.getClass()
                .getMethod("getSignInIntent")
                .invoke(client);

        startActivityForResult(signInIntent, RC_SIGN_IN);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != RC_SIGN_IN) {
            return;
        }

        try {
            if (data == null) {
                throw new IllegalStateException("Google sign-in returned no Intent data");
            }

            Object account = getGoogleAccount(data);
            Object andalusiCredential = convertAccountToAndalusiCredential(account);
            LegacyGoogleBridge.complete(andalusiCredential);
        } catch (Throwable t) {
            LegacyGoogleBridge.fail(t);
        } finally {
            finish();
        }
    }

    private Object getGoogleAccount(Intent data) throws Exception {
        Class<?> googleSignInClass = Class.forName(
                "com.google.android.gms.auth.api.signin.GoogleSignIn"
        );
        Object task = googleSignInClass
                .getMethod("getSignedInAccountFromIntent", Intent.class)
                .invoke(null, data);

        Class<?> taskClass = Class.forName("com.google.android.gms.tasks.Task");
        boolean successful = (Boolean) taskClass.getMethod("isSuccessful").invoke(task);

        if (!successful) {
            Throwable exception = (Throwable) taskClass.getMethod("getException").invoke(task);
            if (exception != null) {
                throw new RuntimeException("Classic Google sign-in failed", exception);
            }
            throw new IllegalStateException("Classic Google sign-in failed without an exception");
        }

        Object account = taskClass.getMethod("getResult").invoke(task);
        if (account == null) {
            throw new IllegalStateException("Google sign-in returned no account");
        }
        return account;
    }

    private Object convertAccountToAndalusiCredential(Object account) throws Exception {
        Class<?> accountClass = account.getClass();

        String idToken = stringGetter(accountClass, account, "getIdToken");
        String email = stringGetter(accountClass, account, "getEmail");
        String id = stringGetter(accountClass, account, "getId");
        String displayName = stringGetter(accountClass, account, "getDisplayName");
        String givenName = stringGetter(accountClass, account, "getGivenName");
        String familyName = stringGetter(accountClass, account, "getFamilyName");

        if (idToken == null || idToken.isEmpty()) {
            throw new IllegalStateException(
                    "Google account was returned but there is no ID token. " +
                    "This normally means the MicroG/OAuth routing step is still failing."
            );
        }

        // GoogleIdTokenCredential.id is normally the account identifier/email for this flow.
        String credentialId = (email != null && !email.isEmpty()) ? email : id;
        if (credentialId == null || credentialId.isEmpty()) {
            throw new IllegalStateException("Google account returned neither email nor account ID");
        }

        Bundle bundle = new Bundle();
        bundle.putString(KEY_ID, credentialId);
        bundle.putString(KEY_ID_TOKEN, idToken);
        putIfNotNull(bundle, KEY_DISPLAY_NAME, displayName);
        putIfNotNull(bundle, KEY_GIVEN_NAME, givenName);
        putIfNotNull(bundle, KEY_FAMILY_NAME, familyName);

        try {
            Method getPhotoUrl = accountClass.getMethod("getPhotoUrl");
            Object photo = getPhotoUrl.invoke(account);
            if (photo instanceof Uri) {
                bundle.putParcelable(KEY_PROFILE_PICTURE_URI, (Uri) photo);
            }
        } catch (NoSuchMethodException ignored) {
            // Optional field.
        }

        // Exact Andalusi 10.0.3 parser found in classes.dex:
        //   Lf5;->a(Landroid/os/Bundle;)Lal5;
        Class<?> parser = Class.forName("f5");
        Method parse = parser.getDeclaredMethod("a", Bundle.class);
        parse.setAccessible(true);
        return parse.invoke(null, bundle);
    }

    private static String stringGetter(Class<?> type, Object target, String methodName) {
        try {
            Object value = type.getMethod(methodName).invoke(target);
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void putIfNotNull(Bundle bundle, String key, String value) {
        if (value != null) {
            bundle.putString(key, value);
        }
    }

    @Override
    public void onBackPressed() {
        LegacyGoogleBridge.fail(new RuntimeException("Google sign-in cancelled"));
        super.onBackPressed();
    }
}

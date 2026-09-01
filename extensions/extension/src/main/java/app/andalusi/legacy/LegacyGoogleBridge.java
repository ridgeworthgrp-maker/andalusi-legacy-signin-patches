package app.andalusi.legacy;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Version-pinned adapter for Andalusi's suspend function returning Result<String>. */
public final class LegacyGoogleBridge {
    private static final String LOG_TAG = "AndalusiLegacyLogin";
    private static final String SERVER_CLIENT_ID =
            "752406979491-m39pf3vd9p88r5buiidqtirjt45e2k0i.apps.googleusercontent.com";
    static final String REQUEST_ID = "andalusi.legacy.request";
    private static final Object LOCK = new Object();
    private static long nextId;
    private static Request pending;

    private static final class Request {
        final long id;
        final Object target;
        final Method resume;
        final Constructor<?> result;
        boolean suspended;
        boolean completed;
        Serializable value;

        Request(long id, Object continuation) throws Exception {
            this.id = id;
            target = Class.forName("xl2").getMethod("intercepted").invoke(continuation);
            resume = Class.forName("wl2").getMethod("resumeWith", Object.class);
            result = Class.forName("emb").getConstructor(Object.class);
        }
    }

    private LegacyGoogleBridge() {}

    /** Records non-identifying Google claim classifications for either sign-in implementation. */
    public static void inspectGoogleToken(String token) {
        try {
            if (token == null) return;
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                Log.e(LOG_TAG, "Google credential claims: format=invalid");
                return;
            }
            JSONObject header = new JSONObject(new String(
                    Base64.decode(parts[0], Base64.URL_SAFE), StandardCharsets.UTF_8));
            JSONObject claims = new JSONObject(new String(
                    Base64.decode(parts[1], Base64.URL_SAFE), StandardCharsets.UTF_8));
            String algorithm = header.optString("alg");
            algorithm = "RS256".equals(algorithm) ? "RS256" :
                    algorithm.isEmpty() ? "missing" : "other";
            String issuer = claims.optString("iss");
            issuer = ("accounts.google.com".equals(issuer) ||
                    "https://accounts.google.com".equals(issuer)) ? "google" :
                    issuer.isEmpty() ? "missing" : "other";
            String authorizedParty = claims.optString("azp");
            authorizedParty = authorizedParty.isEmpty() ? "missing" :
                    SERVER_CLIENT_ID.equals(authorizedParty) ? "matches" : "other";
            long issuedAt = claims.optLong("iat", 0);
            long expiresAt = claims.optLong("exp", 0);
            long now = System.currentTimeMillis() / 1000;
            Log.e(LOG_TAG, "Google credential summary: alg=" + algorithm +
                    " keyId=" + header.has("kid") + " issuer=" + issuer +
                    " audienceMatch=" + SERVER_CLIENT_ID.equals(claims.optString("aud")) +
                    " authorizedParty=" + authorizedParty +
                    " noncePresent=" + !claims.optString("nonce").isEmpty() +
                    " emailVerified=" + claims.optBoolean("email_verified", false) +
                    " subjectPresent=" + !claims.optString("sub").isEmpty() +
                    " ageSeconds=" + (issuedAt > 0 ? now - issuedAt : -1) +
                    " lifetimeSeconds=" +
                    (issuedAt > 0 && expiresAt >= issuedAt ? expiresAt - issuedAt : -1));
        } catch (Throwable ignored) {
            Log.e(LOG_TAG, "Google credential claims: format=unavailable");
        }
    }

    /**
     * Records enough information to diagnose Andalusi's hidden backend failure without exposing
     * the Google token, email address, request URL, server response, or exception message.
     */
    public static void inspectBackendResult(Object value) {
        if (value == null || !"dmb".equals(value.getClass().getName())) return;
        try {
            Field failureField = value.getClass().getField("a");
            Object failure = failureField.get(value);
            if (!(failure instanceof Throwable)) return;
            Throwable error = (Throwable) failure;
            StringBuilder types = new StringBuilder();
            Throwable cursor = error;
            for (int depth = 0; cursor != null && depth < 6; depth++) {
                if (depth > 0) types.append('>');
                String type = cursor.getClass().getSimpleName();
                if (type.isEmpty()) type = "Throwable";
                types.append(type.replaceAll("[^A-Za-z0-9_$]", ""));
                cursor = cursor.getCause();
            }
            Integer status = findHttpStatus(error);
            Log.e(LOG_TAG, "Backend Google login failed: type=" + types +
                    " status=" + (status == null ? "unknown" : status));
        } catch (Throwable diagnosticError) {
            Log.e(LOG_TAG, "Backend Google login failed: type=unavailable status=unknown");
        }
    }

    private static Integer findHttpStatus(Throwable error) {
        for (Throwable cursor = error, next; cursor != null; cursor = next) {
            next = cursor.getCause();
            try {
                Object response = cursor.getClass().getMethod("getResponse").invoke(cursor);
                if (response == null) continue;
                Object status = response.getClass().getMethod("getStatus").invoke(response);
                if (status == null) continue;
                Object value = status.getClass().getMethod("getValue").invoke(status);
                if (value instanceof Number) return ((Number) value).intValue();
            } catch (Throwable ignored) {
                // Some network failures have no HTTP response.
            }
        }
        return null;
    }

    public static Serializable begin(Context context, Object continuation) {
        final Request request;
        final Serializable suspended;
        try {
            suspended = (Serializable) Class.forName("zn2").getField("a").get(null);
            synchronized (LOCK) {
                if (pending != null) {
                    return failure(new IllegalStateException("A Google sign-in is already in progress"));
                }
                request = new Request(++nextId, continuation);
                pending = request;
            }
        } catch (Exception error) {
            return failure(unwrap(error));
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            if (!isPending(request.id)) return;
            try {
                Intent intent = new Intent(context, LegacyGoogleSignInActivity.class);
                intent.putExtra(REQUEST_ID, request.id);
                if (!(context instanceof android.app.Activity)) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                context.startActivity(intent);
            } catch (Exception error) {
                fail(request.id, error);
            }
        });
        // A fast launch failure must not resume before the caller has suspended.
        synchronized (LOCK) {
            if (request.completed) return request.value;
            request.suspended = true;
            return suspended;
        }
    }

    static boolean isPending(long id) {
        synchronized (LOCK) {
            return pending != null && pending.id == id;
        }
    }

    static void complete(long id, Serializable value) {
        final Request request;
        synchronized (LOCK) {
            if (pending == null || pending.id != id) return;
            request = pending;
            pending = null;
            request.completed = true;
            request.value = value;
            if (!request.suspended) return;
        }
        try {
            // xt5.invokeSuspend boxes Result before resuming its caller.
            // A raw String, credential, or raw dmb here violates the caller's ABI.
            request.resume.invoke(request.target, request.result.newInstance(value));
        } catch (Exception error) {
            throw new IllegalStateException("Could not resume Andalusi login", unwrap(error));
        }
    }

    static void fail(long id, Throwable error) {
        if (isPending(id)) complete(id, failure(unwrap(error)));
    }

    static void cancel(long id) {
        // R8 removed vt5's constructors, so use a retained cancellation exception.
        fail(id, new java.util.concurrent.CancellationException("Google sign-in cancelled"));
    }

    private static Serializable failure(Throwable error) {
        try {
            return (Serializable) Class.forName("dmb")
                    .getConstructor(Throwable.class).newInstance(error);
        } catch (Exception reflectionError) {
            // Never pass a Throwable as a successful Kotlin Result.
            throw new IllegalStateException("Andalusi 10.2.0 Result ABI is unavailable", reflectionError);
        }
    }

    static Throwable unwrap(Throwable error) {
        while (error.getCause() != null &&
                (error instanceof java.lang.reflect.InvocationTargetException ||
                 error instanceof java.lang.reflect.UndeclaredThrowableException)) {
            error = error.getCause();
        }
        return error;
    }
}

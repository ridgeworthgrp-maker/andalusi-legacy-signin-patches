package app.andalusi.legacy;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Version-pinned adapter for Andalusi's suspend function returning Result<String>. */
public final class LegacyGoogleBridge {
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
            target = Class.forName("ah2").getMethod("intercepted").invoke(continuation);
            resume = Class.forName("zg2").getMethod("resumeWith", Object.class);
            result = Class.forName("e8b").getConstructor(Object.class);
        }
    }

    private LegacyGoogleBridge() {}

    public static Serializable begin(Context context, Object continuation) {
        final Request request;
        final Serializable suspended;
        try {
            suspended = (Serializable) Class.forName("ej2").getField("a").get(null);
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
            // zk5.invokeSuspend boxes Result before resuming its caller.
            // A raw String, credential, or raw d8b here violates the caller's ABI.
            request.resume.invoke(request.target, request.result.newInstance(value));
        } catch (Exception error) {
            throw new IllegalStateException("Could not resume Andalusi login", unwrap(error));
        }
    }

    static void fail(long id, Throwable error) {
        if (isPending(id)) complete(id, failure(unwrap(error)));
    }

    static void cancel(long id) {
        // R8 removed xk5's constructors, so it cannot be constructed reflectively.
        fail(id, new java.util.concurrent.CancellationException("Google sign-in cancelled"));
    }

    private static Serializable failure(Throwable error) {
        try {
            return (Serializable) Class.forName("d8b")
                    .getConstructor(Throwable.class).newInstance(error);
        } catch (Exception reflectionError) {
            // Never pass a Throwable as a successful Kotlin Result.
            throw new IllegalStateException("Andalusi 10.0.3 Result ABI is unavailable", reflectionError);
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

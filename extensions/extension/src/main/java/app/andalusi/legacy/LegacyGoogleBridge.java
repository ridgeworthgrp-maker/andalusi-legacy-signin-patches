package app.andalusi.legacy;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Holds Andalusi's suspended Kotlin continuation while the classic Google sign-in UI runs.
 * This class is injected into the patched APK as an extension DEX.
 */
public final class LegacyGoogleBridge {
    private static final Object LOCK = new Object();
    private static Object continuation;

    private LegacyGoogleBridge() {}

    /**
     * Called directly from the patched Lrsh;->C0(...) method.
     * It starts our bridge Activity, then returns Kotlin's COROUTINE_SUSPENDED marker.
     */
    public static Serializable begin(Context context, Object newContinuation) {
        synchronized (LOCK) {
            if (continuation != null) {
                throw new IllegalStateException("A Google sign-in is already in progress");
            }
            continuation = newContinuation;
        }

        try {
            Intent intent = new Intent(context, LegacyGoogleSignInActivity.class);
            if (!(context instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
        } catch (Throwable t) {
            // Resume asynchronously so the coroutine is already in its suspended state.
            new Handler(Looper.getMainLooper()).post(() -> fail(t));
        }

        return coroutineSuspended();
    }

    /** Called by LegacyGoogleSignInActivity when it has built Andalusi's normal credential object. */
    static void complete(Object value) {
        Object target;
        synchronized (LOCK) {
            target = continuation;
            continuation = null;
        }

        if (target == null) {
            return;
        }

        try {
            Method resumeWith = findResumeWith(target.getClass());
            resumeWith.setAccessible(true);
            resumeWith.invoke(target, value);
        } catch (Throwable t) {
            throw new RuntimeException("Could not resume Andalusi login coroutine", unwrap(t));
        }
    }

    /** Resume the original coroutine with a Kotlin Result.Failure compatible object. */
    static void fail(Throwable error) {
        complete(makeKotlinFailure(unwrap(error)));
    }

    private static Method findResumeWith(Class<?> type) throws NoSuchMethodException {
        Class<?> cursor = type;
        while (cursor != null) {
            for (Method method : cursor.getDeclaredMethods()) {
                if (method.getName().equals("resumeWith") && method.getParameterTypes().length == 1) {
                    return method;
                }
            }
            cursor = cursor.getSuperclass();
        }
        throw new NoSuchMethodException("resumeWith(Object)");
    }

    /**
     * In this exact Andalusi 10.0.3 build, Lej2;.a is COROUTINE_SUSPENDED.
     */
    private static Serializable coroutineSuspended() {
        try {
            Class<?> enumClass = Class.forName("ej2");
            Field field = enumClass.getDeclaredField("a");
            field.setAccessible(true);
            return (Serializable) field.get(null);
        } catch (Throwable t) {
            throw new RuntimeException("Could not obtain Andalusi COROUTINE_SUSPENDED marker", t);
        }
    }

    /**
     * Andalusi's bundled Kotlin runtime is R8-obfuscated. Its Result failure wrapper in 10.0.3
     * is Ld8b; with a Throwable constructor. Try that first, then Kotlin's normal helper.
     */
    private static Object makeKotlinFailure(Throwable error) {
        try {
            Class<?> failureClass = Class.forName("d8b");
            Constructor<?> constructor = failureClass.getDeclaredConstructor(Throwable.class);
            constructor.setAccessible(true);
            return constructor.newInstance(error);
        } catch (Throwable ignored) {
            try {
                Class<?> resultKt = Class.forName("kotlin.ResultKt");
                Method createFailure = resultKt.getDeclaredMethod("createFailure", Throwable.class);
                createFailure.setAccessible(true);
                return createFailure.invoke(null, error);
            } catch (Throwable ignoredToo) {
                // Last resort. Normally the d8b path above is the one used by this APK.
                return error;
            }
        }
    }

    static Throwable unwrap(Throwable t) {
        Throwable cursor = t;
        while (cursor.getCause() != null &&
                (cursor instanceof java.lang.reflect.InvocationTargetException ||
                 cursor instanceof java.lang.reflect.UndeclaredThrowableException)) {
            cursor = cursor.getCause();
        }
        return cursor;

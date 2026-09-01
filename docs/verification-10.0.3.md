# Andalusi 10.0.3 verification

Inspected the supplied original APK, package com.andalusi.app.android, versionCode 248,
versionName 10.0.3, minSdk 32. SHA-256:
06929484279a8c894416516e6422c52676ce7109ab9883f495cf75f24a93b6a1.

## Verified ABI

- rsh.C0(Context, ah2): Serializable is public static final, with 6 registers.
- Its caller mz5.invokeSuspend receives an e8b box on asynchronous resume, unwraps
  field a, then passes the successful String to r38.b (the existing login path).
  Synchronous completion returns the unboxed String or d8b failure.
- zk5.invokeSuspend confirms this boxing contract.
- ah2.intercepted(): zg2 and zg2.resumeWith(Object) preserve coroutine dispatch.
- ej2.a is COROUTINE_SUSPENDED; e8b(Object) boxes Result; d8b(Throwable) represents failure.
- f5.a(Bundle): al5 validates the Google credential. al5.b is the token String.
- hg5 embeds web client ID
  752406979491-m39pf3vd9p88r5buiidqtirjt45e2k0i.apps.googleusercontent.com.
- The original helper creates a random 32-byte URL-safe nonce. Its caller passes
  only the resulting token onward. The replacement also sends a random nonce,
  checks the returned nonce/audience, and leaves backend signature verification intact.
- GoogleSignIn is absent. GoogleSignInOptions.Builder and named getters are absent.
  GoogleSignInOptions.b(String) is the retained options JSON factory.
  SignInConfiguration(String, GoogleSignInOptions) remains public.
  GoogleSignInAccount.b is the ID token, .c the email; Status.a is the status code.
- xk5 has no declared constructors in DEX. Cancellation is delivered as a boxed
  failure containing CancellationException; the app may show its generic login error.

The inspection used JADX plus direct DEX table inspection. JADX could not reconstruct
the large mz5 coroutine as Java; its fallback instruction listing was used to verify
the call, result unboxing, and downstream token handoff.

## Classic protocol and nonce

The extension sends app.revanced.android.gms.auth.GOOGLE_SIGN_IN directly to
app.revanced.android.gms with a Parcelable config and nonce extra.
This is the classic protocol used by SignInHubActivity, but calling MicroG directly
also preserves the nonce that the bundled hub would discard.

MicroG RE source checked:
https://github.com/MorpheApp/MicroG-RE/blob/main/play-services-core/src/main/kotlin/org/microg/gms/auth/signin/AuthSignInActivity.kt

That activity accepts config and nonce, checks the actual calling package, and
returns googleSignInAccount and googleSignInStatus. The configuration uses the
actual installed app package. No token is fabricated and no backend is bypassed.

## Device-only validation

A successful patch compilation is not proof of successful authentication.
On a phone, verify account selection, consent, successful backend login, cancellation,
rotation while the picker is open, retry after an error, and an absent MicroG install.
MicroG RE 7.0.0 (versionCode 255070000) or newer is required. Source for release
6.1.4 confirms that its classic sign-in activity never reads or forwards the nonce.
The installed version is checked before opening the picker. A missing/mismatched nonce
fails explicitly. Google OAuth recognition of the re-signed app and backend token
acceptance remain device/server checks. This patch does not override certificate
checks or change the application package.

The original APK is not included in this repository.

## Corrections after the first phone test

The initial downloadable APK incorrectly combined the original Google action with
the renamed MicroG package. The action is now renamed too. The user's phone-patched
copy reached the account chooser because the companion GmsCore patch corrected that
action, but its MicroG RE 6.1.4 could not satisfy the nonce validation.

The manifest now declares MicroG package visibility and original-package/certificate
metadata consumed by MicroG RE's PackageSpoofUtils. The certificate SHA-1 is
e404353443fb03a54702d53e2c7563d791d92559, extracted independently from the original
APK's v2 and v3 signing blocks. The installed package name is unchanged.

Failure dialogs show the stage, MicroG version, and fixed diagnostic text or exception
type. They never include tokens, nonce values, email addresses, or external exception
messages. A toast marks successful token handoff; an error after that toast is in
the app/backend path, which still requires phone testing.

Versioned protocol source:
https://github.com/MorpheApp/MicroG-RE/blob/7.0.0/play-services-core/src/main/kotlin/org/microg/gms/auth/signin/AuthSignInActivity.kt
https://github.com/MorpheApp/MicroG-RE/blob/6.1.4/play-services-core/src/main/kotlin/org/microg/gms/auth/signin/AuthSignInActivity.kt

# Andalusi 10.2.0 verification

The current patch targets only the official Play Store build, package
`com.andalusi.app.android`, versionName `10.2.0`, versionCode `254`, minSdk `32`.
The supplied `base.apk` is the input, with SHA-256:
`d4c60790731f87ff63892ddcf1cf46e0cab7ea98ef574b8ec611356c2ebdf122`.

## Input identity and the previous failure

Both signing blocks contain certificate SHA-1
`d5dbccbf463d80e3d9c338283ddf96a6cf16139e` and SHA-256
`47fce01036870c91ac65541d4870e5ad25c3191228e38a025c1a9c3bd38beafe`.
APK signature verification passes. The SHA-256 independently matches the production
package in [Andalusi's asset links](https://andalusi.app/.well-known/assetlinks.json).

The earlier file supplied as original 10.0.3 was re-signed by DZ4Team. Its
`e404353443fb03a54702d53e2c7563d791d92559` certificate was incorrectly used in dev.2.
Device logs confirmed that MicroG sent that identity and Google rejected it with
`UNREGISTERED_ON_API_CONSOLE`, before returning an ID token. The current manifest
provides the verified Play Store certificate to MicroG's package identity metadata.
The installed app package remains unchanged. No Google token is fabricated.

## Verified bytecode contract

JADX source, fallback instructions, and direct DEX inspection establish:

- `bf1.T(Context, xl2): Serializable` is public static final, with six registers.
- Its caller `m86.invokeSuspend` unwraps asynchronous `emb.a`, then sends the
  successful String to `ke8.b`, the existing login path.
- `xt5.invokeSuspend` confirms that asynchronous completion boxes the inner
  Result with `emb(Object)`. Synchronous completion returns a raw String or `dmb`.
- `xl2.intercepted(): wl2` and `wl2.resumeWith(Object)` preserve coroutine dispatch.
- `zn2.a` is COROUTINE_SUSPENDED; `dmb(Throwable)` represents Result failure.
- `f5.a(Bundle): yt5` validates the Google credential; `yt5.b` contains the token.
- `fp5` and `jei.n0` embed web client ID
  `752406979491-m39pf3vd9p88r5buiidqtirjt45e2k0i.apps.googleusercontent.com`.
- The original helper generates a random 32-byte nonce. The replacement does too,
  forwards it to MicroG, and validates the token's audience and nonce. Backend
  verification and the existing login request are preserved.
- `GoogleSignInOptions.b(String)` is the retained JSON factory;
  `SignInConfiguration(String, GoogleSignInOptions)` is public.
- `GoogleSignInAccount.b` is the ID token, `.c` is email, and `Status.a` is the code.
- R8 removed `vt5` constructors. Cancellation uses a retained standard exception.

## Complete APK assembly

The user's base is a split APK and requires native libraries and density resources.
The matching 10.2.0 arm64 and mdpi splits were obtained from the
[version metadata](https://ws75.aptoide.com/api/7/app/get/app_id=76276641/aab=true).
Each passes APK signature verification against the same production certificate.

| Component | SHA-256 |
| --- | --- |
| arm64 | `e0fdfffc6be0bd3699e9a4a4ab2e063b5af3e306c97a21fe91026f1383aa7a57` |
| mdpi | `ad96f6651d7299e0a9a4ada0f711be61e4393586871c9a294f8125a9cb56bf8f` |

APKEditor 1.4.9 merges these components with version validation and native-library
extraction enabled. Morphe applies only this patch to the merged APK. The final
APK is signed with the same local patch key used for previous downloadable builds.

## Startup correction after the dev.3 phone test

The dev.3 APK compiled and patched successfully, but the phone redirected to Google
Play before sign-in. The original manifest declares `com.pairip.application.Application`.
Its only override calls `LicenseClient.checkLicense(context)` and then delegates to
`com.andalusi.app.android.App.attachBaseContext(context)`. The licensing client's
NOT_LICENSED path starts the Play-supplied intent and closes the app, matching the report.
This is the [Play installer protection](https://support.google.com/googleplay/android-developer/answer/10183279).

The patched manifest now names Andalusi's own `App` directly, skipping the added
Play installation-licence startup wrapper. The original application initialization,
account authentication, Google token validation, backend login, billing and subscription
code remain intact. No successful licence response is fabricated.

Direct DEX inspection found two callers of `LicenseClient.checkLicense`: the wrapper
and `LicenseContentProvider.onCreate`. The provider is absent from this version's
manifest. The patch rejects an unexpected application class or a declared licensing
provider instead of silently assuming that another build has the same startup path.
Actual launch and sign-in on the repaired APK still require a phone test.

## Runtime requirements

MicroG RE 7.0.0 (255070000) or newer is required for classic sign-in nonce support.
The extension sends `app.revanced.android.gms.auth.GOOGLE_SIGN_IN` directly with
the config and nonce extras, preserving the nonce through the account picker.
Protocol reference: [MicroG RE 7.0.0 sign-in activity](https://github.com/MorpheApp/MicroG-RE/blob/7.0.0/play-services-core/src/main/kotlin/org/microg/gms/auth/signin/AuthSignInActivity.kt).

Compilation and successful APK patching do not prove successful authentication.
Google's response and Andalusi's backend acceptance require a device test.
Diagnostics contain only fixed messages, stage, build, and MicroG version;
they exclude tokens, account details, nonce values, and external exception messages.
After replacing dev.2, MicroG may need a process restart to discard cached package
identity metadata. Removing its account or clearing application data is unnecessary.

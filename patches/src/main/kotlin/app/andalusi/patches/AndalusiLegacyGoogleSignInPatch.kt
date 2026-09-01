package app.andalusi.patches

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.AccessFlags
import org.w3c.dom.Element

private const val EXTENSION_CLASS = "Lapp/andalusi/legacy/LegacyGoogleBridge;"
private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
private const val MICROG_PACKAGE = "app.revanced.android.gms"
// SHA-1 of the signing certificate in both the original APK's v2 and v3 signing blocks.
private const val ORIGINAL_CERTIFICATE_SHA1 = "e404353443fb03a54702d53e2c7563d791d92559"

/**
 * Andalusi 10.0.3 Google login coroutine helper found in classes2.dex:
 *   Lrsh;->C0(Landroid/content/Context;Lah2;)Ljava/io/Serializable;
 *
 * This fingerprint is intentionally pinned to the exact obfuscated method from 10.0.3.
 */
private object AndalusiGoogleLoginFingerprint : Fingerprint(
    definingClass = "Lrsh;",
    name = "C0",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "Ljava/io/Serializable;",
    parameters = listOf("Landroid/content/Context;", "Lah2;")
)

/** Adds the transparent bridge activity to Andalusi's real AndroidManifest.xml. */
private val addLegacyGoogleSignInActivityPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { document ->
            val application = document.getElementsByTagName("application").item(0) as? Element
                ?: error("Andalusi <application> element was not found")

            val activity = document.createElement("activity").apply {
                setAttributeNS(
                    ANDROID_NS,
                    "android:name",
                    "app.andalusi.legacy.LegacyGoogleSignInActivity"
                )
                setAttributeNS(ANDROID_NS, "android:exported", "false")
                setAttributeNS(ANDROID_NS, "android:excludeFromRecents", "true")
                setAttributeNS(
                    ANDROID_NS,
                    "android:theme",
                    "@android:style/Theme.Translucent.NoTitleBar"
                )
            }
            application.appendChild(activity)

            // MicroG's OAuth request must retain the original app identity after re-signing.
            // Replace existing entries to coexist with companion certificate/GmsCore patches.
            fun putMetadata(name: String, value: String) {
                val nodes = application.getElementsByTagName("meta-data")
                for (index in nodes.length - 1 downTo 0) {
                    val node = nodes.item(index) as Element
                    if (node.getAttributeNS(ANDROID_NS, "name") == name) {
                        node.parentNode.removeChild(node)
                    }
                }
                application.appendChild(document.createElement("meta-data").apply {
                    setAttributeNS(ANDROID_NS, "android:name", name)
                    setAttributeNS(ANDROID_NS, "android:value", value)
                })
            }
            putMetadata("$MICROG_PACKAGE.SPOOFED_PACKAGE_NAME", "com.andalusi.app.android")
            putMetadata("$MICROG_PACKAGE.SPOOFED_PACKAGE_SIGNATURE", ORIGINAL_CERTIFICATE_SHA1)
            putMetadata("app.revanced.MICROG_PACKAGE_NAME", MICROG_PACKAGE)

            // Android package visibility is needed for the installed-version and intent checks.
            val manifest = document.documentElement
            val queries = document.getElementsByTagName("queries").item(0) as? Element
                ?: document.createElement("queries").also { manifest.appendChild(it) }
            val packages = queries.getElementsByTagName("package")
            if ((0 until packages.length).none {
                    (packages.item(it) as Element).getAttributeNS(ANDROID_NS, "name") == MICROG_PACKAGE
                }) {
                queries.appendChild(document.createElement("package").apply {
                    setAttributeNS(ANDROID_NS, "android:name", MICROG_PACKAGE)
                })
            }
        }
    }
}

@Suppress("unused")
val andalusiLegacyGoogleSignInPatch = bytecodePatch(
    name = "Andalusi legacy Google sign-in",
    description = "Replaces Andalusi 10.0.3 Credential Manager login with classic Google Sign-In so it can use MicroG RE.",
    default = false
) {
    compatibleWith("com.andalusi.app.android"("10.0.3"))
    dependsOn(addLegacyGoogleSignInActivityPatch)
    extendWith("extensions/extension.mpe")

    execute {
        // Short-circuit the original Credential Manager coroutine helper.
        // p0 = Context, p1 = the Kotlin continuation object.
        AndalusiGoogleLoginFingerprint.method.addInstructions(
            0,
            """
                invoke-static/range {p0 .. p1}, $EXTENSION_CLASS->begin(Landroid/content/Context;Ljava/lang/Object;)Ljava/io/Serializable;
                move-result-object v0
                return-object v0
            """.trimIndent()
        )
    }
}

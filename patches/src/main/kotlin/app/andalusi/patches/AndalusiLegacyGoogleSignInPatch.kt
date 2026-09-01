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
// Play Store certificate, independently matched to andalusi.app/.well-known/assetlinks.json.
private const val ORIGINAL_CERTIFICATE_SHA1 = "d5dbccbf463d80e3d9c338283ddf96a6cf16139e"

/**
 * Play Store Andalusi 10.2.0 Google login coroutine helper:
 *   Lbf1;->T(Landroid/content/Context;Lxl2;)Ljava/io/Serializable;
 *
 * This fingerprint is intentionally pinned to the verified official 10.2.0 bytecode.
 */
private object AndalusiGoogleLoginFingerprint : Fingerprint(
    definingClass = "Lbf1;",
    name = "T",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "Ljava/io/Serializable;",
    parameters = listOf("Landroid/content/Context;", "Lxl2;")
)

/** Adds the transparent bridge activity to Andalusi's real AndroidManifest.xml. */
private val addLegacyGoogleSignInActivityPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { document ->
            val application = document.getElementsByTagName("application").item(0) as? Element
                ?: error("Andalusi <application> element was not found")

            // Play's wrapper only starts its installation licence check before delegating
            // to Andalusi's App. A re-signed build must start the original application.
            val originalApplication = "com.andalusi.app.android.App"
            val declaredApplication = application.getAttributeNS(ANDROID_NS, "name")
            check(declaredApplication in setOf("com.pairip.application.Application", originalApplication)) {
                "Unexpected Andalusi application class: $declaredApplication"
            }
            val providers = application.getElementsByTagName("provider")
            check((0 until providers.length).none {
                (providers.item(it) as Element).getAttributeNS(ANDROID_NS, "name") ==
                    "com.pairip.licensecheck.LicenseContentProvider"
            }) { "Unexpected additional Play licence startup provider" }
            application.setAttributeNS(ANDROID_NS, "android:name", originalApplication)

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
    description = "Replaces official Andalusi 10.2.0 Credential Manager login with classic Google Sign-In so it can use MicroG RE.",
    default = false
) {
    compatibleWith("com.andalusi.app.android"("10.2.0"))
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

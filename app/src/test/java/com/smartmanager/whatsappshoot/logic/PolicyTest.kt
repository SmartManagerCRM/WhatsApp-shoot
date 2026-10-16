package com.smartmanager.whatsappshoot.logic

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Static checks that back the privacy / offline / licensing claims in the documentation. */
class PolicyTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/AndroidManifest.xml").exists() }
    // XML comments are documentation, not declarations.
    private val manifest = File(root, "app/src/main/AndroidManifest.xml").readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
    private fun sources(dir: String) = File(root, dir).walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
    private val appSources get() = sources("app/src/main")

    @Test fun noInternetOrNetworkStatePermission() {
        assertFalse(manifest.contains("android.permission.INTERNET"))
        assertFalse(manifest.contains("ACCESS_NETWORK_STATE"))
    }

    @Test fun noBroadStorageOrOverlayOrContactPermissions() {
        for (p in listOf("READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE", "SYSTEM_ALERT_WINDOW", "READ_CONTACTS", "READ_SMS", "RECORD_AUDIO", "CAMERA", "READ_MEDIA")) {
            assertFalse("$p must not be requested", manifest.contains(p))
        }
    }

    @Test fun backupsDisabledSoActivationAndScreenshotsNeverLeaveTheDevice() {
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(File(root, "app/src/main/res/xml/data_extraction_rules.xml").readText().contains("<exclude domain=\"sharedpref\""))
    }

    @Test fun onlyTheAccessibilityServiceIsExported_andFileProviderIsNot() {
        val providerBlock = manifest.substringAfter("<provider").substringBefore("</provider>")
        assertTrue(providerBlock.contains("android:exported=\"false\""))
        assertTrue(providerBlock.contains("grantUriPermissions=\"true\""))
        // exported components: MainActivity (launcher) and the accessibility service only
        assertEquals(2, Regex("android:exported=\"true\"").findAll(manifest).count())
    }

    @Test fun fileProviderSharesOnlyTheScreenshotFolder() {
        val paths = File(root, "app/src/main/res/xml/file_paths.xml").readText()
        assertTrue(paths.contains("<cache-path name=\"screenshots\" path=\"screenshots/\""))
        assertEquals(1, Regex("<[a-z-]+-path ").findAll(paths).count())
    }

    @Test fun noNetworkApisInAnySource() {
        val banned = listOf("java.net.", "javax.net.", "HttpURLConnection", "okhttp", "retrofit", "ktor", "WebView", "firebase", "crashlytics", "Socket(")
        for (f in appSources + sources("license-core/src/main")) {
            val text = f.readText()
            for (b in banned) assertFalse("${f.name} uses $b", text.contains(b, ignoreCase = true))
        }
    }

    @Test fun noLoggingInAppSources_soNothingSensitiveCanBeLogged() {
        for (f in appSources) assertFalse("${f.name} logs", Regex("\\bLog\\.[a-z]\\(|println\\(|Timber").containsMatchIn(f.readText()))
    }

    @Test fun appNeverClicksSend() {
        // The accessibility layer must contain no reference to WhatsApp's send button.
        val svc = File(root, "app/src/main/java/com/smartmanager/whatsappshoot/platform/WhatsAppAccessibilityService.kt").readText()
        assertFalse(svc.contains("\"send\"", ignoreCase = true))
        assertFalse(WhatsAppProfile().let { p -> (p.shareRowTitleIds + p.inputFieldIds).any { it.equals("send", true) } })
    }

    @Test fun noPrivateKeyMaterialCommittedAnywhere() {
        val marker = "BEGIN " + "PRIVATE KEY"
        val skip = setOf(".git", "build", ".gradle", "secrets")
        root.walkTopDown().onEnter { it.name !in skip }.filter { it.isFile && it.length() < 2_000_000 && it.extension !in setOf("jar", "png", "zip") }.forEach {
            val t = it.readText()
            assertFalse("${it.path} contains private key material", t.contains(marker) || t.startsWith("WSSKEY1"))
        }
    }

    @Test fun appModuleOnlyDependsOnVerificationCode_notSigningCode() {
        // License issuing lives in :license-tool only.
        val gradle = File(root, "app/build.gradle.kts").readText()
        assertFalse(gradle.contains(":license-tool"))
        assertTrue(sources("license-core/src/main").none { it.readText().contains("initSign") })
    }

    @Test fun licensingPackageHasNoDependencyOnScreenshotCode() {
        for (f in sources("license-core/src/main")) {
            val t = f.readText()
            assertFalse(f.name, Regex("^import .*whatsappshoot\\.(logic|platform|ui)", RegexOption.MULTILINE).containsMatchIn(t))
        }
    }
}

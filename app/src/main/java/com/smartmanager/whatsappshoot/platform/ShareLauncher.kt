package com.smartmanager.whatsappshoot.platform

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import com.smartmanager.whatsappshoot.logic.AttachmentLauncher
import com.smartmanager.whatsappshoot.logic.ConversationIdentity
import com.smartmanager.whatsappshoot.logic.IdentityHasher
import com.smartmanager.whatsappshoot.logic.LaunchResult
import com.smartmanager.whatsappshoot.logic.StoredScreenshot

/**
 * Opens WhatsApp's own share screen with the screenshot as an image attachment.
 *
 * Android and WhatsApp offer no supported way to inject a file into an *already open* chat, so this uses the
 * documented ACTION_SEND route addressed to WhatsApp: WhatsApp then shows its recipient list. The user (or, when the
 * experimental option is on and the match is unambiguous, the accessibility layer) chooses the chat, and the user
 * presses Send. This class never sends anything.
 */
class ShareLauncher(private val context: Context, private val graph: () -> AppGraph) : AttachmentLauncher {

    override fun launchShare(
        shot: StoredScreenshot,
        expected: ConversationIdentity?,
        hasher: IdentityHasher,
        tryDirectSelect: Boolean,
    ): LaunchResult {
        val g = graph()
        val pkg = choosePackage(g) ?: return LaunchResult.WhatsAppNotInstalled
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shot.file)
        } catch (e: Exception) {
            return LaunchResult.Failed("Cannot create content URI")
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = shot.format.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("screenshot", uri)
            setPackage(pkg)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Temporary, read-only grant to exactly the WhatsApp package we address; revoked on cleanup.
        context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            context.startActivity(intent)
            LaunchResult.Launched(directSelectPending = tryDirectSelect && WhatsAppAccessibilityService.instance != null)
                .also { scheduleBlockedLaunchFallback(intent, pkg) }
        } catch (e: ActivityNotFoundException) {
            LaunchResult.WhatsAppNotInstalled
        } catch (e: Exception) {
            LaunchResult.Failed(e.javaClass.simpleName)
        }
    }

    /**
     * Android may silently refuse to start an activity from the background. If WhatsApp's share screen did not come up
     * (we still see the same conversation), offer a notification whose tap opens it (a user gesture is always allowed).
     */
    private fun scheduleBlockedLaunchFallback(intent: Intent, pkg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val screen = WhatsAppAccessibilityService.instance?.currentWhatsAppScreen()
            if (screen?.kind == com.smartmanager.whatsappshoot.logic.ScreenKind.CONVERSATION) {
                graph().notifier.showShareFallback(intent)
            }
        }, 2_500L)
    }

    fun revoke(shot: StoredScreenshot) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shot.file)
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // file already gone
        }
    }

    /** Prefer the WhatsApp variant that was just in front (Business vs regular); else the first installed one. */
    private fun choosePackage(g: AppGraph): String? {
        val candidates = listOfNotNull(g.lastWhatsAppPackage) + g.profile.packages.toList()
        return candidates.firstOrNull { isInstalled(it) }
    }

    private fun isInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0); true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}

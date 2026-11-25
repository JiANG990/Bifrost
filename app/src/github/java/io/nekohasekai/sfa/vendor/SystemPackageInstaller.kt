package io.nekohasekai.sfa.vendor

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.nekohasekai.sfa.Application
import java.io.File

object SystemPackageInstaller {

    /**
     * USER_ACTION_NOT_REQUIRED is ignored unless the caller really holds
     * INSTALL_PACKAGES, so without that permission the session is guaranteed to fail
     * after the download.
     */
    fun canSystemSilentInstall(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return Application.application.checkSelfPermission(android.Manifest.permission.INSTALL_PACKAGES) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Hands the APK to the system installer. This is the only path that works without
     * INSTALL_PACKAGES: Android shows its own confirmation dialog and the user taps install.
     */
    fun install(context: Context, apkFile: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            context.packageName + ".cache",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

}

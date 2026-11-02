package com.wmserp.app.core.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.wmserp.app.BuildConfig
import com.wmserp.app.domain.model.InstalledVersion

/** Version of the running APK, read from the package manager (falls back to BuildConfig). */
class AndroidInstalledVersion(context: Context) : InstalledVersion {
    override val versionName: String
    override val versionCode: Long

    init {
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
        }.getOrNull()
        versionName = info?.versionName?.takeIf { it.isNotBlank() } ?: BuildConfig.VERSION_NAME
        versionCode = info?.longVersionCode ?: BuildConfig.VERSION_CODE.toLong()
    }
}

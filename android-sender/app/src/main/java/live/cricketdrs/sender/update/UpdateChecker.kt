package live.cricketdrs.sender.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import live.cricketdrs.sender.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class AppVersionInfo(
    val versionName: String,
    val versionCode: Int,
    val apkUrl: String,
    val force: Boolean,
    val message: String,
)

object UpdateChecker {
    const val MANIFEST_URL = "https://cricketdrs.com/downloads/version.json"
    private const val PREFS = "cricketdrs_update"
    private const val KEY_SKIP_CODE = "skip_until_version_code"

    fun fetchRemoteVersion(manifestUrl: String = MANIFEST_URL): AppVersionInfo? {
        return try {
            val bust = if (manifestUrl.contains("?")) "&" else "?"
            val conn = URL("$manifestUrl${bust}t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Cache-Control", "no-cache")
            conn.inputStream.bufferedReader().use { reader ->
                val data = JSONObject(reader.readText().ifBlank { "{}" })
                val code = data.optInt("versionCode", 0)
                    .takeIf { it > 0 }
                    ?: data.optInt("androidVersionCode", 0)
                if (code <= 0) return null
                val apk = pickApkUrl(data)
                if (apk.isBlank()) return null
                val notes = data.optString("message").ifBlank { data.optString("releaseNotes") }
                AppVersionInfo(
                    versionName = data.optString("versionName")
                        .ifBlank { data.optString("androidVersion") }
                        .ifBlank { data.optString("version") },
                    versionCode = code,
                    apkUrl = apk,
                    force = data.optBoolean("force", data.optBoolean("forceUpdate", false)),
                    message = notes,
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    fun skipAutoPromptFor(context: Context, versionCode: Int) {
        if (versionCode <= 0) return
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SKIP_CODE, versionCode)
            .apply()
    }

    fun isAutoPromptSkipped(context: Context, remoteVersionCode: Int): Boolean {
        if (remoteVersionCode <= 0) return false
        val skipped = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SKIP_CODE, 0)
        return skipped > 0 && remoteVersionCode <= skipped
    }

    fun clearSkip(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SKIP_CODE)
            .apply()
    }

    private fun pickApkUrl(data: JSONObject): String {
        val byAbi = data.optJSONObject("apkUrls")
        if (byAbi != null) {
            for (abi in Build.SUPPORTED_ABIS) {
                val url = byAbi.optString(abi)
                if (url.isNotBlank()) return absolutize(url)
            }
        }
        val raw = data.optString("apkUrl")
            .ifBlank { data.optString("androidApk") }
            .ifBlank { data.optString("url") }
        return absolutize(raw)
    }

    private fun absolutize(raw: String): String {
        if (raw.isBlank()) return raw
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        val base = BuildConfig.API_BASE_URL.trimEnd('/')
        return if (raw.startsWith("/")) "$base$raw" else "$base/$raw"
    }

    fun localVersionCode(context: Context): Int =
        try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt()
            else @Suppress("DEPRECATION") pi.versionCode
        } catch (_: Exception) {
            BuildConfig.VERSION_CODE
        }

    fun localVersionName(context: Context): String =
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                ?: BuildConfig.VERSION_NAME
        } catch (_: Exception) {
            BuildConfig.VERSION_NAME
        }

    fun downloadApk(
        context: Context,
        apkUrl: String,
        onProgress: (Int) -> Unit,
        expectedVersionCode: Int = 0,
    ): File {
        val app = context.applicationContext
        val dir = File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWith("cricketdrs-update-") && f.name.endsWith(".apk")) {
                try { f.delete() } catch (_: Exception) { }
            }
        }
        val dest = File(dir, "cricketdrs-update-v${System.currentTimeMillis()}.apk")
        val tmp = File(dest.absolutePath + ".part")

        val bust = if (apkUrl.contains("?")) "&" else "?"
        val conn = (URL("$apkUrl${bust}v=$expectedVersionCode&t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection).apply {
            connectTimeout = 30000
            readTimeout = 180000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.android.package-archive,*/*")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code downloading update")
            val total = conn.contentLengthLong.coerceAtLeast(0L)
            var lastPct = -1
            FileOutputStream(tmp).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var soFar = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        soFar += n
                        if (total > 0L) {
                            val pct = ((soFar * 100) / total).toInt().coerceIn(0, 99)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                    out.flush()
                }
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            validateApkOrThrow(app, dest, expectedVersionCode)
            onProgress(100)
            return dest
        } catch (e: Exception) {
            try { tmp.delete() } catch (_: Exception) { }
            try { dest.delete() } catch (_: Exception) { }
            throw e
        } finally {
            try { conn.disconnect() } catch (_: Exception) { }
        }
    }

    fun validateApkOrThrow(context: Context, apkFile: File, expectedVersionCode: Int = 0) {
        if (!apkFile.exists() || apkFile.length() < 1024L) {
            throw IllegalStateException("Downloaded file too small (${apkFile.length()} bytes)")
        }
        FileInputStream(apkFile).use { input ->
            val magic = ByteArray(4)
            if (input.read(magic) != 4 || magic[0] != 0x50.toByte() || magic[1] != 0x4B.toByte()) {
                throw IllegalStateException("Downloaded file is not an APK")
            }
        }
        val info = context.packageManager.getPackageArchiveInfo(
            apkFile.absolutePath,
            PackageManager.GET_ACTIVITIES,
        ) ?: throw IllegalStateException("Android could not parse the APK")
        if (info.packageName != context.packageName) {
            throw IllegalStateException("APK package mismatch: ${info.packageName}")
        }
        val apkCode = if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION") info.versionCode
        }
        val localCode = localVersionCode(context)
        if (apkCode <= localCode) {
            throw IllegalStateException("Server APK is v$apkCode but you have v$localCode.")
        }
        if (expectedVersionCode > 0 && apkCode != expectedVersionCode) {
            throw IllegalStateException("Server APK version mismatch (expected v$expectedVersionCode, got v$apkCode).")
        }
        if (!signingCertsCompatible(context, apkFile)) {
            throw IllegalStateException(
                "Signing key changed. Uninstall Cricket DRS, then install the new APK from cricketdrs.com/downloads.",
            )
        }
    }

    fun signingCertsCompatible(context: Context, apkFile: File): Boolean {
        val installed = installedCertDigests(context) ?: return true
        val incoming = apkCertDigests(context, apkFile.absolutePath) ?: return true
        if (installed.isEmpty() || incoming.isEmpty()) return true
        return installed.any { it in incoming }
    }

    private fun installedCertDigests(context: Context): Set<String>? {
        return try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= 28) {
                val pi = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val sigs = pi.signingInfo?.apkContentsSigners ?: return null
                sigs.map { sha256Hex(it.toByteArray()) }.toSet()
            } else {
                @Suppress("DEPRECATION")
                val pi = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                (pi.signatures ?: emptyArray()).map { sha256Hex(it.toByteArray()) }.toSet()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun apkCertDigests(context: Context, apkPath: String): Set<String>? {
        return try {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val legacy = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            val sigs = legacy?.signatures
            if (sigs != null && sigs.isNotEmpty()) {
                return sigs.map { sha256Hex(it.toByteArray()) }.toSet()
            }
            if (Build.VERSION.SDK_INT >= 28) {
                val pi = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNING_CERTIFICATES)
                pi?.signingInfo?.apkContentsSigners?.map { sha256Hex(it.toByteArray()) }?.toSet()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(bytes)
        return dig.joinToString("") { "%02x".format(it) }
    }

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openUnknownSourcesSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(intent)
    }

    fun installApk(context: Context, apkFile: File, expectedVersionCode: Int = 0) {
        validateApkOrThrow(context, apkFile, expectedVersionCode)
        if (!canInstallPackages(context)) {
            openUnknownSourcesSettings(context)
            throw IllegalStateException("Allow Install unknown apps for Cricket DRS, then tap Update again")
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
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

package live.cricketdrs.sender.update

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun UpdateGate(auto: Boolean = true, manualTrigger: Int = 0) {
    val context = LocalContext.current
    val activity = context as? Activity
    var pending by remember { mutableStateOf<AppVersionInfo?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    fun runCheck(forceShowUpToDate: Boolean) {
        if (activity == null || checking) return
        checking = true
        status = null
        Thread {
            try {
                val remote = UpdateChecker.fetchRemoteVersion()
                val local = UpdateChecker.localVersionCode(activity)
                activity.runOnUiThread {
                    checking = false
                    when {
                        remote == null -> {
                            if (forceShowUpToDate) status = "Could not check for updates."
                        }
                        remote.versionCode > local -> {
                            if (!forceShowUpToDate &&
                                !remote.force &&
                                UpdateChecker.isAutoPromptSkipped(activity, remote.versionCode)
                            ) {
                                return@runOnUiThread
                            }
                            pending = remote
                        }
                        forceShowUpToDate -> {
                            UpdateChecker.clearSkip(activity)
                            status = "You are on the latest version (v${UpdateChecker.localVersionName(activity)})."
                        }
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    checking = false
                    if (forceShowUpToDate) status = e.message ?: "Update check failed"
                }
            }
        }.start()
    }

    LaunchedEffect(auto) {
        if (auto) runCheck(forceShowUpToDate = false)
    }
    LaunchedEffect(manualTrigger) {
        if (manualTrigger > 0) runCheck(forceShowUpToDate = true)
    }

    pending?.let { info ->
        UpdateDownloadDialog(
            info = info,
            onLater = {
                if (!info.force) {
                    UpdateChecker.skipAutoPromptFor(activity ?: context, info.versionCode)
                }
                pending = null
            },
            onClose = { pending = null },
        )
    }

    status?.let { msg ->
        AlertDialog(
            onDismissRequest = { status = null },
            title = { Text("Update") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { status = null }) { Text("OK") }
            },
        )
    }
}

@Composable
fun UpdateDownloadDialog(
    info: AppVersionInfo,
    onLater: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!info.force && !downloading) onLater() },
        title = { Text("Update available", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "v${UpdateChecker.localVersionName(context)} -> v${info.versionName}",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
                if (info.message.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(info.message, fontSize = 13.sp)
                }
                if (downloading) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("Downloading... $progress%", fontSize = 12.sp)
                }
                if (!error.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(error.orEmpty(), color = Color(0xFFD32F2F), fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !downloading,
                onClick = {
                    if (activity == null) return@TextButton
                    downloading = true
                    error = null
                    progress = 0
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    scope.launch {
                        try {
                            val file = withContext(Dispatchers.IO) {
                                UpdateChecker.downloadApk(activity, info.apkUrl, { pct ->
                                    activity.runOnUiThread { progress = pct }
                                }, info.versionCode)
                            }
                            UpdateChecker.installApk(activity, file, info.versionCode)
                            UpdateChecker.clearSkip(activity)
                            onClose()
                        } catch (e: Exception) {
                            error = e.message ?: "Update failed"
                            if ((e.message ?: "").contains("Signing key", ignoreCase = true)) {
                                UpdateChecker.skipAutoPromptFor(activity, info.versionCode)
                            }
                        } finally {
                            downloading = false
                            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }
                },
            ) {
                Text(if (downloading) "Please wait..." else "Update now")
            }
        },
        dismissButton = {
            if (!info.force && !downloading) {
                TextButton(onClick = onLater) { Text("Later") }
            }
        },
    )
}

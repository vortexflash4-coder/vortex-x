package com.vortexx.player.core.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

enum class VideoAccess { Full, Partial, None }

data class MediaAccess(
    val audio: Boolean,
    val video: VideoAccess,
) {
    val hasAny: Boolean get() = audio || video != VideoAccess.None
}

/** Runtime permission sets per Android release (scoped storage; no "all files" access). */
object MediaPermissions {

    val audio: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    val video: Array<String>
        get() = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    val all: Array<String> get() = (audio + video).distinct().toTypedArray()

    fun access(context: Context): MediaAccess {
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        val audioGranted = audio.all(::granted)
        val video = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && granted(Manifest.permission.READ_MEDIA_VIDEO) -> VideoAccess.Full
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> VideoAccess.Partial
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> VideoAccess.Full
            else -> VideoAccess.None
        }
        return MediaAccess(audio = audioGranted, video = video)
    }

    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * Compose state for one permission group. Re-checks on every resume so changes
 * made in Android settings are picked up, and detects permanent denial.
 */
@Stable
class PermissionGroupState internal constructor(
    granted: Boolean,
    private val onLaunch: () -> Unit,
    private val openSettings: () -> Unit,
) {
    var granted: Boolean by mutableStateOf(granted)
        internal set
    var permanentlyDenied: Boolean by mutableStateOf(false)
        internal set

    fun request() = onLaunch()
    fun openAppSettings() = openSettings()
}

@Composable
fun rememberPermissionGroupState(
    permissions: Array<String>,
    isGranted: (MediaAccess) -> Boolean,
    onResult: (Boolean) -> Unit = {},
): PermissionGroupState {
    val context = LocalContext.current
    var requestedOnce by rememberSaveable { mutableStateOf(false) }
    lateinit var state: PermissionGroupState

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val nowGranted = isGranted(MediaPermissions.access(context))
        state.granted = nowGranted
        val activity = context.findActivity()
        state.permanentlyDenied = !nowGranted && activity != null &&
            permissions.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        onResult(nowGranted)
    }

    state = remember(permissions.joinToString()) {
        PermissionGroupState(
            granted = isGranted(MediaPermissions.access(context)),
            onLaunch = {
                requestedOnce = true
                launcher.launch(permissions)
            },
            openSettings = { context.startActivity(MediaPermissions.appSettingsIntent(context)) },
        )
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val nowGranted = isGranted(MediaPermissions.access(context))
        if (nowGranted != state.granted) {
            state.granted = nowGranted
            if (nowGranted) {
                state.permanentlyDenied = false
                onResult(true)
            }
        }
        if (!requestedOnce) state.permanentlyDenied = false
    }
    return state
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

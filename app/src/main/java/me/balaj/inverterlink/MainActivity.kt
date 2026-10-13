package me.balaj.inverterlink

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    private lateinit var model: AppModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val preview = BuildConfig.DEBUG && intent.getBooleanExtra("preview", false)
        model =
            ViewModelProvider(
                this,
                object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        @Suppress("UNCHECKED_CAST")
                        return AppModel(application, preview) as T
                    }
                },
            )[AppModel::class.java]
        setContent {
            var permissionAction by remember { mutableStateOf<(() -> Unit)?>(null) }
            val lan =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    granted ->
                    if (granted) permissionAction?.invoke()
                    permissionAction = null
                }
            val notification =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    granted ->
                    model.preferences { it.copy(liveStatus = granted) }
                }
            InverterApp(
                model,
                localAction = { action ->
                    if (
                        Build.VERSION.SDK_INT >= 37 &&
                            ContextCompat.checkSelfPermission(
                                this,
                                "android.permission.ACCESS_LOCAL_NETWORK",
                            ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        permissionAction = action
                        lan.launch("android.permission.ACCESS_LOCAL_NETWORK")
                    } else action()
                },
                enableStatus = { enabled ->
                    if (
                        enabled &&
                            Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(
                                this,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) != PackageManager.PERMISSION_GRANTED
                    )
                        notification.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else model.preferences { it.copy(liveStatus = enabled) }
                },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        if (::model.isInitialized) model.onForeground(true)
    }

    override fun onStop() {
        if (::model.isInitialized) model.onForeground(false)
        super.onStop()
    }
}

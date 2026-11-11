package com.smartinvest

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.smartinvest.data.importer.ReceiptInbox
import com.smartinvest.data.widget.WidgetRefresher
import com.smartinvest.domain.repository.SettingsRepository
import com.smartinvest.notification.NotificationHelper
import com.smartinvest.ui.navigation.SmartInvestNavHost
import com.smartinvest.ui.theme.SmartInvestTheme
import com.smartinvest.ui.util.MoneyVisibility
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var widgetRefresher: WidgetRefresher

    private var lastHideAmounts: Boolean? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Path of a receipt shared via ACTION_SEND, consumed by the navigation host. */
    private var sharedReceiptPath by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        NotificationHelper.ensureChannel(this)
        requestNotificationPermissionIfNeeded()
        observePrivacyMode()
        handleSharedIntent(intent)

        setContent {
            SmartInvestTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SmartInvestNavHost(
                        sharedReceiptPath = sharedReceiptPath,
                        onSharedReceiptHandled = { sharedReceiptPath = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedIntent(intent)
    }

    /** Keeps the global amount mask in sync with the saved preference. */
    private fun observePrivacyMode() {
        lifecycleScope.launch {
            settingsRepository.settings.collect { settings ->
                MoneyVisibility.update(settings.hideAmounts)
                val previous = lastHideAmounts
                if (previous != null && previous != settings.hideAmounts) {
                    // The widget is a RemoteViews surface, so refresh it to apply the mask.
                    widgetRefresher.refresh()
                }
                lastHideAmounts = settings.hideAmounts
            }
        }
    }

    private fun handleSharedIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val mimeType = intent.type
        val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)

        lifecycleScope.launch {
            val staged = withContext(Dispatchers.IO) {
                when {
                    uri != null -> ReceiptInbox.stage(this@MainActivity, uri, mimeType)
                    !text.isNullOrBlank() -> ReceiptInbox.stageText(this@MainActivity, text)
                    else -> null
                }
            }
            staged?.let { sharedReceiptPath = it.file.absolutePath }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

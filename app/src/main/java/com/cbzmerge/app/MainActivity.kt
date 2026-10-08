package com.cbzmerge.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.cbzmerge.app.ui.App
import com.cbzmerge.app.ui.AppTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShare(intent)
        setContent { AppTheme { App(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Files shared from another app ("Share" > CBZ Merge) are added to the list. */
    private fun handleShare(intent: Intent?) {
        val uris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.stream())
            Intent.ACTION_SEND_MULTIPLE -> intent.streams()
            else -> emptyList()
        }
        vm.addShared(uris)
    }

    @Suppress("DEPRECATION")
    private fun Intent.stream(): Uri? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun Intent.streams(): List<Uri> =
        if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
}

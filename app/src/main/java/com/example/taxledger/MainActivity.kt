package com.example.taxledger

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.taxledger.ui.TaxLedgerApp
import com.example.taxledger.ui.theme.LedgerTheme

class MainActivity : ComponentActivity() {
    private var incomingUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingUri = attachmentFrom(intent)
        enableEdgeToEdge()
        setContent {
            LedgerTheme {
                TaxLedgerApp(importUri = incomingUri, onImportHandled = { incomingUri = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingUri = attachmentFrom(intent)
    }

    @Suppress("DEPRECATION")
    private fun attachmentFrom(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        Intent.ACTION_VIEW -> intent.data
        else -> null
    }
}

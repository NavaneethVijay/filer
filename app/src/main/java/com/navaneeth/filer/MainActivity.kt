package com.navaneeth.filer

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.navaneeth.filer.ui.navigation.FilerNavGraph
import com.navaneeth.filer.ui.theme.FilerTheme
import com.navaneeth.filer.data.preferences.PreferencesManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_NAVIGATE_TO = "extra_navigate_to"
        const val DEST_INDEX_STATUS = "index_status"
        val navTargetFlow = MutableStateFlow<String?>(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request notification permission on Android 13+ so persistent indexing progress displays
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        intent?.getStringExtra(EXTRA_NAVIGATE_TO)?.let { target ->
            navTargetFlow.value = target
        }

        setContent {
            val prefs = remember { PreferencesManager(applicationContext) }
            val phoneColors by prefs.phoneColorsFlow.collectAsState(initial = prefs.usePhoneColors)
            FilerTheme(dynamicColor = phoneColors) {
                val navController = rememberNavController()
                FilerNavGraph(navController = navController)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_NAVIGATE_TO)?.let { target ->
            navTargetFlow.value = target
        }
    }
}

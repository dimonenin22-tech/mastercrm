package com.mastercrm

import android.content.Context
import android.content.Intent
import com.mastercrm.data.service.BookingSyncService
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.mastercrm.ui.navigation.Screen
import com.mastercrm.ui.screens.*
import com.mastercrm.ui.theme.MasterCRMTheme
import com.mastercrm.ui.theme.RosePrimary
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class BottomNavItem(
    val screen: Screen,
    val icon: ImageVector,
    val label: String
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MasterCRMTheme {
                val app = MasterCrmApp.instance
                val snackbarHostState = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()

                var currentScreen by remember {
                    mutableStateOf(if (app.authRepository.isLoggedIn()) Screen.Calendar else Screen.Login)
                }

                // Listen to auth revoked events (401 / 403)
                LaunchedEffect(Unit) {
                    app.authRevokedEvents.collectLatest {
                        currentScreen = Screen.Login
                        snackbarHostState.showSnackbar("Сесію завершено. Будь ласка, увійдіть знову.")
                    }
                }

                // Listen to sync and conflict notifications
                LaunchedEffect(Unit) {
                    app.syncRepository.syncEvents.collectLatest { message ->
                        snackbarHostState.showSnackbar(message)
                    }
                }

                // Request POST_NOTIFICATIONS permission on Android 13+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val permissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission()
                    ) { }
                    LaunchedEffect(Unit) {
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }

                // Perform background sync periodically while logged in
                LaunchedEffect(currentScreen) {
                    if (app.authRepository.isLoggedIn()) {
                        try {
                            val syncServiceIntent = Intent(this@MainActivity, BookingSyncService::class.java)
                            ContextCompat.startForegroundService(this@MainActivity, syncServiceIntent)
                        } catch (e: Exception) {
                            // Safe fallback
                        }

                        // Check & request battery optimization exemption
                        try {
                            val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                                val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = android.net.Uri.parse("package:$packageName")
                                }
                                startActivity(intent)
                            }
                        } catch (_: Exception) {}

                        while (true) {
                            app.syncRepository.performSync()
                            delay(30_000L)
                        }
                    }
                }

                val navItems = listOf(
                    BottomNavItem(Screen.Calendar, Icons.Default.CalendarToday, "Розклад"),
                    BottomNavItem(Screen.Clients, Icons.Default.People, "Клієнти"),
                    BottomNavItem(Screen.Finance, Icons.Default.AccountBalanceWallet, "Фінанси"),
                    BottomNavItem(Screen.Marketing, Icons.Default.AutoAwesome, "Маркетинг"),
                    BottomNavItem(Screen.Settings, Icons.Default.Settings, "Студія")
                )

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    bottomBar = {
                        if (currentScreen != Screen.Login) {
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surface
                            ) {
                                navItems.forEach { item ->
                                    val isSelected = currentScreen.route == item.screen.route
                                    NavigationBarItem(
                                        selected = isSelected,
                                        onClick = { currentScreen = item.screen },
                                        icon = {
                                            Icon(
                                                imageVector = item.icon,
                                                contentDescription = item.label,
                                                tint = if (isSelected) RosePrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        },
                                        label = {
                                            Text(
                                                text = item.label,
                                                maxLines = 1,
                                                softWrap = false,
                                                fontSize = 10.5.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                ) { padding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        when (currentScreen) {
                            Screen.Login -> LoginScreen(
                                onLoginSuccess = { currentScreen = Screen.Calendar }
                            )
                            Screen.Calendar -> CalendarScreen()
                            Screen.Clients -> ClientsScreen()
                            Screen.Finance -> FinanceScreen()
                            Screen.Marketing -> MarketingScreen()
                            Screen.Settings -> SettingsScreen(
                                onLogout = { currentScreen = Screen.Login }
                            )
                        }
                    }
                }
            }
        }
    }
}

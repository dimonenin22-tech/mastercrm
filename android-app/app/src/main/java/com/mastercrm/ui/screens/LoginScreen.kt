package com.mastercrm.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mastercrm.MasterCrmApp
import com.mastercrm.ui.theme.RosePrimary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit
) {
    val app = MasterCrmApp.instance
    val scope = rememberCoroutineScope()

    var otpCode by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showServerUrlDialog by remember { mutableStateOf(false) }
    var serverUrlInput by remember { mutableStateOf(app.authRepository.getServerBaseUrl()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Вхід для майстра", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { showServerUrlDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Налаштування сервера",
                            tint = Color.Gray
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = RosePrimary,
                        modifier = Modifier.size(56.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "MasterCRM v2.0",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Введіть 6-значний OTP-код із Telegram бота (команда /login):",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = Color.Gray
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    OutlinedTextField(
                        value = otpCode,
                        onValueChange = { if (it.length <= 6) otpCode = it },
                        label = { Text("6-значний код") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            letterSpacing = 6.sp
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = errorMessage!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = {
                            if (otpCode.length != 6) {
                                errorMessage = "Введіть рівно 6 цифр коду"
                                return@Button
                            }
                            isLoading = true
                            errorMessage = null

                            scope.launch {
                                try {
                                    val api = app.apiClient.getApiService()
                                    val response = api.exchangeOtpCode(
                                        mapOf(
                                            "otp_code" to otpCode.trim(),
                                            "device_name" to "${Build.MANUFACTURER} ${Build.MODEL}"
                                        )
                                    )

                                    if (response.success && response.data != null) {
                                        val data = response.data
                                        app.authRepository.saveAuth(
                                            deviceToken = data.deviceToken,
                                            masterId = data.masterId,
                                            masterName = data.masterName,
                                            role = data.role
                                        )
                                        // Trigger initial sync
                                        app.syncRepository.performSync()
                                        onLoginSuccess()
                                    } else {
                                        errorMessage = response.error?.message ?: "Невірний код авторизації"
                                    }
                                } catch (e: Exception) {
                                    errorMessage = "Помилка зв'язку: ${e.localizedMessage ?: "Сервер недоступний"}"
                                } finally {
                                    isLoading = false
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = RosePrimary),
                        enabled = !isLoading && otpCode.length == 6
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                        } else {
                            Text("Увійти", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (showServerUrlDialog) {
        AlertDialog(
            onDismissRequest = { showServerUrlDialog = false },
            title = { Text("Адреса сервера") },
            text = {
                Column {
                    Text("Вкажіть URL бекенду без необхідності перезапису APK:")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = serverUrlInput,
                        onValueChange = { serverUrlInput = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    app.authRepository.setServerBaseUrl(serverUrlInput.trim())
                    showServerUrlDialog = false
                }) {
                    Text("Зберегти")
                }
            },
            dismissButton = {
                TextButton(onClick = { showServerUrlDialog = false }) {
                    Text("Скасувати")
                }
            }
        )
    }
}

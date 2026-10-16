package com.mastercrm.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mastercrm.MasterCrmApp
import com.mastercrm.data.local.entity.ClientEntity
import com.mastercrm.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun normalizeUkrainianPhone(rawPhone: String): String {
    val digitsOnly = rawPhone.replace(Regex("[^0-9]"), "")
    return when {
        digitsOnly.startsWith("380") && digitsOnly.length == 12 -> digitsOnly
        digitsOnly.startsWith("0") && digitsOnly.length == 10 -> "38$digitsOnly"
        digitsOnly.length == 9 -> "380$digitsOnly"
        else -> digitsOnly
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientsScreen() {
    val app = MasterCrmApp.instance
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var searchQuery by remember { mutableStateOf("") }

    val clients by remember(searchQuery) {
        if (searchQuery.isBlank()) {
            app.database.clientDao().getAllClientsFlow()
        } else {
            app.database.clientDao().searchClientsFlow(searchQuery.trim())
        }
    }.collectAsState(initial = emptyList())

    // 800ms Debounce tracker for WhatsApp button
    var lastWhatsAppClickTime by remember { mutableStateOf(0L) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Клієнтська база", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search Input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Пошук за ім'ям або телефоном...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Очистити")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (clients.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (searchQuery.isBlank()) "Клієнтів поки немає." else "Нічого не знайдено за запитом.",
                        color = Color.Gray
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(clients, key = { it.id }) { client ->
                        val isVip = client.totalVisits >= 5 || client.totalSpent >= 3000.0

                        ClientCard(
                            client = client,
                            isVip = isVip,
                            onResetRemoval = {
                                scope.launch {
                                    // Optimistic Room update
                                    val updated = client.copy(visitsSinceLastRemoval = 0)
                                    app.database.clientDao().insertOrUpdate(updated)

                                    // Queue mutation
                                    app.syncRepository.queueMutation(
                                        action = "client_reset_removal_counter",
                                        payload = mapOf("client_id" to client.id)
                                    )
                                }
                            },
                            onWhatsAppClick = {
                                val now = System.currentTimeMillis()
                                if (now - lastWhatsAppClickTime > 800) {
                                    lastWhatsAppClickTime = now
                                    val normalized = normalizeUkrainianPhone(client.phone)
                                    val text = Uri.encode("Привіт, ${client.firstName}! 💅 Нагадуємо про візит до MasterCRM або час оновити манікюр!")
                                    val intent = Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://wa.me/$normalized?text=$text")
                                    )
                                    try {
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        // WhatsApp not installed fallback
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ClientCard(
    client: ClientEntity,
    isVip: Boolean,
    onResetRemoval: () -> Unit,
    onWhatsAppClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${client.firstName} ${client.lastName ?: ""}".trim(),
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                    if (isVip) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(Color(0xFFFFD700), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "VIP ⭐",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                Text(
                    text = "LTV: ${client.totalSpent.toInt()} грн",
                    fontWeight = FontWeight.SemiBold,
                    color = RosePrimary,
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "📞 ${client.phone}", color = Color.Gray, fontSize = 13.sp)

            Spacer(modifier = Modifier.height(8.dp))

            // Removal counter badge & Reset button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val alertColor = if (client.visitsSinceLastRemoval >= 3) Color(0xFFD32F2F) else Color(0xFF388E3C)
                    Box(
                        modifier = Modifier
                            .background(alertColor.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Без зняття: ${client.visitsSinceLastRemoval} візитів",
                            color = alertColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }

                    if (client.visitsSinceLastRemoval >= 3) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "⚠️ Пора зняти!", color = Color(0xFFD32F2F), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (client.visitsSinceLastRemoval > 0) {
                    TextButton(onClick = onResetRemoval, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        Text("Скинути зняття", fontSize = 11.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Divider(color = DividerColor)
            Spacer(modifier = Modifier.height(8.dp))

            // Action row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Візитів: ${client.totalVisits}",
                    fontSize = 12.sp,
                    color = Color.Gray
                )

                Button(
                    onClick = onWhatsAppClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Chat,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Нагадати у WhatsApp", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

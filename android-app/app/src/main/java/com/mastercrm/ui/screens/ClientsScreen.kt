package com.mastercrm.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.mastercrm.data.local.entity.AppointmentEntity
import com.mastercrm.data.local.entity.ClientEntity
import com.mastercrm.data.local.entity.VisitBriefEntity
import com.mastercrm.ui.theme.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

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
    var selectedClientForDetail by remember { mutableStateOf<ClientEntity?>(null) }
    var selectedClientForBrief by remember { mutableStateOf<ClientEntity?>(null) }

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
                            onClick = { selectedClientForDetail = client },
                            onResetRemoval = {
                                scope.launch {
                                    val updated = client.copy(visitsSinceLastRemoval = 0)
                                    app.database.clientDao().insertOrUpdate(updated)
                                    app.syncRepository.queueMutation(
                                        action = "client_reset_removal_counter",
                                        payload = mapOf("client_id" to client.id)
                                    )
                                    Toast.makeText(context, "Лічильник зняття скинуто!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onToggleBlacklist = {
                                scope.launch {
                                    val updated = client.copy(isBlacklisted = !client.isBlacklisted)
                                    app.database.clientDao().insertOrUpdate(updated)
                                    val statusMsg = if (updated.isBlacklisted) "Клієнта додано в чорний список" else "Клієнта вилучено з чорного списку"
                                    Toast.makeText(context, statusMsg, Toast.LENGTH_SHORT).show()
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
                                        Toast.makeText(context, "WhatsApp не встановлено", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // Modal Sheet for Client Details & Diagnostic Brief History
    selectedClientForDetail?.let { client ->
        ClientDetailSheet(
            client = client,
            onDismiss = { selectedClientForDetail = null },
            onOpenNewBrief = {
                selectedClientForBrief = client
            },
            onResetRemoval = {
                scope.launch {
                    val updated = client.copy(visitsSinceLastRemoval = 0)
                    app.database.clientDao().insertOrUpdate(updated)
                    selectedClientForDetail = updated
                    app.syncRepository.queueMutation(
                        action = "client_reset_removal_counter",
                        payload = mapOf("client_id" to client.id)
                    )
                    Toast.makeText(context, "Лічильник зняття скинуто!", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // Visit Brief Dialog
    selectedClientForBrief?.let { client ->
        VisitBriefDialog(
            appointmentId = "apt-manual-${System.currentTimeMillis()}",
            clientId = client.id,
            onDismiss = { selectedClientForBrief = null },
            onSaved = {
                selectedClientForBrief = null
            }
        )
    }
}

@Composable
fun ClientCard(
    client: ClientEntity,
    isVip: Boolean,
    onClick: () -> Unit,
    onResetRemoval: () -> Unit,
    onToggleBlacklist: () -> Unit,
    onWhatsAppClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${client.firstName} ${client.lastName ?: ""}".trim(),
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                    if (isVip) {
                        Spacer(modifier = Modifier.width(6.dp))
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
                    if (client.discountPercent > 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .background(RosePrimary.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "-${client.discountPercent}%",
                                color = RosePrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "LTV: ${client.totalSpent.toInt()} грн",
                        fontWeight = FontWeight.SemiBold,
                        color = RosePrimary,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = onToggleBlacklist,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = if (client.isBlacklisted) Icons.Default.Block else Icons.Default.CheckCircle,
                            contentDescription = "Чорний список",
                            tint = if (client.isBlacklisted) Color(0xFFD32F2F) else Color.LightGray,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            if (client.isBlacklisted) {
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .background(Color(0xFFD32F2F).copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "⚠️ Чорний список (No-Show: ${client.noShowCount})",
                        color = Color(0xFFD32F2F),
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
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
                        Text("Скинути", fontSize = 11.sp)
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
                    text = "Візитів: ${client.totalVisits} • Деталі 👉",
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
                    Text("WhatsApp", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientDetailSheet(
    client: ClientEntity,
    onDismiss: () -> Unit,
    onOpenNewBrief: () -> Unit,
    onResetRemoval: () -> Unit
) {
    val app = MasterCrmApp.instance
    val appointments by app.database.appointmentDao().getByClientIdFlow(client.id).collectAsState(initial = emptyList())
    val briefs by app.database.visitBriefDao().getByClientIdFlow(client.id).collectAsState(initial = emptyList())
    val latestBrief = briefs.firstOrNull()
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${client.firstName} ${client.lastName ?: ""}".trim(), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                if (client.isBlacklisted) {
                    Text("Чорний список", color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Key Metrics Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("Візитів", color = Color.Gray, fontSize = 11.sp)
                            Text("${client.totalVisits}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("LTV Загалом", color = Color.Gray, fontSize = 11.sp)
                            Text("${client.totalSpent.toInt()} грн", fontWeight = FontWeight.Bold, color = RosePrimary, fontSize = 16.sp)
                        }
                    }
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("Знижка", color = Color.Gray, fontSize = 11.sp)
                            Text("${client.discountPercent}%", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                }

                Text("📞 Телефон: ${client.phone}", fontSize = 13.sp)

                // Removal status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val alertColor = if (client.visitsSinceLastRemoval >= 3) Color(0xFFD32F2F) else Color(0xFF388E3C)
                    Text("Без повного зняття: ${client.visitsSinceLastRemoval} візитів", color = alertColor, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    if (client.visitsSinceLastRemoval > 0) {
                        OutlinedButton(
                            onClick = onResetRemoval,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Скинути", fontSize = 11.sp)
                        }
                    }
                }

                Divider(color = DividerColor)

                // Nail Diagnostic Profile (from latest visit brief)
                Text("Діагностика нігтів (Останній візит):", fontWeight = FontWeight.Bold, fontSize = 14.sp)

                if (latestBrief != null) {
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("• Покриття: ${latestBrief.coatingCondition ?: "Не вказано"}", fontSize = 12.sp)
                            Text("• Пластина: ${latestBrief.nailPlateType ?: "Нормальна"}", fontSize = 12.sp)
                            Text("• Кутикула: ${latestBrief.cuticleType ?: "Нормальна"}", fontSize = 12.sp)
                            Text("• База/гель: ${latestBrief.baseGelUsed ?: "Rubber Base"}", fontSize = 12.sp)
                            Text("• Форма/Дизайн: ${latestBrief.designAndLength ?: "Мигдаль"}", fontSize = 12.sp)
                            if (!latestBrief.healthNotes.isNullOrBlank()) {
                                Text("• Здоров'я: ${latestBrief.healthNotes}", color = Color(0xFFD32F2F), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                            if (!latestBrief.coffeePrefs.isNullOrBlank()) {
                                Text("• Вподобання: ☕️ ${latestBrief.coffeePrefs}", fontSize = 12.sp)
                            }
                        }
                    }
                } else {
                    Text("Діагностику ще не заповнено. Створіть перший бриф візиту.", color = Color.Gray, fontSize = 12.sp)
                }

                Button(
                    onClick = onOpenNewBrief,
                    colors = ButtonDefaults.buttonColors(containerColor = RosePrimary),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.EditNote, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Заповнити новий бриф візиту")
                }

                Divider(color = DividerColor)

                // History of Visits
                Text("Історія записів (${appointments.size}):", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                if (appointments.isEmpty()) {
                    Text("Історія візитів порожня", color = Color.Gray, fontSize = 12.sp)
                } else {
                    appointments.take(5).forEach { apt ->
                        Card(
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("${apt.date} • ${apt.startTime}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text(apt.status, color = if (apt.status == "COMPLETED") StatusCompleted else RosePrimary, fontSize = 11.sp)
                                }
                                Text("${apt.totalPrice.toInt()} грн", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = RosePrimary)) {
                Text("Закрити")
            }
        }
    )
}

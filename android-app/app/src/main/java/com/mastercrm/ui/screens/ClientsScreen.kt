package com.mastercrm.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import com.mastercrm.data.local.entity.AppointmentEntity
import com.mastercrm.data.local.entity.ClientEntity
import com.mastercrm.data.local.entity.VisitBriefEntity
import com.mastercrm.ui.theme.*
import kotlinx.coroutines.launch
import java.util.Locale

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
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(clients, key = { it.id }) { client ->
                        val isVip = client.totalVisits >= 5 || client.totalSpent >= 3000.0

                        ClientCard(
                            client = client,
                            isVip = isVip,
                            onClick = { selectedClientForDetail = client },
                            onOpenBrief = { selectedClientForBrief = client },
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

    // Native Material 3 ModalBottomSheet for Client Details & Diagnostic Brief History
    selectedClientForDetail?.let { client ->
        ClientDetailBottomSheet(
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
            },
            onToggleBlacklist = {
                scope.launch {
                    val updated = client.copy(isBlacklisted = !client.isBlacklisted)
                    app.database.clientDao().insertOrUpdate(updated)
                    selectedClientForDetail = updated
                    val statusMsg = if (updated.isBlacklisted) "Клієнта додано в чорний список" else "Клієнта вилучено з чорного списку"
                    Toast.makeText(context, statusMsg, Toast.LENGTH_SHORT).show()
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
    onOpenBrief: () -> Unit,
    onWhatsAppClick: () -> Unit
) {
    val initials = remember(client.firstName, client.lastName) {
        val f = client.firstName.take(1)
        val l = (client.lastName ?: "").take(1)
        (f + l).uppercase(Locale.getDefault()).ifBlank { "💅" }
    }

    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Top Row: Avatar + Name + Phone + LTV
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Circular Avatar
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .background(RosePrimary.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = initials,
                        fontWeight = FontWeight.Bold,
                        color = RosePrimary,
                        fontSize = 16.sp
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${client.firstName} ${client.lastName ?: ""}".trim(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
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
                    }
                    Text(
                        text = client.phone,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )
                }

                // LTV Badge
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${client.totalSpent.toInt()} ₴",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = RosePrimary
                    )
                    Text(
                        text = "LTV",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
            }

            if (client.isBlacklisted) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .background(Color(0xFFD32F2F).copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "⚠️ Чорний список (No-Show: ${client.noShowCount})",
                        color = Color(0xFFD32F2F),
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Bottom Badges & Action Icons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SuggestionChip(
                        onClick = onClick,
                        label = { Text("Візитів: ${client.totalVisits}", fontSize = 11.sp) }
                    )

                    val isDanger = client.visitsSinceLastRemoval >= 3
                    val removalColor = if (isDanger) Color(0xFFD32F2F) else Color(0xFF388E3C)
                    SuggestionChip(
                        onClick = onClick,
                        label = {
                            Text(
                                text = "Без зняття: ${client.visitsSinceLastRemoval}/3",
                                fontSize = 11.sp,
                                color = removalColor,
                                fontWeight = if (isDanger) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onOpenBrief,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.EditNote,
                            contentDescription = "Бриф",
                            tint = RosePrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = onWhatsAppClick,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Chat,
                            contentDescription = "WhatsApp",
                            tint = Color(0xFF25D366),
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ClientDetailBottomSheet(
    client: ClientEntity,
    onDismiss: () -> Unit,
    onOpenNewBrief: () -> Unit,
    onResetRemoval: () -> Unit,
    onToggleBlacklist: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val app = MasterCrmApp.instance
    val appointments by app.database.appointmentDao().getByClientIdFlow(client.id).collectAsState(initial = emptyList())
    val briefs by app.database.visitBriefDao().getByClientIdFlow(client.id).collectAsState(initial = emptyList())
    val latestBrief = briefs.firstOrNull()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Profile Card
            item {
                ElevatedCard(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "${client.firstName} ${client.lastName ?: ""}".trim(),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "📞 ${client.phone}",
                                    color = Color.Gray,
                                    fontSize = 13.sp
                                )
                            }

                            IconButton(onClick = onToggleBlacklist) {
                                Icon(
                                    imageVector = if (client.isBlacklisted) Icons.Default.Block else Icons.Default.CheckCircle,
                                    contentDescription = "Чорний список",
                                    tint = if (client.isBlacklisted) Color(0xFFD32F2F) else Color.LightGray
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AssistChip(
                                onClick = {},
                                label = { Text("LTV: ${client.totalSpent.toInt()} ₴") }
                            )
                            AssistChip(
                                onClick = {},
                                label = { Text("Візитів: ${client.totalVisits}") }
                            )
                            if (client.discountPercent > 0) {
                                AssistChip(
                                    onClick = {},
                                    label = { Text("Знижка: -${client.discountPercent}%") }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Removal Counter Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val alertColor = if (client.visitsSinceLastRemoval >= 3) Color(0xFFD32F2F) else Color(0xFF388E3C)
                            Text(
                                text = "Без повного зняття: ${client.visitsSinceLastRemoval} візитів",
                                color = alertColor,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                            if (client.visitsSinceLastRemoval > 0) {
                                OutlinedButton(
                                    onClick = onResetRemoval,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                                ) {
                                    Text("Скинути зняття", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }

            // Nail Diagnostics Card (FlowRow of FilterChips)
            item {
                ElevatedCard(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Spa, contentDescription = null, tint = RosePrimary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Діагностика нігтьової пластини", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }

                        if (latestBrief != null) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                FilterChip(
                                    selected = true,
                                    onClick = {},
                                    label = { Text("Покриття: ${latestBrief.coatingCondition ?: "Ідеально"}", fontSize = 11.sp) }
                                )
                                FilterChip(
                                    selected = true,
                                    onClick = {},
                                    label = { Text("Пластина: ${latestBrief.nailPlateType ?: "Нормальна"}", fontSize = 11.sp) }
                                )
                                FilterChip(
                                    selected = true,
                                    onClick = {},
                                    label = { Text("Кутикула: ${latestBrief.cuticleType ?: "Нормальна"}", fontSize = 11.sp) }
                                )
                                FilterChip(
                                    selected = true,
                                    onClick = {},
                                    label = { Text("База/Гель: ${latestBrief.baseGelUsed ?: "Rubber Base"}", fontSize = 11.sp) }
                                )
                                FilterChip(
                                    selected = true,
                                    onClick = {},
                                    label = { Text("Форма: ${latestBrief.designAndLength ?: "Мигдаль"}", fontSize = 11.sp) }
                                )
                            }

                            if (!latestBrief.healthNotes.isNullOrBlank()) {
                                Text(
                                    text = "⚠️ Особливості: ${latestBrief.healthNotes}",
                                    color = Color(0xFFD32F2F),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            if (!latestBrief.coffeePrefs.isNullOrBlank()) {
                                Text(
                                    text = "☕️ Вподобання: ${latestBrief.coffeePrefs}",
                                    color = Color.Gray,
                                    fontSize = 12.sp
                                )
                            }
                        } else {
                            Text("Діагностику ще не заповнено.", color = Color.Gray, fontSize = 12.sp)
                        }

                        Spacer(modifier = Modifier.height(4.dp))

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
                    }
                }
            }

            // History of Appointments Section
            item {
                Text("Історія візитів (${appointments.size})", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }

            if (appointments.isEmpty()) {
                item {
                    Text("Історія записів порожня.", color = Color.Gray, fontSize = 13.sp)
                }
            } else {
                items(appointments, key = { it.id }) { apt ->
                    ElevatedCard(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("${apt.date} • ${apt.startTime}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(apt.status, color = if (apt.status == "COMPLETED") StatusCompleted else RosePrimary, fontSize = 11.sp)
                            }
                            Text("${apt.totalPrice.toInt()} ₴", fontWeight = FontWeight.Bold, color = RosePrimary, fontSize = 15.sp)
                        }
                    }
                }
            }
        }
    }
}

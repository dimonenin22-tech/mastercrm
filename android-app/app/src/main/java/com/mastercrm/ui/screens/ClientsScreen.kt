package com.mastercrm.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.window.Dialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
                    app.syncRepository.queueMutation(
                        action = "client_update",
                        payload = mapOf(
                            "client_id" to client.id,
                            "is_blacklisted" to updated.isBlacklisted
                        )
                    )
                    val statusMsg = if (updated.isBlacklisted) "Клієнта додано в чорний список" else "Клієнта вилучено з чорного списку"
                    Toast.makeText(context, statusMsg, Toast.LENGTH_SHORT).show()
                }
            },
            onUpdateDiscount = { newDiscount ->
                scope.launch {
                    val updated = client.copy(discountPercent = newDiscount)
                    app.database.clientDao().insertOrUpdate(updated)
                    selectedClientForDetail = updated
                    app.syncRepository.queueMutation(
                        action = "client_update",
                        payload = mapOf(
                            "client_id" to client.id,
                            "discount_percent" to newDiscount
                        )
                    )
                    Toast.makeText(context, "Знижку $newDiscount% збережено!", Toast.LENGTH_SHORT).show()
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
    onToggleBlacklist: () -> Unit,
    onUpdateDiscount: (Int) -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val app = MasterCrmApp.instance
    val context = LocalContext.current
    val appointments by app.database.appointmentDao().getByClientIdFlow(client.id).collectAsState(initial = emptyList())
    val briefs by app.database.visitBriefDao().getByClientIdFlow(client.id).collectAsState(initial = emptyList())
    val latestBrief = briefs.firstOrNull()
    val reviews by app.database.reviewDao().getReviewsForClientFlow(client.id).collectAsState(initial = emptyList())
    val allServices by app.database.serviceDao().getAllFlow().collectAsState(initial = emptyList())
    var viewingPhotoUrl by remember { mutableStateOf<String?>(null) }

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
                val isDanger = client.visitsSinceLastRemoval >= 3
                OutlinedCard(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Top Profile Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = client.firstName.take(1).uppercase(),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${client.firstName} ${client.lastName ?: ""}".trim(),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable {
                                        val norm = normalizeUkrainianPhone(client.phone)
                                        if (norm.isNotEmpty()) {
                                            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:+$norm"))
                                            context.startActivity(intent)
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Phone,
                                        contentDescription = "Телефон",
                                        tint = RosePrimary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (client.phone.startsWith("+")) client.phone else "+${client.phone}",
                                        color = RosePrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            if (client.telegramId != null) {
                                Surface(
                                    color = Color(0xFFE3F2FD),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.padding(end = 4.dp)
                                ) {
                                    Text(
                                        "TG ✓",
                                        color = Color(0xFF1976D2),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            IconButton(onClick = onToggleBlacklist) {
                                Icon(
                                    imageVector = if (client.isBlacklisted) Icons.Default.Block else Icons.Default.CheckCircle,
                                    contentDescription = "Чорний список",
                                    tint = if (client.isBlacklisted) Color(0xFFD32F2F) else Color.LightGray
                                )
                            }
                        }

                        // Middle Metrics Row (Two Equal Pills)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        "Усього витрачено (LTV)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.Gray
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        "${client.totalSpent.toInt()} ₴",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        "Кількість візитів",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.Gray
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        "${client.totalVisits}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // Bottom Removal Counter Banner
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isDanger) Color(0xFFFFEBEE) else Color(0xFFE8F5E9),
                            border = BorderStroke(1.dp, if (isDanger) Color(0xFFFFCDD2) else Color(0xFFC8E6C9)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "🔄 Без зняття: ${client.visitsSinceLastRemoval} з 3",
                                    color = if (isDanger) Color(0xFFD32F2F) else Color(0xFF2E7D32),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                                if (client.visitsSinceLastRemoval > 0) {
                                    FilledTonalButton(
                                        onClick = onResetRemoval,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text("Скинути", fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Manual Viber & WhatsApp Template Sender Card
            item {
                val latestApt = appointments.firstOrNull()
                val srvTitle = allServices.find { it.id == latestApt?.serviceId }?.title ?: "манікюр"
                val dateStr = latestApt?.date ?: "найближчим часом"
                val timeStr = latestApt?.startTime ?: "10:00"

                var selectedTemplateType by remember { mutableStateOf("24h") }

                fun buildText(type: String): String {
                    return when (type) {
                        "24h" -> "Привіт, ${client.firstName}! Нагадуємо про ваш запис на завтра $dateStr о $timeStr ($srvTitle). Будемо раді вас бачити у студії JuNail!"
                        "2h" -> "Привіт, ${client.firstName}! Чекаємо на вас сьогодні о $timeStr на $srvTitle у студії JuNail! Якщо виникають запізнення, будь ласка, попередьте."
                        "deposit" -> "Привіт, ${client.firstName}! Для фіксації вашого запису на $dateStr о $timeStr внесіть, будь ласка, завдаток. Посилання на Моно-банку: https://send.monobank.ua/jar/123456 . Дякуємо!"
                        "review" -> "${client.firstName}, дякуємо, що відвідали студію JuNail! ✨ Як вам ваші нігтики? Нам дуже важлива ваша думка!"
                        "2weeks" -> "Привіт, ${client.firstName}! Минуло 2 тижні з вашого візиту на $srvTitle. Як тримається покриття? Будемо раді оновити ваш манікюр!"
                        else -> ""
                    }
                }

                var compiledText by remember(selectedTemplateType, client.firstName, dateStr, timeStr, srvTitle) {
                    mutableStateOf(buildText(selectedTemplateType))
                }

                ElevatedCard(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Send, contentDescription = null, tint = RosePrimary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("📲 Надіслати шаблон у Viber / WhatsApp", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            FilterChip(
                                selected = selectedTemplateType == "24h",
                                onClick = {
                                    selectedTemplateType = "24h"
                                    compiledText = buildText("24h")
                                },
                                label = { Text("🔔 24 год", fontSize = 11.5.sp) }
                            )
                            FilterChip(
                                selected = selectedTemplateType == "2h",
                                onClick = {
                                    selectedTemplateType = "2h"
                                    compiledText = buildText("2h")
                                },
                                label = { Text("⏰ 2 год", fontSize = 11.5.sp) }
                            )
                            FilterChip(
                                selected = selectedTemplateType == "deposit",
                                onClick = {
                                    selectedTemplateType = "deposit"
                                    compiledText = buildText("deposit")
                                },
                                label = { Text("💳 Завдаток", fontSize = 11.5.sp) }
                            )
                            FilterChip(
                                selected = selectedTemplateType == "review",
                                onClick = {
                                    selectedTemplateType = "review"
                                    compiledText = buildText("review")
                                },
                                label = { Text("💖 Відгук", fontSize = 11.5.sp) }
                            )
                            FilterChip(
                                selected = selectedTemplateType == "2weeks",
                                onClick = {
                                    selectedTemplateType = "2weeks"
                                    compiledText = buildText("2weeks")
                                },
                                label = { Text("💅 2 тижні", fontSize = 11.5.sp) }
                            )
                        }

                        OutlinedTextField(
                            value = compiledText,
                            onValueChange = { compiledText = it },
                            label = { Text("Текст повідомлення") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 6
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    val norm = normalizeUkrainianPhone(client.phone)
                                    if (norm.isNotBlank()) {
                                        val waUrl = "https://wa.me/$norm?text=${Uri.encode(compiledText)}"
                                        val waIntent = Intent(Intent.ACTION_VIEW, Uri.parse(waUrl))
                                        try {
                                            context.startActivity(waIntent)
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Не вдалося відкрити WhatsApp", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        Toast.makeText(context, "Некоректний номер телефону", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("🟢 WhatsApp", fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = {
                                    val norm = normalizeUkrainianPhone(client.phone)
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Viber Message", compiledText))
                                    Toast.makeText(context, "Текст скопійовано для Viber", Toast.LENGTH_SHORT).show()

                                    if (norm.isNotBlank()) {
                                        try {
                                            val viberIntent = Intent(Intent.ACTION_VIEW, Uri.parse("viber://chat?number=%2B$norm"))
                                            context.startActivity(viberIntent)
                                        } catch (e: Exception) {
                                            try {
                                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_TEXT, compiledText)
                                                    setPackage("com.viber.voip")
                                                }
                                                context.startActivity(sendIntent)
                                            } catch (ex: Exception) {
                                                Toast.makeText(context, "Viber не знайдено на пристрої", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7360F2)),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("🟣 Viber", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Personal Discount Selector
            item {
                ElevatedCard(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Percent, contentDescription = null, tint = RosePrimary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Персональна знижка клієнта", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(0, 5, 10, 15).forEach { d ->
                                FilterChip(
                                    selected = client.discountPercent == d,
                                    onClick = { onUpdateDiscount(d) },
                                    label = { Text(if (d == 0) "0%" else "$d%") }
                                )
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
                            if (!latestBrief.photoBeforeUrl.isNullOrBlank() || !latestBrief.photoAfterUrl.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    if (!latestBrief.photoBeforeUrl.isNullOrBlank()) {
                                        val beforeBmp = remember(latestBrief.photoBeforeUrl) { decodeBase64ToBitmap(latestBrief.photoBeforeUrl) }
                                        if (beforeBmp != null) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text("Фото До", fontSize = 11.sp, color = Color.Gray)
                                                Image(
                                                    bitmap = beforeBmp.asImageBitmap(),
                                                    contentDescription = "Фото До",
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .size(72.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .clickable { viewingPhotoUrl = latestBrief.photoBeforeUrl }
                                                )
                                            }
                                        }
                                    }
                                    if (!latestBrief.photoAfterUrl.isNullOrBlank()) {
                                        val afterBmp = remember(latestBrief.photoAfterUrl) { decodeBase64ToBitmap(latestBrief.photoAfterUrl) }
                                        if (afterBmp != null) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text("Фото Після", fontSize = 11.sp, color = Color.Gray)
                                                Image(
                                                    bitmap = afterBmp.asImageBitmap(),
                                                    contentDescription = "Фото Після",
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .size(72.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .clickable { viewingPhotoUrl = latestBrief.photoAfterUrl }
                                                )
                                            }
                                        }
                                    }
                                }
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
                    val aptBrief = briefs.find { it.appointmentId == apt.id }
                    ElevatedCard(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("${apt.date} • ${apt.startTime}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(apt.status, color = if (apt.status == "COMPLETED") StatusCompleted else RosePrimary, fontSize = 11.sp)
                                }
                                Text("${apt.totalPrice.toInt()} ₴", fontWeight = FontWeight.Bold, color = RosePrimary, fontSize = 15.sp)
                            }
                            if (aptBrief != null && (!aptBrief.photoBeforeUrl.isNullOrBlank() || !aptBrief.photoAfterUrl.isNullOrBlank())) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (!aptBrief.photoBeforeUrl.isNullOrBlank()) {
                                        val bBmp = remember(aptBrief.photoBeforeUrl) { decodeBase64ToBitmap(aptBrief.photoBeforeUrl) }
                                        if (bBmp != null) {
                                            Image(
                                                bitmap = bBmp.asImageBitmap(),
                                                contentDescription = "Фото До",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .size(60.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable { viewingPhotoUrl = aptBrief.photoBeforeUrl }
                                            )
                                        }
                                    }
                                    if (!aptBrief.photoAfterUrl.isNullOrBlank()) {
                                        val aBmp = remember(aptBrief.photoAfterUrl) { decodeBase64ToBitmap(aptBrief.photoAfterUrl) }
                                        if (aBmp != null) {
                                            Image(
                                                bitmap = aBmp.asImageBitmap(),
                                                contentDescription = "Фото Після",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .size(60.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable { viewingPhotoUrl = aptBrief.photoAfterUrl }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Reviews & Wear Reports Section
            item {
                Text("⭐ Відгуки та стан покриття (${reviews.size})", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }

            if (reviews.isEmpty()) {
                item {
                    Text("Відгуків від клієнта поки немає.", color = Color.Gray, fontSize = 13.sp)
                }
            } else {
                items(reviews, key = { it.id }) { rev ->
                    ElevatedCard(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val isWear = rev.reviewType == "WEAR_2WEEKS"
                                Text(
                                    if (isWear) "✨ Через 2 тижні (Носка)" else "💅 Після візиту",
                                    fontSize = 11.sp,
                                    color = RosePrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text("⭐".repeat(rev.rating.coerceIn(1, 5)), fontSize = 12.sp)
                            }
                            if (!rev.comment.isNullOrBlank()) {
                                Text("«${rev.comment}»", fontSize = 13.sp)
                            }
                            if (!rev.photoUrl.isNullOrBlank()) {
                                val thumbBmp = remember(rev.photoUrl) { decodeBase64ToBitmap(rev.photoUrl) }
                                if (thumbBmp != null) {
                                    Image(
                                        bitmap = thumbBmp.asImageBitmap(),
                                        contentDescription = "Фото стану покриття",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(72.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { viewingPhotoUrl = rev.photoUrl }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (!viewingPhotoUrl.isNullOrBlank()) {
        val fullBmp = remember(viewingPhotoUrl) { decodeBase64ToBitmap(viewingPhotoUrl) }
        Dialog(onDismissRequest = { viewingPhotoUrl = null }) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (fullBmp != null) {
                        Image(
                            bitmap = fullBmp.asImageBitmap(),
                            contentDescription = "Фото огляду",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 450.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                    } else {
                        Text("Не вдалося відобразити фото", color = Color.Gray)
                    }
                    Button(
                        onClick = { viewingPhotoUrl = null },
                        colors = ButtonDefaults.buttonColors(containerColor = RosePrimary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Закрити")
                    }
                }
            }
        }
    }
}

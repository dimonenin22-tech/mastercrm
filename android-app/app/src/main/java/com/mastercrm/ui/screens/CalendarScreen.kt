package com.mastercrm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mastercrm.MasterCrmApp
import com.mastercrm.data.local.entity.AppointmentEntity
import com.mastercrm.ui.theme.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen() {
    val app = MasterCrmApp.instance
    val scope = rememberCoroutineScope()

    val todayKyiv = remember { LocalDate.now(ZoneId.of("Europe/Kyiv")) }
    var selectedDate by remember { mutableStateOf(LocalDate.now(ZoneId.of("Europe/Kyiv"))) }
    val dateStr = selectedDate.format(DateTimeFormatter.ISO_LOCAL_DATE)

    val appointments by app.database.appointmentDao()
        .getAppointmentsByDateFlow(dateStr)
        .collectAsState(initial = emptyList())

    val allActiveAppointments by app.database.appointmentDao()
        .getAllActiveAppointmentsFlow()
        .collectAsState(initial = emptyList())

    val datesWithAppointments = remember(allActiveAppointments) {
        allActiveAppointments
            .filter { it.status != "CANCELLED_BY_CLIENT" && it.status != "CANCELLED_BY_MASTER" }
            .map { it.date }
            .toSet()
    }

    val clients by app.database.clientDao()
        .getAllClientsFlow()
        .collectAsState(initial = emptyList())

    val services by app.database.serviceDao()
        .getActiveServicesFlow()
        .collectAsState(initial = emptyList())

    var isSyncing by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var selectedAppointmentForBrief by remember { mutableStateOf<AppointmentEntity?>(null) }
    var appointmentToComplete by remember { mutableStateOf<Triple<AppointmentEntity, String, String>?>(null) }

    val clientsMap = remember(clients) { clients.associateBy { it.id } }
    val servicesMap = remember(services) { services.associateBy { it.id } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Розклад", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                isSyncing = true
                                app.syncRepository.performSync(forceFullSync = true)
                                isSyncing = false
                            }
                        }
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Повна синхронізація")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = RosePrimary,
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Add, contentDescription = "Новий запис")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Day selector header (Swipe / Arrows)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = { selectedDate = selectedDate.minusDays(1) }) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "Попередній день")
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { selectedDate = todayKyiv }
                ) {
                    Text(
                        text = selectedDate.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    if (selectedDate == todayKyiv) {
                        Text(
                            text = "Сьогодні",
                            color = RosePrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                IconButton(onClick = { selectedDate = selectedDate.plusDays(1) }) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "Наступний день")
                }
            }

            // Горизонтальна смужка дат із крапками-індикаторами
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                (-3..3).forEach { offset ->
                    val day = selectedDate.plusDays(offset.toLong())
                    val dayStr = day.format(DateTimeFormatter.ISO_LOCAL_DATE)
                    val isSelected = offset == 0
                    val hasApts = datesWithAppointments.contains(dayStr)
                    val isToday = day == todayKyiv

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .background(
                                color = if (isSelected) RosePrimary else if (isToday) RosePrimary.copy(alpha = 0.1f) else Color.Transparent,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { selectedDate = day }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = day.format(DateTimeFormatter.ofPattern("EEE")),
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = day.dayOfMonth.toString(),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                        )
                        if (hasApts) {
                            Text(text = "●", fontSize = 8.sp, color = if (isSelected) Color.White else RosePrimary)
                        } else {
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                    }
                }
            }

            Divider(color = DividerColor)

            if (appointments.isEmpty()) {
                val todayStr = todayKyiv.format(DateTimeFormatter.ISO_LOCAL_DATE)
                val upcomingApts = remember(allActiveAppointments, dateStr, todayStr) {
                    allActiveAppointments
                        .filter { !it.deleted && it.status != "CANCELLED_BY_CLIENT" && it.status != "CANCELLED_BY_MASTER" && it.date >= todayStr }
                        .take(5)
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                    ) {
                        Text(
                            text = "На обрану дату ($dateStr) записів немає.",
                            color = Color.Gray,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        if (upcomingApts.isNotEmpty()) {
                            Text(
                                text = "📌 Найближчі активні записи:",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            upcomingApts.forEach { upcoming ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            try { selectedDate = LocalDate.parse(upcoming.date) } catch (_: Exception) {}
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(text = "📅 ${upcoming.date} о ${upcoming.startTime}", fontWeight = FontWeight.Bold, color = RosePrimary)
                                            Text(text = "Послуга: ${servicesMap[upcoming.serviceId]?.title ?: "Манікюр"}", fontSize = 13.sp)
                                            Text(text = "Клієнт: ${clientsMap[upcoming.clientId]?.firstName ?: "Клієнт"}", fontSize = 12.sp, color = Color.Gray)
                                        }
                                        Button(
                                            onClick = { try { selectedDate = LocalDate.parse(upcoming.date) } catch (_: Exception) {} },
                                            colors = ButtonDefaults.buttonColors(containerColor = RosePrimary)
                                        ) {
                                            Text("Перейти", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    isSyncing = true
                                    app.syncRepository.performSync(forceFullSync = true)
                                    isSyncing = false
                                }
                            }
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Повна синхронізація з сервером")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(appointments, key = { it.id }) { apt ->
                        val client = clientsMap[apt.clientId]
                        val service = servicesMap[apt.serviceId]

                        AppointmentCard(
                            appointment = apt,
                            clientName = client?.firstName ?: "Клієнт",
                            clientPhone = client?.phone ?: "",
                            serviceTitle = service?.title ?: "Послуга",
                            onStatusChange = { newStatus ->
                                scope.launch {
                                    // 1. Optimistic update in Room
                                    val updatedApt = apt.copy(status = newStatus)
                                    app.database.appointmentDao().insertOrUpdate(updatedApt)

                                    // 2. Queue mutation
                                    app.syncRepository.queueMutation(
                                        action = "appointment_update_status",
                                        payload = mapOf(
                                            "appointment_id" to apt.id,
                                            "status" to newStatus
                                        )
                                    )
                                }
                            },
                            onCompleteRequest = {
                                appointmentToComplete = Triple(
                                    apt,
                                    client?.firstName ?: "Клієнт",
                                    service?.title ?: "Послуга"
                                )
                            },
                            onOpenBrief = {
                                selectedAppointmentForBrief = apt
                            }
                        )
                    }
                }
            }
        }
    }

    // Dialog for Manual Appointment Creation
    if (showCreateDialog) {
        CreateAppointmentDialog(
            selectedDate = dateStr,
            services = services,
            onDismiss = { showCreateDialog = false },
            onConfirm = { clientName, clientPhone, srvId, startTime, price ->
                scope.launch {
                    val aptId = UUID.randomUUID().toString()
                    val masterId = app.authRepository.getMasterId() ?: "m-01"

                    // Optimistic local appointment
                    val newEntity = AppointmentEntity(
                        id = aptId,
                        masterId = masterId,
                        clientId = "",
                        serviceId = srvId,
                        date = dateStr,
                        startTime = startTime,
                        endTime = startTime,
                        status = "CONFIRMED",
                        totalPrice = price,
                        depositAmount = 0.0,
                        isDepositPaid = false,
                        cancellationReason = "",
                        deleted = false,
                        version = 1,
                        updatedAt = ""
                    )
                    app.database.appointmentDao().insertOrUpdate(newEntity)

                    // Queue mutation to backend
                    app.syncRepository.queueMutation(
                        action = "appointment_create",
                        payload = mapOf(
                            "master_id" to masterId,
                            "service_id" to srvId,
                            "client_name" to clientName,
                            "client_phone" to clientPhone,
                            "date" to dateStr,
                            "start_time" to startTime,
                            "total_price" to price,
                            "status" to "CONFIRMED"
                        )
                    )
                    showCreateDialog = false
                }
            }
        )
    }

    // Dialog for VisitBrief
    selectedAppointmentForBrief?.let { apt ->
        VisitBriefDialog(
            appointmentId = apt.id,
            clientId = apt.clientId,
            onDismiss = { selectedAppointmentForBrief = null },
            onSaved = { selectedAppointmentForBrief = null }
        )
    }

    // Confirmation Dialog before completing appointment
    appointmentToComplete?.let { (apt, cName, sTitle) ->
        AlertDialog(
            onDismissRequest = { appointmentToComplete = null },
            title = { Text("Завершити візит клієнта?") },
            text = {
                Text("Ви дійсно виконали роботу для клієнта $cName ($sTitle)? Цю дію можна виконати лише 1 раз. Одразу після підтвердження відкриється анкета-дебриф по стану нігтів клієнта.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val updatedApt = apt.copy(status = "COMPLETED")
                            app.database.appointmentDao().insertOrUpdate(updatedApt)
                            app.syncRepository.queueMutation(
                                action = "appointment_update_status",
                                payload = mapOf(
                                    "appointment_id" to apt.id,
                                    "status" to "COMPLETED"
                                )
                            )
                            val targetApt = apt
                            appointmentToComplete = null
                            selectedAppointmentForBrief = targetApt
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusCompleted)
                ) {
                    Text("✅ Так, виконано — заповнити бриф")
                }
            },
            dismissButton = {
                TextButton(onClick = { appointmentToComplete = null }) {
                    Text("Скасувати")
                }
            }
        )
    }
}

@Composable
fun AppointmentCard(
    appointment: AppointmentEntity,
    clientName: String,
    clientPhone: String,
    serviceTitle: String,
    onStatusChange: (String) -> Unit,
    onCompleteRequest: () -> Unit,
    onOpenBrief: () -> Unit
) {
    val statusColor = when (appointment.status) {
        "CONFIRMED" -> StatusConfirmed
        "COMPLETED" -> StatusCompleted
        "CANCELLED_BY_CLIENT", "CANCELLED_BY_MASTER" -> StatusCancelled
        else -> StatusPending
    }

    val statusLabel = when (appointment.status) {
        "CONFIRMED" -> "Підтверджено"
        "COMPLETED" -> "Виконано"
        "CANCELLED_BY_CLIENT", "CANCELLED_BY_MASTER" -> "Скасовано"
        else -> "Очікує"
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${appointment.startTime} - ${appointment.endTime}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Box(
                    modifier = Modifier
                        .background(statusColor.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = statusLabel,
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(text = serviceTitle, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(text = "$clientName ($clientPhone)", color = Color.Gray, fontSize = 13.sp)
            Text(text = "${appointment.totalPrice.toInt()} грн", fontWeight = FontWeight.Bold, color = RosePrimary, fontSize = 14.sp)

            Spacer(modifier = Modifier.height(12.dp))
            Divider(color = DividerColor)
            Spacer(modifier = Modifier.height(8.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (appointment.status) {
                    "PENDING" -> {
                        TextButton(onClick = onOpenBrief) {
                            Icon(Icons.Default.EditNote, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Бриф", fontSize = 13.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = { onStatusChange("CONFIRMED") },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("✅ Підтвердити", fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = { onStatusChange("CANCELLED_BY_MASTER") },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("❌ Відхилити", fontSize = 12.sp, color = StatusCancelled)
                            }
                        }
                    }
                    "CONFIRMED" -> {
                        TextButton(onClick = onOpenBrief) {
                            Icon(Icons.Default.EditNote, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Бриф", fontSize = 13.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onCompleteRequest,
                                colors = ButtonDefaults.buttonColors(containerColor = StatusCompleted),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("✨ Виконано", fontSize = 12.sp)
                            }
                            IconButton(onClick = { onStatusChange("CANCELLED_BY_MASTER") }) {
                                Icon(Icons.Default.Cancel, contentDescription = "Скасувати", tint = StatusCancelled)
                            }
                        }
                    }
                    "COMPLETED" -> {
                        Surface(
                            color = Color(0xFFE8F5E9),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                "✅ Роботу виконано",
                                color = Color(0xFF2E7D32),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                        FilledTonalButton(
                            onClick = onOpenBrief,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.EditNote, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("📋 Редагувати бриф", fontSize = 12.sp)
                        }
                    }
                    else -> {
                        Surface(
                            color = Color(0xFFFFEBEE),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                "❌ Скасовано",
                                color = StatusCancelled,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        TextButton(onClick = onOpenBrief) {
                            Icon(Icons.Default.EditNote, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Бриф", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CreateAppointmentDialog(
    selectedDate: String,
    services: List<com.mastercrm.data.local.entity.ServiceEntity>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, phone: String, srvId: String, time: String, price: Double) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("10:00") }
    var selectedServiceId by remember { mutableStateOf(services.firstOrNull()?.id ?: "") }
    var priceText by remember { mutableStateOf(services.firstOrNull()?.price?.toString() ?: "500") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новий запис ($selectedDate)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Ім'я клієнта") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Телефон (+380...)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = time,
                    onValueChange = { time = it },
                    label = { Text("Час початку (HH:MM)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { priceText = it },
                    label = { Text("Вартість (грн)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val p = priceText.toDoubleOrNull() ?: 500.0
                    onConfirm(name, phone, selectedServiceId, time, p)
                },
                colors = ButtonDefaults.buttonColors(containerColor = RosePrimary)
            ) {
                Text("Створити")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Скасувати") }
        }
    )
}


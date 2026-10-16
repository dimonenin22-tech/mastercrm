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
import java.time.format.DateTimeFormatter
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen() {
    val app = MasterCrmApp.instance
    val scope = rememberCoroutineScope()

    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    val dateStr = selectedDate.format(DateTimeFormatter.ISO_LOCAL_DATE)

    val appointments by app.database.appointmentDao()
        .getAppointmentsByDateFlow(dateStr)
        .collectAsState(initial = emptyList())

    val clients by app.database.clientDao()
        .getAllClientsFlow()
        .collectAsState(initial = emptyList())

    val services by app.database.serviceDao()
        .getActiveServicesFlow()
        .collectAsState(initial = emptyList())

    var isSyncing by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var selectedAppointmentForBrief by remember { mutableStateOf<AppointmentEntity?>(null) }

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
                                app.syncRepository.performSync()
                                isSyncing = false
                            }
                        }
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Синхронізація")
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
                    modifier = Modifier.clickable { selectedDate = LocalDate.now() }
                ) {
                    Text(
                        text = selectedDate.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    if (selectedDate == LocalDate.now()) {
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

            Divider(color = DividerColor)

            if (appointments.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "На цей день немає записів.\nНатисніть '+' для створення.",
                        color = Color.Gray,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
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
            onSave = { notes, condition, coffee ->
                scope.launch {
                    app.syncRepository.queueMutation(
                        action = "visit_brief_save",
                        payload = mapOf(
                            "appointment_id" to apt.id,
                            "client_id" to apt.clientId,
                            "material_notes" to notes,
                            "nail_condition" to condition,
                            "coffee_prefs" to coffee
                        )
                    )
                    selectedAppointmentForBrief = null
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
    onOpenBrief: () -> Unit
) {
    val statusColor = when (appointment.status) {
        "CONFIRMED" -> StatusConfirmed
        "COMPLETED" -> StatusCompleted
        "CANCELLED_BY_CLIENT", "CANCELLED_BY_MASTER" -> StatusCancelled
        else -> StatusPending
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
                        text = appointment.status,
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
                TextButton(onClick = onOpenBrief) {
                    Icon(Icons.Default.EditNote, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Бриф", fontSize = 13.sp)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (appointment.status != "COMPLETED") {
                        IconButton(onClick = { onStatusChange("COMPLETED") }) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "Завершити", tint = StatusCompleted)
                        }
                    }
                    if (appointment.status != "CONFIRMED") {
                        IconButton(onClick = { onStatusChange("CONFIRMED") }) {
                            Icon(Icons.Default.ThumbUp, contentDescription = "Підтвердити", tint = StatusConfirmed)
                        }
                    }
                    if (!appointment.status.startsWith("CANCELLED")) {
                        IconButton(onClick = { onStatusChange("CANCELLED_BY_MASTER") }) {
                            Icon(Icons.Default.Cancel, contentDescription = "Скасувати", tint = StatusCancelled)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisitBriefDialog(
    appointmentId: String,
    clientId: String,
    onDismiss: () -> Unit,
    onSave: (notes: String, condition: String, coffee: String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var materialNotes by remember { mutableStateOf("") }
    var nailCondition by remember { mutableStateOf("") }
    var coffeePrefs by remember { mutableStateOf("") }
    var attachedPhotoType by remember { mutableStateOf("AFTER") }
    var photoUploadStatus by remember { mutableStateOf<String?>(null) }
    var isUploadingPhoto by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Бриф візиту клієнта") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = materialNotes,
                    onValueChange = { materialNotes = it },
                    label = { Text("Матеріали (база, колір, топ)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = nailCondition,
                    onValueChange = { nailCondition = it },
                    label = { Text("Стан нігтів / особливості") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = coffeePrefs,
                    onValueChange = { coffeePrefs = it },
                    label = { Text("Вподобання (кава, чай, вода)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(4.dp))
                Text("Фото процедури:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = attachedPhotoType == "BEFORE",
                        onClick = { attachedPhotoType = "BEFORE" },
                        label = { Text("Фото До") }
                    )
                    FilterChip(
                        selected = attachedPhotoType == "AFTER",
                        onClick = { attachedPhotoType = "AFTER" },
                        label = { Text("Фото Після") }
                    )
                }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            try {
                                isUploadingPhoto = true
                                val api = MasterCrmApp.instance.apiClient.getApiService()
                                val resp = api.uploadPhoto(
                                    mapOf(
                                        "client_id" to clientId,
                                        "appointment_id" to appointmentId,
                                        "photo_type" to attachedPhotoType,
                                        "filename" to "photo_${attachedPhotoType.lowercase()}_${appointmentId.take(8)}.jpg"
                                    )
                                )
                                if (resp.success) {
                                    photoUploadStatus = "Фото $attachedPhotoType збережено!"
                                }
                            } catch (e: Exception) {
                                photoUploadStatus = "Помилка додавання фото"
                            } finally {
                                isUploadingPhoto = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isUploadingPhoto
                ) {
                    Icon(Icons.Default.AddAPhoto, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isUploadingPhoto) "Завантаження..." else "Прикріпити фото $attachedPhotoType")
                }

                photoUploadStatus?.let { status ->
                    Text(status, color = RosePrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(materialNotes, nailCondition, coffeePrefs) },
                colors = ButtonDefaults.buttonColors(containerColor = RosePrimary)
            ) {
                Text("Зберегти")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Закрити") }
        }
    )
}

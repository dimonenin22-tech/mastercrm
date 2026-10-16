package com.mastercrm.ui.screens

import android.widget.Toast
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mastercrm.MasterCrmApp
import com.mastercrm.data.local.entity.MasterEntity
import com.mastercrm.data.local.entity.ServiceEntity
import com.mastercrm.data.remote.dto.ScheduleDto
import com.mastercrm.data.remote.dto.ScheduleExceptionDto
import com.mastercrm.ui.theme.DividerColor
import com.mastercrm.ui.theme.RosePrimary
import com.mastercrm.ui.theme.StatusCancelled
import com.mastercrm.ui.theme.StatusCompleted
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onLogout: () -> Unit
) {
    val app = MasterCrmApp.instance
    val scope = rememberCoroutineScope()
    val isOwner = app.authRepository.getRole() == "owner"

    var selectedSettingsTab by remember { mutableStateOf(0) }
    val tabTitles = listOf("Студія", "Графік роботи", "Шаблони SMS/Bot")

    val masters by app.database.masterDao().getAllMastersFlow().collectAsState(initial = emptyList())
    val services by app.database.serviceDao().getAllServicesFlow().collectAsState(initial = emptyList())

    var showAddMasterDialog by remember { mutableStateOf(false) }
    var showAddServiceDialog by remember { mutableStateOf(false) }
    var showCleanDemoConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Налаштування студії", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(
                selectedTabIndex = selectedSettingsTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = RosePrimary
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedSettingsTab == index,
                        onClick = { selectedSettingsTab = index },
                        text = { Text(title, fontWeight = if (selectedSettingsTab == index) FontWeight.Bold else FontWeight.Normal) }
                    )
                }
            }

            when (selectedSettingsTab) {
                0 -> StudioSettingsTab(
                    isOwner = isOwner,
                    masters = masters,
                    services = services,
                    onLogout = onLogout,
                    onAddMaster = { showAddMasterDialog = true },
                    onAddService = { showAddServiceDialog = true },
                    onCleanDemoData = { showCleanDemoConfirm = true }
                )
                1 -> ScheduleSettingsTab()
                2 -> ReminderTemplatesTab()
            }
        }
    }

    if (showAddMasterDialog) {
        AddMasterDialog(
            onDismiss = { showAddMasterDialog = false },
            onConfirm = { name, phone, spec ->
                scope.launch {
                    val id = UUID.randomUUID().toString()
                    val entity = MasterEntity(
                        id = id,
                        name = name,
                        phone = phone,
                        role = "master",
                        specialization = spec,
                        isActive = true,
                        deleted = false,
                        version = 1,
                        updatedAt = ""
                    )
                    app.database.masterDao().insertOrUpdateAll(listOf(entity))
                    app.syncRepository.queueMutation(
                        action = "master_create",
                        payload = mapOf(
                            "name" to name,
                            "phone" to phone,
                            "specialization" to spec,
                            "role" to "master"
                        )
                    )
                    showAddMasterDialog = false
                }
            }
        )
    }

    if (showAddServiceDialog) {
        AddServiceDialog(
            onDismiss = { showAddServiceDialog = false },
            onConfirm = { title, price, duration ->
                scope.launch {
                    val id = UUID.randomUUID().toString()
                    val entity = ServiceEntity(
                        id = id,
                        categoryId = "cat-01",
                        title = title,
                        price = price,
                        durationMinutes = duration,
                        bufferMinutes = 15,
                        isActive = true,
                        deleted = false,
                        version = 1,
                        updatedAt = ""
                    )
                    app.database.serviceDao().insertOrUpdate(entity)
                    app.syncRepository.queueMutation(
                        action = "service_create",
                        payload = mapOf(
                            "title" to title,
                            "price" to price,
                            "duration_minutes" to duration,
                            "buffer_minutes" to 15,
                            "category_id" to "cat-01"
                        )
                    )
                    showAddServiceDialog = false
                }
            }
        )
    }

    if (showCleanDemoConfirm) {
        val context = LocalContext.current
        var isCleaning by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { if (!isCleaning) showCleanDemoConfirm = false },
            title = { Text("Очистити демо-дані?") },
            text = {
                Text("Усі тестові записи, доходи, витрати та фото буде видалено. Налаштування студії та майстри збережуться.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                isCleaning = true
                                val api = app.apiClient.getApiService()
                                val resp = api.cleanDemoData()
                                if (resp.success) {
                                    Toast.makeText(context, "Демо-дані успішно очищено!", Toast.LENGTH_LONG).show()
                                    app.syncRepository.performSync()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "Помилка: ${e.message}", Toast.LENGTH_SHORT).show()
                            } finally {
                                isCleaning = false
                                showCleanDemoConfirm = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusCancelled),
                    enabled = !isCleaning
                ) {
                    if (isCleaning) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                    } else {
                        Text("Очистити")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCleanDemoConfirm = false },
                    enabled = !isCleaning
                ) {
                    Text("Скасувати")
                }
            }
        )
    }
}

@Composable
fun StudioSettingsTab(
    isOwner: Boolean,
    masters: List<MasterEntity>,
    services: List<ServiceEntity>,
    onLogout: () -> Unit,
    onAddMaster: () -> Unit,
    onAddService: () -> Unit,
    onCleanDemoData: () -> Unit
) {
    val app = MasterCrmApp.instance
    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Profile & Current Role
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = app.authRepository.getMasterName() ?: "Майстер",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                        Text(
                            text = if (isOwner) "Роль: Власниця (Повний доступ)" else "Роль: Майстер",
                            color = RosePrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            app.authRepository.clearAuth()
                            onLogout()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusCancelled)
                    ) {
                        Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Вийти")
                    }
                }
            }
        }

        // Masters Management (Owner only)
        if (isOwner) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Майстри студії", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    TextButton(onClick = onAddMaster) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Додати")
                    }
                }
            }

            items(masters, key = { it.id }) { master ->
                val initials = master.name.take(2).uppercase()
                ElevatedCard(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .background(RosePrimary.copy(alpha = 0.15f), RoundedCornerShape(21.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(initials, fontWeight = FontWeight.Bold, color = RosePrimary, fontSize = 14.sp)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(text = master.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(text = "${master.phone} • ${master.specialization ?: "Nail Master"}", color = Color.Gray, fontSize = 12.sp)
                            }
                        }

                        if (master.role != "owner") {
                            IconButton(onClick = {
                                scope.launch {
                                    app.database.masterDao().deleteById(master.id)
                                    app.syncRepository.queueMutation(
                                        action = "master_delete",
                                        payload = mapOf("master_id" to master.id)
                                    )
                                }
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Видалити", tint = Color.Gray)
                            }
                        }
                    }
                }
            }
        }

        // Services Management
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Послуги студії", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (isOwner) {
                    TextButton(onClick = onAddService) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Додати")
                    }
                }
            }
        }

        items(services, key = { it.id }) { service ->
            var showEditPriceDialog by remember { mutableStateOf(false) }

            ElevatedCard(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = service.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SuggestionChip(
                                    onClick = {},
                                    label = { Text("Послуга", fontSize = 11.sp) }
                                )
                                SuggestionChip(
                                    onClick = {},
                                    label = { Text("${service.durationMinutes} хв", fontSize = 11.sp) }
                                )
                            }
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${service.price.toInt()} ₴",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = RosePrimary
                                )
                                IconButton(
                                    onClick = { showEditPriceDialog = true },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = "Змінити ціну", modifier = Modifier.size(16.dp), tint = Color.Gray)
                                }
                            }
                            Switch(
                                checked = service.isActive,
                                onCheckedChange = { isChecked ->
                                    scope.launch {
                                        val updated = service.copy(isActive = isChecked)
                                        app.database.serviceDao().insertOrUpdate(updated)
                                        app.syncRepository.queueMutation(
                                            action = "service_update",
                                            payload = mapOf(
                                                "service_id" to service.id,
                                                "is_active" to isChecked
                                            )
                                        )
                                    }
                                },
                                modifier = Modifier.scale(0.8f)
                            )
                        }
                    }

                    if (isOwner) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        app.database.serviceDao().deleteById(service.id)
                                        app.syncRepository.queueMutation(
                                            action = "service_delete",
                                            payload = mapOf("service_id" to service.id)
                                        )
                                    }
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Видалити", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            if (showEditPriceDialog) {
                var newPriceText by remember { mutableStateOf(service.price.toInt().toString()) }
                AlertDialog(
                    onDismissRequest = { showEditPriceDialog = false },
                    title = { Text("Зміна ціни: ${service.title}") },
                    text = {
                        OutlinedTextField(
                            value = newPriceText,
                            onValueChange = { newPriceText = it },
                            label = { Text("Нова ціна (₴)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val p = newPriceText.toDoubleOrNull()
                                if (p != null && p > 0) {
                                    scope.launch {
                                        val updated = service.copy(price = p)
                                        app.database.serviceDao().insertOrUpdate(updated)
                                        app.syncRepository.queueMutation(
                                            action = "service_update",
                                            payload = mapOf("service_id" to service.id, "price" to p)
                                        )
                                    }
                                }
                                showEditPriceDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = RosePrimary)
                        ) {
                            Text("Зберегти")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showEditPriceDialog = false }) { Text("Скасувати") }
                    }
                )
            }
        }

        // Clean demo data section (Owner only)
        if (isOwner) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = StatusCancelled.copy(alpha = 0.08f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Тестові дані", fontWeight = FontWeight.Bold, color = StatusCancelled, fontSize = 15.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Очищення демо-записів та статистики перед початком реальної роботи студії.", color = Color.Gray, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = onCleanDemoData,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusCancelled),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Очистити демо-дані")
                        }
                    }
                }
            }
        }
    }
}

val DAY_NAMES = listOf("Понеділок", "Вівторок", "Середа", "Четвер", "П'ятниця", "Субота", "Неділя")

@Composable
fun ScheduleSettingsTab() {
    val app = MasterCrmApp.instance
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var scheduleDays by remember {
        mutableStateOf(
            (0..6).map { dow ->
                ScheduleDto(
                    id = "sch-$dow",
                    masterId = app.authRepository.getMasterId() ?: "m-01",
                    dayOfWeek = dow,
                    startTime = "09:00",
                    endTime = "19:00",
                    slotStepMinutes = 30,
                    isWorkingDay = dow < 5
                )
            }
        )
    }

    var exceptions by remember { mutableStateOf<List<ScheduleExceptionDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var showAddExceptionDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            isLoading = true
            val api = app.apiClient.getApiService()
            val resp = api.getSchedule()
            if (resp.success && resp.data != null) {
                if (resp.data.schedules.isNotEmpty()) {
                    scheduleDays = resp.data.schedules.sortedBy { it.dayOfWeek }
                }
                exceptions = resp.data.exceptions
            }
        } catch (e: Exception) {
            // Keep defaults
        } finally {
            isLoading = false
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Робочі дні та години", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                val api = app.apiClient.getApiService()
                                val resp = api.updateSchedule(
                                    mapOf(
                                        "days" to scheduleDays.map {
                                            mapOf(
                                                "day_of_week" to it.dayOfWeek,
                                                "start_time" to it.startTime,
                                                "end_time" to it.endTime,
                                                "slot_step_minutes" to it.slotStepMinutes,
                                                "is_working_day" to it.isWorkingDay
                                            )
                                        }
                                    )
                                )
                                if (resp.success) {
                                    Toast.makeText(context, "Графік роботи успішно оновлено!", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "Помилка: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RosePrimary)
                ) {
                    Text("Зберегти графік")
                }
            }
        }

        items(scheduleDays) { day ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = day.isWorkingDay,
                                onCheckedChange = { isChecked ->
                                    scheduleDays = scheduleDays.map {
                                        if (it.dayOfWeek == day.dayOfWeek) it.copy(isWorkingDay = isChecked) else it
                                    }
                                },
                                modifier = Modifier.scale(0.85f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = DAY_NAMES.getOrElse(day.dayOfWeek) { "День ${day.dayOfWeek}" },
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }

                        if (!day.isWorkingDay) {
                            Text("Вихідний", fontSize = 13.sp, color = Color.Gray, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    if (day.isWorkingDay) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = day.startTime,
                                onValueChange = { newStart ->
                                    scheduleDays = scheduleDays.map {
                                        if (it.dayOfWeek == day.dayOfWeek) it.copy(startTime = newStart) else it
                                    }
                                },
                                label = { Text("Початок") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Text("—", fontWeight = FontWeight.Bold)
                            OutlinedTextField(
                                value = day.endTime,
                                onValueChange = { newEnd ->
                                    scheduleDays = scheduleDays.map {
                                        if (it.dayOfWeek == day.dayOfWeek) it.copy(endTime = newEnd) else it
                                    }
                                },
                                label = { Text("Кінець") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = day.slotStepMinutes == 30,
                                onClick = {
                                    val newStep = if (day.slotStepMinutes == 30) 60 else 30
                                    scheduleDays = scheduleDays.map {
                                        if (it.dayOfWeek == day.dayOfWeek) it.copy(slotStepMinutes = newStep) else it
                                    }
                                },
                                label = { Text("${day.slotStepMinutes}хв", fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Вихідні та відпустки", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                TextButton(onClick = { showAddExceptionDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Додати вихідний")
                }
            }
        }

        if (exceptions.isEmpty()) {
            item {
                Text("Індивідуальних вихідних ще не додано.", color = Color.Gray, fontSize = 13.sp)
            }
        } else {
            items(exceptions) { exc ->
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = exc.date, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(text = exc.reason ?: "Вихідний", color = Color.Gray, fontSize = 12.sp)
                        }
                        Box(
                            modifier = Modifier
                                .background(StatusCancelled.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Вихідний", color = StatusCancelled, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }

    if (showAddExceptionDialog) {
        var excDate by remember { mutableStateOf("2026-12-31") }
        var excReason by remember { mutableStateOf("Святковий день") }

        AlertDialog(
            onDismissRequest = { showAddExceptionDialog = false },
            title = { Text("Додати вихідний день") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = excDate,
                        onValueChange = { excDate = it },
                        label = { Text("Дата (РРРР-ММ-ДД)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = excReason,
                        onValueChange = { excReason = it },
                        label = { Text("Причина (відпустка, лікарняний...)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                val api = app.apiClient.getApiService()
                                val resp = api.addScheduleException(
                                    mapOf(
                                        "date" to excDate,
                                        "is_day_off" to true,
                                        "reason" to excReason
                                    )
                                )
                                if (resp.success && resp.data != null) {
                                    exceptions = exceptions + resp.data
                                    Toast.makeText(context, "Вихідний додано!", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "Помилка: ${e.message}", Toast.LENGTH_SHORT).show()
                            } finally {
                                showAddExceptionDialog = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RosePrimary)
                ) {
                    Text("Зберегти")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddExceptionDialog = false }) { Text("Скасувати") }
            }
        )
    }
}

data class TemplateItem(
    val type: String,
    val title: String,
    var text: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderTemplatesTab() {
    val app = MasterCrmApp.instance
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val templates = remember {
        mutableStateListOf(
            TemplateItem("WELCOME", "Привітання нового клієнта", "Привіт, {name}! Раді вітати у студії MasterCRM. Ваш запис підтверджено!"),
            TemplateItem("REMINDER_24H", "Нагадування за 24 години", "Привіт, {name}! Нагадуємо про візит завтра ({date}) о {time} на «{service}». Чекаємо на вас!"),
            TemplateItem("REMINDER_2H", "Нагадування за 2 години", "Привіт, {name}! До зустрічі через 2 години о {time}!"),
            TemplateItem("REVIEW_10MIN", "Запит відгуку після візиту", "Дякуємо за візит, {name}! Будь ласка, залиште короткий відгук про послугу «{service}»."),
            TemplateItem("REVIEW_2WEEKS", "Запрошення через 2 тижні", "Привіт, {name}! Минуло 2 тижні з останнього візиту. Час оновити манікюр!")
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("Шаблони автоматичних повідомлень", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("Використовуйте чіпи нижче для вставки динамічних змінних.", color = Color.Gray, fontSize = 12.sp)
        }

        items(templates) { tmpl ->
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tmpl.title, fontWeight = FontWeight.Bold, fontSize = 14.sp)

                    OutlinedTextField(
                        value = tmpl.text,
                        onValueChange = { tmpl.text = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("{name}", "{date}", "{time}", "{service}").forEach { placeholder ->
                            SuggestionChip(
                                onClick = { tmpl.text = tmpl.text + " " + placeholder },
                                label = { Text(placeholder, fontSize = 11.sp) }
                            )
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(
                            onClick = {
                                scope.launch {
                                    try {
                                        val api = app.apiClient.getApiService()
                                        val resp = api.updateReminderTemplate(
                                            mapOf(
                                                "type" to tmpl.type,
                                                "template_text" to tmpl.text,
                                                "is_active" to true
                                            )
                                        )
                                        if (resp.success) {
                                            Toast.makeText(context, "Шаблон ${tmpl.type} збережено!", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Помилка: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = RosePrimary)
                        ) {
                            Text("Зберегти")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AddMasterDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, phone: String, spec: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var spec by remember { mutableStateOf("Nail Master") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новий майстер") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Ім'я майстра") },
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
                    value = spec,
                    onValueChange = { spec = it },
                    label = { Text("Спеціалізація") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank() && phone.isNotBlank()) onConfirm(name, phone, spec) },
                colors = ButtonDefaults.buttonColors(containerColor = RosePrimary)
            ) {
                Text("Створити")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } }
    )
}

@Composable
fun AddServiceDialog(
    onDismiss: () -> Unit,
    onConfirm: (title: String, price: Double, duration: Int) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var priceText by remember { mutableStateOf("") }
    var durationText by remember { mutableStateOf("60") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Нова послуга") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Назва послуги") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { priceText = it },
                    label = { Text("Ціна (грн)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = durationText,
                    onValueChange = { durationText = it },
                    label = { Text("Тривалість (хвилин)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val p = priceText.toDoubleOrNull() ?: 0.0
                    val d = durationText.toIntOrNull() ?: 60
                    if (title.isNotBlank() && p > 0) onConfirm(title, p, d)
                },
                colors = ButtonDefaults.buttonColors(containerColor = RosePrimary)
            ) {
                Text("Створити")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } }
    )
}

package com.mastercrm.ui.screens

import android.content.Context
import android.content.Intent
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
    val tabTitles = listOf("Студія", "Майстри", "Графік роботи", "Шаблони SMS/Bot")

    val masters by app.database.masterDao().getAllMastersFlow().collectAsState(initial = emptyList())
    val services by app.database.serviceDao().getAllServicesFlow().collectAsState(initial = emptyList())

    var showAddMasterDialog by remember { mutableStateOf(false) }
    var editingMaster by remember { mutableStateOf<MasterEntity?>(null) }
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
            ScrollableTabRow(
                selectedTabIndex = selectedSettingsTab,
                edgePadding = 12.dp,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = RosePrimary
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedSettingsTab == index,
                        onClick = { selectedSettingsTab = index },
                        text = {
                            Text(
                                title,
                                maxLines = 1,
                                softWrap = false,
                                fontWeight = if (selectedSettingsTab == index) FontWeight.Bold else FontWeight.Normal
                            )
                        }
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
                1 -> MastersManagementTab(
                    masters = masters,
                    isOwner = isOwner,
                    onAddMaster = { showAddMasterDialog = true },
                    onEditMaster = { editingMaster = it }
                )
                2 -> ScheduleSettingsTab()
                3 -> ReminderTemplatesTab()
            }
        }
    }

    if (showAddMasterDialog) {
        AddEditMasterDialog(
            master = null,
            onDismiss = { showAddMasterDialog = false },
            onConfirm = { name, phone, tgId, role, spec ->
                scope.launch {
                    val id = UUID.randomUUID().toString()
                    val entity = MasterEntity(
                        id = id,
                        name = name,
                        phone = phone,
                        telegramChatId = tgId,
                        role = role,
                        specialization = spec,
                        isActive = true,
                        deleted = false,
                        version = 1,
                        updatedAt = ""
                    )
                    app.database.masterDao().insertOrUpdateAll(listOf(entity))
                    app.syncRepository.queueMutation(
                        action = "master_create",
                        payload = buildMap {
                            put("id", id)
                            put("name", name)
                            put("phone", phone)
                            if (tgId != null) put("telegram_chat_id", tgId)
                            put("specialization", spec)
                            put("role", role)
                        }
                    )
                    showAddMasterDialog = false
                }
            }
        )
    }

    if (editingMaster != null) {
        AddEditMasterDialog(
            master = editingMaster,
            onDismiss = { editingMaster = null },
            onConfirm = { name, phone, tgId, role, spec ->
                val current = editingMaster ?: return@AddEditMasterDialog
                scope.launch {
                    val updated = current.copy(
                        name = name,
                        phone = phone,
                        telegramChatId = tgId,
                        role = role,
                        specialization = spec,
                        version = current.version + 1
                    )
                    app.database.masterDao().insertOrUpdateAll(listOf(updated))
                    app.syncRepository.queueMutation(
                        action = "master_update",
                        payload = buildMap {
                            put("master_id", current.id)
                            put("name", name)
                            put("phone", phone)
                            put("telegram_chat_id", tgId ?: "")
                            put("specialization", spec)
                            put("role", role)
                        }
                    )
                    editingMaster = null
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
                            "id" to id,
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

@OptIn(ExperimentalLayoutApi::class)
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
    val context = LocalContext.current

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

        // Telegram Bot & Push Notifications Settings Card
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Сповіщення в Telegram майстру", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                "Надсилати картку нового онлайн-запису з кнопками 1-tap підтвердження/відхилення у Telegram",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                        var notifyTg by remember { mutableStateOf(true) }
                        Switch(
                            checked = notifyTg,
                            onCheckedChange = { isChecked ->
                                notifyTg = isChecked
                                scope.launch {
                                    app.syncRepository.queueMutation(
                                        action = "studio_setting_set",
                                        payload = mapOf(
                                            "key" to "notify_master_telegram",
                                            "value" to isChecked.toString()
                                        )
                                    )
                                }
                            },
                            modifier = Modifier.scale(0.85f)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "💡 Для отримання сповіщень у Telegram майстер повинен хоча б один раз надіслати боту команду /login або /start.",
                        color = RosePrimary,
                        fontSize = 11.5.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Divider(color = Color.LightGray.copy(alpha = 0.4f))
                    Spacer(modifier = Modifier.height(10.dp))

                    val pm = remember { context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager }
                    var isIgnoringBattery by remember {
                        mutableStateOf(pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Фонова синхронізація 24/7", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                if (isIgnoringBattery) "✅ Фоновий режим без обмежень активовано"
                                else "Вимкніть економію батареї, щоб пуші приходили миттєво при закритому додатку",
                                color = if (isIgnoringBattery) Color(0xFF2E7D32) else Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                    }

                    if (!isIgnoringBattery) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                try {
                                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = android.net.Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    try {
                                        val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                        context.startActivity(intent)
                                    } catch (_: Exception) {}
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("🔋 Дозволити роботу у фоні (Вимкнути економію батареї)", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Prepayment (Deposit) Settings Card
        item {
            var depositMode by remember { mutableStateOf("NONE") } // NONE, FIRST_TIME, ALL
            var depositAmount by remember { mutableStateOf("200") }
            var jarLink by remember { mutableStateOf("") }
            var cardDetails by remember { mutableStateOf("") }
            var isSavingDeposit by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                try {
                    val api = app.apiClient.getApiService()
                    val resp = api.executeAction("settings_get", emptyMap<String, Any>())
                    if (resp.success && resp.data is Map<*, *>) {
                        val map = resp.data as Map<String, Any>
                        depositMode = map["deposit_mode"]?.toString() ?: if (map["deposit_required"]?.toString() == "true") "ALL" else "NONE"
                        depositAmount = map["deposit_amount"]?.toString() ?: "200"
                        jarLink = map["jar_link"]?.toString() ?: ""
                        cardDetails = map["card_details"]?.toString() ?: ""
                    }
                } catch (e: Exception) {
                    // Keep defaults
                }
            }

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CreditCard, contentDescription = null, tint = RosePrimary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("💳 Налаштування передоплати (Завдатку)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }

                    Text("Кому відправляти запит на передоплату:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FilterChip(
                            selected = depositMode == "NONE",
                            onClick = { depositMode = "NONE" },
                            label = { Text("Нікому (Без передоплати)") }
                        )
                        FilterChip(
                            selected = depositMode == "FIRST_TIME",
                            onClick = { depositMode = "FIRST_TIME" },
                            label = { Text("Лише новим клієнтам (Вперше)") }
                        )
                        FilterChip(
                            selected = depositMode == "ALL",
                            onClick = { depositMode = "ALL" },
                            label = { Text("Усім клієнтам") }
                        )
                    }

                    OutlinedTextField(
                        value = depositAmount,
                        onValueChange = { depositAmount = it },
                        label = { Text("Сума передоплати (грн)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = jarLink,
                        onValueChange = { jarLink = it },
                        label = { Text("Посилання на Monobank Банку") },
                        placeholder = { Text("https://send.monobank.ua/jar/...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = cardDetails,
                        onValueChange = { cardDetails = it },
                        label = { Text("Реквізити картки") },
                        placeholder = { Text("5375 ... (Ім'я П.)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            scope.launch {
                                isSavingDeposit = true
                                try {
                                    val api = app.apiClient.getApiService()
                                    val resp = api.executeAction(
                                        "settings_update",
                                        mapOf(
                                            "settings" to mapOf(
                                                "deposit_mode" to depositMode,
                                                "deposit_required" to if (depositMode == "ALL") "true" else "false",
                                                "deposit_amount" to depositAmount.trim(),
                                                "jar_link" to jarLink.trim(),
                                                "card_details" to cardDetails.trim()
                                            )
                                        )
                                    )
                                    if (resp.success) {
                                        Toast.makeText(context, "💾 Налаштування оплати збережено!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Помилка збереження", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Помилка зв'язку: ${e.message}", Toast.LENGTH_SHORT).show()
                                } finally {
                                    isSavingDeposit = false
                                }
                            }
                        },
                        enabled = !isSavingDeposit,
                        colors = ButtonDefaults.buttonColors(containerColor = RosePrimary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isSavingDeposit) "Збереження..." else "💾 Зберегти налаштування оплати", fontWeight = FontWeight.Bold)
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
            TemplateItem(
                "REMINDER_24H",
                "1. Нагадування про запис за 24 години",
                "🔔 Привіт, {name}! Нагадуємо про ваш запис завтра ({date}) о {time} на послугу «{service}». Чекаємо на вас!"
            ),
            TemplateItem(
                "REMINDER_2H",
                "2. Нагадування про запис за 2 години",
                "⏰ Привіт, {name}! Нагадуємо, що ваш візит на «{service}» відбудеться вже сьогодні через 2 години — о {time}!"
            ),
            TemplateItem(
                "REVIEW_10MIN_TEXT_ASK",
                "3. Запит відгуку через 10 хв після візиту (Крок 1: Текст)",
                "💖 Дякуємо за візит, {name}! Як вам результат процедури «{service}»? Напишіть, будь ласка, у відповідь кілька слів про ваші враження:"
            ),
            TemplateItem(
                "REVIEW_10MIN_AUTO_REPLY",
                "4. Авто-відповідь студії на відгук (Крок 2: Відповідь майстра)",
                "Дякуємо за довіру! Чекаємо на наступну корекцію ❤️"
            ),
            TemplateItem(
                "REVIEW_10MIN_STARS_ASK",
                "5. Запит оцінки від 1 до 5 зірочок (Крок 3: Оцінка)",
                "✨ А тепер оцініть, будь ласка, ваш візит від 1 до 5 зірочок нижче:"
            ),
            TemplateItem(
                "WEAR_2WEEKS_TEXT_ASK",
                "6. Запит про носку через 2 тижні (Крок 1: Текст)",
                "💅 Привіт, {name}! Минуло 2 тижні з вашого візиту («{service}»). Напишіть, будь ласка, як носиться покриття? Чи все тримається ідеально?"
            ),
            TemplateItem(
                "WEAR_2WEEKS_PHOTO_ASK",
                "7. Запит фото нігтів через 2 тижні (Крок 2: Фото)",
                "📸 Дякуємо за відповідь! А тепер надішліть, будь ласка, у цей чат фото стану ваших нігтів зараз (або натисніть кнопку нижче, щоб продовжити без фото):"
            ),
            TemplateItem(
                "WEAR_2WEEKS_FINAL_BOOK_OFFER",
                "8. Відповідь після звіту + Пропозиція записатися",
                "💖 Дякуємо за ваш звіт та фото! Настав час планувати наступну корекцію — оберіть зручне віконце завчасно за кнопкою нижче:"
            )
        )
    }

    LaunchedEffect(Unit) {
        try {
            val api = app.apiClient.getApiService()
            val resp = api.getReminderTemplates()
            if (resp.success && resp.data != null) {
                resp.data.forEach { serverTmpl ->
                    val idx = templates.indexOfFirst { it.type == serverTmpl.type }
                    if (idx >= 0 && serverTmpl.templateText.isNotBlank()) {
                        templates[idx] = templates[idx].copy(text = serverTmpl.templateText)
                    }
                }
            }
        } catch (e: Exception) {
            // Keep default in-memory templates
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp, start = 16.dp, end = 16.dp),
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
fun MastersManagementTab(
    masters: List<MasterEntity>,
    isOwner: Boolean,
    onAddMaster: () -> Unit,
    onEditMaster: (MasterEntity) -> Unit
) {
    val app = MasterCrmApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val botUsername = "zachanails_bot"

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Майстри студії (${masters.size})",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Керування доступом та сповіщеннями в Telegram",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
                if (isOwner) {
                    Button(
                        onClick = onAddMaster,
                        colors = ButtonDefaults.buttonColors(containerColor = RosePrimary),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Додати", fontSize = 12.sp)
                    }
                }
            }
        }

        if (masters.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("Майстрів ще не додано", color = Color.Gray)
                }
            }
        } else {
            items(masters, key = { it.id }) { master ->
                val initials = master.name.take(2).uppercase()
                ElevatedCard(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Top info row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .background(RosePrimary.copy(alpha = 0.15f), RoundedCornerShape(22.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(initials, fontWeight = FontWeight.Bold, color = RosePrimary, fontSize = 15.sp)
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = master.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${master.phone} • ${master.specialization ?: "Nail Master"}",
                                        color = Color.Gray,
                                        fontSize = 12.sp
                                    )
                                }
                            }

                            // Role badge
                            Surface(
                                color = if (master.role == "owner") RosePrimary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = if (master.role == "owner") "👑 Власник" else "👩‍🎨 Майстер",
                                    color = if (master.role == "owner") RosePrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        // Telegram Status Badge
                        if (master.telegramChatId != null) {
                            Surface(
                                color = Color(0xFFE8F5E9),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "✅ Telegram ID: ${master.telegramChatId}",
                                        color = Color(0xFF2E7D32),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        } else {
                            Surface(
                                color = Color(0xFFFFF3E0),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "⚠️ Telegram не прив'язано (немає сповіщень)",
                                        color = Color(0xFFE65100),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }

                        // Action Buttons Row
                        if (isOwner) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = { onEditMaster(master) },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("ID / Дані", fontSize = 11.5.sp, maxLines = 1, softWrap = false)
                                }

                                Button(
                                    onClick = {
                                        val inviteUrl = "https://t.me/$botUsername?start=join_${master.id}"
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(
                                                Intent.EXTRA_TEXT,
                                                "Привіт, ${master.name}! 👋\n" +
                                                "Приєднайся до нашої студії в Telegram для автоматичного отримання сповіщень про нові записи та входу в додаток JuNailCRM:\n" +
                                                inviteUrl
                                            )
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, "Запросити майстра в Telegram"))
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF229ED9)),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Запросити в ТГ", fontSize = 11.5.sp, maxLines = 1, softWrap = false)
                                }

                                val ownerCount = masters.count { it.role == "owner" && !it.deleted }
                                val canDelete = master.role != "owner" || ownerCount > 1

                                IconButton(
                                    onClick = {
                                        if (!canDelete) {
                                            Toast.makeText(context, "Неможливо видалити єдиного власника студії!", Toast.LENGTH_LONG).show()
                                            return@IconButton
                                        }
                                        scope.launch {
                                            app.database.masterDao().deleteById(master.id)
                                            app.syncRepository.queueMutation(
                                                action = "master_delete",
                                                payload = mapOf("master_id" to master.id)
                                            )
                                            Toast.makeText(context, "Майстра видалено", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Видалити",
                                        tint = if (canDelete) StatusCancelled else Color.LightGray,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AddEditMasterDialog(
    master: MasterEntity? = null,
    onDismiss: () -> Unit,
    onConfirm: (name: String, phone: String, tgId: Long?, role: String, spec: String) -> Unit
) {
    val isEdit = master != null
    var name by remember { mutableStateOf(master?.name ?: "") }
    var phone by remember { mutableStateOf(master?.phone ?: "+380") }
    var tgIdText by remember { mutableStateOf(master?.telegramChatId?.toString() ?: "") }
    var role by remember { mutableStateOf(master?.role ?: "master") }
    var spec by remember { mutableStateOf(master?.specialization ?: "Nail Master") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "Редагування майстра" else "Новий майстер", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Ім'я майстра *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Телефон (+380...) *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = tgIdText,
                    onValueChange = { tgIdText = it.filter { c -> c.isDigit() } },
                    label = { Text("Telegram ID (числа)") },
                    placeholder = { Text("напр. 683419385") },
                    supportingText = {
                        Text("💡 Майстер може дізнатись свій ID у боті командою /myid або прив'язатись через посилання", fontSize = 11.sp)
                    },
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Роль власника студії:", fontSize = 13.sp)
                    Switch(
                        checked = role == "owner",
                        onCheckedChange = { isOwnerChecked ->
                            role = if (isOwnerChecked) "owner" else "master"
                        }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank() && phone.isNotBlank()) {
                        val parsedTg = tgIdText.toLongOrNull()
                        onConfirm(name.trim(), phone.trim(), parsedTg, role, spec.trim())
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = RosePrimary)
            ) {
                Text(if (isEdit) "Зберегти" else "Створити")
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

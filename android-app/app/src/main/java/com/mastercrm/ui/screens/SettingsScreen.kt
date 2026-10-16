package com.mastercrm.ui.screens

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
import com.mastercrm.data.local.entity.MasterEntity
import com.mastercrm.data.local.entity.ServiceEntity
import com.mastercrm.ui.theme.DividerColor
import com.mastercrm.ui.theme.RosePrimary
import com.mastercrm.ui.theme.StatusCancelled
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

    val masters by app.database.masterDao().getAllMastersFlow().collectAsState(initial = emptyList())
    val services by app.database.serviceDao().getAllServicesFlow().collectAsState(initial = emptyList())

    var showAddMasterDialog by remember { mutableStateOf(false) }
    var showAddServiceDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Налаштування студії", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                        TextButton(onClick = { showAddMasterDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Додати")
                        }
                    }
                }

                items(masters, key = { it.id }) { master ->
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
                                Text(text = master.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(text = "${master.phone} • ${master.specialization ?: "Nail Master"}", color = Color.Gray, fontSize = 12.sp)
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
                        TextButton(onClick = { showAddServiceDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Додати")
                        }
                    }
                }
            }

            items(services, key = { it.id }) { service ->
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = service.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text(
                                text = "${service.price.toInt()} грн • ${service.durationMinutes} хв (+${service.bufferMinutes}хв буфер)",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                        if (isOwner) {
                            IconButton(onClick = {
                                scope.launch {
                                    app.database.serviceDao().deleteById(service.id)
                                    app.syncRepository.queueMutation(
                                        action = "service_delete",
                                        payload = mapOf("service_id" to service.id)
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

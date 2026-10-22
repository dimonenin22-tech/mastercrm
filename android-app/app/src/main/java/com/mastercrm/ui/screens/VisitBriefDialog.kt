package com.mastercrm.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.ui.text.style.TextAlign
import com.mastercrm.data.local.entity.ProductEntity
import com.mastercrm.ui.theme.StatusCancelled
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
import com.mastercrm.data.local.entity.VisitBriefEntity
import com.mastercrm.ui.theme.RosePrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID

data class BriefMaterialItem(
    val productId: String,
    val productName: String,
    val unit: String,
    val quantityText: String,
    val isFromNorm: Boolean = true
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VisitBriefDialog(
    appointmentId: String,
    clientId: String,
    initialBrief: VisitBriefEntity? = null,
    onDismiss: () -> Unit,
    onSaved: (VisitBriefEntity) -> Unit = {}
) {
    val app = MasterCrmApp.instance
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var coatingCondition by remember { mutableStateOf(initialBrief?.coatingCondition ?: "Ідеально") }
    var nailPlateType by remember { mutableStateOf(initialBrief?.nailPlateType ?: "Нормальна") }
    var cuticleType by remember { mutableStateOf(initialBrief?.cuticleType ?: "Нормальна") }
    var baseGelUsed by remember { mutableStateOf(initialBrief?.baseGelUsed ?: "Rubber Base") }
    var designAndLength by remember { mutableStateOf(initialBrief?.designAndLength ?: "Мигдаль (2), Однотон") }
    var healthNotes by remember { mutableStateOf(initialBrief?.healthNotes ?: "") }
    var coffeePrefs by remember { mutableStateOf(initialBrief?.coffeePrefs ?: "Капучино з корицею") }
    var materialNotes by remember { mutableStateOf(initialBrief?.materialNotes ?: "") }

    var attachedPhotoType by remember { mutableStateOf("AFTER") }
    var isUploadingPhoto by remember { mutableStateOf(false) }
    var photoUploadStatus by remember { mutableStateOf<String?>(null) }
    var photoBeforeUrl by remember { mutableStateOf(initialBrief?.photoBeforeUrl ?: "") }
    var photoAfterUrl by remember { mutableStateOf(initialBrief?.photoAfterUrl ?: "") }
    var viewingFullPhotoUrl by remember { mutableStateOf<String?>(null) }

    val allProducts by app.database.productDao().getAllProductsFlow().collectAsState(initial = emptyList())
    var consumedMaterials by remember { mutableStateOf<List<BriefMaterialItem>>(emptyList()) }
    var showAddMaterialPicker by remember { mutableStateOf(false) }

    LaunchedEffect(appointmentId, clientId) {
        val existing = withContext(Dispatchers.IO) {
            val byApt = app.database.visitBriefDao().getByAppointmentId(appointmentId)
            if (byApt == null && clientId.isNotBlank()) {
                app.database.visitBriefDao().getByClientIdList(clientId).firstOrNull()
            } else {
                byApt
            }
        }
        if (existing != null) {
            existing.coatingCondition?.takeIf { it.isNotBlank() }?.let { coatingCondition = it }
            existing.nailPlateType?.takeIf { it.isNotBlank() }?.let { nailPlateType = it }
            existing.cuticleType?.takeIf { it.isNotBlank() }?.let { cuticleType = it }
            existing.baseGelUsed?.takeIf { it.isNotBlank() }?.let { baseGelUsed = it }
            existing.designAndLength?.takeIf { it.isNotBlank() }?.let { designAndLength = it }
            existing.healthNotes?.takeIf { it.isNotBlank() }?.let { healthNotes = it }
            existing.coffeePrefs?.takeIf { it.isNotBlank() }?.let { coffeePrefs = it }
            existing.materialNotes?.takeIf { it.isNotBlank() }?.let { materialNotes = it }
            existing.photoBeforeUrl?.takeIf { it.isNotBlank() }?.let { photoBeforeUrl = it }
            existing.photoAfterUrl?.takeIf { it.isNotBlank() }?.let { photoAfterUrl = it }

            val savedJson = existing.consumedMaterialsJson
            if (!savedJson.isNullOrBlank() && savedJson != "[]") {
                try {
                    val listType = object : TypeToken<List<Map<String, Any>>>() {}.type
                    val parsedList: List<Map<String, Any>> = Gson().fromJson(savedJson, listType) ?: emptyList()
                    consumedMaterials = parsedList.mapNotNull { m ->
                        val pid = m["product_id"] as? String ?: return@mapNotNull null
                        val pname = m["name"] as? String ?: "Матеріал"
                        val unit = m["unit"] as? String ?: "шт"
                        val q = m["quantity"]?.toString() ?: "1.0"
                        val isNorm = (m["is_from_norm"] as? Boolean) ?: true
                        BriefMaterialItem(
                            productId = pid,
                            productName = pname,
                            unit = unit,
                            quantityText = q,
                            isFromNorm = isNorm
                        )
                    }
                } catch (_: Exception) {}
            }
        }

        if (consumedMaterials.isEmpty()) {
            withContext(Dispatchers.IO) {
                val apt = app.database.appointmentDao().getById(appointmentId)
                val srvId = apt?.serviceId
                if (!srvId.isNullOrBlank()) {
                    try {
                        val resp = app.apiClient.getApiService().getServiceConsumables(mapOf("service_id" to srvId))
                        if (resp.success && !resp.data.isNullOrEmpty()) {
                            withContext(Dispatchers.Main) {
                                consumedMaterials = resp.data.map {
                                    val qFormatted = if (it.quantityPerService % 1.0 == 0.0) it.quantityPerService.toInt().toString() else it.quantityPerService.toString()
                                    BriefMaterialItem(
                                        productId = it.productId,
                                        productName = it.productName,
                                        unit = it.unit,
                                        quantityText = qFormatted,
                                        isFromNorm = true
                                    )
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            scope.launch(Dispatchers.IO) {
                try {
                    withContext(Dispatchers.Main) { isUploadingPhoto = true }
                    val inputStream = context.contentResolver.openInputStream(selectedUri)
                    val originalBitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()

                    if (originalBitmap != null) {
                        val maxDimension = 800
                        val width = originalBitmap.width
                        val height = originalBitmap.height
                        val ratio = minOf(maxDimension.toFloat() / width, maxDimension.toFloat() / height, 1.0f)
                        val scaledBitmap = if (ratio < 1.0f) {
                            Bitmap.createScaledBitmap(
                                originalBitmap,
                                (width * ratio).toInt(),
                                (height * ratio).toInt(),
                                true
                            )
                        } else {
                            originalBitmap
                        }
                        val outputStream = ByteArrayOutputStream()
                        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
                        val bytes = outputStream.toByteArray()
                        val base64Str = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)

                        withContext(Dispatchers.Main) {
                            if (attachedPhotoType == "BEFORE") {
                                photoBeforeUrl = base64Str
                            } else {
                                photoAfterUrl = base64Str
                            }
                            photoUploadStatus = "Фото $attachedPhotoType успішно прикріплено! ✅"
                        }
                        try {
                            val api = MasterCrmApp.instance.apiClient.getApiService()
                            api.uploadPhoto(
                                mapOf(
                                    "client_id" to clientId,
                                    "appointment_id" to appointmentId,
                                    "photo_type" to attachedPhotoType,
                                    "filename" to "photo_${attachedPhotoType.lowercase()}_${appointmentId.take(8)}.jpg",
                                    "image_base64" to base64Str
                                )
                            )
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {
                } finally {
                    withContext(Dispatchers.Main) {
                        isUploadingPhoto = false
                    }
                }
            }
        }
    }

    val coatingOptions = listOf("Ідеально", "Відшарування", "Тріщини / Скол", "Переношене (>4 тиж)", "Без покриття")
    val nailPlateOptions = listOf("Нормальна", "Тонка / Ослаблена", "Волога", "Суха / Ламка", "Клююча")
    val cuticleOptions = listOf("Нормальна", "Суха / Налипаюча", "Волога / Тонка", "Близько судини")
    val baseGelOptions = listOf("Rubber Base", "Hard Gel", "Acrylgel", "Polygel", "Fiber Base")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Діагностична картка візиту 💅", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. Стан старого покриття
                OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Стан попереднього покриття:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            coatingOptions.forEach { opt ->
                                FilterChip(
                                    selected = coatingCondition == opt,
                                    onClick = { coatingCondition = opt },
                                    label = { Text(opt, fontSize = 11.sp) }
                                )
                            }
                        }
                    }
                }

                // 2. Тип нігтьової пластини
                OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Тип нігтьової пластини:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            nailPlateOptions.forEach { opt ->
                                FilterChip(
                                    selected = nailPlateType == opt,
                                    onClick = { nailPlateType = opt },
                                    label = { Text(opt, fontSize = 11.sp) }
                                )
                            }
                        }
                    }
                }

                // 3. Тип кутикули
                OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Тип кутикули та чутливість:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            cuticleOptions.forEach { opt ->
                                FilterChip(
                                    selected = cuticleType == opt,
                                    onClick = { cuticleType = opt },
                                    label = { Text(opt, fontSize = 11.sp) }
                                )
                            }
                        }
                    }
                }

                // 4. Матеріал (База / Гель)
                OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("База / Гель укріплення:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            baseGelOptions.forEach { opt ->
                                FilterChip(
                                    selected = baseGelUsed == opt,
                                    onClick = { baseGelUsed = opt },
                                    label = { Text(opt, fontSize = 11.sp) }
                                )
                            }
                        }
                    }
                }

                // 4.1. Витрачені матеріали (Норми та Склад)
                OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📦 Витрачені матеріали:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            OutlinedButton(
                                onClick = { showAddMaterialPicker = true },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("+ Зі складу", fontSize = 11.sp, maxLines = 1, softWrap = false)
                            }
                        }

                        if (consumedMaterials.isEmpty()) {
                            Text(
                                "Норми для послуги не задані або матеріали не обрані. Натисніть «+ Зі складу», щоб списати матеріали вручну.",
                                color = Color.Gray,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        } else {
                            consumedMaterials.forEach { item ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                                            Text(item.productName.ifBlank { "Матеріал" }, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                                            val badge = if (item.isFromNorm) "📋 За нормою (${item.unit})" else "✋ Додано вручну (${item.unit})"
                                            val stockInfo = allProducts.find { it.id == item.productId }?.let { " • Склад: ${it.currentStock} ${it.unit}" } ?: ""
                                            Text(badge + stockInfo, fontSize = 11.sp, color = Color.Gray)
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            val step = if (item.unit == "мл" || item.unit == "г") 0.5 else 1.0
                                            IconButton(
                                                onClick = {
                                                    val cur = item.quantityText.replace(",", ".").trim().toDoubleOrNull() ?: 1.0
                                                    val next = (cur - step).coerceAtLeast(0.1)
                                                    val txt = if (next % 1.0 == 0.0) next.toInt().toString() else "%.1f".format(java.util.Locale.US, next)
                                                    consumedMaterials = consumedMaterials.map {
                                                        if (it.productId == item.productId) it.copy(quantityText = txt) else it
                                                    }
                                                },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(Icons.Default.Remove, contentDescription = "Зменшити", modifier = Modifier.size(14.dp))
                                            }

                                            OutlinedTextField(
                                                value = item.quantityText,
                                                onValueChange = { newTxt ->
                                                    consumedMaterials = consumedMaterials.map {
                                                        if (it.productId == item.productId) it.copy(quantityText = newTxt) else it
                                                    }
                                                },
                                                singleLine = true,
                                                modifier = Modifier.width(62.dp),
                                                textStyle = LocalTextStyle.current.copy(
                                                    textAlign = TextAlign.Center,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            )

                                            IconButton(
                                                onClick = {
                                                    val cur = item.quantityText.replace(",", ".").trim().toDoubleOrNull() ?: 1.0
                                                    val next = cur + step
                                                    val txt = if (next % 1.0 == 0.0) next.toInt().toString() else "%.1f".format(java.util.Locale.US, next)
                                                    consumedMaterials = consumedMaterials.map {
                                                        if (it.productId == item.productId) it.copy(quantityText = txt) else it
                                                    }
                                                },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(Icons.Default.Add, contentDescription = "Збільшити", modifier = Modifier.size(14.dp))
                                            }

                                            IconButton(
                                                onClick = {
                                                    consumedMaterials = consumedMaterials.filter { it.productId != item.productId }
                                                },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(Icons.Default.Delete, contentDescription = "Видалити", tint = StatusCancelled, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 5. Дизайн, форма, матеріали
                OutlinedTextField(
                    value = designAndLength,
                    onValueChange = { designAndLength = it },
                    label = { Text("Форма, довжина, дизайн") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = materialNotes,
                    onValueChange = { materialNotes = it },
                    label = { Text("Колір гель-лаку / Бренд топу") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 6. Здоров'я & Вподобання
                OutlinedTextField(
                    value = healthNotes,
                    onValueChange = { healthNotes = it },
                    label = { Text("Алергії / Оніхолізис / Травми") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = coffeePrefs,
                    onValueChange = { coffeePrefs = it },
                    label = { Text("Вподобання клієнта (кава, чай)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 7. Фотофіксація
                Spacer(modifier = Modifier.height(4.dp))
                Text("Фотофіксація роботи:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    onClick = { photoPickerLauncher.launch("image/*") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isUploadingPhoto
                ) {
                    Icon(Icons.Default.AddAPhoto, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isUploadingPhoto) "Завантаження..." else "Обрати фото $attachedPhotoType з галереї")
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (photoBeforeUrl.isNotBlank()) {
                        val beforeBmp = remember(photoBeforeUrl) { decodeBase64ToBitmap(photoBeforeUrl) }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Фото До ✓", fontSize = 11.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                            if (beforeBmp != null) {
                                Image(
                                    bitmap = beforeBmp.asImageBitmap(),
                                    contentDescription = "Фото До",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { viewingFullPhotoUrl = photoBeforeUrl }
                                )
                            }
                        }
                    }
                    if (photoAfterUrl.isNotBlank()) {
                        val afterBmp = remember(photoAfterUrl) { decodeBase64ToBitmap(photoAfterUrl) }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Фото Після ✓", fontSize = 11.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                            if (afterBmp != null) {
                                Image(
                                    bitmap = afterBmp.asImageBitmap(),
                                    contentDescription = "Фото Після",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { viewingFullPhotoUrl = photoAfterUrl }
                                )
                            }
                        }
                    }
                }

                photoUploadStatus?.let { status ->
                    Text(status, color = RosePrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        val briefId = initialBrief?.id ?: "brief-${UUID.randomUUID().toString().take(8)}"
                        val masterId = app.authRepository.getMasterId() ?: "m-01"

                        val materialsPayload = consumedMaterials.mapNotNull { item ->
                            val q = item.quantityText.replace(",", ".").trim().toDoubleOrNull() ?: 0.0
                            if (q > 0.0) {
                                mapOf(
                                    "product_id" to item.productId,
                                    "name" to item.productName,
                                    "unit" to item.unit,
                                    "quantity" to q,
                                    "is_from_norm" to item.isFromNorm
                                )
                            } else null
                        }
                        val materialsJson = Gson().toJson(materialsPayload)

                        val entity = VisitBriefEntity(
                            id = briefId,
                            appointmentId = appointmentId,
                            clientId = clientId,
                            masterId = masterId,
                            materialNotes = materialNotes,
                            nailCondition = coatingCondition,
                            coffeePrefs = coffeePrefs,
                            photoUrl = photoAfterUrl.ifBlank { photoBeforeUrl },
                            coatingCondition = coatingCondition,
                            nailPlateType = nailPlateType,
                            cuticleType = cuticleType,
                            baseGelUsed = baseGelUsed,
                            designAndLength = designAndLength,
                            healthNotes = healthNotes,
                            photoBeforeUrl = photoBeforeUrl,
                            photoAfterUrl = photoAfterUrl,
                            consumedMaterialsJson = materialsJson,
                            deleted = false,
                            version = (initialBrief?.version ?: 0) + 1,
                            updatedAt = ""
                        )

                        // Save in local Room
                        app.database.visitBriefDao().insertOrUpdate(entity)

                        val briefPayload = mapOf(
                            "id" to briefId,
                            "appointment_id" to appointmentId,
                            "client_id" to clientId,
                            "master_id" to masterId,
                            "material_notes" to materialNotes,
                            "nail_condition" to coatingCondition,
                            "coffee_prefs" to coffeePrefs,
                            "coating_condition" to coatingCondition,
                            "nail_plate_type" to nailPlateType,
                            "cuticle_type" to cuticleType,
                            "base_gel_used" to baseGelUsed,
                            "design_and_length" to designAndLength,
                            "health_notes" to healthNotes,
                            "photo_url" to photoAfterUrl.ifBlank { photoBeforeUrl },
                            "photo_before_url" to photoBeforeUrl,
                            "photo_after_url" to photoAfterUrl,
                            "consumed_materials" to materialsPayload,
                            "consumed_materials_json" to materialsJson
                        )

                        // Sync to backend
                        try {
                            val api = app.apiClient.getApiService()
                            api.saveVisitBrief(briefPayload)
                            Toast.makeText(context, "Бриф візиту збережено!", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            // Offline queue
                            app.syncRepository.queueMutation(
                                action = "visit_brief_save",
                                payload = briefPayload
                            )
                            Toast.makeText(context, "Бриф збережено локально (офлайн)", Toast.LENGTH_SHORT).show()
                        }

                        // Trigger sync in background to reconcile product stock balances
                        scope.launch(Dispatchers.IO) {
                            try {
                                app.syncRepository.performSync()
                            } catch (_: Exception) {}
                        }

                        onSaved(entity)
                        onDismiss()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = RosePrimary)
            ) {
                Text("Зберегти бриф")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Скасувати") }
        }
    )

    if (!viewingFullPhotoUrl.isNullOrBlank()) {
        val fullBmp = remember(viewingFullPhotoUrl) { decodeBase64ToBitmap(viewingFullPhotoUrl) }
        Dialog(onDismissRequest = { viewingFullPhotoUrl = null }) {
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
                            contentDescription = "Фото візиту",
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
                        onClick = { viewingFullPhotoUrl = null },
                        colors = ButtonDefaults.buttonColors(containerColor = RosePrimary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Закрити")
                    }
                }
            }
        }
    }

    if (showAddMaterialPicker) {
        var selectedProd by remember { mutableStateOf(allProducts.firstOrNull()) }
        var qtyInput by remember { mutableStateOf("1.0") }

        AlertDialog(
            onDismissRequest = { showAddMaterialPicker = false },
            title = { Text("Додати матеріал зі складу", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (allProducts.isEmpty()) {
                        Text("На складі поки немає матеріалів. Додайте їх у розділі Фінанси -> Склад.", color = Color.Gray, fontSize = 13.sp)
                    } else {
                        Text("Оберіть матеріал:", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            allProducts.forEach { prod ->
                                FilterChip(
                                    selected = selectedProd?.id == prod.id,
                                    onClick = { selectedProd = prod },
                                    label = { Text("${prod.name} (${prod.currentStock} ${prod.unit})", fontSize = 11.sp) }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = qtyInput,
                            onValueChange = { qtyInput = it },
                            label = { Text("Кількість (${selectedProd?.unit ?: "шт"})") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                if (allProducts.isNotEmpty()) {
                    Button(
                        onClick = {
                            val prod = selectedProd ?: return@Button
                            val q = qtyInput.replace(",", ".").trim().toDoubleOrNull() ?: 1.0
                            val qFormatted = if (q % 1.0 == 0.0) q.toInt().toString() else "%.1f".format(java.util.Locale.US, q)
                            val existingIndex = consumedMaterials.indexOfFirst { it.productId == prod.id }
                            if (existingIndex >= 0) {
                                consumedMaterials = consumedMaterials.mapIndexed { idx, item ->
                                    if (idx == existingIndex) item.copy(quantityText = qFormatted) else item
                                }
                            } else {
                                consumedMaterials = consumedMaterials + BriefMaterialItem(
                                    productId = prod.id,
                                    productName = prod.name,
                                    unit = prod.unit,
                                    quantityText = qFormatted,
                                    isFromNorm = false
                                )
                            }
                            showAddMaterialPicker = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RosePrimary)
                    ) {
                        Text("Додати")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddMaterialPicker = false }) {
                    Text("Скасувати")
                }
            }
        )
    }
}

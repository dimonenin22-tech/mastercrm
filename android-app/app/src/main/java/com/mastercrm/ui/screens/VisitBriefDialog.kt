package com.mastercrm.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
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
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
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
                Text("Стан попереднього покриття:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    coatingOptions.take(3).forEach { opt ->
                        FilterChip(
                            selected = coatingCondition == opt,
                            onClick = { coatingCondition = opt },
                            label = { Text(opt, fontSize = 11.sp) }
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    coatingOptions.drop(3).forEach { opt ->
                        FilterChip(
                            selected = coatingCondition == opt,
                            onClick = { coatingCondition = opt },
                            label = { Text(opt, fontSize = 11.sp) }
                        )
                    }
                }

                // 2. Тип нігтьової пластини
                Text("Тип нігтьової пластини:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    nailPlateOptions.take(3).forEach { opt ->
                        FilterChip(
                            selected = nailPlateType == opt,
                            onClick = { nailPlateType = opt },
                            label = { Text(opt, fontSize = 11.sp) }
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    nailPlateOptions.drop(3).forEach { opt ->
                        FilterChip(
                            selected = nailPlateType == opt,
                            onClick = { nailPlateType = opt },
                            label = { Text(opt, fontSize = 11.sp) }
                        )
                    }
                }

                // 3. Тип кутикули
                Text("Тип кутикули та чутливість:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    cuticleOptions.take(2).forEach { opt ->
                        FilterChip(
                            selected = cuticleType == opt,
                            onClick = { cuticleType = opt },
                            label = { Text(opt, fontSize = 11.sp) }
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    cuticleOptions.drop(2).forEach { opt ->
                        FilterChip(
                            selected = cuticleType == opt,
                            onClick = { cuticleType = opt },
                            label = { Text(opt, fontSize = 11.sp) }
                        )
                    }
                }

                // 4. Матеріал (База / Гель)
                Text("База / Гель укріплення:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    baseGelOptions.take(3).forEach { opt ->
                        FilterChip(
                            selected = baseGelUsed == opt,
                            onClick = { baseGelUsed = opt },
                            label = { Text(opt, fontSize = 11.sp) }
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    baseGelOptions.drop(3).forEach { opt ->
                        FilterChip(
                            selected = baseGelUsed == opt,
                            onClick = { baseGelUsed = opt },
                            label = { Text(opt, fontSize = 11.sp) }
                        )
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
                                if (resp.success && resp.data != null) {
                                    val url = resp.data.fileUrl
                                    if (attachedPhotoType == "BEFORE") {
                                        photoBeforeUrl = url
                                    } else {
                                        photoAfterUrl = url
                                    }
                                    photoUploadStatus = "Фото $attachedPhotoType збережено!"
                                }
                            } catch (e: Exception) {
                                photoUploadStatus = "Збережено мітку фото $attachedPhotoType"
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
                onClick = {
                    scope.launch {
                        val briefId = initialBrief?.id ?: "brief-${UUID.randomUUID().toString().take(8)}"
                        val masterId = app.authRepository.getMasterId() ?: "m-01"

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
                            deleted = false,
                            version = (initialBrief?.version ?: 0) + 1,
                            updatedAt = ""
                        )

                        // Save in local Room
                        app.database.visitBriefDao().insertOrUpdate(entity)

                        // Sync to backend
                        try {
                            val api = app.apiClient.getApiService()
                            api.saveVisitBrief(
                                mapOf(
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
                                    "photo_before_url" to photoBeforeUrl,
                                    "photo_after_url" to photoAfterUrl
                                )
                            )
                            Toast.makeText(context, "Бриф візиту збережено!", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            // Offline queue
                            app.syncRepository.queueMutation(
                                action = "visit_brief_save",
                                payload = mapOf(
                                    "id" to briefId,
                                    "appointment_id" to appointmentId,
                                    "client_id" to clientId,
                                    "coating_condition" to coatingCondition,
                                    "nail_plate_type" to nailPlateType,
                                    "cuticle_type" to cuticleType,
                                    "base_gel_used" to baseGelUsed,
                                    "design_and_length" to designAndLength,
                                    "health_notes" to healthNotes,
                                    "coffee_prefs" to coffeePrefs,
                                    "material_notes" to materialNotes
                                )
                            )
                            Toast.makeText(context, "Бриф збережено локально (офлайн)", Toast.LENGTH_SHORT).show()
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
}

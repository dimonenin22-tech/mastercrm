package com.mastercrm.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.mastercrm.MasterCrmApp
import com.mastercrm.data.local.entity.ServiceEntity
import com.mastercrm.ui.theme.RosePrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter

fun generateStoryBitmap(
    title: String,
    subtitle: String,
    items: List<Pair<String, String>>,
    footer: String
): Bitmap {
    val width = 1080
    val height = 1920
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // 1. Background gradient (Beauty Studio Luxury Gradient)
    val gradient = LinearGradient(
        0f, 0f, 0f, height.toFloat(),
        intArrayOf(
            android.graphics.Color.parseColor("#FFF5F7"),
            android.graphics.Color.parseColor("#FCE4EC"),
            android.graphics.Color.parseColor("#F8BBD0")
        ),
        floatArrayOf(0f, 0.5f, 1f),
        Shader.TileMode.CLAMP
    )
    val bgPaint = Paint().apply { shader = gradient }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

    // Decorative frame
    val framePaint = Paint().apply {
        color = android.graphics.Color.parseColor("#E91E63")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }
    canvas.drawRoundRect(40f, 40f, (width - 40).toFloat(), (height - 40).toFloat(), 32f, 32f, framePaint)

    // 2. Title & Subtitle Paint
    val titlePaint = Paint().apply {
        color = android.graphics.Color.parseColor("#880E4F")
        textSize = 62f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    val subtitlePaint = Paint().apply {
        color = android.graphics.Color.parseColor("#4A148C")
        textSize = 34f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    canvas.drawText(title, (width / 2).toFloat(), 200f, titlePaint)
    canvas.drawText(subtitle, (width / 2).toFloat(), 260f, subtitlePaint)

    // Decorative line
    val linePaint = Paint().apply {
        color = android.graphics.Color.parseColor("#E91E63")
        strokeWidth = 4f
    }
    canvas.drawLine(200f, 310f, (width - 200).toFloat(), 310f, linePaint)

    // 3. Items list
    var yOffset = 420f
    val itemTitlePaint = Paint().apply {
        color = android.graphics.Color.parseColor("#212121")
        textSize = 38f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }

    val itemValuePaint = Paint().apply {
        color = android.graphics.Color.parseColor("#AD1457")
        textSize = 38f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
        isAntiAlias = true
    }

    val cardBgPaint = Paint().apply {
        color = android.graphics.Color.parseColor("#FFFFFF")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    for ((leftText, rightText) in items) {
        if (yOffset > 1600f) break

        // White card background for each item
        canvas.drawRoundRect(100f, yOffset - 50f, (width - 100).toFloat(), yOffset + 35f, 20f, 20f, cardBgPaint)

        canvas.drawText(leftText, 140f, yOffset, itemTitlePaint)
        canvas.drawText(rightText, (width - 140).toFloat(), yOffset, itemValuePaint)
        yOffset += 120f
    }

    // 4. Footer
    val footerPaint = Paint().apply {
        color = android.graphics.Color.parseColor("#4A148C")
        textSize = 36f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    canvas.drawText(footer, (width / 2).toFloat(), (height - 120).toFloat(), footerPaint)

    return bitmap
}

fun shareBitmapStory(context: Context, bitmap: Bitmap, fileName: String) {
    val storiesDir = File(context.cacheDir, "stories").apply { mkdirs() }
    val file = File(storiesDir, fileName)

    FileOutputStream(file).use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    }

    val contentUri: Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, contentUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Поділитися у Instagram Stories"))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketingScreen() {
    val app = MasterCrmApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val services by app.database.serviceDao().getActiveServicesFlow().collectAsState(initial = emptyList())
    var isGenerating by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Маркетинг & Сторіс", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Генератор контенту для Instagram (1080x1920):",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )

            // Story Card 1: Free Slots
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = RosePrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Сторіс «Вільні віконця»",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Генерує готову стильну картинку 1080x1920 з розкладом віконців на найближчі дні для публікації в Instagram.",
                        color = Color.Gray,
                        fontSize = 13.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            scope.launch {
                                isGenerating = true
                                withContext(Dispatchers.Default) {
                                    val today = LocalDate.now()
                                    val slots = listOf(
                                        today.format(DateTimeFormatter.ofPattern("EEE, d MMM")) to "10:00, 14:00",
                                        today.plusDays(1).format(DateTimeFormatter.ofPattern("EEE, d MMM")) to "11:30, 16:00",
                                        today.plusDays(2).format(DateTimeFormatter.ofPattern("EEE, d MMM")) to "13:00, 17:30",
                                        today.plusDays(3).format(DateTimeFormatter.ofPattern("EEE, d MMM")) to "10:00, 15:00",
                                        today.plusDays(4).format(DateTimeFormatter.ofPattern("EEE, d MMM")) to "12:00, 16:30"
                                    )

                                    val bmp = generateStoryBitmap(
                                        title = "ВІЛЬНІ ВІКОНЦЯ 💅",
                                        subtitle = "Оберіть зручний час для вашого манікюру",
                                        items = slots,
                                        footer = "Запис онлайн через Telegram бот або в Direct! 💌"
                                    )
                                    withContext(Dispatchers.Main) {
                                        shareBitmapStory(context, bmp, "story_slots.png")
                                    }
                                }
                                isGenerating = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RosePrimary),
                        shape = RoundedCornerShape(10.dp),
                        enabled = !isGenerating,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Згенерувати та поділитися")
                    }
                }
            }

            // Story Card 2: PriceList Story
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color(0xFF673AB7))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Сторіс «Прайс-лист»",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Автоматично бере актуальні ціни з вашої бази послуг та створює естетичний прайс-лист для закріплених сторіс.",
                        color = Color.Gray,
                        fontSize = 13.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            scope.launch {
                                isGenerating = true
                                withContext(Dispatchers.Default) {
                                    val priceItems = if (services.isNotEmpty()) {
                                        services.take(6).map { s -> s.title to "${s.price.toInt()} грн" }
                                    } else {
                                        listOf(
                                            "Комплекс манікюру" to "650 грн",
                                            "Гігієнічний манікюр" to "350 грн",
                                            "Педикюр з покриттям" to "800 грн",
                                            "Зняття матеріалу" to "150 грн",
                                            "Дизайн (1 ніготь)" to "50 грн"
                                        )
                                    }

                                    val bmp = generateStoryBitmap(
                                        title = "ПРАЙС-ЛИСТ ПОСЛУГ ✨",
                                        subtitle = "Турбота та краса ваших нігтів",
                                        items = priceItems,
                                        footer = "Чекаємо на вас у нашій затишній студії! ☕️"
                                    )
                                    withContext(Dispatchers.Main) {
                                        shareBitmapStory(context, bmp, "story_pricelist.png")
                                    }
                                }
                                isGenerating = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF673AB7)),
                        shape = RoundedCornerShape(10.dp),
                        enabled = !isGenerating,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Згенерувати та поділитися")
                    }
                }
            }
        }
    }
}

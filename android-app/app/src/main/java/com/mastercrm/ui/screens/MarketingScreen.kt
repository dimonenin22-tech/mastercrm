package com.mastercrm.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.RateReview
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
import com.mastercrm.data.local.entity.ReviewEntity
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

fun generateTelegramReviewStoryBitmap(
    clientName: String,
    rating: Int,
    reviewText: String,
    masterReply: String,
    footerBotText: String
): Bitmap {
    val width = 1080
    val height = 1920
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // 1. Dark Telegram Messenger Background (#0e1621)
    val bgPaint = Paint().apply {
        color = android.graphics.Color.parseColor("#0e1621")
    }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

    // 2. Top Header Bar (Telegram chat header #17212b)
    val headerPaint = Paint().apply {
        color = android.graphics.Color.parseColor("#17212b")
    }
    canvas.drawRect(0f, 0f, width.toFloat(), 220f, headerPaint)

    // Avatar Circle
    val avatarPaint = Paint().apply {
        color = android.graphics.Color.parseColor("#E91E63")
        isAntiAlias = true
    }
    canvas.drawCircle(120f, 110f, 55f, avatarPaint)

    val avatarLetterPaint = Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 48f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    canvas.drawText("💅", 120f, 126f, avatarLetterPaint)

    // Header Title
    val headerTitlePaint = Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 40f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }
    canvas.drawText("MasterCRM | Відгуки клієнтів", 210f, 105f, headerTitlePaint)

    val headerSubPaint = Paint().apply {
        color = android.graphics.Color.parseColor("#56b3f5")
        textSize = 28f
        isAntiAlias = true
    }
    canvas.drawText("онлайн • перевірені візити", 210f, 145f, headerSubPaint)

    // 3. Client Review Message Bubble (#182533)
    val bubblePaint = Paint().apply {
        color = android.graphics.Color.parseColor("#182533")
        isAntiAlias = true
    }
    canvas.drawRoundRect(70f, 320f, (width - 70).toFloat(), 920f, 28f, 28f, bubblePaint)

    // Client Name inside bubble
    val clientNamePaint = Paint().apply {
        color = android.graphics.Color.parseColor("#56b3f5")
        textSize = 36f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }
    canvas.drawText(clientName, 110f, 390f, clientNamePaint)

    // Stars Rating
    val starsText = "⭐️".repeat(rating.coerceIn(1, 5))
    val starsPaint = Paint().apply {
        textSize = 42f
        isAntiAlias = true
    }
    canvas.drawText(starsText, 110f, 450f, starsPaint)

    // Review Text (Multiline wrap)
    val textPaint = Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 34f
        isAntiAlias = true
    }

    val words = reviewText.split(" ")
    var line = ""
    var textY = 530f
    for (w in words) {
        val testLine = if (line.isEmpty()) w else "$line $w"
        if (textPaint.measureText(testLine) < 860f) {
            line = testLine
        } else {
            canvas.drawText(line, 110f, textY, textPaint)
            line = w
            textY += 54f
        }
    }
    if (line.isNotEmpty()) {
        canvas.drawText(line, 110f, textY, textPaint)
    }

    // Time stamp
    val timePaint = Paint().apply {
        color = android.graphics.Color.parseColor("#6c7883")
        textSize = 24f
        textAlign = Paint.Align.RIGHT
        isAntiAlias = true
    }
    canvas.drawText("14:32", (width - 110).toFloat(), 880f, timePaint)

    // 4. Master Reply Bubble (#2b5278)
    val replyBubblePaint = Paint().apply {
        color = android.graphics.Color.parseColor("#2b5278")
        isAntiAlias = true
    }
    canvas.drawRoundRect(200f, 980f, (width - 70).toFloat(), 1380f, 28f, 28f, replyBubblePaint)

    val replyTitlePaint = Paint().apply {
        color = android.graphics.Color.parseColor("#80c4f8")
        textSize = 34f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }
    canvas.drawText("Відповідь студії / майстра:", 240f, 1050f, replyTitlePaint)

    val replyWords = masterReply.split(" ")
    var rLine = ""
    var rY = 1120f
    for (w in replyWords) {
        val testLine = if (rLine.isEmpty()) w else "$rLine $w"
        if (textPaint.measureText(testLine) < 730f) {
            rLine = testLine
        } else {
            canvas.drawText(rLine, 240f, rY, textPaint)
            rLine = w
            rY += 54f
        }
    }
    if (rLine.isNotEmpty()) {
        canvas.drawText(rLine, 240f, rY, textPaint)
    }

    canvas.drawText("14:35 ✓✓", (width - 110).toFloat(), 1340f, timePaint)

    // 5. Bottom Brand & Booking Banner
    val bannerPaint = Paint().apply {
        color = android.graphics.Color.parseColor("#17212b")
        isAntiAlias = true
    }
    canvas.drawRoundRect(70f, 1540f, (width - 70).toFloat(), 1760f, 24f, 24f, bannerPaint)

    val bannerBorder = Paint().apply {
        color = android.graphics.Color.parseColor("#E91E63")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    canvas.drawRoundRect(70f, 1540f, (width - 70).toFloat(), 1760f, 24f, 24f, bannerBorder)

    val bannerTextPaint = Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 36f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    canvas.drawText("💌 Запис на манікюр 24/7:", (width / 2).toFloat(), 1620f, bannerTextPaint)

    val bannerSubTextPaint = Paint().apply {
        color = android.graphics.Color.parseColor("#E91E63")
        textSize = 34f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    canvas.drawText(footerBotText, (width / 2).toFloat(), 1690f, bannerSubTextPaint)

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

    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Вільні віконця", "Прайс-лист", "Відгуки клієнтів")

    val services by app.database.serviceDao().getActiveServicesFlow().collectAsState(initial = emptyList())
    val reviews by app.database.reviewDao().getAllReviewsFlow().collectAsState(initial = emptyList())

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
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = RosePrimary
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp) }
                    )
                }
            }

            when (selectedTab) {
                0 -> FreeSlotsStoryTab(onShareSlots = { title, sub, items, footer ->
                    scope.launch(Dispatchers.Default) {
                        val bmp = generateStoryBitmap(title, sub, items, footer)
                        withContext(Dispatchers.Main) {
                            shareBitmapStory(context, bmp, "story_slots.png")
                        }
                    }
                })
                1 -> PriceListStoryTab(services = services, onSharePrice = { title, sub, items, footer ->
                    scope.launch(Dispatchers.Default) {
                        val bmp = generateStoryBitmap(title, sub, items, footer)
                        withContext(Dispatchers.Main) {
                            shareBitmapStory(context, bmp, "story_pricelist.png")
                        }
                    }
                })
                2 -> ReviewStoryGeneratorTab(reviews = reviews, onShareReview = { client, rating, text, reply, footer ->
                    scope.launch(Dispatchers.Default) {
                        val bmp = generateTelegramReviewStoryBitmap(client, rating, text, reply, footer)
                        withContext(Dispatchers.Main) {
                            shareBitmapStory(context, bmp, "story_review_tg.png")
                        }
                    }
                })
            }
        }
    }
}

@Composable
fun FreeSlotsStoryTab(
    onShareSlots: (title: String, sub: String, items: List<Pair<String, String>>, footer: String) -> Unit
) {
    var isGenerating by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
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
                    text = "Генерує готову естетичну сторіс 1080x1920 у фірмовому б'юті-градієнті для швидкого запису клієнтів на найближчі дні.",
                    color = androidx.compose.ui.graphics.Color.Gray,
                    fontSize = 13.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        isGenerating = true
                        val today = LocalDate.now()
                        val slots = listOf(
                            today.format(DateTimeFormatter.ofPattern("EEE, d MMM")) to "10:00, 14:00",
                            today.plusDays(1).format(DateTimeFormatter.ofPattern("EEE, d MMM")) to "11:30, 16:00",
                            today.plusDays(2).format(DateTimeFormatter.ofPattern("EEE, d MMM")) to "13:00, 17:30",
                            today.plusDays(3).format(DateTimeFormatter.ofPattern("EEE, d MMM")) to "10:00, 15:00",
                            today.plusDays(4).format(DateTimeFormatter.ofPattern("EEE, d MMM")) to "12:00, 16:30"
                        )
                        onShareSlots(
                            "ВІЛЬНІ ВІКОНЦЯ 💅",
                            "Оберіть зручний час для вашого манікюру",
                            slots,
                            "Запис онлайн через Telegram бот або в Direct! 💌"
                        )
                        isGenerating = false
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
    }
}

@Composable
fun PriceListStoryTab(
    services: List<ServiceEntity>,
    onSharePrice: (title: String, sub: String, items: List<Pair<String, String>>, footer: String) -> Unit
) {
    var isGenerating by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
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
                    text = "Автоматично бере актуальні ціни з вашої бази послуг та генерує преміум-прайс для закріплених хайлайтс в Instagram.",
                    color = androidx.compose.ui.graphics.Color.Gray,
                    fontSize = 13.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        isGenerating = true
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

                        onSharePrice(
                            "ПРАЙС-ЛИСТ ПОСЛУГ ✨",
                            "Турбота та краса ваших нігтів",
                            priceItems,
                            "Чекаємо на вас у нашій затишній студії! ☕️"
                        )
                        isGenerating = false
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

@Composable
fun ReviewStoryGeneratorTab(
    reviews: List<ReviewEntity>,
    onShareReview: (client: String, rating: Int, text: String, reply: String, footer: String) -> Unit
) {
    var clientName by remember { mutableStateOf("Олена (Клієнтка)") }
    var rating by remember { mutableStateOf(5) }
    var reviewText by remember { mutableStateOf("Неймовірна робота майстра! Манікюр тримається вже 4-й тиждень без жодної відслойки. Кава та атмосфера на висоті! Дуже дякую! ❤️") }
    var masterReply by remember { mutableStateOf("Олено, щиро дякуємо за довіру! Завжди раді бачити вас знову! ✨") }
    var botHandle by remember { mutableStateOf("@MasterCRM_bot") }
    var isGenerating by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.RateReview, contentDescription = null, tint = RosePrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Генератор сторіс відгуку (Telegram Chat)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }

                    Text("Створює ультра-реалістичний скріншот листування в Telegram з відгуком клієнта для Instagram Stories (1080x1920).", color = Color.Gray, fontSize = 12.sp)

                    OutlinedTextField(
                        value = clientName,
                        onValueChange = { clientName = it },
                        label = { Text("Ім'я клієнта") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Оцінка:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        (1..5).forEach { star ->
                            FilterChip(
                                selected = rating == star,
                                onClick = { rating = star },
                                label = { Text("⭐ $star", fontSize = 11.sp) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = reviewText,
                        onValueChange = { reviewText = it },
                        label = { Text("Текст відгуку клієнта") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = masterReply,
                        onValueChange = { masterReply = it },
                        label = { Text("Відповідь майстра") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = botHandle,
                        onValueChange = { botHandle = it },
                        label = { Text("Telegram бот студії для запису") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            isGenerating = true
                            onShareReview(clientName, rating, reviewText, masterReply, botHandle)
                            isGenerating = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RosePrimary),
                        shape = RoundedCornerShape(10.dp),
                        enabled = !isGenerating && clientName.isNotBlank() && reviewText.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Згенерувати Telegram Story", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

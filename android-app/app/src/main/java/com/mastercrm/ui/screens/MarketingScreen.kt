package com.mastercrm.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.core.content.FileProvider
import com.mastercrm.MasterCrmApp
import com.mastercrm.data.local.entity.ClientEntity
import com.mastercrm.data.local.entity.ReviewEntity
import com.mastercrm.data.local.entity.ServiceEntity
import com.mastercrm.ui.theme.RosePrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class StoryStyle(
    val title: String,
    val bgStart: Int,
    val bgEnd: Int,
    val cardBg: Int,
    val cardBorder: Int,
    val primaryText: Int,
    val accentText: Int,
    val subText: Int,
    val isDark: Boolean
) {
    GLAMOUR_DARK(
        title = "Dark Luxury",
        bgStart = 0xFF0B0F19.toInt(),
        bgEnd = 0xFF1E1B4B.toInt(),
        cardBg = 0xDD1F2937.toInt(),
        cardBorder = 0xFFF472B6.toInt(),
        primaryText = 0xFFFFFFFF.toInt(),
        accentText = 0xFFF472B6.toInt(),
        subText = 0xFF9CA3AF.toInt(),
        isDark = true
    ),
    NUDE_MINIMAL(
        title = "Nude Aesthetic",
        bgStart = 0xFFFAF5F0.toInt(),
        bgEnd = 0xFFEFE6DD.toInt(),
        cardBg = 0xF5FFFFFF.toInt(),
        cardBorder = 0xFFD7CCC8.toInt(),
        primaryText = 0xFF3E2723.toInt(),
        accentText = 0xFF8D6E63.toInt(),
        subText = 0xFF795548.toInt(),
        isDark = false
    ),
    ROSE_GLASS(
        title = "Soft Pink Glass",
        bgStart = 0xFFFCE7F3.toInt(),
        bgEnd = 0xFFFBCFE8.toInt(),
        cardBg = 0xE6FFFFFF.toInt(),
        cardBorder = 0xFFEC4899.toInt(),
        primaryText = 0xFF831843.toInt(),
        accentText = 0xFFBE185D.toInt(),
        subText = 0xFF9D174D.toInt(),
        isDark = false
    ),
    NEON_CYBER(
        title = "Bold Pop",
        bgStart = 0xFF09090B.toInt(),
        bgEnd = 0xFF18181B.toInt(),
        cardBg = 0xF218181B.toInt(),
        cardBorder = 0xFFEC4899.toInt(),
        primaryText = 0xFFFFFFFF.toInt(),
        accentText = 0xFFEC4899.toInt(),
        subText = 0xFF06B6D4.toInt(),
        isDark = true
    ),
    CLEAN_EDITORIAL(
        title = "Vogue Monochrome",
        bgStart = 0xFFF8FAFC.toInt(),
        bgEnd = 0xFFF1F5F9.toInt(),
        cardBg = 0xFFFFFFFF.toInt(),
        cardBorder = 0xFF0F172A.toInt(),
        primaryText = 0xFF0F172A.toInt(),
        accentText = 0xFF0F172A.toInt(),
        subText = 0xFF64748B.toInt(),
        isDark = false
    )
}

data class ReviewStoryData(
    val clientName: String,
    val rating: Int,
    val reviewText: String,
    val masterReply: String,
    val serviceTitle: String,
    val timestamp: String = "18:45"
)

fun renderCanvaStoryBitmap(
    mode: Int, // 0 = Slots, 1 = Prices, 2 = Review
    style: StoryStyle,
    headerTitle: String,
    headerSubtitle: String,
    footerText: String,
    slotsDateString: String,
    activeTimeSlots: List<String>,
    activeServices: List<Pair<String, String>>, // Title, Price (and optional duration)
    reviewData: ReviewStoryData
): Bitmap {
    val width = 1080
    val height = 1920
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // 1. Background Gradient
    val gradient = LinearGradient(
        0f, 0f, 0f, height.toFloat(),
        intArrayOf(style.bgStart, style.bgEnd),
        floatArrayOf(0f, 1f),
        Shader.TileMode.CLAMP
    )
    val bgPaint = Paint().apply { shader = gradient }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

    // 2. Luxury Outer Frame
    val framePaint = Paint().apply {
        color = style.cardBorder
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    canvas.drawRoundRect(40f, 40f, (width - 40).toFloat(), (height - 40).toFloat(), 32f, 32f, framePaint)

    // 3. Header
    val titlePaint = Paint().apply {
        color = style.accentText
        textSize = 58f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    canvas.drawText(headerTitle, (width / 2).toFloat(), 180f, titlePaint)

    val subtitlePaint = Paint().apply {
        color = style.subText
        textSize = 30f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    canvas.drawText(headerSubtitle, (width / 2).toFloat(), 235f, subtitlePaint)

    val dividerPaint = Paint().apply {
        color = style.cardBorder
        strokeWidth = 3f
        isAntiAlias = true
    }
    canvas.drawLine(240f, 280f, (width - 240).toFloat(), 280f, dividerPaint)

    // 4. Content Area by Mode
    when (mode) {
        0 -> { // Free Slots
            // Date Card
            val cardPaint = Paint().apply {
                color = style.cardBg
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            val borderPaint = Paint().apply {
                color = style.cardBorder
                style = Paint.Style.STROKE
                strokeWidth = 2.5f
                isAntiAlias = true
            }

            canvas.drawRoundRect(100f, 330f, (width - 100).toFloat(), 430f, 20f, 20f, cardPaint)
            canvas.drawRoundRect(100f, 330f, (width - 100).toFloat(), 430f, 20f, 20f, borderPaint)

            val dateTextPaint = Paint().apply {
                color = style.accentText
                textSize = 38f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }
            canvas.drawText(slotsDateString, (width / 2).toFloat(), 395f, dateTextPaint)

            // Slot Badges Grid (2 columns)
            var curY = 490f
            val leftColX = 140f
            val rightColX = 560f
            val slotWidth = 380f
            val slotHeight = 90f

            val slotBgPaint = Paint().apply {
                color = style.cardBg
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            val slotBorderPaint = Paint().apply {
                color = style.cardBorder
                style = Paint.Style.STROKE
                strokeWidth = 2f
                isAntiAlias = true
            }
            val slotTextPaint = Paint().apply {
                color = style.primaryText
                textSize = 36f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }

            for (i in activeTimeSlots.indices) {
                if (curY > 1550f) break
                val isLeft = (i % 2 == 0)
                val x = if (isLeft) leftColX else rightColX
                val y = curY

                canvas.drawRoundRect(x, y, x + slotWidth, y + slotHeight, 16f, 16f, slotBgPaint)
                canvas.drawRoundRect(x, y, x + slotWidth, y + slotHeight, 16f, 16f, slotBorderPaint)
                canvas.drawText(activeTimeSlots[i], x + (slotWidth / 2), y + 58f, slotTextPaint)

                if (!isLeft || i == activeTimeSlots.size - 1) {
                    curY += 120f
                }
            }

            if (activeTimeSlots.isEmpty()) {
                val emptyPaint = Paint().apply {
                    color = style.subText
                    textSize = 36f
                    textAlign = Paint.Align.CENTER
                    isAntiAlias = true
                }
                canvas.drawText("Всі віконця зайняті на цю дату 💅", (width / 2).toFloat(), 700f, emptyPaint)
            }
        }
        1 -> { // Price List
            var curY = 360f
            val itemBgPaint = Paint().apply {
                color = style.cardBg
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            val itemBorderPaint = Paint().apply {
                color = style.cardBorder
                style = Paint.Style.STROKE
                strokeWidth = 2f
                isAntiAlias = true
            }
            val titleTextPaint = Paint().apply {
                color = style.primaryText
                textSize = 34f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
            }
            val priceTextPaint = Paint().apply {
                color = style.accentText
                textSize = 36f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.RIGHT
                isAntiAlias = true
            }

            for ((srvTitle, srvPrice) in activeServices) {
                if (curY > 1580f) break
                canvas.drawRoundRect(90f, curY, (width - 90).toFloat(), curY + 115f, 18f, 18f, itemBgPaint)
                canvas.drawRoundRect(90f, curY, (width - 90).toFloat(), curY + 115f, 18f, 18f, itemBorderPaint)

                canvas.drawText(srvTitle, 130f, curY + 70f, titleTextPaint)
                canvas.drawText(srvPrice, (width - 130).toFloat(), curY + 70f, priceTextPaint)
                curY += 145f
            }
        }
        2 -> { // Telegram Chat Review
            val chatBgPaint = Paint().apply {
                color = if (style.isDark) 0xFF17212B.toInt() else 0xFFF0F4F8.toInt()
                isAntiAlias = true
            }
            canvas.drawRoundRect(80f, 320f, (width - 80).toFloat(), 1520f, 28f, 28f, chatBgPaint)

            // Chat Header
            val avatarPaint = Paint().apply {
                color = style.accentText
                isAntiAlias = true
            }
            canvas.drawCircle(160f, 410f, 50f, avatarPaint)

            val initials = reviewData.clientName.take(2).uppercase(Locale.getDefault())
            val avatarTextPaint = Paint().apply {
                color = Color.White.hashCode()
                textSize = 40f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }
            canvas.drawText(initials.ifBlank { "💅" }, 160f, 424f, avatarTextPaint)

            val clientTitlePaint = Paint().apply {
                color = if (style.isDark) Color.White.hashCode() else 0xFF0F172A.toInt()
                textSize = 38f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
            }
            canvas.drawText(reviewData.clientName, 240f, 400f, clientTitlePaint)

            val serviceBadgePaint = Paint().apply {
                color = style.accentText
                textSize = 28f
                isAntiAlias = true
            }
            canvas.drawText("💅 ${reviewData.serviceTitle}", 240f, 440f, serviceBadgePaint)

            // Incoming Message Bubble
            val bubblePaint = Paint().apply {
                color = if (style.isDark) 0xFF182533.toInt() else Color.White.hashCode()
                isAntiAlias = true
            }
            canvas.drawRoundRect(120f, 500f, (width - 120).toFloat(), 1050f, 24f, 24f, bubblePaint)

            // Rating Stars
            val stars = "⭐️".repeat(reviewData.rating.coerceIn(1, 5))
            val starsPaint = Paint().apply {
                textSize = 44f
                isAntiAlias = true
            }
            canvas.drawText(stars, 160f, 570f, starsPaint)

            // Multiline review text
            val reviewTextPaint = Paint().apply {
                color = if (style.isDark) Color.White.hashCode() else 0xFF1E293B.toInt()
                textSize = 32f
                isAntiAlias = true
            }
            val words = reviewData.reviewText.split(" ")
            var line = ""
            var textY = 640f
            for (w in words) {
                val test = if (line.isEmpty()) w else "$line $w"
                if (reviewTextPaint.measureText(test) < 740f) {
                    line = test
                } else {
                    canvas.drawText(line, 160f, textY, reviewTextPaint)
                    line = w
                    textY += 50f
                }
            }
            if (line.isNotEmpty()) {
                canvas.drawText(line, 160f, textY, reviewTextPaint)
            }

            // Timestamp in client bubble
            val timePaint = Paint().apply {
                color = if (style.isDark) 0xFF6C7883.toInt() else 0xFF94A3B8.toInt()
                textSize = 22f
                textAlign = Paint.Align.RIGHT
                isAntiAlias = true
            }
            canvas.drawText(reviewData.timestamp, (width - 150).toFloat(), 1020f, timePaint)

            // Master Reply Bubble
            val replyBgPaint = Paint().apply {
                color = if (style.isDark) 0xFF2B5278.toInt() else 0xFFE0F2FE.toInt()
                isAntiAlias = true
            }
            canvas.drawRoundRect(220f, 1100f, (width - 120).toFloat(), 1460f, 24f, 24f, replyBgPaint)

            val replyHeaderPaint = Paint().apply {
                color = if (style.isDark) 0xFF80C4F8.toInt() else 0xFF0284C7.toInt()
                textSize = 30f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                isAntiAlias = true
            }
            canvas.drawText("MasterCRM Відповідь:", 260f, 1160f, replyHeaderPaint)

            val replyTextPaint = Paint().apply {
                color = if (style.isDark) Color.White.hashCode() else 0xFF0F172A.toInt()
                textSize = 30f
                isAntiAlias = true
            }
            val rWords = reviewData.masterReply.split(" ")
            var rLine = ""
            var rY = 1220f
            for (w in rWords) {
                val test = if (rLine.isEmpty()) w else "$rLine $w"
                if (replyTextPaint.measureText(test) < 680f) {
                    rLine = test
                } else {
                    canvas.drawText(rLine, 260f, rY, replyTextPaint)
                    rLine = w
                    rY += 48f
                }
            }
            if (rLine.isNotEmpty()) {
                canvas.drawText(rLine, 260f, rY, replyTextPaint)
            }

            canvas.drawText("${reviewData.timestamp} ✓✓", (width - 150).toFloat(), 1430f, timePaint)
        }
    }

    // 5. Branded Footer
    val footerBoxPaint = Paint().apply {
        color = style.cardBg
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    val footerBorderPaint = Paint().apply {
        color = style.cardBorder
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        isAntiAlias = true
    }
    canvas.drawRoundRect(100f, 1720f, (width - 100).toFloat(), 1820f, 20f, 20f, footerBoxPaint)
    canvas.drawRoundRect(100f, 1720f, (width - 100).toFloat(), 1820f, 20f, 20f, footerBorderPaint)

    val footerPaint = Paint().apply {
        color = style.accentText
        textSize = 32f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    canvas.drawText(footerText, (width / 2).toFloat(), 1782f, footerPaint)

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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MarketingScreen() {
    val app = MasterCrmApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedMode by remember { mutableStateOf(0) } // 0 = Free Slots, 1 = Price List, 2 = Review
    val modes = listOf("Вільні віконця", "Прайс-лист", "Відгук клієнта")

    var selectedStyle by remember { mutableStateOf(StoryStyle.GLAMOUR_DARK) }

    // Mode 0: Free Slots State
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var freeSlotsTitle by remember { mutableStateOf("ВІЛЬНІ ВІКОНЦЯ 💅") }
    var freeSlotsSubtitle by remember { mutableStateOf("Оберіть зручний час для вашого манікюру") }
    var freeSlotsFooter by remember { mutableStateOf("Онлайн-запис через Telegram бот або в Direct! 💌") }
    var customTimeSlots by remember { mutableStateOf(mutableListOf<String>()) }
    var activeSlotToggles by remember { mutableStateOf(mapOf<String, Boolean>()) }
    var showAddCustomSlotDialog by remember { mutableStateOf(false) }

    // Mode 1: Price List State
    val allServices by app.database.serviceDao().getActiveServicesFlow().collectAsState(initial = emptyList())
    var priceTitle by remember { mutableStateOf("ПРАЙС-ЛИСТ ПОСЛУГ ✨") }
    var priceSubtitle by remember { mutableStateOf("Турбота та краса ваших нігтів") }
    var priceFooter by remember { mutableStateOf("Чекаємо на вас у нашій затишній студії! ☕️") }
    var showDurationInPrice by remember { mutableStateOf(true) }
    var includedServiceIds by remember { mutableStateOf(setOf<String>()) }

    // Mode 2: Client Review State
    val allClients by app.database.clientDao().getAllClientsFlow().collectAsState(initial = emptyList())
    val allReviews by app.database.reviewDao().getAllReviewsFlow().collectAsState(initial = emptyList())
    var selectedClient by remember { mutableStateOf<ClientEntity?>(null) }
    var reviewTitle by remember { mutableStateOf("ВІДГУКИ КЛІЄНТІВ ❤️") }
    var reviewSubtitle by remember { mutableStateOf("Чесні враження про наші процедури") }
    var reviewClientName by remember { mutableStateOf("Олена (Клієнтка)") }
    var reviewServiceTitle by remember { mutableStateOf("Комплекс манікюру") }
    var reviewRating by remember { mutableStateOf(5) }
    var reviewText by remember { mutableStateOf("Неймовірна робота майстра! Манікюр тримається вже 4-й тиждень без жодної відслойки. Кава та атмосфера на висоті! Дуже дякую! ❤️") }
    var reviewMasterReply by remember { mutableStateOf("Олено, щиро дякуємо за довіру! Завжди раді бачити вас знову! ✨") }
    var reviewFooter by remember { mutableStateOf("Запис 24/7 у нашому Telegram боті! 💌") }

    // Sync included services on first load
    LaunchedEffect(allServices) {
        if (includedServiceIds.isEmpty() && allServices.isNotEmpty()) {
            includedServiceIds = allServices.take(6).map { it.id }.toSet()
        }
    }

    // Dynamic Free Slots Calculation for selectedDate
    val dateAppointments by app.database.appointmentDao().getAppointmentsByDateFlow(
        selectedDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
    ).collectAsState(initial = emptyList())

    val calculatedFreeSlots = remember(selectedDate, dateAppointments, customTimeSlots) {
        val busyTimes = dateAppointments
            .filter { it.status in listOf("PENDING", "CONFIRMED", "COMPLETED") }
            .map { it.startTime }
            .toSet()

        val generated = mutableListOf<String>()
        var cur = LocalTime.of(10, 0)
        val end = LocalTime.of(19, 0)
        while (!cur.isAfter(end)) {
            val timeStr = cur.format(DateTimeFormatter.ofPattern("HH:mm"))
            if (!busyTimes.contains(timeStr)) {
                generated.add(timeStr)
            }
            cur = cur.plusMinutes(90)
        }
        (generated + customTimeSlots).distinct().sorted()
    }

    // Initialize toggles for slots
    LaunchedEffect(calculatedFreeSlots) {
        val newMap = activeSlotToggles.toMutableMap()
        for (slot in calculatedFreeSlots) {
            if (!newMap.containsKey(slot)) {
                newMap[slot] = true
            }
        }
        activeSlotToggles = newMap
    }

    val activeSelectedSlots = remember(calculatedFreeSlots, activeSlotToggles) {
        calculatedFreeSlots.filter { activeSlotToggles[it] != false }
    }

    // Active price items
    val activePriceItems = remember(allServices, includedServiceIds, showDurationInPrice) {
        val list = if (allServices.isNotEmpty()) {
            allServices.filter { includedServiceIds.contains(it.id) }.map { s ->
                val title = if (showDurationInPrice) "${s.title} (${s.durationMinutes} хв)" else s.title
                title to "${s.price.toInt()} грн"
            }
        } else {
            listOf(
                "Комплекс манікюру (90 хв)" to "650 грн",
                "Гігієнічний манікюр (45 хв)" to "350 грн",
                "Педикюр з покриттям (80 хв)" to "800 грн",
                "Зняття матеріалу (30 хв)" to "150 грн",
                "Дизайн нігтів (20 хв)" to "100 грн"
            )
        }
        list
    }

    // Real-Time Live Bitmap Generation
    var liveBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(
        selectedMode,
        selectedStyle,
        freeSlotsTitle,
        freeSlotsSubtitle,
        freeSlotsFooter,
        selectedDate,
        activeSelectedSlots,
        priceTitle,
        priceSubtitle,
        priceFooter,
        activePriceItems,
        reviewTitle,
        reviewSubtitle,
        reviewClientName,
        reviewServiceTitle,
        reviewRating,
        reviewText,
        reviewMasterReply,
        reviewFooter
    ) {
        withContext(Dispatchers.Default) {
            val formattedDate = selectedDate.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale("uk")))
                .replaceFirstChar { it.uppercase() }

            val bmp = when (selectedMode) {
                0 -> renderCanvaStoryBitmap(
                    mode = 0,
                    style = selectedStyle,
                    headerTitle = freeSlotsTitle,
                    headerSubtitle = freeSlotsSubtitle,
                    footerText = freeSlotsFooter,
                    slotsDateString = formattedDate,
                    activeTimeSlots = activeSelectedSlots,
                    activeServices = emptyList(),
                    reviewData = ReviewStoryData("", 5, "", "", "")
                )
                1 -> renderCanvaStoryBitmap(
                    mode = 1,
                    style = selectedStyle,
                    headerTitle = priceTitle,
                    headerSubtitle = priceSubtitle,
                    footerText = priceFooter,
                    slotsDateString = "",
                    activeTimeSlots = emptyList(),
                    activeServices = activePriceItems,
                    reviewData = ReviewStoryData("", 5, "", "", "")
                )
                else -> renderCanvaStoryBitmap(
                    mode = 2,
                    style = selectedStyle,
                    headerTitle = reviewTitle,
                    headerSubtitle = reviewSubtitle,
                    footerText = reviewFooter,
                    slotsDateString = "",
                    activeTimeSlots = emptyList(),
                    activeServices = emptyList(),
                    reviewData = ReviewStoryData(
                        clientName = reviewClientName,
                        rating = reviewRating,
                        reviewText = reviewText,
                        masterReply = reviewMasterReply,
                        serviceTitle = reviewServiceTitle
                    )
                )
            }
            withContext(Dispatchers.Main) {
                liveBitmap = bmp
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Canva Story Studio ✨", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Live Preview Card (9:16 Aspect Ratio)
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                        modifier = Modifier
                            .fillMaxWidth(0.68f)
                            .aspectRatio(9f / 16f)
                            .clip(RoundedCornerShape(20.dp))
                            .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
                    ) {
                        if (liveBitmap != null) {
                            Image(
                                bitmap = liveBitmap!!.asImageBitmap(),
                                contentDescription = "Live Story Preview",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = RosePrimary)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            liveBitmap?.let { bmp ->
                                val fileName = when (selectedMode) {
                                    0 -> "story_slots_${System.currentTimeMillis()}.png"
                                    1 -> "story_prices_${System.currentTimeMillis()}.png"
                                    else -> "story_review_${System.currentTimeMillis()}.png"
                                }
                                shareBitmapStory(context, bmp, fileName)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RosePrimary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .height(48.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Поділитися в Instagram Stories", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // 2. Canva Visual Style Selector (5 signature styles)
            item {
                Text("Оберіть стиль дизайну:", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(6.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(StoryStyle.values()) { style ->
                        FilterChip(
                            selected = selectedStyle == style,
                            onClick = { selectedStyle = style },
                            label = { Text(style.title, fontSize = 12.sp) },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .background(Color(style.bgStart), CircleShape)
                                        .border(1.dp, Color.Gray, CircleShape)
                                )
                            }
                        )
                    }
                }
            }

            // 3. Mode Selector Tabs
            item {
                TabRow(
                    selectedTabIndex = selectedMode,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = RosePrimary
                ) {
                    modes.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedMode == index,
                            onClick = { selectedMode = index },
                            text = { Text(title, fontWeight = if (selectedMode == index) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp) }
                        )
                    }
                }
            }

            // 4. Mode-specific Controls
            when (selectedMode) {
                0 -> { // Mode 1: Free Slots Controls
                    item {
                        Text("Оберіть дату на найближчі 14 днів:", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items((0..13).toList()) { dayOffset ->
                                val date = LocalDate.now().plusDays(dayOffset.toLong())
                                val isSelected = selectedDate == date
                                val label = date.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale("uk")))
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { selectedDate = date },
                                    label = { Text(label, fontSize = 11.sp) }
                                )
                            }
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Віконця на цю дату (натисніть щоб приховати):", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            TextButton(onClick = { showAddCustomSlotDialog = true }) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(2.dp))
                                Text("Свій час", fontSize = 12.sp)
                            }
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            calculatedFreeSlots.forEach { slot ->
                                val isChecked = activeSlotToggles[slot] != false
                                FilterChip(
                                    selected = isChecked,
                                    onClick = {
                                        activeSlotToggles = activeSlotToggles.toMutableMap().apply {
                                            put(slot, !isChecked)
                                        }
                                    },
                                    label = { Text(slot, fontWeight = if (isChecked) FontWeight.Bold else FontWeight.Normal) },
                                    leadingIcon = {
                                        Icon(
                                            if (isChecked) Icons.Default.Check else Icons.Default.Close,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                )
                            }
                        }
                    }

                    item {
                        OutlinedTextField(
                            value = freeSlotsTitle,
                            onValueChange = { freeSlotsTitle = it },
                            label = { Text("Заголовок") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    item {
                        OutlinedTextField(
                            value = freeSlotsFooter,
                            onValueChange = { freeSlotsFooter = it },
                            label = { Text("Нижній заклик до дії (CTA)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                1 -> { // Mode 2: Price List Controls
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Показувати тривалість процедури (хв)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Switch(
                                checked = showDurationInPrice,
                                onCheckedChange = { showDurationInPrice = it }
                            )
                        }
                    }

                    item {
                        Text("Оберіть послуги для сторіс (з бази без видалення):", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }

                    items(allServices, key = { it.id }) { service ->
                        val isChecked = includedServiceIds.contains(service.id)
                        ElevatedCard(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = if (isChecked) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(service.title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text("${service.price.toInt()} грн • ${service.durationMinutes} хв", color = Color.Gray, fontSize = 12.sp)
                                }
                                Switch(
                                    checked = isChecked,
                                    onCheckedChange = {
                                        includedServiceIds = if (it) {
                                            includedServiceIds + service.id
                                        } else {
                                            includedServiceIds - service.id
                                        }
                                    }
                                )
                            }
                        }
                    }

                    item {
                        OutlinedTextField(
                            value = priceTitle,
                            onValueChange = { priceTitle = it },
                            label = { Text("Заголовок прайсу") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    item {
                        OutlinedTextField(
                            value = priceFooter,
                            onValueChange = { priceFooter = it },
                            label = { Text("Нижній текст сторіс") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                2 -> { // Mode 3: Telegram Review Controls
                    item {
                        var expandedClientDropdown by remember { mutableStateOf(false) }

                        ExposedDropdownMenuBox(
                            expanded = expandedClientDropdown,
                            onExpandedChange = { expandedClientDropdown = it },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = selectedClient?.let { "${it.firstName} ${it.lastName ?: ""}".trim() } ?: "✍️ Вільний ввід (Без прив'язки)",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Вибір клієнта для відгуку") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedClientDropdown) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor()
                            )

                            ExposedDropdownMenu(
                                expanded = expandedClientDropdown,
                                onDismissRequest = { expandedClientDropdown = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("✍️ Вільний ввід (Без прив'язки до клієнта)") },
                                    onClick = {
                                        selectedClient = null
                                        expandedClientDropdown = false
                                    }
                                )
                                allClients.forEach { client ->
                                    DropdownMenuItem(
                                        text = { Text("${client.firstName} ${client.lastName ?: ""}".trim()) },
                                        onClick = {
                                            selectedClient = client
                                            reviewClientName = "${client.firstName} ${client.lastName ?: ""}".trim()
                                            // Auto-fill review if available
                                            val clientReviews = allReviews.filter { it.clientId == client.id }
                                            if (clientReviews.isNotEmpty()) {
                                                val r = clientReviews.first()
                                                reviewRating = r.rating
                                                if (!r.comment.isNullOrBlank()) reviewText = r.comment
                                            }
                                            expandedClientDropdown = false
                                        }
                                    }
                                )
                            }
                        }
                    }

                    item {
                        OutlinedTextField(
                            value = reviewClientName,
                            onValueChange = { reviewClientName = it },
                            label = { Text("Ім'я клієнта") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    item {
                        OutlinedTextField(
                            value = reviewServiceTitle,
                            onValueChange = { reviewServiceTitle = it },
                            label = { Text("Назва послуги у відгуку") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Оцінка:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            (1..5).forEach { star ->
                                FilterChip(
                                    selected = reviewRating == star,
                                    onClick = { reviewRating = star },
                                    label = { Text("⭐ $star", fontSize = 11.sp) }
                                )
                            }
                        }
                    }

                    item {
                        OutlinedTextField(
                            value = reviewText,
                            onValueChange = { reviewText = it },
                            label = { Text("Текст відгуку клієнта") },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    item {
                        OutlinedTextField(
                            value = reviewMasterReply,
                            onValueChange = { reviewMasterReply = it },
                            label = { Text("Відповідь майстра") },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    if (showAddCustomSlotDialog) {
        var inputTime by remember { mutableStateOf("19:30") }
        AlertDialog(
            onDismissRequest = { showAddCustomSlotDialog = false },
            title = { Text("Додати вільне віконце") },
            text = {
                OutlinedTextField(
                    value = inputTime,
                    onValueChange = { inputTime = it },
                    label = { Text("Час (напр. 19:30)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (inputTime.isNotBlank()) {
                            customTimeSlots = (customTimeSlots + inputTime.trim()).toMutableList()
                            activeSlotToggles = activeSlotToggles.toMutableMap().apply {
                                put(inputTime.trim(), true)
                            }
                        }
                        showAddCustomSlotDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RosePrimary)
                ) {
                    Text("Додати")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddCustomSlotDialog = false }) { Text("Скасувати") }
            }
        )
    }
}

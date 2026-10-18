package com.mastercrm.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.mastercrm.MasterCrmApp
import com.mastercrm.data.local.entity.ReviewEntity
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// =====================================================================
// 1. МОДЕЛІ ДАНИХ ДЛЯ КОНСТРУКТОРА СТОРІС
// =====================================================================
enum class StoryMode(val title: String) {
    FREE_SLOTS("Вільні віконця"),
    PRICE_LIST("Прайс-лист"),
    CLIENT_REVIEW("Відгук (TG Чат)"),
    ALL_REVIEWS_LIST("Усі відгуки")
}

enum class CanvaStyle(
    val label: String,
    val bgStart: Int,
    val bgEnd: Int,
    val cardBg: Int,
    val accent: Int,
    val textPrimary: Int,
    val textSecondary: Int
) {
    GLAMOUR_DARK(
        label = "Dark Luxury",
        bgStart = Color.parseColor("#0B0F19"),
        bgEnd = Color.parseColor("#2E1065"),
        cardBg = Color.parseColor("#26FFFFFF"),
        accent = Color.parseColor("#F472B6"),
        textPrimary = Color.parseColor("#FFFFFF"),
        textSecondary = Color.parseColor("#CBD5E1")
    ),
    NUDE_MINIMAL(
        label = "Nude Aesthetic",
        bgStart = Color.parseColor("#FAF6F0"),
        bgEnd = Color.parseColor("#E6D7C3"),
        cardBg = Color.parseColor("#CCFFFFFF"),
        accent = Color.parseColor("#8D6E63"),
        textPrimary = Color.parseColor("#2D1E18"),
        textSecondary = Color.parseColor("#6D4C41")
    ),
    ROSE_GLASS(
        label = "Soft Pink",
        bgStart = Color.parseColor("#FFF1F2"),
        bgEnd = Color.parseColor("#FBCFE8"),
        cardBg = Color.parseColor("#D9FFFFFF"),
        accent = Color.parseColor("#DB2777"),
        textPrimary = Color.parseColor("#831843"),
        textSecondary = Color.parseColor("#9D174D")
    ),
    NEON_CYBER(
        label = "Neon Pop",
        bgStart = Color.parseColor("#09090B"),
        bgEnd = Color.parseColor("#18181B"),
        cardBg = Color.parseColor("#1F2937"),
        accent = Color.parseColor("#06B6D4"),
        textPrimary = Color.parseColor("#F8FAFC"),
        textSecondary = Color.parseColor("#F43F5E")
    ),
    CLEAN_EDITORIAL(
        label = "Vogue Clean",
        bgStart = Color.parseColor("#FFFFFF"),
        bgEnd = Color.parseColor("#F1F5F9"),
        cardBg = Color.parseColor("#F8FAFC"),
        accent = Color.parseColor("#0F172A"),
        textPrimary = Color.parseColor("#0F172A"),
        textSecondary = Color.parseColor("#475569")
    )
}

data class ServiceStoryItem(
    val id: String,
    val title: String,
    val price: Int,
    val durationMinutes: Int,
    val includedInStory: Boolean = true
)

data class ClientStoryOption(
    val id: String,
    val name: String,
    val lastServiceTitle: String = "Комплекс Манікюр",
    val savedReviews: List<SavedReviewOption> = emptyList()
)

data class SavedReviewOption(
    val id: String,
    val clientName: String = "Клієнт",
    val rating: Int = 5,
    val comment: String = "",
    val serviceTitle: String = "Манікюр",
    val photoUrl: String? = null,
    val reviewType: String = "POST_VISIT",
    val updatedAt: String = ""
)

fun decodeBase64ToBitmap(dataUriOrBase64: String?): Bitmap? {
    if (dataUriOrBase64.isNullOrBlank()) return null
    return try {
        val base64Data = if (dataUriOrBase64.contains(",")) {
            dataUriOrBase64.substringAfter(",")
        } else {
            dataUriOrBase64
        }
        val bytes = android.util.Base64.decode(base64Data.trim(), android.util.Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) {
        null
    }
}

// =====================================================================
// 2. ЯДРО РЕНДЕРИНГУ CANVA BITMAP (1080x1920) ДЛЯ LIVE PREVIEW ТА ЕКСПОРТУ
// =====================================================================
object CanvaStoryRenderer {

    fun renderStoryBitmap(
        mode: StoryMode,
        style: CanvaStyle,
        studioName: String,
        headerText: String,
        footerText: String,
        // Для вільних віконець
        dateLabel: String,
        selectedSlots: List<String>,
        // Для прайс-листа
        services: List<ServiceStoryItem>,
        showDurationOnPrice: Boolean,
        // Для відгуку клієнта
        reviewClientName: String,
        reviewServiceTitle: String,
        reviewStars: Int,
        reviewComment: String,
        reviewMasterReply: String
    ): Bitmap {
        val w = 1080
        val h = 1920
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // 1. Фон у вибраному стилі Canva
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                style.bgStart, style.bgEnd, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        // Декоративні м'які кола на фоні
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.accent
            alpha = 28
            this.style = Paint.Style.FILL
        }
        canvas.drawCircle(w * 0.85f, h * 0.12f, 340f, glowPaint)
        canvas.drawCircle(w * 0.15f, h * 0.85f, 420f, glowPaint)

        // 2. Верхній бейдж студії
        val studioPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.accent
            textSize = 40f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.12f
        }
        canvas.drawText(studioName.uppercase(), w / 2f, 210f, studioPaint)

        // 3. Головний заголовок
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.textPrimary
            textSize = 72f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(headerText, w / 2f, 310f, titlePaint)

        when (mode) {
            StoryMode.FREE_SLOTS -> drawFreeSlotsContent(canvas, w, style, dateLabel, selectedSlots)
            StoryMode.PRICE_LIST -> drawPriceListContent(canvas, w, style, services.filter { it.includedInStory }, showDurationOnPrice)
            StoryMode.CLIENT_REVIEW, StoryMode.ALL_REVIEWS_LIST -> drawTelegramReviewContent(
                canvas, w, style, reviewClientName, reviewServiceTitle, reviewStars, reviewComment, reviewMasterReply
            )
        }

        // 4. Нижній підпис (CTA)
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.textSecondary
            textSize = 38f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(footerText, w / 2f, h - 150f, footerPaint)

        return bmp
    }

    private fun drawFreeSlotsContent(
        canvas: Canvas,
        w: Int,
        style: CanvaStyle,
        dateLabel: String,
        slots: List<String>
    ) {
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.accent
            textSize = 48f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(dateLabel, w / 2f, 395f, subPaint)

        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.cardBg
            this.style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.accent
            this.style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        val slotTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.textPrimary
            textSize = 56f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        if (slots.isEmpty()) {
            canvas.drawText("Вільних віконець немає", w / 2f, 800f, subPaint)
            return
        }

        val cols = 2
        val cardW = 410f
        val cardH = 135f
        val gapX = 50f
        val gapY = 42f
        val startX = (w - (cols * cardW + gapX)) / 2f
        val startY = 490f

        slots.take(12).forEachIndexed { idx, timeStr ->
            val col = idx % cols
            val row = idx / cols
            val left = startX + col * (cardW + gapX)
            val top = startY + row * (cardH + gapY)
            val rect = RectF(left, top, left + cardW, top + cardH)
            canvas.drawRoundRect(rect, 36f, 36f, cardPaint)
            canvas.drawRoundRect(rect, 36f, 36f, strokePaint)
            canvas.drawText(timeStr, rect.centerX(), rect.centerY() + 20f, slotTextPaint)
        }
    }

    private fun drawPriceListContent(
        canvas: Canvas,
        w: Int,
        style: CanvaStyle,
        services: List<ServiceStoryItem>,
        showDuration: Boolean
    ) {
        val containerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.cardBg
            this.style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.accent
            alpha = 90
            this.style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.textPrimary
            textSize = 42f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val durPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.textSecondary
            textSize = 32f
        }
        val pricePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.accent
            textSize = 46f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }

        var currentY = 420f
        val rowH = if (showDuration) 145f else 118f
        val marginH = 85f

        services.take(8).forEach { srv ->
            val rect = RectF(marginH, currentY, w - marginH, currentY + rowH)
            canvas.drawRoundRect(rect, 28f, 28f, containerPaint)
            canvas.drawRoundRect(rect, 28f, 28f, strokePaint)

            val titleShort = if (srv.title.length > 26) srv.title.take(26) + "…" else srv.title
            if (showDuration) {
                canvas.drawText(titleShort, marginH + 40f, currentY + 62f, namePaint)
                canvas.drawText("⏱ ${srv.durationMinutes} хв", marginH + 40f, currentY + 110f, durPaint)
            } else {
                canvas.drawText(titleShort, marginH + 40f, currentY + 74f, namePaint)
            }

            canvas.drawText("${srv.price} ₴", w - marginH - 40f, currentY + (rowH / 2f) + 16f, pricePaint)
            currentY += rowH + 26f
        }
    }

    private fun drawTelegramReviewContent(
        canvas: Canvas,
        w: Int,
        style: CanvaStyle,
        clientName: String,
        serviceTitle: String,
        stars: Int,
        comment: String,
        masterReply: String
    ) {
        val margin = 80f
        val chatTop = 440f
        val chatRect = RectF(margin, chatTop, w - margin, 1460f)

        // Зовнішня рамка вікна Telegram
        val chatBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#17212B")
            this.style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.accent
            this.style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawRoundRect(chatRect, 44f, 44f, chatBgPaint)
        canvas.drawRoundRect(chatRect, 44f, 44f, borderPaint)

        // Шапка чату з аватаркою клієнта
        val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.accent }
        canvas.drawCircle(margin + 95f, chatTop + 95f, 52f, avatarPaint)

        val initPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 44f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val initial = clientName.trim().firstOrNull()?.uppercase() ?: "К"
        canvas.drawText(initial, margin + 95f, chatTop + 110f, initPaint)

        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 42f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val srvPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#64B5F6")
            textSize = 32f
        }
        canvas.drawText(clientName.ifBlank { "Клієнт студії" }, margin + 175f, chatTop + 85f, namePaint)
        canvas.drawText("💅 Послуга: $serviceTitle", margin + 175f, chatTop + 132f, srvPaint)

        // Розділювач шапки
        val divPaint = Paint().apply { color = Color.parseColor("#2B3846"); strokeWidth = 2f }
        canvas.drawLine(margin, chatTop + 175f, w - margin, chatTop + 175f, divPaint)

        // Вхідна бульбашка повідомлення клієнта
        val bubbleRect = RectF(margin + 45f, chatTop + 220f, w - margin - 90f, chatTop + 720f)
        val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#182533")
            this.style = Paint.Style.FILL
        }
        canvas.drawRoundRect(bubbleRect, 32f, 32f, bubblePaint)

        // Зірочки оцінки
        val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FACC15")
            textSize = 46f
        }
        val starsStr = "⭐".repeat(stars.coerceIn(1, 5))
        canvas.drawText("Оцінка візиту: $starsStr", bubbleRect.left + 40f, bubbleRect.top + 70f, starPaint)

        // Багаторядковий текст відгуку
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 40f
        }
        val textWidth = (bubbleRect.width() - 80f).toInt()
        val staticLayout = StaticLayout.Builder
            .obtain(comment.ifBlank { "Дякую за ідеальний манікюр! Покриття тримається супер 💖" }, 0, comment.ifBlank { "Дякую за ідеальний манікюр! Покриття тримається супер 💖" }.length, textPaint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(8f, 1.1f)
            .setMaxLines(6)
            .build()

        canvas.save()
        canvas.translate(bubbleRect.left + 40f, bubbleRect.top + 115f)
        staticLayout.draw(canvas)
        canvas.restore()

        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#6C7883")
            textSize = 28f
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText("18:42", bubbleRect.right - 30f, bubbleRect.bottom - 24f, timePaint)

        // Вихідна бульбашка-відповідь майстра
        if (masterReply.isNotBlank()) {
            val replyRect = RectF(margin + 160f, chatTop + 770f, w - margin - 45f, chatTop + 960f)
            val replyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#2B5278")
                this.style = Paint.Style.FILL
            }
            canvas.drawRoundRect(replyRect, 32f, 32f, replyPaint)

            val replyTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 36f
            }
            val replyLayout = StaticLayout.Builder
                .obtain(masterReply, 0, masterReply.length, replyTextPaint, (replyRect.width() - 70f).toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(3)
                .build()

            canvas.save()
            canvas.translate(replyRect.left + 35f, replyRect.top + 35f)
            replyLayout.draw(canvas)
            canvas.restore()

            canvas.drawText("18:44 ✓✓", replyRect.right - 30f, replyRect.bottom - 20f, timePaint)
        }
    }

    fun shareBitmapToStories(context: Context, bitmap: Bitmap) {
        val dir = File(context.cacheDir, "stories").apply { mkdirs() }
        val file = File(dir, "mastercrm_story_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Поділитися в Stories"))
    }
}

// =====================================================================
// 3. ГОЛОВНИЙ ЕКРАН МАРКЕТИНГУ З LIVE-ПРЕВ'Ю ТА КОНСТРУКТОРОМ
// =====================================================================
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MarketingStudioScreen(
    studioName: String = "MasterCRM Studio",
    // Функція, що повертає РЕАЛЬНО вільні віконця з локальної Room БД на вибрану дату
    getRealFreeSlotsForDate: (String) -> List<String>,
    initialServices: List<ServiceStoryItem>,
    clientsWithReviews: List<ClientStoryOption>,
    allReviews: List<ReviewEntity> = emptyList()
) {
    val context = LocalContext.current

    var selectedMode by remember { mutableStateOf(StoryMode.FREE_SLOTS) }
    var selectedStyle by remember { mutableStateOf(CanvaStyle.GLAMOUR_DARK) }
    var viewingPhotoUrl by remember { mutableStateOf<String?>(null) }

    // --- Стан для Вільних віконець ---
    val next7Days = remember {
        (0..6).map { LocalDate.now().plusDays(it.toLong()) }
    }
    var selectedDate by remember { mutableStateOf(next7Days.first()) }
    val dateIso = selectedDate.toString()
    val dateFormatted = remember(selectedDate) {
        selectedDate.format(DateTimeFormatter.ofPattern("d MMMM (EEEE)", Locale("uk", "UA")))
    }

    // Реальні вільні слоти на цей день із БД + можливість вмикати/вимикати та додавати свій час
    var availableDaySlots by remember(dateIso) {
        mutableStateOf(getRealFreeSlotsForDate(dateIso).ifEmpty { listOf("10:00", "12:00", "14:30", "16:00", "18:00") })
    }
    var activeSlots by remember(dateIso) {
        mutableStateOf(availableDaySlots.toSet())
    }
    var showAddSlotDialog by remember { mutableStateOf(false) }
    var customTimeInput by remember { mutableStateOf("") }

    // --- Стан для Прайс-листа ---
    var serviceItems by remember(initialServices) { mutableStateOf(initialServices) }
    var showDurationOnPrice by remember { mutableStateOf(true) }

    // --- Стан для Відгуків (Опціональний вибір клієнта) ---
    var clientDropdownExpanded by remember { mutableStateOf(false) }
    var selectedClient by remember { mutableStateOf<ClientStoryOption?>(null) }
    var reviewClientName by remember { mutableStateOf("Аліна") }
    var reviewServiceTitle by remember { mutableStateOf("Комплекс: Манікюр + Покриття") }
    var reviewStars by remember { mutableIntStateOf(5) }
    var reviewComment by remember {
        mutableStateOf("Дівчата, це найкращий манікюр у моєму житті! Минуло 3 тижні — жодної відслойки, кутикула ідеальна 💖")
    }
    var reviewMasterReply by remember {
        mutableStateOf("Дякуємо за довіру! Чекаємо на наступну корекцію ❤️")
    }

    // --- Заголовки ---
    var headerText by remember(selectedMode) {
        mutableStateOf(
            when (selectedMode) {
                StoryMode.FREE_SLOTS -> "ВІЛЬНІ ВІКОНЦЯ"
                StoryMode.PRICE_LIST -> "ПРАЙС ПОСЛУГ"
                StoryMode.CLIENT_REVIEW -> "ВІДГУКИ КЛІЄНТІВ"
                StoryMode.ALL_REVIEWS_LIST -> "УСІ ВІДГУКИ"
            }
        )
    }
    var footerText by remember { mutableStateOf("Запис у Direct або через Telegram-бот у шапці профілю 💅") }

    // --- Миттєва генерація Live-Прев'ю Bitmap ---
    val previewBitmap = remember(
        selectedMode, selectedStyle, studioName, headerText, footerText,
        dateFormatted, activeSlots, availableDaySlots, serviceItems, showDurationOnPrice,
        reviewClientName, reviewServiceTitle, reviewStars, reviewComment, reviewMasterReply
    ) {
        CanvaStoryRenderer.renderStoryBitmap(
            mode = selectedMode,
            style = selectedStyle,
            studioName = studioName,
            headerText = headerText,
            footerText = footerText,
            dateLabel = dateFormatted,
            selectedSlots = availableDaySlots.filter { it in activeSlots },
            services = serviceItems,
            showDurationOnPrice = showDurationOnPrice,
            reviewClientName = reviewClientName,
            reviewServiceTitle = reviewServiceTitle,
            reviewStars = reviewStars,
            reviewComment = reviewComment,
            reviewMasterReply = reviewMasterReply
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Перемикач типу шаблону (Вільні вікна / Прайс / Відгук)
        item {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                StoryMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = selectedMode == mode,
                        onClick = { selectedMode = mode },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = StoryMode.entries.size)
                    ) {
                        Text(mode.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                    }
                }
            }
        }

        if (selectedMode != StoryMode.ALL_REVIEWS_LIST) {
            // 2. LIVE PREVIEW ПРЯМО В ЗАСТОСУНКУ + КНОПКА ПОДІЛИТИСЯ
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "👁 Передперегляд Stories (Наживо)",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(10.dp))

                        Image(
                            bitmap = previewBitmap.asImageBitmap(),
                            contentDescription = "Story Live Preview",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth(0.62f)
                                .aspectRatio(9f / 16f)
                                .clip(RoundedCornerShape(18.dp))
                                .border(2.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                        )

                        Spacer(Modifier.height(14.dp))

                        Button(
                            onClick = { CanvaStoryRenderer.shareBitmapToStories(context, previewBitmap) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Поділитися в Instagram Stories / Telegram", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // 3. ВИБІР ОДНОГО З 5 СТИЛІВ CANVA
            item {
                Text("🎨 Стиль оформлення Canva (5 шаблонів):", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(CanvaStyle.entries) { style ->
                        val isSelected = style == selectedStyle
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedStyle = style },
                            label = { Text(style.label, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                            leadingIcon = if (isSelected) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            } else null
                        )
                    }
                }
            }
        }

        // 4. НАЛАШТУВАННЯ ЗАЛЕЖНО ВІД ОБРАНОГО РЕЖИМУ
        when (selectedMode) {
            StoryMode.FREE_SLOTS -> {
                item {
                    OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("📅 1. Оберіть день (підтягуються реально вільні вікна):", style = MaterialTheme.typography.titleSmall)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(next7Days) { d ->
                                    val label = d.format(DateTimeFormatter.ofPattern("dd.MM (EE)", Locale("uk", "UA")))
                                    FilterChip(
                                        selected = d == selectedDate,
                                        onClick = { selectedDate = d },
                                        label = { Text(label) }
                                    )
                                }
                            }

                            HorizontalDivider()

                            Text("⏰ 2. Натисніть на час, щоб прибрати або додати на картинку:", style = MaterialTheme.typography.titleSmall)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                availableDaySlots.forEach { slot ->
                                    val isSlotChecked = slot in activeSlots
                                    FilterChip(
                                        selected = isSlotChecked,
                                        onClick = {
                                            activeSlots = if (isSlotChecked) activeSlots - slot else activeSlots + slot
                                        },
                                        label = { Text(slot) },
                                        leadingIcon = if (isSlotChecked) {
                                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                        } else null
                                    )
                                }

                                AssistChip(
                                    onClick = { showAddSlotDialog = true },
                                    label = { Text("+ Свій час") },
                                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                )
                            }
                        }
                    }
                }
            }

            StoryMode.PRICE_LIST -> {
                item {
                    OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Показувати тривалість (хв)", style = MaterialTheme.typography.bodyLarge)
                                Switch(checked = showDurationOnPrice, onCheckedChange = { showDurationOnPrice = it })
                            }
                            HorizontalDivider()
                            Text(
                                "💅 Оберіть послуги для генерації прайсу (вимкнені залишаються в базі, але ховаються з картинки):",
                                style = MaterialTheme.typography.titleSmall
                            )

                            serviceItems.forEachIndexed { idx, srv ->
                                ElevatedCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            serviceItems = serviceItems.toMutableList().also {
                                                it[idx] = srv.copy(includedInStory = !srv.includedInStory)
                                            }
                                        },
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = srv.includedInStory,
                                            onCheckedChange = { checked ->
                                                serviceItems = serviceItems.toMutableList().also {
                                                    it[idx] = srv.copy(includedInStory = checked)
                                                }
                                            }
                                        )
                                        Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                            Text(srv.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                            Text("${srv.durationMinutes} хв", style = MaterialTheme.typography.bodySmall)
                                        }
                                        Text("${srv.price} ₴", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            StoryMode.CLIENT_REVIEW -> {
                item {
                    OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (allReviews.isNotEmpty()) {
                                Text("⭐ Швидкий вибір із відгуків клієнтів (в 1 клік):", style = MaterialTheme.typography.titleSmall)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(allReviews.take(15)) { rv ->
                                        AssistChip(
                                            onClick = {
                                                reviewClientName = rv.clientName.ifBlank { "Клієнт" }
                                                reviewServiceTitle = rv.serviceTitle.ifBlank { "Манікюр" }
                                                reviewComment = rv.comment ?: ""
                                                reviewStars = rv.rating
                                            },
                                            label = {
                                                val badge = if (rv.reviewType == "WEAR_2WEEKS") "✨ 2 тиж: " else "💅 "
                                                val shortText = (rv.comment ?: "").take(20)
                                                Text("$badge${rv.rating}⭐ ${rv.clientName}: $shortText…")
                                            }
                                        )
                                    }
                                }
                                HorizontalDivider()
                            }

                            Text("👤 1. Оберіть клієнта з бази (опціонально) або редагуйте вручну:", style = MaterialTheme.typography.titleSmall)

                            ExposedDropdownMenuBox(
                                expanded = clientDropdownExpanded,
                                onExpandedChange = { clientDropdownExpanded = !clientDropdownExpanded }
                            ) {
                                OutlinedTextField(
                                    value = selectedClient?.name ?: "✍️ Вільний ввід (Без прив'язки)",
                                    onValueChange = {},
                                    readOnly = true,
                                    singleLine = true,
                                    label = { Text("Клієнт") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = clientDropdownExpanded) },
                                    modifier = Modifier.menuAnchor().fillMaxWidth()
                                )
                                ExposedDropdownMenu(
                                    expanded = clientDropdownExpanded,
                                    onDismissRequest = { clientDropdownExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("✍️ Вільний ввід (Без прив'язки до клієнта)") },
                                        onClick = {
                                            selectedClient = null
                                            clientDropdownExpanded = false
                                        }
                                    )
                                    clientsWithReviews.forEach { cl ->
                                        DropdownMenuItem(
                                            text = { Text("${cl.name} (${cl.savedReviews.size} відгуків)") },
                                            onClick = {
                                                selectedClient = cl
                                                reviewClientName = cl.name
                                                reviewServiceTitle = cl.lastServiceTitle
                                                cl.savedReviews.firstOrNull()?.let { firstRev ->
                                                    reviewStars = firstRev.rating
                                                    reviewComment = firstRev.comment
                                                    reviewServiceTitle = firstRev.serviceTitle
                                                }
                                                clientDropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            // Якщо у вибраного клієнта є збережені відгуки — показуємо чіпи для швидкого підвантаження
                            selectedClient?.savedReviews?.takeIf { it.isNotEmpty() }?.let { revs ->
                                Text("Збережені відгуки цього клієнта:", style = MaterialTheme.typography.labelMedium)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(revs) { rv ->
                                        AssistChip(
                                            onClick = {
                                                reviewStars = rv.rating
                                                reviewComment = rv.comment
                                                reviewServiceTitle = rv.serviceTitle
                                            },
                                            label = { Text("${rv.rating}⭐ ${rv.comment.take(22)}…") }
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = reviewClientName,
                                onValueChange = { reviewClientName = it },
                                label = { Text("Ім'я клієнта на скріншоті") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = reviewServiceTitle,
                                onValueChange = { reviewServiceTitle = it },
                                label = { Text("Послуга") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            // Компактний інтерактивний 5-зірковий рейтинг (1..5)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Оцінка:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    (1..5).forEach { star ->
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(
                                                    if (star <= reviewStars) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                                )
                                                .clickable { reviewStars = star },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "⭐",
                                                fontSize = 18.sp,
                                                modifier = Modifier.alpha(if (star <= reviewStars) 1f else 0.35f)
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.width(2.dp))
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                ) {
                                    Text(
                                        text = "$reviewStars / 5",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = reviewComment,
                                onValueChange = { reviewComment = it },
                                label = { Text("Текст відгуку клієнта") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3
                            )

                            OutlinedTextField(
                                value = reviewMasterReply,
                                onValueChange = { reviewMasterReply = it },
                                label = { Text("Ваша відповідь у чаті (опціонально)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
                    }
                }
            StoryMode.ALL_REVIEWS_LIST -> {
                item {
                    Text(
                        text = "⭐ Усі збережені відгуки студії (${allReviews.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (allReviews.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("Поки що немає збережених відгуків.", style = MaterialTheme.typography.bodyLarge)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Відгуки з'являтимуться тут автоматично після опитувань клієнтів у Telegram!",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(allReviews, key = { it.id }) { rev ->
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val isWear = rev.reviewType == "WEAR_2WEEKS"
                                    SuggestionChip(
                                        onClick = {},
                                        label = {
                                            Text(
                                                if (isWear) "✨ Через 2 тижні (Носка)" else "💅 Після візиту (10 хв)",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    )
                                    Text("⭐".repeat(rev.rating.coerceIn(1, 5)), fontSize = 14.sp)
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = rev.clientName.ifBlank { "Клієнт" },
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "💅 ${rev.serviceTitle.ifBlank { "Манікюр" }}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            text = if (!rev.comment.isNullOrBlank()) "«${rev.comment}»" else "«Без текстового коментаря»",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }

                                    if (!rev.photoUrl.isNullOrBlank()) {
                                        val thumbBmp = remember(rev.photoUrl) { decodeBase64ToBitmap(rev.photoUrl) }
                                        if (thumbBmp != null) {
                                            Image(
                                                bitmap = thumbBmp.asImageBitmap(),
                                                contentDescription = "Фото нігтів",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .size(76.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                                                    .clickable { viewingPhotoUrl = rev.photoUrl }
                                            )
                                        }
                                    }
                                }

                                Button(
                                    onClick = {
                                        reviewClientName = rev.clientName.ifBlank { "Клієнт" }
                                        reviewServiceTitle = rev.serviceTitle.ifBlank { "Манікюр" }
                                        reviewComment = rev.comment.orEmpty()
                                        reviewStars = rev.rating
                                        selectedMode = StoryMode.CLIENT_REVIEW
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("✨ В Сторіс (Автозаповнення)")
                                }
                            }
                        }
                    }
                }
            }
        }

        if (selectedMode != StoryMode.ALL_REVIEWS_LIST) {
            // 5. РЕДАГУВАННЯ ЗАГОЛОВКА ТА ПІДПИСУ СТОРІС
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("✏️ Тексти на шаблоні:", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            value = headerText,
                            onValueChange = { headerText = it },
                            label = { Text("Заголовок шаблону") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = footerText,
                            onValueChange = { footerText = it },
                            label = { Text("Нижній підпис (CTA)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
            }
        }
    }

    if (viewingPhotoUrl != null) {
        val fullBmp = remember(viewingPhotoUrl) { decodeBase64ToBitmap(viewingPhotoUrl) }
        Dialog(onDismissRequest = { viewingPhotoUrl = null }) {
            Card(
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(0.92f)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("📸 Фото стану покриття", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    if (fullBmp != null) {
                        Image(
                            bitmap = fullBmp.asImageBitmap(),
                            contentDescription = "Фото нігтів",
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(14.dp)),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Text("Не вдалося завантажити фото.")
                    }
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { viewingPhotoUrl = null },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Закрити")
                    }
                }
            }
        }
    }

    // Діалог додавання кастомного часу
    if (showAddSlotDialog) {
        AlertDialog(
            onDismissRequest = { showAddSlotDialog = false },
            title = { Text("Додати свій час") },
            text = {
                OutlinedTextField(
                    value = customTimeInput,
                    onValueChange = { customTimeInput = it },
                    placeholder = { Text("Наприклад: 19:30") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val clean = customTimeInput.trim()
                    if (clean.isNotEmpty()) {
                        if (clean !in availableDaySlots) {
                            availableDaySlots = (availableDaySlots + clean).sorted()
                        }
                        activeSlots = activeSlots + clean
                    }
                    customTimeInput = ""
                    showAddSlotDialog = false
                }) {
                    Text("Додати")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddSlotDialog = false }) { Text("Скасувати") }
            }
        )
    }
}

// =====================================================================
// 4. ТОЧКА ВХОДУ MarketingScreen (ЗВ'ЯЗКА З ROOM БД ТА РЕАЛЬНИМИ ДАНИМИ)
// =====================================================================
@Composable
fun MarketingScreen() {
    val app = MasterCrmApp.instance

    // 1. Послуги з Room
    val serviceEntities by app.database.serviceDao().getActiveServicesFlow().collectAsState(initial = emptyList())
    val services = remember(serviceEntities) {
        if (serviceEntities.isEmpty()) {
            listOf(
                ServiceStoryItem("s-1", "Комплекс: Зняття + Манікюр + Покриття", 650, 90),
                ServiceStoryItem("s-2", "Гігієнічний манікюр", 350, 45),
                ServiceStoryItem("s-3", "Нарощування нігтів (Gel/Polygel)", 950, 150),
                ServiceStoryItem("s-4", "Дизайн френч / градієнт", 150, 30),
                ServiceStoryItem("s-5", "Ремонт / укріплення 1 нігтя", 60, 15)
            )
        } else {
            serviceEntities.map {
                ServiceStoryItem(
                    id = it.id,
                    title = it.title,
                    price = it.price.toInt(),
                    durationMinutes = it.durationMinutes,
                    includedInStory = true
                )
            }
        }
    }

    // 2. Клієнти, Відгуки та Записи для контексту
    val clientEntities by app.database.clientDao().getAllClientsFlow().collectAsState(initial = emptyList())
    val reviewEntities by app.database.reviewDao().getAllReviewsFlow().collectAsState(initial = emptyList())
    val appointmentEntities by app.database.appointmentDao().getAllAppointmentsFlow().collectAsState(initial = emptyList())

    val clientsWithReviews = remember(clientEntities, reviewEntities, appointmentEntities, serviceEntities) {
        clientEntities.map { cl ->
            val clReviews = reviewEntities.filter { it.clientId == cl.id }.map { rv ->
                val appFound = appointmentEntities.find { it.id == rv.appointmentId }
                val srvTitle = rv.serviceTitle.ifBlank {
                    appFound?.serviceId?.let { sId -> serviceEntities.find { it.id == sId }?.title } ?: "Комплекс Манікюр"
                }
                SavedReviewOption(
                    id = rv.id,
                    clientName = rv.clientName.ifBlank { cl.firstName },
                    rating = rv.rating,
                    comment = rv.comment ?: "",
                    serviceTitle = srvTitle,
                    photoUrl = rv.photoUrl,
                    reviewType = rv.reviewType,
                    updatedAt = rv.updatedAt
                )
            }
            val lastApp = appointmentEntities.filter { it.clientId == cl.id }.maxByOrNull { it.date }
            val lastSrvTitle = lastApp?.serviceId?.let { sId -> serviceEntities.find { it.id == sId }?.title } ?: "Комплекс Манікюр"
            val fullName = "${cl.firstName} ${cl.lastName.orEmpty()}".trim()
            ClientStoryOption(
                id = cl.id,
                name = fullName.ifEmpty { "Клієнт" },
                lastServiceTitle = lastSrvTitle,
                savedReviews = clReviews
            )
        }
    }

    MarketingStudioScreen(
        studioName = "MasterCRM Studio",
        getRealFreeSlotsForDate = { dateIso ->
            val dayApps = appointmentEntities.filter { it.date == dateIso && !it.status.startsWith("CANCELLED") }
            val standardSlots = listOf("09:00", "11:00", "13:30", "15:30", "18:00")
            standardSlots.filter { slot ->
                dayApps.none { it.startTime == slot }
            }
        },
        initialServices = services,
        clientsWithReviews = clientsWithReviews,
        allReviews = reviewEntities
    )
}

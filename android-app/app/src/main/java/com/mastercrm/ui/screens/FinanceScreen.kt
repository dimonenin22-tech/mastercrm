package com.mastercrm.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mastercrm.MasterCrmApp
import com.mastercrm.data.local.entity.ExpenseEntity
import com.mastercrm.data.local.entity.IncomeEntity
import com.mastercrm.data.local.entity.ProductEntity
import com.mastercrm.data.local.entity.ServiceEntity
import com.mastercrm.data.remote.dto.FinanceReportDto
import com.mastercrm.data.remote.dto.ProductDto
import com.mastercrm.data.remote.dto.ServiceConsumableItemDto
import com.mastercrm.ui.theme.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class DateFilterMode {
    THIS_MONTH, ALL_TIME
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinanceScreen() {
    val app = MasterCrmApp.instance
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabTitles = listOf("Аналітика", "Доходи", "Витрати", "Склад")

    val incomes by app.database.incomeDao().getAllIncomesFlow().collectAsState(initial = emptyList())
    val expenses by app.database.expenseDao().getAllExpensesFlow().collectAsState(initial = emptyList())
    val products by app.database.productDao().getAllProductsFlow().collectAsState(initial = emptyList())
    val services by app.database.serviceDao().getActiveServicesFlow().collectAsState(initial = emptyList())

    var dateFilterMode by remember { mutableStateOf(DateFilterMode.THIS_MONTH) }
    var financeReport by remember { mutableStateOf<FinanceReportDto?>(null) }
    var isLoadingReport by remember { mutableStateOf(false) }

    // Proactive background sync on entering Finance Screen
    LaunchedEffect(Unit) {
        scope.launch {
            try {
                app.syncRepository.performSync()
            } catch (e: Exception) {
                // Offline fallback - Room is source of truth
            }

            try {
                isLoadingReport = true
                val today = LocalDate.now()
                val fromDate = today.withDayOfMonth(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
                val toDate = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

                val api = app.apiClient.getApiService()
                val resp = api.getFinanceReport(mapOf("from_date" to fromDate, "to_date" to toDate))
                if (resp.success && resp.data != null) {
                    financeReport = resp.data
                }
            } catch (e: Exception) {
                // Keep Room values
            } finally {
                isLoadingReport = false
            }
        }
    }

    var showAddIncomeDialog by remember { mutableStateOf(false) }
    var showAddExpenseDialog by remember { mutableStateOf(false) }
    var showRestockDialog by remember { mutableStateOf<ProductEntity?>(null) }
    var showWriteoffDialog by remember { mutableStateOf<ProductEntity?>(null) }
    var showAuditDialog by remember { mutableStateOf<ProductEntity?>(null) }
    var showAddProductDialog by remember { mutableStateOf(false) }
    var showConsumablesDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Фінанси та Склад", fontWeight = FontWeight.Bold) },
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
                selectedTabIndex = selectedTabIndex,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = RosePrimary
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(title, fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal) }
                    )
                }
            }

            when (selectedTabIndex) {
                0 -> AnalyticsTab(
                    report = financeReport,
                    incomes = incomes,
                    expenses = expenses,
                    dateFilterMode = dateFilterMode,
                    onFilterChange = { dateFilterMode = it }
                )
                1 -> IncomesTab(incomes, onAddIncome = { showAddIncomeDialog = true })
                2 -> ExpensesTab(expenses, onAddExpense = { showAddExpenseDialog = true })
                3 -> InventoryTab(
                    products = products,
                    onAddProduct = { showAddProductDialog = true },
                    onManageConsumables = { showConsumablesDialog = true },
                    onRestock = { showRestockDialog = it },
                    onWriteoff = { showWriteoffDialog = it },
                    onAudit = { showAuditDialog = it }
                )
            }
        }
    }

    // Dialogs
    if (showAddIncomeDialog) {
        AddFinanceRecordDialog(
            title = "Додати дохід",
            categories = listOf("services", "tips"),
            onDismiss = { showAddIncomeDialog = false },
            onConfirm = { amount, category, method, desc ->
                scope.launch {
                    val id = UUID.randomUUID().toString()
                    val masterId = app.authRepository.getMasterId() ?: "m-01"
                    val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

                    val entity = IncomeEntity(
                        id = id,
                        appointmentId = null,
                        masterId = masterId,
                        clientId = null,
                        amount = amount,
                        paymentMethod = method,
                        date = today,
                        category = category,
                        deleted = false,
                        version = 1,
                        updatedAt = ""
                    )
                    app.database.incomeDao().insertOrUpdate(entity)
                    app.syncRepository.queueMutation(
                        action = "income_add",
                        payload = mapOf(
                            "master_id" to masterId,
                            "amount" to amount,
                            "payment_method" to method,
                            "date" to today,
                            "category" to category
                        )
                    )
                    showAddIncomeDialog = false
                }
            }
        )
    }

    if (showAddExpenseDialog) {
        AddFinanceRecordDialog(
            title = "Додати витрату",
            categories = listOf("MATERIALS", "RENT", "ADS", "OTHER"),
            onDismiss = { showAddExpenseDialog = false },
            onConfirm = { amount, category, method, desc ->
                scope.launch {
                    val id = UUID.randomUUID().toString()
                    val masterId = app.authRepository.getMasterId() ?: "m-01"
                    val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

                    val entity = ExpenseEntity(
                        id = id,
                        masterId = masterId,
                        category = category,
                        amount = amount,
                        paymentMethod = method,
                        date = today,
                        description = desc,
                        deleted = false,
                        version = 1,
                        updatedAt = ""
                    )
                    app.database.expenseDao().insertOrUpdate(entity)
                    app.syncRepository.queueMutation(
                        action = "expense_add",
                        payload = mapOf(
                            "master_id" to masterId,
                            "category" to category,
                            "amount" to amount,
                            "payment_method" to method,
                            "date" to today,
                            "description" to desc
                        )
                    )
                    showAddExpenseDialog = false
                }
            }
        )
    }

    if (showAddProductDialog) {
        AddProductDialog(
            onDismiss = { showAddProductDialog = false },
            onConfirm = { name, unit, initialStock, minAlert, price ->
                scope.launch {
                    try {
                        val api = app.apiClient.getApiService()
                        val resp = api.createProduct(
                            mapOf(
                                "name" to name,
                                "unit" to unit,
                                "current_stock" to initialStock,
                                "min_stock_alert" to minAlert,
                                "purchase_price" to price
                            )
                        )
                        if (resp.success && resp.data != null) {
                            val p = resp.data
                            val entity = ProductEntity(
                                id = p.id,
                                name = p.name,
                                unit = p.unit,
                                currentStock = p.currentStock,
                                minStockAlert = p.minStockAlert,
                                purchasePrice = p.purchasePrice,
                                deleted = false,
                                version = 1,
                                updatedAt = ""
                            )
                            app.database.productDao().insertOrUpdate(entity)
                            Toast.makeText(context, "Матеріал додано на склад!", Toast.LENGTH_SHORT).show()
                        } else {
                            // Offline fallback
                            val id = "prod-${UUID.randomUUID().toString().take(8)}"
                            val entity = ProductEntity(
                                id = id,
                                name = name,
                                unit = unit,
                                currentStock = initialStock,
                                minStockAlert = minAlert,
                                purchasePrice = price,
                                deleted = false,
                                version = 1,
                                updatedAt = ""
                            )
                            app.database.productDao().insertOrUpdate(entity)
                            app.syncRepository.queueMutation(
                                action = "product_create",
                                payload = mapOf(
                                    "name" to name,
                                    "unit" to unit,
                                    "current_stock" to initialStock,
                                    "min_stock_alert" to minAlert,
                                    "purchase_price" to price
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Toast.makeText(context, "Збережено локально", Toast.LENGTH_SHORT).show()
                    } finally {
                        showAddProductDialog = false
                    }
                }
            }
        )
    }

    showRestockDialog?.let { prod ->
        RestockDialog(
            product = prod,
            onDismiss = { showRestockDialog = null },
            onConfirm = { qty ->
                scope.launch {
                    val updated = prod.copy(currentStock = prod.currentStock + qty)
                    app.database.productDao().insertOrUpdate(updated)
                    app.syncRepository.queueMutation(
                        action = "product_restock",
                        payload = mapOf(
                            "product_id" to prod.id,
                            "quantity" to qty,
                            "comment" to "Поповнення з Android додатку"
                        )
                    )
                    showRestockDialog = null
                    Toast.makeText(context, "Склад поповнено на +$qty ${prod.unit}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    showWriteoffDialog?.let { prod ->
        WriteoffDialog(
            product = prod,
            onDismiss = { showWriteoffDialog = null },
            onConfirm = { qty, reason ->
                scope.launch {
                    try {
                        val api = app.apiClient.getApiService()
                        val resp = api.writeoffProduct(
                            mapOf(
                                "product_id" to prod.id,
                                "quantity" to qty,
                                "reason" to reason
                            )
                        )
                        if (resp.success) {
                            val newStock = (prod.currentStock - qty).coerceAtLeast(0.0)
                            app.database.productDao().insertOrUpdate(prod.copy(currentStock = newStock))
                            Toast.makeText(context, "Списано $qty ${prod.unit} ($reason)", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, resp.error?.message ?: "Помилка списання", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        // Offline queue mutation
                        val newStock = (prod.currentStock - qty).coerceAtLeast(0.0)
                        app.database.productDao().insertOrUpdate(prod.copy(currentStock = newStock))
                        app.syncRepository.queueMutation(
                            action = "product_writeoff",
                            payload = mapOf(
                                "product_id" to prod.id,
                                "quantity" to qty,
                                "reason" to reason
                            )
                        )
                        Toast.makeText(context, "Списання збережено в чергу офлайн", Toast.LENGTH_SHORT).show()
                    } finally {
                        showWriteoffDialog = null
                    }
                }
            }
        )
    }

    showAuditDialog?.let { prod ->
        InventoryAuditDialog(
            product = prod,
            onDismiss = { showAuditDialog = null },
            onConfirm = { realQty ->
                scope.launch {
                    val updated = prod.copy(currentStock = realQty)
                    app.database.productDao().insertOrUpdate(updated)
                    app.syncRepository.queueMutation(
                        action = "inventory_audit",
                        payload = mapOf(
                            "product_id" to prod.id,
                            "real_quantity" to realQty
                        )
                    )
                    showAuditDialog = null
                    Toast.makeText(context, "Залишок оновлено: $realQty ${prod.unit}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (showConsumablesDialog) {
        ServiceConsumablesBOMDialog(
            services = services,
            products = products,
            onDismiss = { showConsumablesDialog = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsTab(
    report: FinanceReportDto?,
    incomes: List<IncomeEntity>,
    expenses: List<ExpenseEntity>,
    dateFilterMode: DateFilterMode,
    onFilterChange: (DateFilterMode) -> Unit
) {
    val currentMonthPrefix = remember {
        LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"))
    }

    // Filter Room collections according to selected date mode
    val filteredIncomes = remember(incomes, dateFilterMode) {
        if (dateFilterMode == DateFilterMode.THIS_MONTH) {
            incomes.filter { it.date.startsWith(currentMonthPrefix) }
        } else {
            incomes
        }
    }

    val filteredExpenses = remember(expenses, dateFilterMode) {
        if (dateFilterMode == DateFilterMode.THIS_MONTH) {
            expenses.filter { it.date.startsWith(currentMonthPrefix) }
        } else {
            expenses
        }
    }

    // Pure local Room calculations (Single Source of Truth, NEVER vanishing to 0)
    val totalIncome = filteredIncomes.sumOf { it.amount }
    val tips = filteredIncomes.filter { it.category == "tips" }.sumOf { it.amount }
    val grossRev = totalIncome - tips
    val totalExpense = filteredExpenses.sumOf { it.amount }
    val netProfit = totalIncome - totalExpense

    val margin = if (totalIncome > 0) {
        ((netProfit / totalIncome) * 100.0).coerceIn(-100.0, 100.0)
    } else 0.0

    val serviceCount = filteredIncomes.count { it.category != "tips" }
    val avgCheck = if (serviceCount > 0) {
        grossRev / serviceCount
    } else if (report?.averageCheck != null && report.averageCheck > 0.0) {
        report.averageCheck
    } else 0.0

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Date Period Filter Chips
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = dateFilterMode == DateFilterMode.THIS_MONTH,
                    onClick = { onFilterChange(DateFilterMode.THIS_MONTH) },
                    label = { Text("Поточний місяць") }
                )
                FilterChip(
                    selected = dateFilterMode == DateFilterMode.ALL_TIME,
                    onClick = { onFilterChange(DateFilterMode.ALL_TIME) },
                    label = { Text("За весь час") }
                )
            }
        }

        // Net Profit Hero Card
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = if (dateFilterMode == DateFilterMode.THIS_MONTH) "Чистий прибуток за місяць (Net Profit)" else "Чистий прибуток за весь час",
                        color = Color.Gray,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "${netProfit.toInt()} грн",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (netProfit >= 0) StatusCompleted else StatusCancelled
                    )
                }
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricSmallCard("Виручка (Послуги)", "${grossRev.toInt()} грн", StatusConfirmed, Modifier.weight(1f))
                MetricSmallCard("Чайові ☕️", "${tips.toInt()} грн", RosePrimary, Modifier.weight(1f))
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricSmallCard("Витрати (Загальні)", "${totalExpense.toInt()} грн", StatusCancelled, Modifier.weight(1f))
                MetricSmallCard("Маржинальність", "${margin.toInt()}%", Color(0xFF673AB7), Modifier.weight(1f))
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Середній чек:", fontWeight = FontWeight.SemiBold)
                        Text("Виконано послуг: $serviceCount", fontSize = 12.sp, color = Color.Gray)
                    }
                    Text("${avgCheck.toInt()} грн", fontWeight = FontWeight.Bold, color = RosePrimary, fontSize = 18.sp)
                }
            }
        }

        // CSV Export for FOP button
        item {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            var isExporting by remember { mutableStateOf(false) }

            Button(
                onClick = {
                    scope.launch {
                        try {
                            isExporting = true
                            val today = LocalDate.now()
                            val fromDate = if (dateFilterMode == DateFilterMode.THIS_MONTH) {
                                today.withDayOfMonth(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
                            } else "2020-01-01"
                            val toDate = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

                            val api = MasterCrmApp.instance.apiClient.getApiService()
                            val resp = api.exportFinanceCsv(mapOf("from_date" to fromDate, "to_date" to toDate))
                            if (resp.success && resp.data != null) {
                                val csvText = resp.data.csv
                                val file = java.io.File(context.cacheDir, "finance_report_${fromDate}_${toDate}.csv")
                                file.writeText(csvText)
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file
                                )
                                val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/csv"
                                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                    putExtra(android.content.Intent.EXTRA_SUBJECT, "Звіт доходів та витрат для ФОП ($fromDate - $toDate)")
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                val chooser = android.content.Intent.createChooser(sendIntent, "Поділитися CSV звітом для ФОП")
                                context.startActivity(chooser)
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "Помилка експорту: ${e.message}", Toast.LENGTH_SHORT).show()
                        } finally {
                            isExporting = false
                        }
                    }
                },
                enabled = !isExporting,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RosePrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                if (isExporting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Експорт у CSV (для ФОП)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
fun MetricSmallCard(title: String, value: String, color: Color, modifier: Modifier) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, color = Color.Gray, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@Composable
fun IncomesTab(incomes: List<IncomeEntity>, onAddIncome: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (incomes.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Записів про доходи немає", color = Color.Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(incomes, key = { it.id }) { inc ->
                    ElevatedCard(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = if (inc.category == "tips") "Чайові ☕️" else "Послуга 💅",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(text = "${inc.date} • ${inc.paymentMethod}", color = Color.Gray, fontSize = 12.sp)
                            }
                            Text("+${inc.amount.toInt()} ₴", fontWeight = FontWeight.Bold, color = StatusCompleted, fontSize = 17.sp)
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onAddIncome,
            containerColor = RosePrimary,
            contentColor = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Додати дохід")
        }
    }
}

@Composable
fun ExpensesTab(expenses: List<ExpenseEntity>, onAddExpense: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (expenses.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Записів про витрати немає", color = Color.Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(expenses, key = { it.id }) { exp ->
                    ElevatedCard(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(text = exp.category, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    text = "${exp.date} • ${exp.description ?: "Без опису"}",
                                    color = Color.Gray,
                                    fontSize = 12.sp
                                )
                            }
                            Text("-${exp.amount.toInt()} ₴", fontWeight = FontWeight.Bold, color = StatusCancelled, fontSize = 17.sp)
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onAddExpense,
            containerColor = RosePrimary,
            contentColor = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Додати витрату")
        }
    }
}

@Composable
fun InventoryTab(
    products: List<ProductEntity>,
    onAddProduct: () -> Unit,
    onManageConsumables: () -> Unit,
    onRestock: (ProductEntity) -> Unit,
    onWriteoff: (ProductEntity) -> Unit,
    onAudit: (ProductEntity) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Inventory actions bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onAddProduct,
                colors = ButtonDefaults.buttonColors(containerColor = RosePrimary),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Додати матеріал", fontSize = 12.sp)
            }

            OutlinedButton(
                onClick = onManageConsumables,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Норми послуг", fontSize = 12.sp)
            }
        }

        if (products.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Склад порожній. Натисніть «Додати матеріал»", color = Color.Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(products, key = { it.id }) { prod ->
                    val isLowStock = prod.currentStock <= prod.minStockAlert
                    val progress = (prod.currentStock / (prod.minStockAlert * 3.0)).toFloat().coerceIn(0f, 1f)
                    val indicatorColor = when {
                        prod.currentStock <= prod.minStockAlert -> Color(0xFFD32F2F)
                        prod.currentStock <= prod.minStockAlert * 1.5 -> Color(0xFFFFA000)
                        else -> Color(0xFF388E3C)
                    }

                    ElevatedCard(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = prod.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                if (isLowStock) {
                                    Box(
                                        modifier = Modifier
                                            .background(Color(0xFFD32F2F).copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text("⚠️ Закінчується!", color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Visual Stock Progress Indicator
                            LinearProgressIndicator(
                                progress = progress,
                                color = indicatorColor,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "Залишок: ${prod.currentStock} ${prod.unit} (мін. ${prod.minStockAlert}) • Ціна: ${prod.purchasePrice.toInt()} ₴/${prod.unit}",
                                color = if (isLowStock) Color(0xFFD32F2F) else Color.Gray,
                                fontSize = 13.sp
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilledTonalButton(
                                    onClick = { onRestock(prod) },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("➕ Поповнити", fontSize = 11.sp, maxLines = 1, softWrap = false)
                                }
                                FilledTonalButton(
                                    onClick = { onWriteoff(prod) },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = StatusCancelled.copy(alpha = 0.15f),
                                        contentColor = StatusCancelled
                                    ),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("➖ Списати", fontSize = 11.sp, maxLines = 1, softWrap = false)
                                }
                                OutlinedButton(
                                    onClick = { onAudit(prod) },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                                    modifier = Modifier.weight(0.7f)
                                ) {
                                    Text("Факт", fontSize = 11.sp, maxLines = 1, softWrap = false)
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
fun AddFinanceRecordDialog(
    title: String,
    categories: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (amount: Double, category: String, method: String, desc: String) -> Unit
) {
    var amountText by remember { mutableStateOf("") }
    var selectedCat by remember { mutableStateOf(categories.first()) }
    var selectedMethod by remember { mutableStateOf("CASH") }
    var desc by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Сума (грн)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("Опис") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amt = amountText.toDoubleOrNull() ?: 0.0
                    if (amt > 0) onConfirm(amt, selectedCat, selectedMethod, desc)
                },
                colors = ButtonDefaults.buttonColors(containerColor = RosePrimary)
            ) {
                Text("Зберегти")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Скасувати") }
        }
    )
}

@Composable
fun AddProductDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, unit: String, stock: Double, minAlert: Double, price: Double) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("шт") }
    var stockText by remember { mutableStateOf("10") }
    var minAlertText by remember { mutableStateOf("2") }
    var priceText by remember { mutableStateOf("150") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новий матеріал на склад") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Назва матеріалу (напр. База Komilfo 30ml)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = unit,
                        onValueChange = { unit = it },
                        label = { Text("Од. виміру (шт, мл, г)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = priceText,
                        onValueChange = { priceText = it },
                        label = { Text("Ціна закупівлі (грн)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = stockText,
                        onValueChange = { stockText = it },
                        label = { Text("Початковий залишок") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = minAlertText,
                        onValueChange = { minAlertText = it },
                        label = { Text("Мін. залишок (alert)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val s = stockText.toDoubleOrNull() ?: 0.0
                    val ma = minAlertText.toDoubleOrNull() ?: 2.0
                    val p = priceText.toDoubleOrNull() ?: 0.0
                    if (name.isNotBlank()) onConfirm(name.trim(), unit.trim(), s, ma, p)
                },
                colors = ButtonDefaults.buttonColors(containerColor = RosePrimary)
            ) {
                Text("Додати")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } }
    )
}

@Composable
fun RestockDialog(
    product: ProductEntity,
    onDismiss: () -> Unit,
    onConfirm: (qty: Double) -> Unit
) {
    var qtyText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Поповнення: ${product.name}") },
        text = {
            OutlinedTextField(
                value = qtyText,
                onValueChange = { qtyText = it },
                label = { Text("Кількість (${product.unit})") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    val q = qtyText.toDoubleOrNull() ?: 0.0
                    if (q > 0) onConfirm(q)
                },
                colors = ButtonDefaults.buttonColors(containerColor = RosePrimary)
            ) {
                Text("Поповнити")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } }
    )
}

@Composable
fun WriteoffDialog(
    product: ProductEntity,
    onDismiss: () -> Unit,
    onConfirm: (qty: Double, reason: String) -> Unit
) {
    var qtyText by remember { mutableStateOf("1") }
    var reason by remember { mutableStateOf("Використано / Знос") }
    val reasons = listOf("Використано / Знос", "Брак матеріалу", "Закінчився термін придатності", "Пошкоджено / Розлито")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Списання матеріалу: ${product.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Поточний залишок: ${product.currentStock} ${product.unit}", color = Color.Gray, fontSize = 13.sp)
                OutlinedTextField(
                    value = qtyText,
                    onValueChange = { qtyText = it },
                    label = { Text("Кількість для списання (${product.unit})") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Причина списання") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val q = qtyText.toDoubleOrNull() ?: 0.0
                    if (q > 0) onConfirm(q, reason)
                },
                colors = ButtonDefaults.buttonColors(containerColor = StatusCancelled)
            ) {
                Text("Списати")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } }
    )
}

@Composable
fun InventoryAuditDialog(
    product: ProductEntity,
    onDismiss: () -> Unit,
    onConfirm: (realQty: Double) -> Unit
) {
    var realQtyText by remember { mutableStateOf(product.currentStock.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Інвентаризація: ${product.name}") },
        text = {
            OutlinedTextField(
                value = realQtyText,
                onValueChange = { realQtyText = it },
                label = { Text("Фактичний залишок (${product.unit})") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    val q = realQtyText.toDoubleOrNull() ?: product.currentStock
                    onConfirm(q)
                },
                colors = ButtonDefaults.buttonColors(containerColor = RosePrimary)
            ) {
                Text("Зберегти факт")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ServiceConsumablesBOMDialog(
    services: List<ServiceEntity>,
    products: List<ProductEntity>,
    onDismiss: () -> Unit
) {
    val app = MasterCrmApp.instance
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var selectedService by remember { mutableStateOf(services.firstOrNull()) }
    var consumables by remember { mutableStateOf<List<ServiceConsumableItemDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    // Load consumables when selected service changes
    LaunchedEffect(selectedService?.id) {
        val srvId = selectedService?.id ?: return@LaunchedEffect
        isLoading = true
        try {
            val api = app.apiClient.getApiService()
            val resp = api.getServiceConsumables(mapOf("service_id" to srvId))
            if (resp.success && resp.data != null) {
                consumables = resp.data
            } else {
                consumables = emptyList()
            }
        } catch (e: Exception) {
            consumables = emptyList()
        } finally {
            isLoading = false
        }
    }

    var showAddConsumableSheet by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Норми витрат по послугах", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Закрити", modifier = Modifier.size(20.dp))
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Оберіть послугу:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)

                // Service selector with FlowRow so all services fit naturally
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    services.forEach { srv ->
                        FilterChip(
                            selected = selectedService?.id == srv.id,
                            onClick = { selectedService = srv },
                            label = { Text(srv.title, fontSize = 11.5.sp) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Матеріали для процедури:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    OutlinedButton(
                        onClick = { showAddConsumableSheet = true },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Додати", fontSize = 12.sp, maxLines = 1, softWrap = false)
                    }
                }

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = RosePrimary)
                    }
                } else if (consumables.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text("Матеріалів ще не прив'язано до послуги", color = Color.Gray, fontSize = 12.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(consumables) { item ->
                            Card(
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(item.productName.ifBlank { "Матеріал" }, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                        Text("Норма: ${item.quantityPerService} ${item.unit}", color = Color.Gray, fontSize = 11.sp)
                                    }
                                    IconButton(
                                        onClick = {
                                            val updatedList = consumables.filter { it.productId != item.productId }
                                            consumables = updatedList
                                            scope.launch {
                                                val srvId = selectedService?.id ?: return@launch
                                                try {
                                                    val api = app.apiClient.getApiService()
                                                    api.setServiceConsumables(
                                                        mapOf(
                                                            "service_id" to srvId,
                                                            "consumables" to updatedList.map {
                                                                mapOf("product_id" to it.productId, "quantity_per_service" to it.quantityPerService)
                                                            }
                                                        )
                                                    )
                                                    Toast.makeText(context, "Видалено з норм послуги", Toast.LENGTH_SHORT).show()
                                                } catch (e: Exception) {
                                                    // error
                                                }
                                            }
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = RosePrimary),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Готово", fontWeight = FontWeight.Bold)
            }
        }
    )

    if (showAddConsumableSheet) {
        var selectedProduct by remember { mutableStateOf(products.firstOrNull()) }
        var qtyText by remember { mutableStateOf("1.0") }

        AlertDialog(
            onDismissRequest = { showAddConsumableSheet = false },
            title = { Text("Прив'язати матеріал", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Матеріал зі складу:", fontSize = 13.sp)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        products.forEach { prod ->
                            FilterChip(
                                selected = selectedProduct?.id == prod.id,
                                onClick = { selectedProduct = prod },
                                label = { Text(prod.name, fontSize = 11.sp) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = qtyText,
                        onValueChange = { qtyText = it },
                        label = { Text("Витрата на 1 послугу (${selectedProduct?.unit ?: "шт"})") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val prod = selectedProduct ?: return@Button
                        val srvId = selectedService?.id ?: return@Button
                        val q = qtyText.toDoubleOrNull() ?: 1.0
                        val updatedList = consumables + ServiceConsumableItemDto(
                            productId = prod.id,
                            productName = prod.name,
                            quantityPerService = q,
                            unit = prod.unit
                        )
                        consumables = updatedList

                        scope.launch {
                            try {
                                val api = app.apiClient.getApiService()
                                api.setServiceConsumables(
                                    mapOf(
                                        "service_id" to srvId,
                                        "consumables" to updatedList.map {
                                            mapOf("product_id" to it.productId, "quantity_per_service" to it.quantityPerService)
                                        }
                                    )
                                )
                                Toast.makeText(context, "Матеріал прив'язано до послуги!", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Помилка збереження", Toast.LENGTH_SHORT).show()
                            } finally {
                                showAddConsumableSheet = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RosePrimary)
                ) {
                    Text("Зберегти")
                }
            },
            dismissButton = { TextButton(onClick = { showAddConsumableSheet = false }) { Text("Скасувати") } }
        )
    }
}

package com.mastercrm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mastercrm.MasterCrmApp
import com.mastercrm.data.local.entity.ExpenseEntity
import com.mastercrm.data.local.entity.IncomeEntity
import com.mastercrm.data.local.entity.ProductEntity
import com.mastercrm.data.remote.dto.FinanceReportDto
import com.mastercrm.ui.theme.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinanceScreen() {
    val app = MasterCrmApp.instance
    val scope = rememberCoroutineScope()

    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabTitles = listOf("Аналітика", "Доходи", "Витрати", "Склад")

    val incomes by app.database.incomeDao().getAllIncomesFlow().collectAsState(initial = emptyList())
    val expenses by app.database.expenseDao().getAllExpensesFlow().collectAsState(initial = emptyList())
    val products by app.database.productDao().getAllProductsFlow().collectAsState(initial = emptyList())

    var financeReport by remember { mutableStateOf<FinanceReportDto?>(null) }
    var isLoadingReport by remember { mutableStateOf(false) }

    // Fetch report on launch
    LaunchedEffect(Unit) {
        scope.launch {
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
                // Keep local calculations
            } finally {
                isLoadingReport = false
            }
        }
    }

    var showAddIncomeDialog by remember { mutableStateOf(false) }
    var showAddExpenseDialog by remember { mutableStateOf(false) }
    var showRestockDialog by remember { mutableStateOf<ProductEntity?>(null) }
    var showAuditDialog by remember { mutableStateOf<ProductEntity?>(null) }

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
                0 -> AnalyticsTab(financeReport, incomes, expenses, isLoadingReport)
                1 -> IncomesTab(incomes, onAddIncome = { showAddIncomeDialog = true })
                2 -> ExpensesTab(expenses, onAddExpense = { showAddExpenseDialog = true })
                3 -> InventoryTab(
                    products = products,
                    onRestock = { showRestockDialog = it },
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
                }
            }
        )
    }
}

@Composable
fun AnalyticsTab(
    report: FinanceReportDto?,
    incomes: List<IncomeEntity>,
    expenses: List<ExpenseEntity>,
    isLoading: Boolean
) {
    val totalIncome = incomes.sumOf { it.amount }
    val tips = incomes.filter { it.category == "tips" }.sumOf { it.amount }
    val totalExpense = expenses.sumOf { it.amount }
    val netProfit = totalIncome - totalExpense

    val grossRev = report?.grossRevenue ?: (totalIncome - tips)
    val displayNet = report?.netProfit ?: netProfit
    val avgCheck = report?.averageCheck ?: 0.0
    val margin = report?.marginPercent ?: 0.0

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Чистий прибуток (Net Profit)", color = Color.Gray, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "${displayNet.toInt()} грн",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (displayNet >= 0) StatusCompleted else StatusCancelled
                    )
                }
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricSmallCard("Виручка", "${grossRev.toInt()} грн", StatusConfirmed, Modifier.weight(1f))
                MetricSmallCard("Чайові", "${tips.toInt()} грн", RosePrimary, Modifier.weight(1f))
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricSmallCard("Витрати", "${totalExpense.toInt()} грн", StatusCancelled, Modifier.weight(1f))
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
                    Text("Середній чек:", fontWeight = FontWeight.SemiBold)
                    Text("${avgCheck.toInt()} грн", fontWeight = FontWeight.Bold, color = RosePrimary)
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
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(incomes, key = { it.id }) { inc ->
                    Card(
                        shape = RoundedCornerShape(12.dp),
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
                                Text(
                                    text = if (inc.category == "tips") "Чайові ☕️" else "Послуга 💅",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                )
                                Text(text = "${inc.date} • ${inc.paymentMethod}", color = Color.Gray, fontSize = 12.sp)
                            }
                            Text("+${inc.amount.toInt()} грн", fontWeight = FontWeight.Bold, color = StatusCompleted, fontSize = 16.sp)
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
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(expenses, key = { it.id }) { exp ->
                    Card(
                        shape = RoundedCornerShape(12.dp),
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
                                Text(text = exp.category, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(
                                    text = "${exp.date} • ${exp.description ?: "Без опису"}",
                                    color = Color.Gray,
                                    fontSize = 12.sp
                                )
                            }
                            Text("-${exp.amount.toInt()} грн", fontWeight = FontWeight.Bold, color = StatusCancelled, fontSize = 16.sp)
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
    onRestock: (ProductEntity) -> Unit,
    onAudit: (ProductEntity) -> Unit
) {
    if (products.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Склад порожній", color = Color.Gray)
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(products, key = { it.id }) { prod ->
                val isLowStock = prod.currentStock <= prod.minStockAlert

                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = prod.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            if (isLowStock) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFD32F2F), modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Закінчується!", color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Залишок: ${prod.currentStock} ${prod.unit} (мін. ${prod.minStockAlert})",
                            color = if (isLowStock) Color(0xFFD32F2F) else Color.Gray,
                            fontSize = 13.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { onRestock(prod) },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("+ Поповнити", fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = { onAudit(prod) },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Інвентаризація", fontSize = 12.sp)
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

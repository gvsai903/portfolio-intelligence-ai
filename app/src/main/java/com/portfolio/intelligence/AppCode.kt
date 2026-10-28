package com.portfolio.intelligence

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// --- 1. DATA MODELS ---
fun Double.toInr(): String = NumberFormat.getCurrencyInstance(Locale("en", "IN")).format(this)

data class Holding(
    val symbol: String,
    val quantity: Double,
    val avgCost: Double,
    val currentPrice: Double = 0.0,
    val previousClose: Double = 0.0,
    val sector: String = "General"
) {
    val totalInvested: Double get() = quantity * avgCost
    val currentValue: Double get() = quantity * currentPrice
    val unrealizedPnL: Double get() = currentValue - totalInvested
    val unrealizedPnLPercent: Double get() = if (totalInvested > 0) (unrealizedPnL / totalInvested) * 100 else 0.0
    val dayChange: Double get() = (currentPrice - previousClose) * quantity
    val dayChangePercent: Double get() = if (previousClose > 0) ((currentPrice - previousClose) / previousClose) * 100 else 0.0
}

data class NewsCitation(val title: String, val source: String, val url: String, val publishedAt: String)
data class TechnicalIndicator(val name: String, val value: String, val hasInsufficientHistory: Boolean = false)

data class AiResearchReport(
    val symbol: String,
    val verifiedFacts: List<String>,
    val plausibleExplanations: List<String>,
    val unknownFactors: List<String>,
    val citations: List<NewsCitation>,
    val technicals: List<TechnicalIndicator>,
    val generatedAt: String
)

data class ScenarioOutcome(
    val scenarioType: String,
    val probability: Int,
    val narrative: String,
    val supportingEvidence: List<String>,
    val risks: List<String>
)

data class AiOutlook(
    val symbol: String,
    val scenarios: List<ScenarioOutcome>,
    val disclaimer: String
)

enum class AlertType { BUY_REVIEW, SELL_REVIEW, WATCH, DATA_ERROR }
data class AlertItem(val id: String, val symbol: String, val alertType: AlertType, val reason: String, val timestamp: String, val isDeduplicated: Boolean)

data class SectorWeight(val sectorName: String, val percentage: Double, val value: Double)
data class ReportSummary(
    val reportDate: String,
    val executiveSummary: String,
    val topMovers: List<Holding>,
    val worstMovers: List<Holding>,
    val sectorDistribution: List<SectorWeight>
)

data class MarketStatus(val isOpen: Boolean, val sessionName: String, val nextEventTime: String)

// --- 2. CSV PARSER & EXPORTER ---
object CsvProcessor {
    fun parseHoldingsCsv(inputStream: InputStream): Result<List<Holding>> = runCatching {
        val list = mutableListOf<Holding>()
        var symCol = -1; var qtyCol = -1; var avgCol = -1
        BufferedReader(InputStreamReader(inputStream)).forEachLine { raw ->
            val line = raw.trim()
            if (line.isNotEmpty()) {
                val tokens = line.split(",").map { it.trim().replace("\"", "") }
                if (symCol == -1) {
                    tokens.forEachIndexed { i, col ->
                        when (col.lowercase()) {
                            "symbol" -> symCol = i
                            "quantity", "qty" -> qtyCol = i
                            "avg_cost", "avgcost", "average_cost" -> avgCol = i
                        }
                    }
                } else if (tokens.size > maxOf(symCol, qtyCol, avgCol)) {
                    val s = tokens[symCol].uppercase()
                    val q = tokens[qtyCol].toDoubleOrNull() ?: 0.0
                    val a = tokens[avgCol].toDoubleOrNull() ?: 0.0
                    if (s.isNotEmpty() && q > 0) {
                        list.add(Holding(symbol = s, quantity = q, avgCost = a, currentPrice = a, previousClose = a))
                    }
                }
            }
        }
        list
    }

    fun exportHoldingsCsv(holdings: List<Holding>, outputStream: OutputStream): Result<Unit> = runCatching {
        outputStream.bufferedWriter().use { writer ->
            writer.write("symbol,quantity,avg_cost\n")
            holdings.forEach { writer.write("${it.symbol},${it.quantity},${it.avgCost}\n") }
            writer.flush()
        }
    }
}

// --- 3. REPOSITORY & SIMULATION DATA ---
class PortfolioRepository {
    private val _holdings = MutableStateFlow(createInitialHoldings())
    val holdingsFlow: StateFlow<List<Holding>> = _holdings.asStateFlow()

    fun saveHoldings(newList: List<Holding>) { _holdings.value = newList }

    fun getMarketStatus(): MarketStatus {
        val now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
        val day = now.dayOfWeek
        val time = now.toLocalTime()
        val isWeekend = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY
        val open = LocalTime.of(9, 15)
        val close = LocalTime.of(15, 30)
        val isOpen = !isWeekend && time.isAfter(open) && time.isBefore(close)
        val session = when {
            isWeekend -> "Weekend Closed"
            isOpen -> "NSE/BSE Regular Market Open"
            else -> "After-Hours 3-Min Monitor Active"
        }
        return MarketStatus(isOpen, session, if (isOpen) "Closes 15:30 IST" else "Opens 09:15 IST")
    }

    fun getAiResearch(symbol: String): AiResearchReport = AiResearchReport(
        symbol = symbol,
        verifiedFacts = listOf(
            "Q2 Consolidated Net Profit up 12.4% YoY to ₹6,840 Cr (BSE Filing).",
            "Capex deployment of ₹4,200 Cr finalized for digital and green infra."
        ),
        plausibleExplanations = listOf(
            "Sector index correlation to NIFTY rally.",
            "Accelerated institutional inflows over trailing 20 sessions."
        ),
        unknownFactors = listOf("Unverified social media rumors regarding retail arm listing."),
        citations = listOf(
            NewsCitation("Outcome of Board Meeting", "BSE Announcements", "https://bseindia.com", "Today 14:15 IST")
        ),
        technicals = listOf(
            TechnicalIndicator("14-Day RSI", "56.4"),
            TechnicalIndicator("50-Day SMA", "₹2,840.10 (Bullish)"),
            TechnicalIndicator("60-Month Historical Beta", "Insufficient Data", hasInsufficientHistory = true)
        ),
        generatedAt = "Today 14:20 IST [MOCK DATA]"
    )

    fun getAiOutlook(symbol: String): AiOutlook = AiOutlook(
        symbol = symbol,
        scenarios = listOf(
            ScenarioOutcome("Base Case", 55, "Consolidation within current range with positive earnings bias.", listOf("Stable domestic order book"), listOf("Crude price volatility")),
            ScenarioOutcome("Bullish Breakout", 25, "Upward expansion if retail EBITDA exceeds 16%.", listOf("High retail footfall"), listOf("Global liquidity tightening")),
            ScenarioOutcome("Bearish Support Retest", 20, "Retracement toward 200 SMA support.", listOf("Export corridor margin pressure"), listOf("FII outflow"))
        ),
        disclaimer = "Scenarios are probabilistic models. Rules strictly prohibit triggering alerts from unrealized P&L alone."
    )

    fun getAlerts(): List<AlertItem> = listOf(
        AlertItem("1", "TCS", AlertType.SELL_REVIEW, "Trading 14% above 200 SMA; operating margin guidance cut.", "11:42 IST", true),
        AlertItem("2", "HDFCBANK", AlertType.BUY_REVIEW, "PE multiple entered 5-year 15th percentile with institutional inflow.", "10:15 IST", true),
        AlertItem("3", "FEED", AlertType.DATA_ERROR, "NSE tick delay detected. Repeat alert suppressed.", "09:16 IST", true)
    )

    fun getReport(): ReportSummary {
        val list = _holdings.value
        return ReportSummary(
            reportDate = ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).format(DateTimeFormatter.ofPattern("dd MMM yyyy")),
            executiveSummary = "Portfolio monitored continuously via 3-minute cycle. Advances led by Large-cap Energy and IT.",
            topMovers = list.take(3),
            worstMovers = list.takeLast(3),
            sectorDistribution = listOf(
                SectorWeight("Energy", 35.0, 850000.0),
                SectorWeight("IT & Tech", 30.0, 720000.0),
                SectorWeight("Financials", 25.0, 600000.0),
                SectorWeight("Others", 10.0, 240000.0)
            )
        )
    }

    private fun createInitialHoldings(): List<Holding> {
        val list = mutableListOf(
            Holding("RELIANCE", 150.0, 2450.00, 2890.50, 2840.00, "Energy"),
            Holding("TCS", 80.0, 3500.00, 3980.00, 4010.00, "IT"),
            Holding("HDFCBANK", 220.0, 1550.00, 1680.25, 1665.00, "Financials"),
            Holding("INFY", 190.0, 1420.00, 1820.75, 1805.00, "IT"),
            Holding("ITC", 500.0, 380.00, 472.10, 474.00, "FMCG"),
            Holding("TATAMOTORS", 120.0, 680.00, 940.30, 925.00, "Auto"),
            Holding("LT", 60.0, 2900.00, 3550.00, 3510.00, "Capital Goods"),
            Holding("ICICIBANK", 200.0, 910.00, 1220.40, 1208.00, "Financials")
        )
        for (i in 9..106) {
            val cost = 100.0 + (i * 15)
            list.add(Holding("NSE_STOCK_$i", 20.0 + i, cost, cost * 1.08, cost * 1.06, "Diversified"))
        }
        return list
    }
}

// --- 4. VIEWMODEL ---
class PortfolioViewModel(private val repository: PortfolioRepository = PortfolioRepository()) : ViewModel() {
    val holdings = repository.holdingsFlow
    private val _selectedSymbol = MutableStateFlow("RELIANCE")
    val selectedSymbol = _selectedSymbol.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage = _statusMessage.asStateFlow()

    fun selectSymbol(s: String) { _selectedSymbol.value = s }
    fun clearMessage() { _statusMessage.value = null }
    fun getMarketStatus() = repository.getMarketStatus()
    fun getResearch(s: String) = repository.getAiResearch(s)
    fun getOutlook(s: String) = repository.getAiOutlook(s)
    fun getAlerts() = repository.getAlerts()
    fun getReport() = repository.getReport()

    fun importCsv(context: Context, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    CsvProcessor.parseHoldingsCsv(it).getOrThrow()
                } ?: emptyList()
            }.onSuccess {
                if (it.isNotEmpty()) {
                    repository.saveHoldings(it)
                    _statusMessage.value = "Imported ${it.size} holdings successfully."
                }
            }.onFailure {
                _statusMessage.value = "Import Failed: ${it.localizedMessage}"
            }
        }
    }

    fun exportCsv(context: Context, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    CsvProcessor.exportHoldingsCsv(holdings.value, it).getOrThrow()
                }
            }.onSuccess { _statusMessage.value = "Export completed successfully." }
        }
    }
}

// --- 5. UI COMPONENTS & SCREENS ---
val PrimaryBlue = Color(0xFF2979FF)
val GainGreen = Color(0xFF00C853)
val LossRed = Color(0xFFFF3D00)
val DarkSurface = Color(0xFF181F2A)
val DarkCard = Color(0xFF222B38)
val DarkBg = Color(0xFF0F141C)

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Dashboard : Screen("dash", "Dash", Icons.Default.Dashboard)
    object Portfolio : Screen("holdings", "Holdings", Icons.Default.AccountBalance)
    object Research : Screen("research", "Research", Icons.Default.Psychology)
    object Outlook : Screen("outlook", "Outlook", Icons.Default.TrendingUp)
    object Alerts : Screen("alerts", "Alerts", Icons.Default.Notifications)
    object Reports : Screen("reports", "Reports", Icons.Default.Assessment)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

@Composable
fun MockBadge() {
    Surface(color = Color(0xFF512DA8), shape = RoundedCornerShape(4.dp)) {
        Text("MOCK DATA", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
fun DashboardScreen(vm: PortfolioViewModel) {
    val list by vm.holdings.collectAsState()
    val invested = list.sumOf { it.totalInvested }
    val current = list.sumOf { it.currentValue }
    val pnl = current - invested
    val pnlPct = if (invested > 0) (pnl / invested) * 100 else 0.0
    val mStatus = vm.getMarketStatus()

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), colors = CardDefaults.cardColors(containerColor = DarkCard)) {
                Row(modifier = Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(mStatus.sessionName, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (mStatus.isOpen) GainGreen else Color.LightGray)
                        Text(mStatus.nextEventTime, fontSize = 10.sp, color = Color.Gray)
                    }
                    MockBadge()
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = DarkCard)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("TOTAL PORTFOLIO VALUATION", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    Text(current.toInr(), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                    val pnlSign = if (pnl >= 0) "+" else ""
                    Text("$pnlSign${pnl.toInr()} ($pnlSign${String.format("%.2f", pnlPct)}%) All-Time", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (pnl >= 0) GainGreen else LossRed)
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = DarkCard)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("3-Min Monitoring Worker Status", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("• Monitored Holdings: ${list.size} active Indian stocks", fontSize = 12.sp, color = Color.LightGray)
                    Text("• Monitoring Cadence: 3-minute recurring background cycle", fontSize = 12.sp, color = Color.LightGray)
                    Text("• Deduplication Guard: Active (Mutes duplicate triggers within 6h)", fontSize = 12.sp, color = Color.LightGray)
                    Text("• Safety Mode: Read-Only (Automated trades permanently disabled)", fontSize = 12.sp, color = Color.LightGray)
                }
            }
        }
    }
}

@Composable
fun PortfolioScreen(vm: PortfolioViewModel, onSelect: (String) -> Unit) {
    val list by vm.holdings.collectAsState()
    var search by remember { mutableStateOf("") }
    val ctx = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            ctx.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            vm.importCsv(ctx, it)
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { vm.exportCsv(ctx, it) }
    }

    val filtered = list.filter { it.symbol.contains(search.trim(), ignoreCase = true) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Holdings (${filtered.size})", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Row {
                Button(onClick = { importLauncher.launch(arrayOf("*/*")) }, shape = RoundedCornerShape(6.dp)) { Text("Import", fontSize = 11.sp) }
                Spacer(modifier = Modifier.width(6.dp))
                OutlinedButton(onClick = { exportLauncher.launch("holdings.csv") }, shape = RoundedCornerShape(6.dp)) { Text("Export", fontSize = 11.sp) }
            }
        }
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            placeholder = { Text("Search by symbol (e.g. RELIANCE)...", fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            singleLine = true
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filtered, key = { it.symbol }) { h ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onSelect(h.symbol) },
                    colors = CardDefaults.cardColors(containerColor = DarkCard)
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text(h.symbol, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("${h.quantity.toInt()} shares • Avg: ${h.avgCost.toInr()}", fontSize = 11.sp, color = Color.Gray)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(h.currentPrice.toInr(), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            val pColor = if (h.unrealizedPnL >= 0) GainGreen else LossRed
                            Text("${if (h.unrealizedPnL >= 0) "+" else ""}${h.unrealizedPnL.toInr()}", fontSize = 11.sp, color = pColor, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AiResearchScreen(vm: PortfolioViewModel) {
    val list by vm.holdings.collectAsState()
    val sym by vm.selectedSymbol.collectAsState()
    val r = remember(sym) { vm.getResearch(sym) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        LazyRow(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(list.take(20)) { h ->
                FilterChip(selected = h.symbol == sym, onClick = { vm.selectSymbol(h.symbol) }, label = { Text(h.symbol, fontSize = 11.sp) })
            }
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Research: $sym", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    MockBadge()
                }
                Text(r.generatedAt, fontSize = 11.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(10.dp))
            }
            item {
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = DarkCard)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Verified Exchange Announcements", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = GainGreen)
                        r.verifiedFacts.forEach { Text("• $it", fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = DarkCard)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Plausible Explanations", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFFFFB74D))
                        r.plausibleExplanations.forEach { Text("• $it", fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = DarkCard)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Technical Indicators", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        r.technicals.forEach { Text("• ${it.name}: ${it.value}", fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
fun AiOutlookScreen(vm: PortfolioViewModel) {
    val sym by vm.selectedSymbol.collectAsState()
    val outlook = remember(sym) { vm.getOutlook(sym) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("1-3 Month Scenarios: $sym", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            MockBadge()
        }
        Text("Probabilistic distribution. Never financial trade guarantees.", fontSize = 11.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(outlook.scenarios) { sc ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = DarkCard)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(sc.scenarioType, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("${sc.probability}% Prob", color = PrimaryBlue, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Text(sc.narrative, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
                        Text("Key Risks: ${sc.risks.joinToString()}", fontSize = 10.sp, color = Color.Gray)
                    }
                }
            }
            item {
                Text(outlook.disclaimer, fontSize = 10.sp, color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
fun AlertsScreen(vm: PortfolioViewModel) {
    val alerts = remember { vm.getAlerts() }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Review Alerts", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            MockBadge()
        }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
            items(alerts) { a ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = DarkCard)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${a.alertType}: ${a.symbol}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(a.timestamp, fontSize = 10.sp, color = Color.Gray)
                        }
                        Text(a.reason, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        if (a.isDeduplicated) {
                            Text("✓ Suppressed repeat triggers within 6-hour window", fontSize = 10.sp, color = Color.Gray, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ReportsScreen(vm: PortfolioViewModel) {
    val rep = remember { vm.getReport() }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Daily Intelligence Wrap", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                MockBadge()
            }
            Text("Date: ${rep.reportDate}", fontSize = 11.sp, color = Color.Gray)
            Spacer(modifier = Modifier.height(8.dp))
        }
        item {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), colors = CardDefaults.cardColors(containerColor = DarkCard)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Executive Summary", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(rep.executiveSummary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        item {
            Text("Sector Allocation Risk", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
            rep.sectorDistribution.forEach { s ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(s.sectorName, fontSize = 12.sp)
                    Text("${s.percentage}% (${s.value.toInr()})", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun SettingsScreen() {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Monitoring & Backend Settings", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(modifier = Modifier.height(12.dp))
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = DarkCard)) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("3-Minute Dedicated Worker", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("• Polls every 3 minutes from remote backend server.", fontSize = 12.sp, color = Color.LightGray)
                Text("• Telegram & AI credentials never reside on this device.", fontSize = 12.sp, color = Color.LightGray)
                Text("• Status: Disconnected (Simulation Active)", fontSize = 12.sp, color = LossRed)
            }
        }
    }
}

// --- 6. MAIN ACTIVITY & NAVIGATION ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val darkColors = darkColorScheme(primary = PrimaryBlue, background = DarkBg, surface = DarkSurface, surfaceVariant = DarkCard)
            MaterialTheme(colorScheme = darkColors) {
                val vm: PortfolioViewModel = viewModel()
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                val items = listOf(Screen.Dashboard, Screen.Portfolio, Screen.Research, Screen.Outlook, Screen.Alerts, Screen.Reports, Screen.Settings)
                val snackbarHostState = remember { SnackbarHostState() }
                val statusMsg by vm.statusMessage.collectAsState()

                LaunchedEffect(statusMsg) {
                    statusMsg?.let { snackbarHostState.showSnackbar(it); vm.clearMessage() }
                }

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    bottomBar = {
                        NavigationBar(containerColor = DarkSurface) {
                            items.forEach { s ->
                                NavigationBarItem(
                                    selected = currentRoute == s.route,
                                    onClick = {
                                        navController.navigate(s.route) {
                                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(s.icon, contentDescription = s.title) },
                                    label = { Text(s.title, fontSize = 9.sp) }
                                )
                            }
                        }
                    }
                ) { padding ->
                    Box(modifier = Modifier.padding(padding)) {
                        NavHost(navController = navController, startDestination = Screen.Dashboard.route) {
                            composable(Screen.Dashboard.route) { DashboardScreen(vm) }
                            composable(Screen.Portfolio.route) {
                                PortfolioScreen(vm) { sym ->
                                    vm.selectSymbol(sym)
                                    navController.navigate(Screen.Research.route)
                                }
                            }
                            composable(Screen.Research.route) { AiResearchScreen(vm) }
                            composable(Screen.Outlook.route) { AiOutlookScreen(vm) }
                            composable(Screen.Alerts.route) { AlertsScreen(vm) }
                            composable(Screen.Reports.route) { ReportsScreen(vm) }
                            composable(Screen.Settings.route) { SettingsScreen() }
                        }
                    }
                }
            }
        }
    }
}

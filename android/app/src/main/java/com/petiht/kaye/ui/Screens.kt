package com.petiht.kaye.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.petiht.kaye.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

const val DEMO_EMAIL = "demo@petiht.com"
const val DEMO_PASS = "KayeDemo509"
val LANGS = linkedMapOf("en" to "English", "ht" to "Kreyòl", "fr" to "Français", "es" to "Español")
val CURRENCIES = listOf("HTG", "USD", "DOP", "EUR", "XOF", "MXN")

@Composable
fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content,
    )
}

@Composable
private fun BigButton(text: String, icon: ImageVector?, color: Color, onColor: Color, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = onColor),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = onColor, strokeWidth = 2.dp)
        else if (icon != null) Icon(icon, null)
        Spacer(Modifier.width(8.dp))
        Text(text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

// ---------------- auth ----------------

@Composable
fun AuthScreen(busy: Boolean, error: String?, onAnon: () -> Unit, onEmail: (String, String, Boolean) -> Unit) {
    val c = kc
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var create by remember { mutableStateOf(false) }
    Page {
        Spacer(Modifier.height(8.dp))
        NotebookCard {
            Text("Kaye ou, ki konn kalkile.", style = HandStyle, color = c.accent)
            Text("Kaye", fontSize = 44.sp, fontWeight = FontWeight.ExtraBold)
            Text("Your shop notebook that reads your pages on your phone, even with no signal.", fontSize = 16.sp)
        }
        KCard {
            BigButton("Try it now, no account", Icons.Outlined.AutoAwesome, c.accent, c.onAccent, busy = busy, onClick = onAnon)
            Spacer(Modifier.height(6.dp))
            Muted("You get your own private notebook. Save it with an email later.", 13)
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = c.line)
            OutlinedTextField(email, { email = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Email") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, label = { Text("Password (6+ characters)") },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { onEmail(email, pass, create) }, enabled = !busy && email.isNotBlank() && pass.length >= 6,
                modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) {
                Text(if (create) "Create my notebook" else "Sign in", fontWeight = FontWeight.Bold)
            }
            if (error != null) Text(error, color = c.cta, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            TextButton(onClick = { create = !create }) { Text(if (create) "I already have an account" else "New here? Create an account") }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.sunSoft).padding(12.dp)) {
                Text("Judges", fontWeight = FontWeight.ExtraBold)
                Text("Demo account: $DEMO_EMAIL / $DEMO_PASS. Sample data is invented.", fontSize = 14.sp)
                TextButton(onClick = { email = DEMO_EMAIL; pass = DEMO_PASS; create = false }, contentPadding = PaddingValues(0.dp)) { Text("Fill in the demo account") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(suggestedName: String, onDone: (Repo.Profile, Boolean) -> Unit) {
    val c = kc
    var name by remember { mutableStateOf(suggestedName) }
    var business by remember { mutableStateOf("") }
    var market by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("HTG") }
    var lang by remember { mutableStateOf("ht") }
    var sample by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    Page {
        NotebookCard {
            Text("Byenveni!", style = HandStyle, color = c.accent)
            Text("Welcome", style = MaterialTheme.typography.headlineMedium)
            Muted("Three quick things, then your notebook is ready.")
        }
        KCard {
            OutlinedTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Your name") })
            OutlinedTextField(business, { business = it.take(80) }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, label = { Text("Business (e.g. Rice & beans stall)") })
            OutlinedTextField(market, { market = it.take(80) }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, label = { Text("Market (e.g. Marché Cluny, Cap-Haïtien)") })
            Text("Currency", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { CURRENCIES.forEach { FilterChip(currency == it, { currency = it }, { Text(it) }) } }
            Text("Language", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { LANGS.forEach { (k, v) -> FilterChip(lang == k, { lang = k }, { Text(v) }) } }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Checkbox(sample, { sample = it })
                Text("Load a sample notebook (6 weeks of invented sales) so you can explore", fontSize = 14.sp)
            }
            Spacer(Modifier.height(8.dp))
            BigButton("Open my notebook", null, c.accent, c.onAccent, busy = busy) {
                busy = true
                onDone(Repo.Profile(name, business.ifBlank { "My stall" }, currency, market, lang), sample)
            }
        }
    }
}

// ---------------- home ----------------

@Composable
fun HomeScreen(s: KayeState, go: (String) -> Unit, onDelete: (Entry) -> Unit) {
    val c = kc
    val p = s.profile ?: return
    val today = Ledger.today()
    val t = Ledger.totals(s.entries.filter { it.date == today })
    val bal = Ledger.balances(s.credit, s.customers)
    val owed = bal.sumOf { maxOf(0.0, it.owed) }
    val m = Ledger.totals(s.entries, s.month)
    val money = { n: Double -> Ledger.money(n, p.currency) }
    Page {
        NotebookCard {
            Text("Bonjou${p.name.split(" ").firstOrNull()?.takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""}!", style = HandStyle, color = c.accent)
            Text(p.business.ifBlank { "My notebook" }, style = MaterialTheme.typography.headlineMedium)
            Muted(listOf(p.market, LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM"))).filter { it.isNotBlank() }.joinToString(" · "))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Kpi("Sales today", money(t.sales), c.green, Modifier.weight(1f))
            Kpi("Costs today", money(t.expenses), c.cta, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Kpi("Profit today", money(t.profit), if (t.profit >= 0) c.green else c.cta, Modifier.weight(1f))
            Kpi("Owed to you", money(owed), c.ink, Modifier.weight(1f), highlight = true) { go("owed") }
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.accentSoft).padding(12.dp)) {
            Text("This month: ${money(m.sales)} sales · ${money(m.profit)} profit", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            m.onDeviceShare?.let { Text("$it% of AI reads stayed on the device", color = c.green, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Action("Snap a page", "Notebook or receipt", Icons.Outlined.PhotoCamera, c.cta, Modifier.weight(1f)) { go("snap") }
            Action("Quick note", "“Sold 3 rice…”", Icons.Outlined.EditNote, c.accent, Modifier.weight(1f)) { go("note") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Action("Who owes me", "${bal.count { it.owed > 0 }} customer(s)", Icons.Outlined.People, c.accent, Modifier.weight(1f)) { go("owed") }
            Action("Month record", "For you or a lender", Icons.Outlined.MenuBook, c.accent, Modifier.weight(1f)) { go("month") }
        }
        KCard {
            SectionTitle("Latest lines")
            EntryList(s.entries.take(14), s.customers, money, onDelete)
        }
    }
}

@Composable
private fun Action(title: String, sub: String, icon: ImageVector, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    val c = kc
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(c.paper).border(1.dp, c.line, RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(14.dp)) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(tint), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (tint == c.accent) c.onAccent else Color.White)
        }
        Spacer(Modifier.height(8.dp))
        Text(title, fontWeight = FontWeight.ExtraBold)
        Text(sub, color = c.muted, fontSize = 12.sp)
    }
}

@Composable
fun EntryList(list: List<Entry>, customers: List<Customer>, money: (Double) -> String, onDelete: (Entry) -> Unit) {
    val c = kc
    if (list.isEmpty()) { Muted("No lines yet. Snap a page or write a quick note."); return }
    val names = customers.associate { it.id to it.name }
    var last = ""
    list.forEach { e ->
        if (e.date != last) {
            last = e.date
            Text(runCatching { LocalDate.parse(e.date).format(DateTimeFormatter.ofPattern("EEE d MMM")) }.getOrDefault(e.date).uppercase(),
                color = c.muted, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
        }
        val dot = when (e.kind) { "expense" -> c.cta; "credit" -> K.Sun; "payment" -> c.accent; else -> c.green }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (e.kind == "credit" || e.kind == "payment") "${KIND_LABEL[e.kind]} · ${names[e.customerId] ?: "customer"}" else e.item ?: KIND_LABEL[e.kind] ?: "",
                    fontWeight = FontWeight.Bold, maxLines = 1,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(listOfNotNull(if (e.qty != null && e.unitPrice != null) "${trimNum(e.qty)} × ${money(e.unitPrice)}" else KIND_LABEL[e.kind], SRC_LABEL[e.ai]).joinToString(" · "),
                        color = c.muted, fontSize = 12.sp)
                    if (e.pending) Icon(Icons.Outlined.Schedule, "Waiting to sync", tint = c.amber, modifier = Modifier.padding(start = 4.dp).size(14.dp))
                }
            }
            val sign = when (e.kind) { "sale", "payment" -> "+"; "expense" -> "−"; else -> "" }
            Text(sign + money(e.amount), fontFamily = Mono, fontWeight = FontWeight.SemiBold,
                color = when (e.kind) { "expense" -> c.cta; "sale", "payment" -> c.green; else -> c.ink })
            var confirm by remember { mutableStateOf(false) }
            IconButton(onClick = { confirm = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.Close, "Delete line", tint = c.muted, modifier = Modifier.size(16.dp)) }
            if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Delete this line?") },
                confirmButton = { TextButton({ confirm = false; onDelete(e) }) { Text("Delete", color = c.cta) } },
                dismissButton = { TextButton({ confirm = false }) { Text("Keep") } })
        }
    }
}

// ---------------- snap ----------------

@Composable
fun SnapScreen(
    photo: Bitmap?, reading: Boolean, read: Ai.Read?, ai: Ai, money: (Double) -> String,
    onCamera: () -> Unit, onGallery: () -> Unit, onSample: (String) -> Unit, onRead: () -> Unit, onSave: (List<Line>) -> Unit,
) {
    val c = kc
    Page {
        Text("Snap a page", style = MaterialTheme.typography.headlineMedium)
        Muted("Gemini Nano reads the photo on this phone. It only goes to the cloud if you say yes.")
        KCard {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onCamera, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = c.cta, contentColor = Color.White)) { Icon(Icons.Outlined.PhotoCamera, null); Spacer(Modifier.width(6.dp)); Text("Take photo") }
                OutlinedButton(onClick = onGallery, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(14.dp)) { Icon(Icons.Outlined.Image, null); Spacer(Modifier.width(6.dp)); Text("Choose") }
            }
            Muted("No notebook here? Try a sample page:", 13)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Samples.PAGES.forEach { pg -> AssistChip(onClick = { onSample(pg.id) }, label = { Text(pg.label) }) }
            }
            if (photo != null) {
                Image(photo.asImageBitmap(), "Notebook page", Modifier.fillMaxWidth().heightIn(max = 380.dp).padding(vertical = 10.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
                BigButton(if (reading) "Reading the page…" else "Read this page", Icons.Outlined.AutoAwesome, c.accent, c.onAccent, busy = reading, onClick = onRead)
            }
        }
        if (read != null) DraftEditor(read, true, ai, money, onSave)
    }
}

// ---------------- note ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteScreen(
    defaultLang: String, busy: Boolean, read: Ai.Read?, ai: Ai, money: (Double) -> String, dictated: String?,
    onMic: (String) -> Unit, onRead: (String, String) -> Unit, onSave: (List<Line>) -> Unit,
) {
    val c = kc
    var lang by remember { mutableStateOf(defaultLang) }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(dictated) { if (!dictated.isNullOrBlank()) text = (text + " " + dictated).trim() }
    Page {
        Text("Quick note", style = MaterialTheme.typography.headlineMedium)
        Muted("Say it or type it the way you talk. Names are hidden before anything goes to the cloud.")
        KCard {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { LANGS.forEach { (k, v) -> FilterChip(lang == k, { lang = k }, { Text(v) }) } }
            OutlinedTextField(text, { text = it.take(1200) }, Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(top = 6.dp),
                placeholder = { Text("Sold 3 bags of rice, 250 each. Bought charcoal for 400. Marie owes 500.") },
                trailingIcon = { IconButton(onClick = { onMic(lang) }) { Icon(Icons.Outlined.Mic, "Dictate (offline when possible)", tint = c.accent) } })
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Rules.SAMPLES.forEach { s -> AssistChip(onClick = { text = s.text; lang = s.lang }, label = { Text(s.label) }) }
            }
            BigButton(if (busy) "Understanding…" else "Turn into lines", Icons.Outlined.AutoAwesome, c.accent, c.onAccent, enabled = text.isNotBlank(), busy = busy) { onRead(text, lang) }
        }
        if (read != null) DraftEditor(read, false, ai, money, onSave)
    }
}

// ---------------- owed ----------------

@Composable
fun OwedScreen(s: KayeState, money: (Double) -> String, onAdd: (String, String, Double) -> Unit, onRemind: suspend (Balance) -> Ai.Written, onShare: (String) -> Unit) {
    val c = kc
    val bal = Ledger.balances(s.credit, s.customers).filter { it.owed > 0 }
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("credit") }
    Page {
        Text("Who owes me", style = MaterialTheme.typography.headlineMedium)
        Muted("Names stay in your private notebook. The AI never sees them.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Kpi("Owed to you", money(bal.sumOf { it.owed }), c.ink, Modifier.weight(1f), highlight = true)
            Kpi("Customers", bal.size.toString(), c.ink, Modifier.weight(1f))
        }
        KCard {
            SectionTitle("Add credit or a payment")
            OutlinedTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Customer name") })
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(kind == "credit", { kind = "credit" }, { Text("took on credit") })
                FilterChip(kind == "payment", { kind = "payment" }, { Text("paid back") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(amount, { amount = it }, Modifier.weight(1f), singleLine = true, label = { Text("Amount") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Button(onClick = { amount.toDoubleOrNull()?.let { a -> if (name.isNotBlank() && a > 0) { onAdd(name.trim(), kind, a); name = ""; amount = "" } } }) { Text("Save") }
            }
        }
        KCard {
            if (bal.isEmpty()) Muted("Nobody owes you money.")
            bal.forEach { b -> OwedRow(b, money, onAdd, onRemind, onShare) }
        }
    }
}

@Composable
private fun OwedRow(b: Balance, money: (Double) -> String, onAdd: (String, String, Double) -> Unit, onRemind: suspend (Balance) -> Ai.Written, onShare: (String) -> Unit) {
    val c = kc
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<Ai.Written?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pay by remember { mutableStateOf(false) }
    Column(Modifier.padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(c.sunSoft), contentAlignment = Alignment.Center) {
                Text(b.name.split(" ").mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""), fontWeight = FontWeight.ExtraBold, color = if (c.dark) K.Sun else Color(0xFF6B5300))
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(b.name, fontWeight = FontWeight.Bold)
                if (b.last.isNotBlank()) Muted("Last: ${b.last}", 12)
            }
            Text(money(b.owed), fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pay = true }) { Text("Paid back") }
            OutlinedButton(onClick = { busy = true; scope.launchCatching { msg = onRemind(b); busy = false } }, enabled = !busy) {
                if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp)); Text("Polite reminder")
            }
        }
        msg?.let { m ->
            Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(c.paper2).padding(12.dp)) {
                Text(m.text)
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SourceBadge(m.source, m.ms)
                    TextButton(onClick = { onShare(m.text) }) { Text("Share") }
                }
                TraceBox(m.trace)
            }
        }
        if (pay) {
            var v by remember { mutableStateOf(trimNum(b.owed)) }
            AlertDialog(onDismissRequest = { pay = false }, title = { Text("How much did ${b.name} pay back?") },
                text = { OutlinedTextField(v, { v = it }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)) },
                confirmButton = { TextButton({ v.toDoubleOrNull()?.takeIf { it > 0 }?.let { onAdd(b.name, "payment", it) }; pay = false }) { Text("Save") } },
                dismissButton = { TextButton({ pay = false }) { Text("Cancel") } })
        }
        HorizontalDivider(Modifier.padding(top = 10.dp), color = c.line)
    }
}

// ---------------- month ----------------

@Composable
fun MonthScreen(s: KayeState, money: (Double) -> String, onMonth: (Int) -> Unit, onWrite: suspend (Totals) -> Ai.Written, onShare: (String) -> Unit) {
    val c = kc
    val p = s.profile ?: return
    val bal = Ledger.balances(s.credit, s.customers).filter { it.owed > 0 }
    val t = Ledger.totals(s.entries, s.month, p.currency, bal.sumOf { it.owed }, bal.size)
    val ym = YearMonth.parse(s.month)
    val scope = rememberCoroutineScope()
    var rec by remember(s.month) { mutableStateOf<Ai.Written?>(null) }
    var busy by remember { mutableStateOf(false) }
    Page {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onMonth(-1) }) { Icon(Icons.Outlined.ChevronLeft, "Previous month") }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${ym.month.getDisplayName(TextStyle.FULL, Locale.getDefault()).replaceFirstChar { it.uppercase() }} ${ym.year}", style = MaterialTheme.typography.headlineMedium)
                Muted(listOf(p.business, p.market).filter { it.isNotBlank() }.joinToString(" · "), 13)
            }
            IconButton(onClick = { onMonth(1) }, enabled = ym < YearMonth.now()) { Icon(Icons.Outlined.ChevronRight, "Next month") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Kpi("Sales", money(t.sales), c.green, Modifier.weight(1f)); Kpi("Costs", money(t.expenses), c.cta, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Kpi("Profit", money(t.profit), if (t.profit >= 0) c.green else c.cta, Modifier.weight(1f)); Kpi("Owed to you", money(t.owed), c.ink, Modifier.weight(1f), highlight = true)
        }
        KCard {
            SectionTitle("Sales by week")
            val max = (t.byWeek.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
            Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                t.byWeek.forEachIndexed { i, w ->
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                        Box(Modifier.fillMaxWidth().fillMaxHeight((w / max).toFloat().coerceIn(0.02f, 1f) * 0.85f).clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)).background(c.accent))
                        Text("W${i + 1}", fontSize = 11.sp, color = c.muted, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        KCard {
            SectionTitle("Best sellers")
            if (t.topItems.isEmpty()) Muted("No sales this month yet.")
            t.topItems.forEachIndexed { i, (item, amt) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text("${i + 1}. $item", Modifier.weight(1f)); Text(money(amt), fontFamily = Mono) }
            }
        }
        KCard {
            SectionTitle("Where the AI ran this month")
            val total = t.sources.values.sum().coerceAtLeast(1)
            Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(99.dp)).background(c.paper2)) {
                listOf("on_device" to c.green, "in_cloud" to c.accent, "rules" to K.Sun, "manual" to c.muted).forEach { (k, col) ->
                    val n = t.sources[k] ?: 0
                    if (n > 0) Box(Modifier.weight(n.toFloat() / total).fillMaxHeight().background(col))
                }
            }
            Text(listOf("on_device", "in_cloud", "rules", "manual").joinToString("   ") { "${SRC_LABEL[it]} ${t.sources[it] ?: 0}" }, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            t.onDeviceShare?.let { Muted("$it% of AI reads ran on the device: free, private and offline.", 13) }
        }
        KCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Monthly record", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Pill("From totals only", c.green, c.greenSoft, Icons.Outlined.Shield)
            }
            Muted("A short, factual summary you can show a microfinance officer. Kaye does not give financial advice.", 13)
            rec?.let { r ->
                Text(r.text, Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.paper2).padding(12.dp), lineHeight = 22.sp)
                r.highlights.forEach { Text("• $it", fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp)) }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SourceBadge(r.source, r.ms)
                    TextButton(onClick = {
                        onShare("${p.business} — ${ym}\nSales ${money(t.sales)} · Costs ${money(t.expenses)} · Profit ${money(t.profit)} · Owed ${money(t.owed)}\n\n${r.text}\n" + r.highlights.joinToString("\n") { "• $it" })
                    }) { Text("Share") }
                }
                TraceBox(r.trace)
            }
            Spacer(Modifier.height(8.dp))
            BigButton(if (busy) "Writing the record…" else "Write the record", Icons.Outlined.AutoAwesome, c.accent, c.onAccent, enabled = t.entries > 0, busy = busy) {
                busy = true
                scope.launchCatching { rec = onWrite(t); busy = false }
            }
        }
    }
}

// ---------------- settings ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    s: KayeState, ai: Ai, downloadPct: Int?,
    onSave: (Repo.Profile) -> Unit, onDownload: () -> Unit, onLink: (String, String) -> Unit,
    onSample: () -> Unit, onClear: () -> Unit, onSignOut: () -> Unit,
) {
    val c = kc
    val p = s.profile ?: return
    var name by remember { mutableStateOf(p.name) }
    var business by remember { mutableStateOf(p.business) }
    var market by remember { mutableStateOf(p.market) }
    var currency by remember { mutableStateOf(p.currency) }
    var lang by remember { mutableStateOf(p.lang) }
    var confirmClear by remember { mutableStateOf(false) }
    val demo = s.email == DEMO_EMAIL
    Page {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        KCard {
            SectionTitle("Your business")
            OutlinedTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name") })
            OutlinedTextField(business, { business = it.take(80) }, Modifier.fillMaxWidth().padding(top = 6.dp), singleLine = true, label = { Text("Business") })
            OutlinedTextField(market, { market = it.take(80) }, Modifier.fillMaxWidth().padding(top = 6.dp), singleLine = true, label = { Text("Market") })
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { CURRENCIES.forEach { FilterChip(currency == it, { currency = it }, { Text(it) }) } }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { LANGS.forEach { (k, v) -> FilterChip(lang == k, { lang = k }, { Text(v) }) } }
            Button(onClick = { onSave(Repo.Profile(name, business, currency, market, lang)) }) { Text("Save") }
        }
        KCard {
            SectionTitle("AI on this phone")
            Text("Gemini Nano: ${s.nano}", fontWeight = FontWeight.Bold)
            Muted("Gemini Nano runs on the phone through Android AICore (Pixel 9/10, Galaxy S25 and other supported phones). Photos and notes read here never leave the phone.", 13)
            if (downloadPct != null) LinearProgressIndicator({ downloadPct / 100f }, Modifier.fillMaxWidth().padding(vertical = 8.dp))
            Button(onClick = onDownload, enabled = s.nano == "downloadable" && downloadPct == null) { Icon(Icons.Outlined.Memory, null); Spacer(Modifier.width(6.dp)); Text(if (s.nano == "available") "Ready" else "Get Gemini Nano") }
            Text("Remote Config", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
            Muted("From ${ai.configSource}: cloud model ${ai.cloudModel}; on-device languages ${ai.deviceLanguages.joinToString()}; check tolerance ${(ai.tolerance * 100).toInt()}%; cloud photos ${if (ai.cloudPhotos) "allowed with your OK" else "off"}.", 13)
        }
        KCard {
            SectionTitle("Account")
            if (s.anonymous) {
                var em by remember { mutableStateOf("") }
                var pw by remember { mutableStateOf("") }
                Muted("You are using Kaye without an account. Add an email to keep this notebook on any device.", 13)
                OutlinedTextField(em, { em = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Email") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                OutlinedTextField(pw, { pw = it }, Modifier.fillMaxWidth().padding(top = 6.dp), singleLine = true, label = { Text("Password (6+)") }, visualTransformation = PasswordVisualTransformation())
                Button(onClick = { onLink(em, pw) }, enabled = em.contains('@') && pw.length >= 6, modifier = Modifier.padding(top = 6.dp)) { Text("Save my notebook") }
            } else Text("Signed in as ${s.email}")
            OutlinedButton(onClick = onSignOut, modifier = Modifier.padding(top = 6.dp)) { Text("Sign out") }
        }
        KCard {
            SectionTitle("Notebook")
            Muted("Your notebook is stored in your private Firebase account and on this phone, so it works with no signal. Only you can read it.", 13)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSample) { Text("Load sample") }
                OutlinedButton(onClick = { confirmClear = true }) { Text(if (demo) "Reset demo" else "Clear notebook", color = c.cta) }
            }
        }
        KCard {
            SectionTitle("Privacy, in plain words")
            listOf(
                "Photos are read by Gemini Nano on this phone. A photo goes to the cloud only after you tap “Send this photo”.",
                "Quick notes go to the cloud only when the phone cannot read them, and customer names and phone numbers are replaced first.",
                "The monthly record is written from totals only.",
                "Analytics counts where the AI ran and how long it took. Never your lines, names or amounts.",
            ).forEach { Text("• $it", fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp)) }
        }
        if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
            title = { Text(if (demo) "Reset the demo notebook?" else "Clear your notebook?") },
            text = { Text(if (demo) "The demo notebook goes back to the sample data." else "Every line, customer and record is deleted. This cannot be undone.") },
            confirmButton = { TextButton({ confirmClear = false; onClear() }) { Text(if (demo) "Reset" else "Delete everything", color = c.cta) } },
            dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } })
    }
}

fun kotlinx.coroutines.CoroutineScope.launchCatching(block: suspend () -> Unit) {
    launch { try { block() } catch (e: Exception) { /* the engines never throw; keep the UI alive */ } }
}

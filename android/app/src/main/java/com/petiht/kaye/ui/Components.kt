package com.petiht.kaye.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.petiht.kaye.Ai
import com.petiht.kaye.Line

@Composable
fun KCard(modifier: Modifier = Modifier, pad: Int = 16, content: @Composable ColumnScope.() -> Unit) {
    val c = kc
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.paper).border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(pad.dp),
        content = content,
    )
}

/** Notebook paper: ruled lines and a red margin. */
@Composable
fun NotebookCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = kc
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.paper).border(1.dp, c.line, RoundedCornerShape(18.dp))
            .drawBehind {
                val step = 30.dp.toPx()
                var y = step
                while (y < size.height) { drawLine(c.rule, Offset(0f, y), Offset(size.width, y), 2f); y += step }
                drawLine(c.margin, Offset(22.dp.toPx(), 0f), Offset(22.dp.toPx(), size.height), 3f)
            }
            .padding(start = 36.dp, end = 16.dp, top = 18.dp, bottom = 18.dp),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))

@Composable
fun Muted(text: String, size: Int = 14) = Text(text, color = kc.muted, fontSize = size.sp)

@Composable
fun Pill(text: String, fg: Color, bg: Color, icon: ImageVector? = null) {
    Row(
        Modifier.clip(RoundedCornerShape(99.dp)).background(bg).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(15.dp))
        Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

val SRC_LABEL = mapOf("on_device" to "On this phone", "in_cloud" to "Cloud", "rules" to "Offline rules", "manual" to "By hand")

@Composable
fun SourceBadge(source: String, ms: Long = 0) {
    val c = kc
    val (fg, bg, ic) = when (source) {
        "on_device" -> Triple(c.green, c.greenSoft, Icons.Outlined.Memory)
        "in_cloud" -> Triple(c.accent, c.accentSoft, Icons.Outlined.Cloud)
        "rules" -> Triple(if (c.dark) K.Sun else Color(0xFF6B5300), c.sunSoft, Icons.Outlined.List)
        else -> Triple(c.muted, c.paper2, Icons.Outlined.Edit)
    }
    Pill((SRC_LABEL[source] ?: source) + if (ms > 0) " · $ms ms" else "", fg, bg, ic)
}

@Composable
fun TraceBox(lines: List<String>) {
    val c = kc
    var open by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(14.dp)).background(c.paper2).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(12.dp)) {
        Row(Modifier.clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Route, null, tint = c.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Where the AI ran, and why", color = c.accent, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
        }
        if (open) lines.forEachIndexed { i, t -> Text("${i + 1}. $t", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
    }
}

val KIND_LABEL = mapOf("sale" to "Sale", "expense" to "Cost", "credit" to "Credit", "payment" to "Paid back")

/** Editable lines + arithmetic check + save. Shared by "Snap a page" and "Quick note". */
@Composable
fun DraftEditor(read: Ai.Read, isPhoto: Boolean, ai: Ai, money: (Double) -> String, onSave: (List<Line>) -> Unit) {
    val c = kc
    val lines = remember(read) { mutableStateListOf<Line>().apply { addAll(read.lines.map { it.copy() }) } }
    var tick by remember { mutableIntStateOf(0) }
    val check = remember(tick, lines.size) { ai.check(lines, read.totalWritten) }
    KCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${lines.size} line(s) found", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            SourceBadge(read.source, read.ms)
        }
        if (read.hidden > 0) Row(Modifier.padding(top = 6.dp)) { Pill("${read.hidden} name(s) hidden", c.green, c.greenSoft, Icons.Outlined.Shield) }
        if (isPhoto) {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(14.dp)).background(if (check.ok) c.greenSoft else c.amberSoft).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(if (check.ok) Icons.Outlined.CheckCircle else Icons.Outlined.Warning, null, tint = if (check.ok) c.green else c.amber)
                Column {
                    Text(if (check.ok) "The numbers add up." else "Check these numbers", fontWeight = FontWeight.ExtraBold, color = if (check.ok) c.green else c.amber)
                    if (check.ok) Text("Lines total ${money(check.sum)}" + (check.total?.let { ", page total ${money(it)}" } ?: ""), fontSize = 13.sp, color = c.green)
                    else check.issues.forEach { Text("• $it", fontSize = 13.sp) }
                }
            }
        }
        lines.forEachIndexed { idx, l -> LineRow(l, onChange = { tick++ }, onRemove = { lines.removeAt(idx); tick++ }) }
        TextButton(onClick = { lines.add(Line("sale", "")); tick++ }) { Icon(Icons.Default.Add, null); Text("Add a line") }
        Button(
            onClick = { onSave(lines.toList()) }, enabled = lines.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(containerColor = c.cta, contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
        ) { Text("Save ${lines.size} line(s)", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        TraceBox(read.trace)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LineRow(l: Line, onChange: () -> Unit, onRemove: () -> Unit) {
    val c = kc
    var kind by remember(l) { mutableStateOf(l.kind) }
    var item by remember(l) { mutableStateOf(l.item) }
    var qty by remember(l) { mutableStateOf(l.qty?.let { trimNum(it) } ?: "") }
    var price by remember(l) { mutableStateOf(l.unitPrice?.let { trimNum(it) } ?: "") }
    var amount by remember(l) { mutableStateOf(if (l.amount > 0) trimNum(l.amount) else "") }
    var cust by remember(l) { mutableStateOf(l.customer ?: "") }
    var menu by remember { mutableStateOf(false) }
    val tint = when (kind) { "expense" -> c.cta; "credit" -> K.Sun; "payment" -> c.accent; else -> c.green }
    val shape = RoundedCornerShape(14.dp)
    // One compact card per line: type · item · remove / qty · price · amount / customer.
    Column(
        Modifier.fillMaxWidth().padding(top = 10.dp).clip(shape).background(c.paper2).border(1.dp, c.line, shape)
            .drawBehind { drawRect(tint, size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height)) }
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box {
                Row(
                    Modifier.height(40.dp).clip(RoundedCornerShape(10.dp)).background(c.paper).border(1.dp, c.line, RoundedCornerShape(10.dp))
                        .clickable { menu = true }.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(KIND_LABEL[kind] ?: kind, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.ink)
                    Icon(Icons.Default.ArrowDropDown, "Change type", tint = c.muted, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(menu, { menu = false }) {
                    KIND_LABEL.forEach { (k, v) -> DropdownMenuItem(text = { Text(v) }, onClick = { kind = k; l.kind = k; menu = false; onChange() }) }
                }
            }
            CompactField(item, { item = it; l.item = it; onChange() }, "Item", Modifier.weight(1f))
            IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Close, "Remove line", tint = c.muted, modifier = Modifier.size(20.dp)) }
        }
        Row(Modifier.padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CompactField(qty, { qty = it; l.qty = it.toDoubleOrNull(); onChange() }, "Qty", Modifier.weight(0.8f), number = true)
            CompactField(price, { price = it; l.unitPrice = it.toDoubleOrNull(); onChange() }, "Price", Modifier.weight(1f), number = true)
            CompactField(amount, { amount = it; l.amount = it.toDoubleOrNull() ?: 0.0; onChange() }, "Amount", Modifier.weight(1.2f), number = true, money = true,
                color = if (kind == "expense") c.cta else c.ink)
        }
        if (kind == "credit" || kind == "payment") {
            CompactField(cust, { cust = it; l.customer = it.ifBlank { null }; onChange() }, "Customer", Modifier.fillMaxWidth().padding(end = 8.dp))
        }
    }
}

/** A 40dp text field with a placeholder instead of a floating label (OutlinedTextField is 56dp+). */
@Composable
private fun CompactField(
    value: String, onValue: (String) -> Unit, hint: String, modifier: Modifier = Modifier,
    number: Boolean = false, money: Boolean = false, color: Color = kc.ink,
) {
    val c = kc
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    BasicTextField(
        value, onValue, singleLine = true,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        textStyle = TextStyle(
            color = color, fontSize = 15.sp, textAlign = if (money) TextAlign.End else TextAlign.Start,
            fontFamily = if (money) Mono else null, fontWeight = if (money) FontWeight.Bold else FontWeight.Normal,
        ),
        cursorBrush = SolidColor(c.accent),
        modifier = modifier.height(40.dp).onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = hint },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxSize().clip(shape).background(c.paper).border(if (focused) 2.dp else 1.dp, if (focused) c.accent else c.line, shape)
                    .padding(horizontal = 10.dp),
                contentAlignment = if (money) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text(hint, color = c.muted, fontSize = 14.sp)
                inner()
            }
        },
    )
}

fun trimNum(d: Double): String = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

@Composable
fun Kpi(label: String, value: String, color: Color, modifier: Modifier = Modifier, highlight: Boolean = false, onClick: (() -> Unit)? = null) {
    val c = kc
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(c.paper).border(1.dp, c.line, RoundedCornerShape(16.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(14.dp),
    ) {
        Text(label.uppercase(), color = c.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
        Text(
            value, color = color, fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 20.sp,
            modifier = if (highlight) Modifier.drawBehind { drawRect(c.sunSoft, topLeft = Offset(0f, size.height * 0.55f)) } else Modifier,
        )
    }
}

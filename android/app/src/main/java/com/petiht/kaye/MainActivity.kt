package com.petiht.kaye

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.firebase.Firebase
import com.google.firebase.ai.ondevice.DownloadStatus
import com.google.firebase.analytics.analytics
import com.google.firebase.analytics.logEvent
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.auth
import com.google.firebase.firestore.ListenerRegistration
import com.petiht.kaye.ui.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.time.YearMonth

/** App state, observed by Compose. */
class KayeState {
    var uid by mutableStateOf<String?>(null)
    var anonymous by mutableStateOf(false)
    var email by mutableStateOf<String?>(null)
    var profile by mutableStateOf<Repo.Profile?>(null)
    var profileLoaded by mutableStateOf(false)
    var month by mutableStateOf(Ledger.month())
    var entries by mutableStateOf(listOf<Entry>())
    var credit by mutableStateOf(listOf<Entry>())
    var customers by mutableStateOf(listOf<Customer>())
    var pending by mutableStateOf(false)
    var online by mutableStateOf(true)
    var nano by mutableStateOf("unavailable")
}

class MainActivity : ComponentActivity() {
    private val s = KayeState()
    private lateinit var ai: Ai
    private var repo: Repo? = null
    private val listeners = mutableListOf<ListenerRegistration>()

    // UI state that the activity results feed into.
    private var photo by mutableStateOf<Bitmap?>(null)
    private var dictated by mutableStateOf<String?>(null)
    private var consent by mutableStateOf<Pair<String, CompletableDeferred<Boolean>>?>(null)
    private var toast by mutableStateOf<String?>(null)
    private var photoUri: Uri? = null

    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) photoUri?.let { u -> photo = Samples.load(this, u) }
    }
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { photo = Samples.load(this, it) }
    }
    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        dictated = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // App Check: Play Integrity protects Firebase AI Logic and Firestore.
        Firebase.appCheck.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
        ai = Ai(applicationContext)
        lifecycleScope.launch { ai.loadRemoteConfig() }

        Firebase.auth.addAuthStateListener { a ->
            val u = a.currentUser
            if (u?.uid == s.uid && s.profileLoaded) return@addAuthStateListener
            stopWatching()
            s.uid = u?.uid; s.anonymous = u?.isAnonymous == true; s.email = u?.email
            s.profile = null; s.profileLoaded = false; s.entries = emptyList(); s.credit = emptyList(); s.customers = emptyList()
            if (u != null) lifecycleScope.launch {
                val r = Repo(u.uid)
                repo = r
                s.profile = r.loadProfile()
                s.profileLoaded = true
                if (s.profile != null) startWatching()
            }
        }

        // Network + Gemini Nano status, refreshed while the app runs.
        lifecycleScope.launch {
            while (true) {
                s.online = ai.online()
                s.nano = ai.modelStatus()
                delay(6000)
            }
        }

        setContent {
            KayeTheme {
                val scope = rememberCoroutineScope()
                var tab by remember { mutableStateOf("home") }
                var authBusy by remember { mutableStateOf(false) }
                var authError by remember { mutableStateOf<String?>(null) }
                var reading by remember { mutableStateOf(false) }
                var pageRead by remember { mutableStateOf<Ai.Read?>(null) }
                var noteBusy by remember { mutableStateOf(false) }
                var noteRead by remember { mutableStateOf<Ai.Read?>(null) }
                var downloadPct by remember { mutableStateOf<Int?>(null) }
                val c = kc
                val currency = s.profile?.currency ?: "HTG"
                val money = { n: Double -> Ledger.money(n, currency) }

                Surface(Modifier.fillMaxSize(), color = c.paper2) {
                    when {
                        s.uid == null -> Box(Modifier.systemBarsPadding()) {
                            AuthScreen(authBusy, authError,
                                onAnon = {
                                    authBusy = true; authError = null
                                    scope.launch {
                                        try { Firebase.auth.signInAnonymously().await(); log("login", "method" to "anonymous") }
                                        catch (e: Exception) { authError = authMessage(e) }
                                        authBusy = false
                                    }
                                },
                                onEmail = { em, pw, create ->
                                    authBusy = true; authError = null
                                    scope.launch {
                                        try {
                                            if (create) Firebase.auth.createUserWithEmailAndPassword(em, pw).await() else Firebase.auth.signInWithEmailAndPassword(em, pw).await()
                                            log(if (create) "sign_up" else "login", "method" to "password")
                                        } catch (e: Exception) { authError = authMessage(e) }
                                        authBusy = false
                                    }
                                })
                        }
                        !s.profileLoaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        s.profile == null -> Box(Modifier.systemBarsPadding()) {
                            OnboardingScreen(if (s.email == DEMO_EMAIL) "Demo vendor" else "") { p, sample ->
                                val r = repo ?: return@OnboardingScreen
                                r.saveProfile(p, isNew = true)
                                s.profile = p
                                startWatching()
                                if (sample) scope.launch { runCatching { r.seedSample() } }
                            }
                        }
                        else -> {
                            BackHandler(enabled = tab != "home") { tab = "home" }
                            Scaffold(
                                containerColor = c.paper2,
                                topBar = { TopBar(onSettings = { tab = "settings" }) },
                                bottomBar = {
                                    NavigationBar(containerColor = c.paper) {
                                        listOf(
                                            Triple("home", "Today", Icons.Outlined.Home), Triple("snap", "Snap", Icons.Outlined.PhotoCamera),
                                            Triple("note", "Note", Icons.Outlined.EditNote), Triple("owed", "Owed", Icons.Outlined.People),
                                            Triple("month", "Month", Icons.Outlined.MenuBook),
                                        ).forEach { (k, label, icon) ->
                                            NavigationBarItem(selected = tab == k, onClick = { tab = k }, icon = { Icon(icon, null) }, label = { Text(label) },
                                                colors = NavigationBarItemDefaults.colors(indicatorColor = c.accentSoft, selectedIconColor = c.accent, selectedTextColor = c.accent))
                                        }
                                    }
                                },
                            ) { pad ->
                                Box(Modifier.padding(pad)) {
                                    val r = repo
                                    when (tab) {
                                        "snap" -> SnapScreen(
                                            photo, reading, pageRead, ai, money,
                                            onCamera = { openCamera() },
                                            onGallery = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                            onSample = { id -> photo = Samples.draw(id); pageRead = null },
                                            onRead = {
                                                val bmp = photo ?: return@SnapScreen
                                                reading = true
                                                scope.launch {
                                                    pageRead = ai.readPage(bmp) { why -> askConsent(why) }
                                                    reading = false
                                                }
                                            },
                                            onSave = { lines ->
                                                val pr = pageRead ?: return@SnapScreen
                                                save(lines, "photo", pr.source); pageRead = null; photo = null; tab = "home"
                                            },
                                        )
                                        "note" -> NoteScreen(
                                            s.profile?.lang ?: "en", noteBusy, noteRead, ai, money, dictated,
                                            onMic = { lang -> dictate(lang) },
                                            onRead = { text, lang ->
                                                noteBusy = true
                                                scope.launch {
                                                    noteRead = ai.readNote(text, lang, s.customers.map { it.name })
                                                    noteBusy = false
                                                }
                                            },
                                            onSave = { lines ->
                                                val nr = noteRead ?: return@NoteScreen
                                                save(lines, "text", nr.source); noteRead = null; dictated = null; tab = "home"
                                            },
                                        )
                                        "owed" -> OwedScreen(s, money,
                                            onAdd = { name, kind, amount -> save(listOf(Line(kind, if (kind == "credit") "Credit" else "Payment", amount = amount, customer = name)), "manual", "manual") },
                                            onRemind = { b -> ai.writeReminder(b.name, b.owed, currency, s.profile?.lang ?: "ht") },
                                            onShare = { share(it) })
                                        "month" -> MonthScreen(s, money,
                                            onMonth = { d -> val next = YearMonth.parse(s.month).plusMonths(d.toLong()); if (next <= YearMonth.now()) { s.month = next.toString(); restartMonth() } },
                                            onWrite = { t ->
                                                val w = ai.writeRecord(t, s.profile?.lang ?: "en")
                                                r?.saveReport(s.month, w.text, w.highlights, w.source, w.ms, t)
                                                w
                                            },
                                            onShare = { share(it) })
                                        "settings" -> SettingsScreen(s, ai, downloadPct,
                                            onSave = { p -> r?.saveProfile(p, isNew = false); s.profile = p; toast = "Saved." },
                                            onDownload = {
                                                downloadPct = 0
                                                scope.launch {
                                                    var total = 1L
                                                    try {
                                                        ai.download().collect { st ->
                                                            when (st) {
                                                                is DownloadStatus.DownloadStarted -> total = maxOf(1L, st.bytesToDownload)
                                                                is DownloadStatus.DownloadInProgress -> downloadPct = ((st.totalBytesDownloaded * 100) / total).toInt().coerceIn(0, 100)
                                                                is DownloadStatus.DownloadCompleted -> { downloadPct = null; s.nano = "available"; toast = "Gemini Nano is ready on this phone." }
                                                                is DownloadStatus.DownloadFailed -> { downloadPct = null; toast = "Download failed. Try again on Wi-Fi." }
                                                                else -> Unit
                                                            }
                                                        }
                                                    } catch (e: Throwable) { downloadPct = null; toast = "Download failed." }
                                                }
                                            },
                                            onLink = { em, pw ->
                                                scope.launch {
                                                    try {
                                                        Firebase.auth.currentUser?.linkWithCredential(EmailAuthProvider.getCredential(em, pw))?.await()
                                                        s.anonymous = false; s.email = em; toast = "Your notebook is saved to this email."
                                                        log("sign_up", "method" to "link_anonymous")
                                                    } catch (e: Exception) { toast = authMessage(e) }
                                                }
                                            },
                                            onSample = { scope.launch { runCatching { r?.seedSample() }; toast = "Sample notebook loaded." } },
                                            onClear = {
                                                scope.launch {
                                                    runCatching { r?.clear(); if (s.email == DEMO_EMAIL) r?.seedSample() }
                                                    toast = if (s.email == DEMO_EMAIL) "Demo notebook reset." else "Notebook cleared."
                                                }
                                            },
                                            onSignOut = { Firebase.auth.signOut(); tab = "home" })
                                        else -> HomeScreen(s, go = { tab = it }, onDelete = { e -> r?.deleteEntry(e.id) })
                                    }
                                }
                            }
                        }
                    }
                    consent?.let { (why, d) ->
                        AlertDialog(
                            onDismissRequest = { consent = null; d.complete(false) },
                            title = { Text("Send this photo to the cloud?") },
                            text = {
                                Column {
                                    Text(why, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(8.dp))
                                    Text("A photo cannot have names removed. If you say yes, only this one photo goes to Gemini in the cloud through Firebase AI Logic, and Kaye does not store it. If you say no, you can fix the lines by hand.", fontSize = 14.sp)
                                }
                            },
                            confirmButton = { Button(onClick = { consent = null; d.complete(true) }, colors = ButtonDefaults.buttonColors(containerColor = c.cta, contentColor = Color.White)) { Text("Send this photo") } },
                            dismissButton = { TextButton(onClick = { consent = null; d.complete(false) }) { Text("Keep it on this phone") } },
                        )
                    }
                    toast?.let { msg ->
                        LaunchedEffect(msg) { delay(3500); toast = null }
                        Box(Modifier.fillMaxSize().padding(bottom = 110.dp), contentAlignment = Alignment.BottomCenter) {
                            Surface(color = c.ink, contentColor = c.paper, shape = MaterialTheme.shapes.medium, shadowElevation = 6.dp) {
                                Text(msg, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun TopBar(onSettings: () -> Unit) {
        val c = kc
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(40.dp))
            Text("Kaye", fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            Pill(if (s.online) "Online" else "No signal", if (s.online) c.green else c.amber, if (s.online) c.greenSoft else c.amberSoft, if (s.online) Icons.Outlined.Wifi else Icons.Outlined.WifiOff)
            Spacer(Modifier.width(6.dp))
            Pill(if (s.pending) "Waiting" else "Synced", if (s.pending) c.amber else c.green, if (s.pending) c.amberSoft else c.greenSoft, if (s.pending) Icons.Outlined.Schedule else Icons.Outlined.CloudDone)
            IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Settings", tint = c.accent) }
        }
    }

    private suspend fun askConsent(why: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        consent = why to d
        return d.await()
    }

    private fun save(lines: List<Line>, method: String, source: String) {
        val r = repo ?: return
        if (lines.any { (it.kind == "credit" || it.kind == "payment") && it.customer.isNullOrBlank() }) { toast = "Credit and paid-back lines need a customer name."; return }
        val ai = if (source in listOf("on_device", "in_cloud", "rules")) source else "manual"
        val n = r.saveLines(lines, Ledger.today(), method, ai, s.customers)
        log("entries_saved", "count" to n.toString(), "method" to method, "ai" to ai)
        toast = if (s.online) "$n line(s) saved." else "$n line(s) saved on this phone. They will sync when there is signal."
    }

    private fun startWatching() {
        val r = repo ?: return
        stopWatching()
        listeners += r.watchMonth(s.month, { list, pending -> s.entries = list; s.pending = pending }, { toast = "Could not read your notebook: ${it.message}" })
        listeners += r.watchCredit { s.credit = it }
        listeners += r.watchCustomers { s.customers = it }
    }

    private fun restartMonth() {
        val r = repo ?: return
        listeners.removeFirstOrNull()?.remove()
        listeners.add(0, r.watchMonth(s.month, { list, pending -> s.entries = list; s.pending = pending }, { toast = it.message }))
    }

    private fun stopWatching() {
        listeners.forEach { it.remove() }
        listeners.clear()
    }

    private fun openCamera() {
        val dir = File(cacheDir, "photos").apply { mkdirs() }
        val f = File(dir, "page.jpg")
        photoUri = FileProvider.getUriForFile(this, "com.petiht.kaye.files", f)
        runCatching { takePhoto.launch(photoUri!!) }.onFailure { toast = "No camera app found." }
    }

    private fun dictate(lang: String) {
        val tag = mapOf("en" to "en-US", "fr" to "fr-FR", "ht" to "fr-HT", "es" to "es-ES")[lang] ?: "en-US"
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say what you sold, bought or who owes you")
        runCatching { speech.launch(i) }.onFailure { toast = "No speech recognizer on this phone." }
    }

    private fun share(text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share"))
    }

    private fun log(name: String, vararg params: Pair<String, String>) {
        runCatching { Firebase.analytics.logEvent(name) { params.forEach { (k, v) -> param(k, v) } } }
    }

    private fun authMessage(e: Exception): String {
        val code = (e as? FirebaseAuthException)?.errorCode ?: ""
        return when {
            code.contains("INVALID") || code.contains("WRONG_PASSWORD") || code.contains("USER_NOT_FOUND") -> "Wrong email or password."
            code.contains("EMAIL_ALREADY_IN_USE") -> "That email already has a notebook. Sign in instead."
            code.contains("WEAK_PASSWORD") -> "Use at least 6 characters."
            e.message?.contains("network", true) == true -> "No connection. Sign in once with signal; after that Kaye works offline."
            else -> e.message ?: "Could not sign in."
        }
    }
}

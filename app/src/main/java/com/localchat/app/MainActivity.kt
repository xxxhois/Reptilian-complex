package com.localchat.app

import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.EOFException
import java.net.SocketException

private val Ink = Color(0xFF171717)
private val Paper = Color(0xFFF7F6F2)
private val Rule = Color(0xFFE1DFD8)
private val Signal = Color(0xFFE54B4B)
private val Muted = Color(0xFF77746D)

class ChatViewModel(application: android.app.Application) : AndroidViewModel(application) {
    private val db = AppDatabase.create(application)
    private val dao = db.chatDao()
    private val repo = ChatRepository(application, dao)
    private val selectedId = MutableStateFlow("main")
    val conversationId: StateFlow<String> = selectedId.asStateFlow()
    val conversations = dao.conversations()
    val messages = selectedId.flatMapLatest { dao.messages(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    var input by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var attachment by mutableStateOf<Attachment?>(null)
    val streamingText = MutableStateFlow("")
    val waitStatus = MutableStateFlow<String?>(null)
    private val waitBase = MutableStateFlow("正在准备请求")
    private var job: Job? = null
    private var statusJob: Job? = null
    private val imagePhrases = listOf("正在把这张图看清楚", "正在给图片腾出一点思考空间", "正在整理图片里的线索", "图片已经拿稳了，马上开始看", "正在给这张图做个快速检查", "正在把图片交给模型", "这张图的信息量有点大，让我缓一缓", "正在从图片里寻找重点")
    private val thinkingPhrases = listOf("正在想一个靠谱的回答", "让我把思路理顺一下", "正在翻找合适的表达方式", "这个问题值得认真想想", "正在把零散想法拼起来", "快了，答案正在成形", "正在检查有没有遗漏重点", "正在把回答打磨得更清楚", "思路已经有了，再确认一下", "正在召集我的文字处理能力", "正在进行一小段安静的思考")

    init { viewModelScope.launch { val existing = dao.conversations().first(); if (existing.isEmpty()) dao.saveConversation(Conversation("main", "新对话", System.currentTimeMillis())); else if (existing.none { it.id == selectedId.value }) selectedId.value = existing.first().id } }
    fun newConversation() { stop(); error = null; attachment = null; input = ""; val id = repo.newId(); dao.saveConversation(Conversation(id, "新对话", System.currentTimeMillis())); selectedId.value = id }
    fun selectConversation(id: String) { if (id != selectedId.value) { stop(); error = null; attachment = null; selectedId.value = id } }
    fun send() {
        val text = input.trim(); if (text.isEmpty() || job?.isActive == true) return
        val conversation = selectedId.value; val file = attachment; input = ""; attachment = null; error = null
        job = viewModelScope.launch {
            val userId = repo.newId(); val aiId = repo.newId(); val current = dao.conversations().first().firstOrNull { it.id == conversation }
            val title = if (current?.title == "新对话") text.take(28) else current?.title ?: "新对话"
            dao.saveConversation(Conversation(conversation, title, System.currentTimeMillis()))
            dao.saveMessage(Message(userId, conversation, "user", text, attachmentName = file?.name, attachmentUri = file?.uri?.toString(), attachmentMime = file?.mime))
            dao.saveMessage(Message(aiId, conversation, "assistant", "", "streaming"))
            val history = dao.messages(conversation).first().filter { it.status == "completed" && it.content.isNotBlank() }; var output = ""; var lastPersistAt = 0L
            streamingText.value = ""
            startWaitingStatus(if (file?.mime?.startsWith("image/") == true) "image" else "thinking")
            try {
                repo.stream(conversation, history, file, { status -> waitBase.value = status }) { delta ->
                    waitBase.value = "thinking"; output += delta; streamingText.value = output
                    val now = System.currentTimeMillis()
                    if (now - lastPersistAt >= 100L) { dao.saveMessage(Message(aiId, conversation, "assistant", output, "streaming")); lastPersistAt = now }
                }
                dao.saveMessage(Message(aiId, conversation, "assistant", output, "completed")); streamingText.value = ""; dao.touchConversation(conversation, System.currentTimeMillis())
            } catch (e: Exception) {
                streamingText.value = ""
                if (e !is kotlinx.coroutines.CancellationException) {
                    error = if (e is SocketException || e is EOFException || e.message.orEmpty().contains("connection abort", ignoreCase = true)) {
                        "连接中断，已保留已生成内容，请检查网络后重试"
                    } else e.message ?: "请求失败"
                    dao.saveMessage(Message(aiId, conversation, "assistant", output, "failed"))
                }
            } finally { stopWaitingStatus() }
        }
    }
    private fun startWaitingStatus(initial: String) {
        statusJob?.cancel(); waitBase.value = initial
        statusJob = viewModelScope.launch {
            var lastIndex = -1
            while (isActive) {
                val phrases = if (waitBase.value == "image") imagePhrases else thinkingPhrases
                var index = kotlin.random.Random.nextInt(phrases.size)
                if (phrases.size > 1) while (index == lastIndex) index = kotlin.random.Random.nextInt(phrases.size)
                lastIndex = index
                waitStatus.value = phrases[index]
                delay(2000L)
            }
        }
    }
    private fun stopWaitingStatus() { statusJob?.cancel(); statusJob = null; waitStatus.value = null }
    fun stop() { job?.cancel(); job = null; streamingText.value = ""; stopWaitingStatus() }
    fun setFile(uri: Uri) { val app = getApplication<android.app.Application>(); runCatching { app.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }; val cr = app.contentResolver; val name = uri.lastPathSegment?.substringAfterLast('/') ?: "附件"; attachment = Attachment(name, uri, cr.getType(uri) ?: "application/octet-stream", 0) }
}

class MainActivity : ComponentActivity() { private val vm by viewModels<ChatViewModel>(); override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { ChatApp(vm) } } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ChatApp(vm: ChatViewModel) {
    val conversations by vm.conversations.collectAsStateWithLifecycle(emptyList()); val messages by vm.messages.collectAsStateWithLifecycle(emptyList()); val activeId by vm.conversationId.collectAsStateWithLifecycle(); val liveText by vm.streamingText.collectAsStateWithLifecycle(); val waitStatus by vm.waitStatus.collectAsStateWithLifecycle(); val activeTitle = conversations.firstOrNull { it.id == activeId }?.title ?: "新对话"
    val drawerState = rememberDrawerState(DrawerValue.Closed); val scope = rememberCoroutineScope(); val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::setFile) }; val list = rememberLazyListState(); var showSettings by remember { mutableStateOf(false) }
    LaunchedEffect(messages.size, activeId) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.lastIndex) }
    MaterialTheme(colorScheme = lightColorScheme(primary = Ink, onPrimary = Color.White, background = Paper, surface = Paper, surfaceVariant = Color.White, outline = Rule)) {
        ModalNavigationDrawer(drawerState = drawerState, drawerContent = { ModalDrawerSheet(drawerContainerColor = Paper, modifier = Modifier.width(310.dp)) { Column(Modifier.fillMaxHeight().padding(22.dp)) {
            Text("古老的爬虫脑", color = Signal, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp); Spacer(Modifier.height(28.dp)); Button(onClick = { vm.newConversation(); scope.launch { drawerState.close() } }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(3.dp), colors = ButtonDefaults.buttonColors(containerColor = Ink)) { Text("＋  新建对话") }; Spacer(Modifier.height(26.dp)); Text("对话记录", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) { items(conversations, key = { it.id }) { item -> val active = item.id == activeId; TextButton(onClick = { vm.selectConversation(item.id); scope.launch { drawerState.close() } }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), colors = ButtonDefaults.textButtonColors(contentColor = Ink)) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(6.dp).background(if (active) Signal else Rule)); Spacer(Modifier.width(10.dp)); Text(item.title, maxLines = 1, modifier = Modifier.weight(1f), fontWeight = if (active) FontWeight.Bold else FontWeight.Normal) } } } }
            Spacer(Modifier.weight(1f)); Text("本地保存 · ${conversations.size} 个对话", color = Muted, fontSize = 12.sp)
        } } }) {
            Scaffold(topBar = { TopAppBar(title = { Column { Text(activeTitle, fontSize = 17.sp, fontWeight = FontWeight.Bold); Text("PRIVATE CHAT", color = Muted, fontSize = 10.sp, letterSpacing = 1.5.sp) } }, navigationIcon = { IconButton(onClick = { scope.launch { drawerState.open() } }) { Text("☰", fontSize = 22.sp) } }, actions = { TextButton(onClick = { showSettings = true }) { Text("设置", color = Ink) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper)) }, bottomBar = { InputBar(vm, onPick = { picker.launch(arrayOf("*/*")) }) }) { padding ->
                Column(Modifier.fillMaxSize().background(Paper).padding(padding)) { Divider(color = Rule); if (messages.isEmpty()) EmptyState(); LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(vertical = 24.dp)) { items(messages, key = { it.id }) { item -> MessageRow(item, if (item.status == "streaming") liveText else null) } }; waitStatus?.let { Text(it, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) }; vm.attachment?.let { AssistChip(onClick = { vm.attachment = null }, label = { Text("附件  ${it.name}  ×") }, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) }; vm.error?.let { Text(it, color = Signal, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) } }
            }
            if (showSettings) SettingsDialog(onDismiss = { showSettings = false })
        }
    }
}

@Composable private fun EmptyState() { Column(Modifier.fillMaxWidth().padding(top = 70.dp, start = 24.dp, end = 24.dp)) { Text("开始一段新的对话", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink); Spacer(Modifier.height(12.dp)); Text("你的聊天记录只保存在这台设备上。", color = Muted, fontSize = 15.sp) } }
@Composable private fun MessageRow(message: Message, liveContent: String? = null) { val user = message.role == "user"; val messageColor = if (user) Color.White else Ink; Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) { if (!user) Box(Modifier.width(3.dp).heightIn(min = 28.dp).background(Signal)); Surface(color = if (user) Ink else Color.Transparent, contentColor = messageColor, shape = RoundedCornerShape(if (user) 4.dp else 0.dp), modifier = Modifier.widthIn(max = 620.dp).padding(horizontal = if (user) 0.dp else 14.dp)) { Column(Modifier.padding(horizontal = if (user) 15.dp else 0.dp, vertical = 10.dp)) { if (message.attachmentName != null) Text("附件  ${message.attachmentName}", color = if (user) Color.White else Signal, fontSize = 12.sp, fontWeight = FontWeight.Bold); MarkdownContent(liveContent ?: message.content.ifBlank { "…" }, messageColor) } } } }
@Composable private fun MarkdownContent(markdown: String, textColor: Color) { val androidColor = android.graphics.Color.argb((textColor.alpha * 255).toInt(), (textColor.red * 255).toInt(), (textColor.green * 255).toInt(), (textColor.blue * 255).toInt()); AndroidView(factory = { context -> TextView(context).apply { setTextIsSelectable(true); textSize = 16f; setTextColor(androidColor) } }, update = { view -> view.setTextColor(androidColor); io.noties.markwon.Markwon.create(view.context).setMarkdown(view, markdown) }, modifier = Modifier.fillMaxWidth()) }
@Composable private fun SettingsDialog(onDismiss: () -> Unit) { val context = androidx.compose.ui.platform.LocalContext.current; val prefs = remember { context.getSharedPreferences("settings", 0) }; var url by remember { mutableStateOf(prefs.getString("baseUrl", "") ?: "") }; var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }; var model by remember { mutableStateOf(prefs.getString("model", "gpt-5.5") ?: "gpt-5.5") }; AlertDialog(onDismissRequest = onDismiss, title = { Text("连接设置") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Custom API · Responses", color = Muted, fontSize = 13.sp); OutlinedTextField(url, { url = it }, label = { Text("API 地址") }, singleLine = true); OutlinedTextField(token, { token = it }, label = { Text("访问令牌") }, singleLine = true); OutlinedTextField(model, { model = it }, label = { Text("模型") }, singleLine = true) } }, confirmButton = { Button(onClick = { prefs.edit().putString("baseUrl", url).putString("token", token).putString("model", model).apply(); onDismiss() }, colors = ButtonDefaults.buttonColors(containerColor = Ink)) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }) }
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun InputBar(vm: ChatViewModel, onPick: () -> Unit) { Surface(color = Paper) { Column { Divider(color = Rule); Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.Bottom) { IconButton(onClick = onPick) { Text("＋", color = Muted, fontSize = 24.sp) }; TextField(value = vm.input, onValueChange = { vm.input = it }, modifier = Modifier.weight(1f), placeholder = { Text("写下你的问题…", color = Muted) }, maxLines = 5, colors = TextFieldDefaults.textFieldColors(containerColor = Color.White, focusedIndicatorColor = Ink, unfocusedIndicatorColor = Rule)); Spacer(Modifier.width(8.dp)); Button(onClick = { if (vm.input.isNotBlank()) vm.send() else vm.stop() }, shape = RoundedCornerShape(3.dp), colors = ButtonDefaults.buttonColors(containerColor = Ink)) { Text(if (vm.input.isBlank()) "停" else "发送") } } } } }

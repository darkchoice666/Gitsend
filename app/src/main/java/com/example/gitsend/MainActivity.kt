package com.example.gitsend

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private val files = mutableStateListOf<Uri>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    App(files)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    @Suppress("DEPRECATION")
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri: Uri? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                if (uri != null) {
                    files.clear()
                    files.add(uri)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list: List<Uri>? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                if (list != null) {
                    files.clear()
                    files.addAll(list)
                }
            }
        }
    }
}

fun fileName(ctx: Context, uri: Uri): String {
    var name: String? = null
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) name = c.getString(i)
    }
    return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
}

@Composable
fun App(files: SnapshotStateList<Uri>) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("gitsend", Context.MODE_PRIVATE) }
    val main = remember { Handler(Looper.getMainLooper()) }

    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    var repo by remember { mutableStateOf(prefs.getString("lastRepo", "") ?: "") }
    var branch by remember {
        mutableStateOf(prefs.getString("branch:" + (prefs.getString("lastRepo", "") ?: ""), "main") ?: "main")
    }
    var folder by remember {
        mutableStateOf(prefs.getString("folder:" + (prefs.getString("lastRepo", "") ?: ""), "") ?: "")
    }
    var message by remember { mutableStateOf("") }
    var savedRepos by remember {
        mutableStateOf((prefs.getStringSet("repos", emptySet()) ?: emptySet()).toList().sorted())
    }
    var newName by remember { mutableStateOf("") }
    var newPrivate by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    val log = remember { mutableStateListOf<String>() }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            files.clear()
            files.addAll(uris)
        }
    }

    fun send() {
        val r = repo.trim()
        val b = branch.trim().ifEmpty { "main" }
        val f = folder.trim().trim('/')
        val t = token.trim()
        val msg = message.trim()

        val newRepos = (savedRepos + r).distinct().sorted()
        prefs.edit()
            .putString("token", t)
            .putString("lastRepo", r)
            .putString("branch:$r", b)
            .putString("folder:$r", f)
            .putStringSet("repos", newRepos.toSet())
            .apply()
        savedRepos = newRepos

        busy = true
        log.clear()
        val list = files.toList()

        Thread {
            for (u in list) {
                val name = fileName(ctx, u)
                val path = if (f.isEmpty()) name else "$f/$name"
                try {
                    val bytes = ctx.contentResolver.openInputStream(u)!!.use { it.readBytes() }
                    if (bytes.size > 50 * 1024 * 1024) throw Exception("файл больше 50 МБ")
                    val res = GitHubApi.upload(
                        t, r, b, path, bytes,
                        if (msg.isEmpty()) "Add $name" else msg
                    )
                    main.post { log.add("✓ $path — $res") }
                } catch (e: Exception) {
                    main.post { log.add("✗ $name — ${e.message}") }
                }
            }
            main.post { busy = false }
        }.start()
    }

    fun createRepo() {
        val n = newName.trim()
        val t = token.trim()
        val priv = newPrivate
        busy = true
        log.clear()
        Thread {
            try {
                val res = GitHubApi.createRepo(t, n, priv)
                main.post {
                    repo = res.first
                    branch = res.second
                    folder = ""
                    val updated = (savedRepos + res.first).distinct().sorted()
                    savedRepos = updated
                    prefs.edit()
                        .putString("token", t)
                        .putString("lastRepo", res.first)
                        .putString("branch:" + res.first, res.second)
                        .putStringSet("repos", updated.toSet())
                        .apply()
                    newName = ""
                    log.add("✓ репозиторий " + res.first + " создан и выбран")
                }
            } catch (e: Exception) {
                main.post { log.add("✗ " + e.message) }
            }
            main.post { busy = false }
        }.start()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("GitSend", style = MaterialTheme.typography.headlineMedium)

        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Токен GitHub") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = repo,
            onValueChange = { repo = it },
            label = { Text("Репозиторий (владелец/название)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (savedRepos.isNotEmpty()) {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                for (s in savedRepos) {
                    TextButton(onClick = {
                        repo = s
                        branch = prefs.getString("branch:$s", "main") ?: "main"
                        folder = prefs.getString("folder:$s", "") ?: ""
                    }) { Text(s) }
                }
            }
        }

        Text("Новый репозиторий", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = newName,
            onValueChange = { newName = it },
            label = { Text("Название нового репозитория") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = newPrivate, onCheckedChange = { newPrivate = it })
            Text(if (newPrivate) "  Приватный" else "  Публичный")
        }
        Button(
            onClick = { createRepo() },
            enabled = token.isNotBlank() && newName.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Создать репозиторий") }

        OutlinedTextField(
            value = branch,
            onValueChange = { branch = it },
            label = { Text("Ветка") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = folder,
            onValueChange = { folder = it },
            label = { Text("Папка в репозитории (пусто = корень)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = message,
            onValueChange = { message = it },
            label = { Text("Сообщение коммита (необязательно)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = { picker.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Выбрать файлы") }

        if (files.isNotEmpty()) {
            Text("Выбрано файлов: ${files.size}")
            for (u in files) {
                Text("• " + fileName(ctx, u), style = MaterialTheme.typography.bodySmall)
            }
        }

        val ready = token.isNotBlank() && repo.contains("/") && files.isNotEmpty() && !busy
        Button(
            onClick = { send() },
            enabled = ready,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "Отправляю…" else "Отправить на GitHub") }

        for (line in log) {
            Text(line, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

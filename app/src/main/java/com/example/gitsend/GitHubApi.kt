package com.example.gitsend

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object GitHubApi {

    private fun enc(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun encPath(path: String): String =
        path.split("/").filter { it.isNotEmpty() }.joinToString("/") { enc(it) }

    private fun open(url: String, method: String, token: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20000
        c.readTimeout = 120000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        c.setRequestProperty("User-Agent", "GitSend-Android")
        return c
    }

    private fun readBody(c: HttpURLConnection): String {
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        return stream?.bufferedReader()?.use { it.readText() } ?: ""
    }

    private fun errMsg(body: String): String =
        try {
            val o = JSONObject(body)
            val m = o.optString("message", body)
            val e = o.optJSONArray("errors")
            if (e != null && e.length() > 0) {
                val first = e.optJSONObject(0)?.optString("message") ?: e.optString(0)
                "$m: $first"
            } else m
        } catch (e: Exception) {
            body
        }

    /** Загружает файл в репозиторий. Если файл уже есть, перезаписывает его. */
    fun upload(
        token: String,
        repo: String,
        branch: String,
        path: String,
        bytes: ByteArray,
        message: String
    ): String {
        val base = "https://api.github.com/repos/$repo/contents/${encPath(path)}"

        // 1. Проверяем, есть ли уже такой файл (нужен sha для перезаписи)
        var sha: String? = null
        val get = open("$base?ref=${enc(branch)}", "GET", token)
        val getCode = get.responseCode
        val getBody = readBody(get)
        if (getCode == 200) {
            if (getBody.trimStart().startsWith("[")) {
                throw Exception("по этому пути лежит папка, а не файл")
            }
            sha = JSONObject(getBody).optString("sha").ifEmpty { null }
        } else if (getCode != 404) {
            throw Exception("$getCode ${errMsg(getBody)}")
        }

        // 2. Отправляем файл
        val json = JSONObject()
        json.put("message", message)
        json.put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
        json.put("branch", branch)
        if (sha != null) json.put("sha", sha)

        val put = open(base, "PUT", token)
        put.doOutput = true
        put.setRequestProperty("Content-Type", "application/json")
        put.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
        val code = put.responseCode
        val body = readBody(put)
        if (code !in 200..299) throw Exception("$code ${errMsg(body)}")

        return if (sha != null) "обновлён" else "создан"
    }

    /** Создаёт новый репозиторий. Возвращает (владелец/название, ветка по умолчанию). */
    fun createRepo(token: String, name: String, isPrivate: Boolean): Pair<String, String> {
        val json = JSONObject()
        json.put("name", name)
        json.put("private", isPrivate)
        json.put("auto_init", true)

        val c = open("https://api.github.com/user/repos", "POST", token)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val body = readBody(c)
        if (code !in 200..299) throw Exception("$code ${errMsg(body)}")

        val o = JSONObject(body)
        return Pair(o.getString("full_name"), o.optString("default_branch", "main"))
    }
}

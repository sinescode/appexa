package com.turjaun.instacheck

import android.content.Context
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter

enum class UsernameStatus { ACTIVE, AVAILABLE, ERROR, CANCELLED }

data class UsernameResult(
    val username: String,
    var status: UsernameStatus,
    var message: String? = null
)

data class Stats(
    var activeCount: Int = 0,
    var availableCount: Int = 0,
    var errorCount: Int = 0,
    var cancelledCount: Int = 0
)

class MainViewModel {

    // State
    var results = mutableStateListOf<UsernameResult>()
    var stats by mutableStateOf(Stats())
    var processedCount by mutableStateOf(0)
    var totalCount by mutableStateOf(0)
    var progress by mutableStateOf(0f)

    private var active = true
    private val maxRetries = 10
    private val initialDelay = 1000L
    private val maxConcurrent = 5
    private var job: Job? = null

    private val savedAccounts = mutableListOf<JSONObject>()
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .build()

    // Instagram headers
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/115.0 Safari/537.36",
        "x-ig-app-id" to "936619743392459",
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "https://www.instagram.com/",
        "Origin" to "https://www.instagram.com"
    )

    // Use a single CoroutineScope
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun startChecking(usernames: List<String>, filename: String) {
        cancel() // Cancel previous

        results.clear()
        stats = Stats()
        processedCount = 0
        totalCount = usernames.size
        progress = 0f
        active = true
        savedAccounts.clear()

        job = scope.launch {
            val semaphore = Semaphore(maxConcurrent)
            val tasks = usernames.map { username ->
                async { checkUsername(username, semaphore) }
            }
            tasks.awaitAll()
        }
    }

    private suspend fun checkUsername(username: String, semaphore: Semaphore) {
        var retry = 0
        var delayTime = initialDelay
        var status = UsernameStatus.ERROR

        while (retry < maxRetries && active) {
            semaphore.withPermit {
                try {
                    val url = "https://i.instagram.com/api/v1/users/web_profile_info/?username=$username"
                    val requestBuilder = Request.Builder().url(url)
                    headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
                    val request = requestBuilder.build()

                    val response = client.newCall(request).execute()
                    when {
                        response.code == 404 -> status = UsernameStatus.AVAILABLE
                        response.isSuccessful -> {
                            val body = response.body?.string()
                            status = if (body != null && body.contains("\"user\"")) {
                                UsernameStatus.ACTIVE
                            } else {
                                UsernameStatus.AVAILABLE
                            }
                        }
                        else -> {
                            retry++
                            delay(delayTime)
                            delayTime = (delayTime * 2).coerceAtMost(60000L)
                            continue
                        }
                    }

                    val message = when (status) {
                        UsernameStatus.ACTIVE -> "[ACTIVE] $username"
                        UsernameStatus.AVAILABLE -> "[AVAILABLE] $username"
                        UsernameStatus.ERROR -> "[ERROR] $username"
                        UsernameStatus.CANCELLED -> "[CANCELLED] $username"
                    }

                    results.add(UsernameResult(username, status, message))

                    // Update stats
                    when (status) {
                        UsernameStatus.ACTIVE -> {
                            stats = stats.copy(activeCount = stats.activeCount + 1)
                            val obj = JSONObject()
                            obj.put("username", username)
                            savedAccounts.add(obj)
                        }
                        UsernameStatus.AVAILABLE -> stats = stats.copy(availableCount = stats.availableCount + 1)
                        UsernameStatus.ERROR -> stats = stats.copy(errorCount = stats.errorCount + 1)
                        UsernameStatus.CANCELLED -> stats = stats.copy(cancelledCount = stats.cancelledCount + 1)
                    }

                    processedCount++
                    progress = if (totalCount > 0) processedCount.toFloat() / totalCount else 0f
                    return
                } catch (e: Exception) {
                    retry++
                    delay(delayTime)
                    delayTime = (delayTime * 2).coerceAtMost(60000L)
                }
            }
        }

        if (!active) {
            results.add(UsernameResult(username, UsernameStatus.CANCELLED, "[CANCELLED] $username"))
            stats = stats.copy(cancelledCount = stats.cancelledCount + 1)
            processedCount++
            progress = if (totalCount > 0) processedCount.toFloat() / totalCount else 0f
        }
    }

    fun cancel() {
        active = false
        job?.cancel()
    }

    fun saveResults(context: Context, filename: String) {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Insta_Saver")
        if (!dir.exists()) dir.mkdirs()

        val file = File(dir, "final_${filename}.json")
        try {
            val jsonArray = JSONArray()
            for (obj in savedAccounts) jsonArray.put(obj)
            FileWriter(file).use { it.write(jsonArray.toString(2)) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

package com.turjaun.instacheck

import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.random.Random

object InstagramApi {
    private val client = OkHttpClient()

    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0 Safari/537.36"
    private const val APP_ID = "936619743392459"

    private const val MAX_RETRIES = 10
    private const val INITIAL_DELAY = 1000L // ms
    private const val MAX_DELAY = 60000L

    suspend fun checkUsername(username: String, sessionActive: () -> Boolean): UsernameCheck {
        var retries = 0
        var delayTime = INITIAL_DELAY

        while (retries < MAX_RETRIES) {
            if (!sessionActive()) return UsernameCheck(username, UsernameStatus.CANCELLED, "Cancelled")
            try {
                val request = Request.Builder()
                    .url("https://i.instagram.com/api/v1/users/web_profile_info/?username=$username")
                    .header("User-Agent", USER_AGENT)
                    .header("x-ig-app-id", APP_ID)
                    .build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""
                when (response.code) {
                    200 -> {
                        val json = JSONObject(body)
                        val user = json.optJSONObject("data")?.optJSONObject("user")
                        return if (user != null) UsernameCheck(username, UsernameStatus.ACTIVE, "[ACTIVE] $username")
                        else UsernameCheck(username, UsernameStatus.AVAILABLE, "[AVAILABLE] $username")
                    }
                    404 -> return UsernameCheck(username, UsernameStatus.AVAILABLE, "[AVAILABLE] $username")
                    else -> { /* retry */ }
                }
            } catch (e: Exception) {
                // ignore, will retry
            }
            retries++
            delayTime = (delayTime * 2 + Random.nextLong(1000)).coerceAtMost(MAX_DELAY)
            delay(delayTime)
        }
        return UsernameCheck(username, UsernameStatus.ERROR, "[ERROR] $username")
    }
}

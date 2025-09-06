package com.turjaun.instacheck

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.turjaun.instacheck.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withPermit
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Random
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var fileUri: Uri? = null
    private val resultsAdapter = ResultsAdapter()
    private var usernames: List<String> = emptyList()
    private var accountData: MutableMap<String, JSONObject> = mutableMapOf()
    private var activeAccounts: MutableList<JSONObject> = mutableListOf()
    private var processedCount = 0
    private var activeCount = 0
    private var availableCount = 0
    private var errorCount = 0
    private var cancelledCount = 0

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0 Safari/537.36",
        "x-ig-app-id" to "936619743392459",
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "https://www.instagram.com/",
        "Origin" to "https://www.instagram.com",
        "Sec-Fetch-Site" to "same-origin"
    )

    private val MAX_RETRIES = 10
    private val INITIAL_DELAY = 1L * 1000
    private val MAX_DELAY = 60L * 1000
    private val CONCURRENT_LIMIT = 5

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            fileUri = it
            binding.pickFileButton.text = it.path?.split("/")?.last() ?: "File selected"
        }
    }

    private val saveFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri?.let { saveJsonToFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.resultsRecycler.layoutManager = LinearLayoutManager(this)
        binding.resultsRecycler.adapter = resultsAdapter

        binding.btnFile.setOnClickListener { switchTab(true) }
        binding.btnText.setOnClickListener { switchTab(false) }

        binding.pickFileButton.setOnClickListener {
            pickFileLauncher.launch(arrayOf("*/*")) // Accept all, but filter in code
        }

        binding.startFileButton.setOnClickListener { startProcessingFromFile() }
        binding.startTextButton.setOnClickListener { startProcessingFromText() }

        binding.cancelButton.setOnClickListener { cancelProcessing() }
        binding.downloadButton.setOnClickListener { downloadResults() }

        switchTab(true) // Default to file tab
    }

    private fun switchTab(isFile: Boolean) {
        if (isFile) {
            binding.fileSection.visibility = View.VISIBLE
            binding.textSection.visibility = View.GONE
            binding.btnFile.backgroundTintList = ContextCompat.getColorStateList(this, R.color.indigo_50)
            binding.btnFile.setTextColor(ContextCompat.getColor(this, R.color.indigo_700))
            binding.btnText.backgroundTintList = ContextCompat.getColorStateList(this, R.color.gray_100)
            binding.btnText.setTextColor(ContextCompat.getColor(this, R.color.gray_700))
        } else {
            binding.fileSection.visibility = View.GONE
            binding.textSection.visibility = View.VISIBLE
            binding.btnText.backgroundTintList = ContextCompat.getColorStateList(this, R.color.indigo_50)
            binding.btnText.setTextColor(ContextCompat.getColor(this, R.color.indigo_700))
            binding.btnFile.backgroundTintList = ContextCompat.getColorStateList(this, R.color.gray_100)
            binding.btnFile.setTextColor(ContextCompat.getColor(this, R.color.gray_700))
        }
    }

    private fun startProcessingFromFile() {
        if (fileUri == null) {
            Toast.makeText(this, "Please select a file", Toast.LENGTH_SHORT).show()
            return
        }
        val extension = contentResolver.getType(fileUri!!)?.let { if (it.contains("json")) "json" else if (it.contains("text")) "txt" else null }
        if (extension !in listOf("json", "txt")) {
            Toast.makeText(this, "File must be .json or .txt", Toast.LENGTH_SHORT).show()
            return
        }
        loadUsernamesFromFile(fileUri!!, extension!!)
        startProcessing()
    }

    private fun startProcessingFromText() {
        val text = binding.usernameInput.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, "Please enter usernames", Toast.LENGTH_SHORT).show()
            return
        }
        usernames = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        usernames.forEach { accountData[it] = JSONObject().put("username", it) }
        startProcessing()
    }

    private fun loadUsernamesFromFile(uri: Uri, type: String) {
        contentResolver.openInputStream(uri)?.use { input ->
            val reader = BufferedReader(InputStreamReader(input))
            if (type == "txt") {
                usernames = reader.readLines().map { it.trim() }.filter { it.isNotEmpty() }
                usernames.forEach { accountData[it] = JSONObject().put("username", it) }
            } else { // json
                val jsonStr = reader.readText()
                try {
                    val array = JSONArray(jsonStr)
                    usernames = (0 until array.length()).map { array.getJSONObject(it).getString("username") }
                    usernames.forEachIndexed { i, u -> accountData[u] = array.getJSONObject(i) }
                } catch (e: JSONException) {
                    Toast.makeText(this, "Invalid JSON", Toast.LENGTH_SHORT).show()
                    return
                }
            }
        }
    }

    private fun startProcessing() {
        if (usernames.isEmpty()) {
            Toast.makeText(this, "No usernames found", Toast.LENGTH_SHORT).show()
            return
        }
        resetStats()
        binding.progressSection.visibility = View.VISIBLE
        binding.fileSection.visibility = View.GONE
        binding.textSection.visibility = View.GONE
        binding.downloadButton.visibility = View.GONE
        binding.cancelButton.visibility = View.VISIBLE
        updateStats()
        resultsAdapter.clear()

        job = scope.launch {
            val semaphore = Semaphore(CONCURRENT_LIMIT)
            val client = OkHttpClient()
            val deferreds = usernames.map { username ->
                async {
                    semaphore.withPermit {
                        checkUsername(client, username)
                    }
                }
            }
            deferreds.awaitAll()
            withContext(Dispatchers.Main) {
                binding.downloadButton.visibility = View.VISIBLE
                binding.cancelButton.visibility = View.GONE
            }
        }
    }

    private suspend fun checkUsername(client: OkHttpClient, username: String): Pair<String, String> {
        val url = "https://i.instagram.com/api/v1/users/web_profile_info/?username=$username"
        var retryCount = 0
        var delayMs = INITIAL_DELAY

        while (retryCount < MAX_RETRIES) {
            if (job?.isCancelled == true) return "CANCELLED" to "Cancelled: $username"

            try {
                val request = Request.Builder().url(url).headers(Headers.Builder().apply { headers.forEach { add(it.key, it.value) } }.build()).build()
                val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
                val code = response.code
                if (code == 404) {
                    val result = "[AVAILABLE] $username"
                    updateResult("AVAILABLE", result, username)
                    return "AVAILABLE" to result
                } else if (code == 200) {
                    val body = response.body?.string()
                    val json = JSONObject(body)
                    val status = if (json.optJSONObject("data")?.optJSONObject("user") != null) "ACTIVE" else "AVAILABLE"
                    val result = "[$status] $username"
                    updateResult(status, result, username)
                    return status to result
                } else {
                    retryCount++
                    val statusMsg = "[RETRY $retryCount/$MAX_RETRIES] $username - Status: $code, Waiting: ${delayMs / 1000}s"
                    updateStatus(statusMsg, username)
                }
            } catch (e: Exception) {
                retryCount++
                val statusMsg = "[RETRY $retryCount/$MAX_RETRIES] $username - Exception: ${e.message}, Waiting: ${delayMs / 1000}s"
                updateStatus(statusMsg, username)
            }
            delay(delayMs)
            delayMs = min(MAX_DELAY, delayMs * 2 + Random().nextInt(1000).toLong())
        }
        val result = "[ERROR] $username - Max retries exceeded"
        updateResult("ERROR", result, username)
        return "ERROR" to result
    }

    private suspend fun updateResult(status: String, message: String, username: String) {
        withContext(Dispatchers.Main) {
            processedCount++
            when (status) {
                "ACTIVE" -> {
                    activeCount++
                    accountData[username]?.let { activeAccounts.add(it) }
                }
                "AVAILABLE" -> availableCount++
                "ERROR" -> errorCount++
                "CANCELLED" -> cancelledCount++
            }
            resultsAdapter.addItem(ResultItem(status, message))
            updateProgress()
            updateStats()
        }
    }

    private suspend fun updateStatus(message: String, username: String) {
        withContext(Dispatchers.Main) {
            resultsAdapter.addItem(ResultItem("INFO", message))
        }
    }

    private fun updateProgress() {
        val percentage = if (usernames.size > 0) (processedCount * 100 / usernames.size) else 0
        binding.progressBar.progress = percentage
        binding.progressText.text = "Processed: $processedCount/${usernames.size} ($percentage%)"
    }

    private fun updateStats() {
        binding.activeCount.text = activeCount.toString()
        binding.availableCount.text = availableCount.toString()
        binding.errorCount.text = errorCount.toString()
        binding.totalCount.text = usernames.size.toString()
    }

    private fun resetStats() {
        processedCount = 0
        activeCount = 0
        availableCount = 0
        errorCount = 0
        cancelledCount = 0
        activeAccounts.clear()
        updateProgress()
        updateStats()
    }

    private fun cancelProcessing() {
        job?.cancel()
        scope.launch {
            updateStatus("Processing cancelled by user", "")
        }
        binding.cancelButton.visibility = View.GONE
        binding.downloadButton.visibility = View.GONE
    }

    private fun downloadResults() {
        saveFileLauncher.launch("active_accounts.json")
    }

    private fun saveJsonToFile(uri: Uri) {
        val jsonArray = JSONArray(activeAccounts)
        contentResolver.openOutputStream(uri)?.use { output ->
            output.write(jsonArray.toString(2).toByteArray())
        }
        Toast.makeText(this, "File saved", Toast.LENGTH_SHORT).show()
    }
}

data class ResultItem(val status: String, val message: String)

class ResultsAdapter : androidx.recyclerview.widget.RecyclerView.Adapter<ResultsAdapter.ViewHolder>() {
    private val items = mutableListOf<ResultItem>()

    class ViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.icon)
        val message: TextView = view.findViewById(R.id.message)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.result_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.message.text = item.message
        when (item.status) {
            "ACTIVE" -> {
                holder.icon.setImageResource(android.R.drawable.ic_menu_recent_history) // Replace with fa-user-check equivalent
                holder.itemView.setBackgroundColor(Color.parseColor("#FEE2E2"))
                holder.message.setTextColor(Color.parseColor("#DC2626"))
            }
            "AVAILABLE" -> {
                holder.icon.setImageResource(android.R.drawable.ic_menu_add)
                holder.itemView.setBackgroundColor(Color.parseColor("#DCFCE7"))
                holder.message.setTextColor(Color.parseColor("#15803D"))
            }
            "ERROR" -> {
                holder.icon.setImageResource(android.R.drawable.ic_dialog_alert)
                holder.itemView.setBackgroundColor(Color.parseColor("#FEF9C3"))
                holder.message.setTextColor(Color.parseColor("#A16207"))
            }
            else -> {
                holder.icon.setImageResource(android.R.drawable.ic_dialog_info)
                holder.itemView.setBackgroundColor(Color.parseColor("#F3F4F6"))
                holder.message.setTextColor(Color.parseColor("#6B7280"))
            }
        }
    }

    override fun getItemCount() = items.size

    fun addItem(item: ResultItem) {
        items.add(0, item) // Prepend like JS
        notifyItemInserted(0)
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }
}
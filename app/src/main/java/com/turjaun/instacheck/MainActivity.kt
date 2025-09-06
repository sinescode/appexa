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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
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
import java.text.SimpleDateFormat
import java.util.*

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
    private var originalFileName = ""

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
            // Extract filename from URI
            val fileName = it.path?.split("/")?.last() ?: "selected_file"
            originalFileName = fileName.substringBeforeLast(".")
            binding.pickFileButton.text = fileName
            binding.pickFileButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_check_circle, 0, 0, 0)
            binding.pickFileButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.green_50)
            binding.pickFileButton.setTextColor(ContextCompat.getColor(this, R.color.green_700))
        }
    }

    private val saveFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri?.let { saveJsonToFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupClickListeners()
        switchTab(true) // Default to file tab
    }

    private fun setupRecyclerView() {
        binding.resultsRecycler.layoutManager = LinearLayoutManager(this)
        binding.resultsRecycler.adapter = resultsAdapter
    }

    private fun setupClickListeners() {
        binding.btnFile.setOnClickListener { switchTab(true) }
        binding.btnText.setOnClickListener { switchTab(false) }

        binding.pickFileButton.setOnClickListener {
            pickFileLauncher.launch(arrayOf("*/*"))
        }

        binding.startFileButton.setOnClickListener { startProcessingFromFile() }
        binding.startTextButton.setOnClickListener { startProcessingFromText() }
        binding.cancelButton.setOnClickListener { cancelProcessing() }
        binding.downloadButton.setOnClickListener { downloadResults() }
    }

    private fun switchTab(isFile: Boolean) {
        if (isFile) {
            binding.fileSection.visibility = View.VISIBLE
            binding.textSection.visibility = View.GONE
            binding.btnFile.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary_light)
            binding.btnFile.setTextColor(ContextCompat.getColor(this, R.color.primary_dark))
            binding.btnText.backgroundTintList = ContextCompat.getColorStateList(this, R.color.gray_100)
            binding.btnText.setTextColor(ContextCompat.getColor(this, R.color.gray_600))
        } else {
            binding.fileSection.visibility = View.GONE
            binding.textSection.visibility = View.VISIBLE
            binding.btnText.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary_light)
            binding.btnText.setTextColor(ContextCompat.getColor(this, R.color.primary_dark))
            binding.btnFile.backgroundTintList = ContextCompat.getColorStateList(this, R.color.gray_100)
            binding.btnFile.setTextColor(ContextCompat.getColor(this, R.color.gray_600))
        }
    }

    private fun startProcessingFromFile() {
        if (fileUri == null) {
            showError("Please select a file first")
            return
        }
        val extension = contentResolver.getType(fileUri!!)?.let { 
            if (it.contains("json")) "json" else if (it.contains("text")) "txt" else null 
        }
        if (extension !in listOf("json", "txt")) {
            showError("File must be .json or .txt format")
            return
        }
        loadUsernamesFromFile(fileUri!!, extension!!)
        startProcessing()
    }

    private fun startProcessingFromText() {
        val text = binding.usernameInput.text.toString().trim()
        if (text.isEmpty()) {
            showError("Please enter at least one username")
            return
        }
        usernames = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        usernames.forEach { accountData[it] = JSONObject().put("username", it) }
        originalFileName = "manual_input"
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
                    showError("Invalid JSON format")
                    return
                }
            }
        }
    }

    private fun startProcessing() {
        if (usernames.isEmpty()) {
            showError("No valid usernames found")
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
                    semaphore.acquire()
                    try {
                        checkUsername(client, username)
                    } finally {
                        semaphore.release()
                    }
                }
            }
            deferreds.awaitAll()
            withContext(Dispatchers.Main) {
                binding.downloadButton.visibility = if (activeAccounts.isNotEmpty()) View.VISIBLE else View.GONE
                binding.cancelButton.visibility = View.GONE
                showSuccess("Processing completed! Found ${activeAccounts.size} active accounts.")
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
                val request = Request.Builder()
                    .url(url)
                    .headers(Headers.Builder().apply { 
                        headers.forEach { add(it.key, it.value) } 
                    }.build())
                    .build()
                
                val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
                val code = response.code
                
                if (code == 404) {
                    val result = " $username - Available"
                    updateResult("AVAILABLE", result, username)
                    return "AVAILABLE" to result
                } else if (code == 200) {
                    val body = response.body?.string()
                    val json = JSONObject(body)
                    val status = if (json.optJSONObject("data")?.optJSONObject("user") != null) "ACTIVE" else "AVAILABLE"
                    val result = if (status == "ACTIVE") " $username - Active" else " $username - Available"
                    updateResult(status, result, username)
                    return status to result
                } else {
                    retryCount++
                    val statusMsg = " Retry $retryCount/$MAX_RETRIES for $username (Status: $code)"
                    updateStatus(statusMsg, username)
                }
            } catch (e: Exception) {
                retryCount++
                val statusMsg = " Retry $retryCount/$MAX_RETRIES for $username (${e.message?.take(30)}...)"
                updateStatus(statusMsg, username)
            }
            delay(delayMs)
            delayMs = min(MAX_DELAY, delayMs * 2 + Random().nextInt(1000).toLong())
        }
        val result = " $username - Error (Max retries exceeded)"
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
        binding.progressText.text = "Progress: $processedCount/${usernames.size} ($percentage%)"
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
            updateStatus("️ Processing cancelled by user", "")
        }
        binding.cancelButton.visibility = View.GONE
        binding.downloadButton.visibility = if (activeAccounts.isNotEmpty()) View.VISIBLE else View.GONE
        showInfo("Processing cancelled")
    }

    private fun downloadResults() {
        if (activeAccounts.isEmpty()) {
            showError("No active accounts to download")
            return
        }
        
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "final_${originalFileName}_${timestamp}.json"
        saveFileLauncher.launch(fileName)
    }

    private fun saveJsonToFile(uri: Uri) {
        try {
            val jsonArray = JSONArray(activeAccounts)
            contentResolver.openOutputStream(uri)?.use { output ->
                output.write(jsonArray.toString(2).toByteArray())
            }
            showSuccess("Results saved successfully! (${activeAccounts.size} active accounts)")
        } catch (e: Exception) {
            showError("Failed to save file: ${e.message}")
        }
    }

    private fun showSuccess(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showInfo(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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
        
        // Get the status indicator view
        val statusIndicator = holder.itemView.findViewById<View>(R.id.status_indicator)
        
        when (item.status) {
            "ACTIVE" -> {
                holder.icon.setImageResource(android.R.drawable.presence_busy)
                holder.itemView.setBackgroundColor(Color.parseColor("#FECACA"))
                holder.message.setTextColor(Color.parseColor("#DC2626"))
                statusIndicator?.setBackgroundColor(Color.parseColor("#DC2626"))
            }
            "AVAILABLE" -> {
                holder.icon.setImageResource(android.R.drawable.presence_online)
                holder.itemView.setBackgroundColor(Color.parseColor("#D1FAE5"))
                holder.message.setTextColor(Color.parseColor("#059669"))
                statusIndicator?.setBackgroundColor(Color.parseColor("#059669"))
            }
            "ERROR" -> {
                holder.icon.setImageResource(android.R.drawable.ic_dialog_alert)
                holder.itemView.setBackgroundColor(Color.parseColor("#FEF3C7"))
                holder.message.setTextColor(Color.parseColor("#D97706"))
                statusIndicator?.setBackgroundColor(Color.parseColor("#D97706"))
            }
            else -> {
                holder.icon.setImageResource(android.R.drawable.ic_dialog_info)
                holder.itemView.setBackgroundColor(Color.parseColor("#F9FAFB"))
                holder.message.setTextColor(Color.parseColor("#6B7280"))
                statusIndicator?.setBackgroundColor(Color.parseColor("#6B7280"))
            }
        }
    }

    override fun getItemCount() = items.size

    fun addItem(item: ResultItem) {
        items.add(0, item)
        notifyItemInserted(0)
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }
}
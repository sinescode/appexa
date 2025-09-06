package com.turjaun.instacheck

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
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
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Cell

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var fileUri: Uri? = null // For input file (JSON/TXT) for processing
    private val resultsAdapter = ResultsAdapter()
    private var usernames: List<String> = emptyList()
    private var accountData: MutableMap<String, JSONObject> = mutableMapOf()
    private var activeAccounts: MutableList<JSONObject> = mutableListOf()
    private var processedCount = 0
    private var activeCount = 0
    private var availableCount = 0
    private var errorCount = 0
    private var cancelledCount = 0
    private var originalFileName = "" // Name of the file selected for processing

    // Converter variables
    private var jsonFileUri: Uri? = null // URI of the JSON file to be converted to Excel
    private var jsonFileName: String = "" // Name of the JSON file selected for conversion

    // Instagram API headers
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0 Safari/537.36",
        "x-ig-app-id" to "936619743392459",
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "https://www.instagram.com/",
        "Origin" to "https://www.instagram.com",
        "Sec-Fetch-Site" to "same-origin"
    )

    // Constants for processing
    private val MAX_RETRIES = 10
    private val INITIAL_DELAY = 1L * 1000
    private val MAX_DELAY = 60L * 1000
    private val CONCURRENT_LIMIT = 5 // Maximum concurrent API requests

    // ActivityResultLaunchers for file operations
    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            fileUri = it
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

    private val pickJsonFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            jsonFileUri = it
            jsonFileName = it.path?.split("/")?.last() ?: "selected_file.json"
            binding.selectedJsonFile.text = jsonFileName
            binding.convertButton.isEnabled = true // Enable convert button once a JSON is selected
            binding.pickJsonButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_check_circle, 0, 0, 0)
            binding.pickJsonButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.green_50)
            binding.pickJsonButton.setTextColor(ContextCompat.getColor(this, R.color.green_700))
        }
    }

    private val saveExcelLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri: Uri? ->
        uri?.let { convertJsonToExcel(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupRecyclerView()
        setupClickListeners()
        switchTab(0) // Start with the file processing tab

        // Global exception handler for uncaught exceptions
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("UncaughtException", "Uncaught exception in thread ${thread.name}", throwable)
            throwable.printStackTrace()
            // Optionally show a user-facing error message here if appropriate
        }
    }

    private fun setupRecyclerView() {
        binding.resultsRecycler.layoutManager = LinearLayoutManager(this)
        binding.resultsRecycler.adapter = resultsAdapter
    }

    private fun setupClickListeners() {
        binding.btnFile.setOnClickListener { switchTab(0) }
        binding.btnText.setOnClickListener { switchTab(1) }
        binding.btnConverter.setOnClickListener { switchTab(2) }

        binding.pickFileButton.setOnClickListener {
            pickFileLauncher.launch(arrayOf("*/*")) // Allow all file types initially
        }

        binding.pickJsonButton.setOnClickListener {
            pickJsonFileLauncher.launch(arrayOf("application/json")) // Filter for JSON files
        }

        binding.startFileButton.setOnClickListener { startProcessingFromFile() }
        binding.startTextButton.setOnClickListener { startProcessingFromText() }
        binding.convertButton.setOnClickListener {
            // Generate a timestamped filename for the Excel file
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val baseName = jsonFileName.substringBeforeLast(".", "input") // Handle cases where there's no extension
            val fileName = "${baseName}_$timestamp.xlsx"
            saveExcelLauncher.launch(fileName)
        }
        binding.cancelButton.setOnClickListener { cancelProcessing() }
        binding.downloadButton.setOnClickListener { downloadResults() }
    }

    private fun switchTab(tabIndex: Int) {
        // Hide all content sections
        binding.fileSection.visibility = View.GONE
        binding.textSection.visibility = View.GONE
        binding.converterSection.visibility = View.GONE

        // Reset button styles
        binding.btnFile.backgroundTintList = ContextCompat.getColorStateList(this, R.color.gray_100)
        binding.btnFile.setTextColor(ContextCompat.getColor(this, R.color.gray_600))
        binding.btnText.backgroundTintList = ContextCompat.getColorStateList(this, R.color.gray_100)
        binding.btnText.setTextColor(ContextCompat.getColor(this, R.color.gray_600))
        binding.btnConverter.backgroundTintList = ContextCompat.getColorStateList(this, R.color.gray_100)
        binding.btnConverter.setTextColor(ContextCompat.getColor(this, R.color.gray_600))

        // Show the selected tab and update its button style
        when (tabIndex) {
            0 -> {
                binding.fileSection.visibility = View.VISIBLE
                binding.btnFile.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary_light)
                binding.btnFile.setTextColor(ContextCompat.getColor(this, R.color.primary_dark))
            }
            1 -> {
                binding.textSection.visibility = View.VISIBLE
                binding.btnText.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary_light)
                binding.btnText.setTextColor(ContextCompat.getColor(this, R.color.primary_dark))
            }
            2 -> {
                binding.converterSection.visibility = View.VISIBLE
                binding.btnConverter.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary_light)
                binding.btnConverter.setTextColor(ContextCompat.getColor(this, R.color.primary_dark))
            }
        }
    }

    private fun startProcessingFromFile() {
        if (fileUri == null) {
            showError("Please select a file first")
            return
        }
        val mime = contentResolver.getType(fileUri!!)
        val extension = when {
            mime == null -> null
            mime.contains("json") -> "json"
            mime.contains("text") -> "txt"
            else -> null
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
        // Initialize accountData with basic username for manual input
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
            } else { // Assume JSON
                val jsonStr = reader.readText()
                try {
                    val array = JSONArray(jsonStr)
                    usernames = (0 until array.length()).map {
                        try {
                            array.getJSONObject(it).getString("username")
                        } catch (e: Exception) {
                            Log.w("LoadUsernames", "Could not get username from object at index $it", e)
                            "" // Return empty string if username is missing
                        }
                    }.filter { it.isNotEmpty() } // Filter out any empty usernames

                    // Populate accountData with full JSON objects from the array
                    usernames.forEachIndexed { i, u ->
                        try {
                            // Find the correct JSONObject for username 'u'
                            val matchingObject = (0 until array.length()).firstOrNull {
                                try {
                                    array.getJSONObject(it).getString("username") == u
                                } catch (e: Exception) { false }
                            }?.let { array.getJSONObject(it) }
                            if (matchingObject != null) {
                                accountData[u] = matchingObject
                            } else {
                                // If for some reason the username wasn't found again, create a basic entry
                                accountData[u] = JSONObject().put("username", u)
                            }
                        } catch (e: Exception) {
                            Log.printStackTrace("LoadUsernames", "Error processing JSON object for username $u", e)
                            accountData[u] = JSONObject().put("username", u) // Fallback
                        }
                    }
                } catch (e: JSONException) {
                    showError("Invalid JSON format: ${e.message}")
                    usernames = emptyList() // Clear usernames if JSON is invalid
                    accountData.clear()
                }
            }
        } ?: showError("Could not open file stream")
    }

    private fun startProcessing() {
        if (usernames.isEmpty()) {
            showError("No valid usernames found to process")
            return
        }
        resetStats()
        binding.progressSection.visibility = View.VISIBLE
        binding.fileSection.visibility = View.GONE
        binding.textSection.visibility = View.GONE
        binding.converterSection.visibility = View.GONE
        binding.downloadButton.visibility = View.GONE
        binding.cancelButton.visibility = View.VISIBLE
        updateStats() // Update UI with initial stats
        resultsAdapter.clear() // Clear previous results

        job = scope.launch {
            val semaphore = Semaphore(CONCURRENT_LIMIT)
            val client = OkHttpClient()
            val deferreds = usernames.map { username ->
                async {
                    semaphore.acquire() // Acquire a permit before starting the task
                    try {
                        checkUsername(client, username)
                    } finally {
                        semaphore.release() // Release the permit when done or on error
                    }
                }
            }
            deferreds.awaitAll() // Wait for all async tasks to complete

            // Update UI on the main thread after all processing is done
            withContext(Dispatchers.Main) {
                binding.downloadButton.visibility = if (activeAccounts.isNotEmpty()) View.VISIBLE else View.GONE
                binding.cancelButton.visibility = View.GONE
                if (job?.isCancelled != true) { // Only show success if not cancelled
                    showSuccess("Processing completed! Found ${activeAccounts.size} active accounts.")
                }
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

                if (code == 404) { // User not found
                    val resultMessage = "$username - Available"
                    updateResult("AVAILABLE", resultMessage, username)
                    return "AVAILABLE" to resultMessage
                } else if (code == 200) { // User found
                    val body = response.body?.string()
                    val json = JSONObject(body ?: "{}")
                    // Check if the 'user' object exists within 'data' to determine if active/valid
                    val status = if (json.optJSONObject("data")?.optJSONObject("user") != null) "ACTIVE" else "AVAILABLE"
                    val resultMessage = if (status == "ACTIVE") "$username - Active" else "$username - Available"
                    updateResult(status, resultMessage, username)
                    return status to resultMessage
                } else { // Other HTTP errors (e.g., 429 Too Many Requests, 5xx)
                    retryCount++
                    val statusMsg = "Retry $retryCount/$MAX_RETRIES for $username (Status: $code)"
                    updateStatus(statusMsg, username) // Update with retry info
                }
            } catch (e: Exception) { // Network errors, JSON parsing errors, etc.
                retryCount++
                val statusMsg = "Retry $retryCount/$MAX_RETRIES for $username (${e.message?.take(30)}...)"
                updateStatus(statusMsg, username) // Update with error info
            }

            // Wait before retrying
            delay(delayMs)
            // Exponential backoff with jitter
            delayMs = min(MAX_DELAY, (delayMs * 1.5 + Random().nextInt(1000)).toLong())
        }

        // Max retries exceeded
        val resultMessage = "$username - Error (Max retries exceeded)"
        updateResult("ERROR", resultMessage, username)
        return "ERROR" to resultMessage
    }

    private suspend fun updateResult(status: String, message: String, username: String) {
        withContext(Dispatchers.Main) {
            processedCount++
            when (status) {
                "ACTIVE" -> {
                    activeCount++
                    // Attempt to add the full JSON object if it was loaded
                    accountData[username]?.let { activeAccounts.add(it) }
                }
                "AVAILABLE" -> availableCount++
                "ERROR" -> errorCount++
                "CANCELLED" -> cancelledCount++
            }
            resultsAdapter.addItem(ResultItem(status, message)) // Add result to the RecyclerView
            updateProgress()
            updateStats()
        }
    }

    private suspend fun updateStatus(message: String, username: String? = null) {
        withContext(Dispatchers.Main) {
            // Optionally associate status message with a username if provided
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
        job?.cancel() // Cancel the current coroutine job
        scope.launch { // Use a new scope to update UI safely
            updateStatus("Processing cancelled by user")
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
            val jsonArray = JSONArray()
            activeAccounts.forEach { jsonArray.put(it) } // Reconstruct JSONArray from list of JSONObjects

            contentResolver.openOutputStream(uri)?.use { output ->
                output.write(jsonArray.toString(2).toByteArray()) // Use toString(2) for pretty printing
                output.flush()
            } ?: throw Exception("Unable to open output stream")
            showSuccess("Results saved successfully! (${activeAccounts.size} active accounts)")
        } catch (e: Exception) {
            showError("Failed to save file: ${e.message}")
            Log.e("SaveJsonToFile", "Error saving JSON file", e)
        }
    }

    /**
     * Converts JSON data to an Excel (.xlsx) file.
     * This function is designed to handle potential issues like empty files,
     * invalid JSON, and ensure proper writing to the Android file system.
     *
     * @param uri The Uri where the Excel file should be saved.
     */
    private fun convertJsonToExcel(uri: Uri) {
        binding.conversionProgress.visibility = View.VISIBLE
        binding.convertButton.isEnabled = false
        binding.pickJsonButton.isEnabled = false // Disable picking another file during conversion

        scope.launch(Dispatchers.IO) { // Perform I/O operations on the IO dispatcher
            var workbook: XSSFWorkbook? = null // Declare here to ensure it's closed in finally
            var bos: ByteArrayOutputStream? = null // Declare here for finally block

            try {
                Log.d("ExcelConversion", "Starting conversion process for URI: $jsonFileUri")

                if (jsonFileUri == null) {
                    Log.e("ExcelConversion", "No JSON file selected for conversion.")
                    // Update UI on main thread
                    withContext(Dispatchers.Main) {
                        showError("No JSON file selected. Please pick a JSON file first.")
                        resetConverterUI()
                    }
                    return@launch
                }

                // Read the JSON string from the selected URI
                val jsonString = contentResolver.openInputStream(jsonFileUri!!)?.use { inputStream ->
                    inputStream.bufferedReader().use { it.readText() }
                } ?: run {
                    Log.e("ExcelConversion", "Could not open or read from JSON file URI: $jsonFileUri")
                    withContext(Dispatchers.Main) {
                        showError("Failed to read JSON file.")
                        resetConverterUI()
                    }
                    return@launch
                }

                // Parse the JSON string into a JSONArray
                val jsonArray = try {
                    JSONArray(jsonString)
                } catch (e: Exception) {
                    Log.e("ExcelConversion", "Invalid JSON format in file.", e)
                    withContext(Dispatchers.Main) {
                        showError("Invalid JSON format: ${e.message}")
                        resetConverterUI()
                    }
                    return@launch
                }

                if (jsonArray.length() == 0) {
                    Log.e("ExcelConversion", "JSON file is empty or contains no records.")
                    withContext(Dispatchers.Main) {
                        showError("The selected JSON file is empty. No data to convert.")
                        resetConverterUI()
                    }
                    return@launch
                }

                // Initialize Excel workbook and sheet
                workbook = XSSFWorkbook()
                val sheet = workbook.createSheet("Instagram Accounts")

                // Define header row and cells
                val headerRow: Row = sheet.createRow(0)
                val headers = arrayOf("Username", "Password", "Auth Code", "Email") // Assumed fields
                headers.forEachIndexed { index, header ->
                    val cell: Cell = headerRow.createCell(index)
                    cell.setCellValue(header)
                }

                // Populate data rows from JSON objects
                var validRowCount = 0
                for (i in 0 until jsonArray.length()) {
                    try {
                        val jsonObj = jsonArray.getJSONObject(i)
                        val dataRow: Row = sheet.createRow(validRowCount + 1) // +1 for header

                        // Safely get values using optString to avoid NullPointerException
                        dataRow.createCell(0).setCellValue(jsonObj.optString("username", ""))
                        dataRow.createCell(1).setCellValue(jsonObj.optString("password", ""))
                        dataRow.createCell(2).setCellValue(jsonObj.optString("auth_code", ""))
                        dataRow.createCell(3).setCellValue(jsonObj.optString("email", ""))

                        validRowCount++

                        // Log progress periodically for large files
                        if (validRowCount % 100 == 0) {
                            Log.d("ExcelConversion", "Processed $validRowCount rows for Excel.")
                        }
                    } catch (e: Exception) {
                        Log.w("ExcelConversion", "Skipping row $i due to error: ${e.message}")
                        // Continue to the next row even if one is malformed
                    }
                }

                // Auto-size columns for better readability (best effort)
                for (i in 0 until headers.size) {
                    try {
                        sheet.autoSizeColumn(i)
                    } catch (e: Exception) {
                        Log.w("ExcelConversion", "Could not auto-size column $i: ${e.message}")
                    }
                }

                // Write the workbook to a ByteArrayOutputStream
                bos = ByteArrayOutputStream()
                workbook.write(bos)
                bos.flush()
                val bytes = bos.toByteArray()

                // Write the byte array to the output URI provided by the user
                val writtenBytes = contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(bytes)
                    out.flush()
                    bytes.size // Return the number of bytes written
                } ?: run {
                    Log.e("ExcelConversion", "Could not open output stream for URI: $uri")
                    withContext(Dispatchers.Main) {
                        showError("Cannot save the Excel file to the selected location.")
                        resetConverterUI()
                    }
                    return@launch
                }

                Log.d("ExcelConversion", "Successfully wrote $writtenBytes bytes to Excel file.")

                // Update UI on main thread upon successful conversion
                withContext(Dispatchers.Main) {
                    showSuccess("Excel file created successfully with $validRowCount records!")
                    resetConverterUI()
                }

            } catch (e: Exception) {
                Log.e("ExcelConversion", "An unexpected error occurred during Excel conversion.", e)
                // Update UI on main thread to show the error
                withContext(Dispatchers.Main) {
                    showError("Excel conversion failed: ${e.localizedMessage ?: "Unknown error"}")
                    resetConverterUI()
                }
            } finally {
                // Ensure resources are closed regardless of success or failure
                try { workbook?.close() } catch (ignored: Exception) { /* ignore */ }
                try { bos?.close() } catch (ignored: Exception) { /* ignore */ }
                Log.d("ExcelConversion", "Workbook and ByteArrayOutputStream closed.")
            }
        }
    }

    /**
     * Resets the UI elements for the converter tab after conversion or on error.
     */
    private fun resetConverterUI() {
        binding.conversionProgress.visibility = View.GONE
        binding.convertButton.isEnabled = (jsonFileUri != null) // Re-enable if a file is selected
        binding.pickJsonButton.isEnabled = true
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

// --- Adapter and data class (kept unchanged as they are functional) ---

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

        val statusIndicator = holder.itemView.findViewById<View>(R.id.status_indicator)

        when (item.status) {
            "ACTIVE" -> {
                holder.icon.setImageResource(android.R.drawable.presence_busy)
                holder.itemView.setBackgroundColor(Color.parseColor("#FECACA")) // Light red
                holder.message.setTextColor(Color.parseColor("#DC2626")) // Dark red
                statusIndicator?.setBackgroundColor(Color.parseColor("#DC2626"))
            }
            "AVAILABLE" -> {
                holder.icon.setImageResource(android.R.drawable.presence_online)
                holder.itemView.setBackgroundColor(Color.parseColor("#D1FAE5")) // Light green
                holder.message.setTextColor(Color.parseColor("#059669")) // Dark green
                statusIndicator?.setBackgroundColor(Color.parseColor("#059669"))
            }
            "ERROR" -> {
                holder.icon.setImageResource(android.R.drawable.ic_dialog_alert)
                holder.itemView.setBackgroundColor(Color.parseColor("#FEF3C7")) // Light yellow/orange
                holder.message.setTextColor(Color.parseColor("#D97706")) // Dark yellow/orange
                statusIndicator?.setBackgroundColor(Color.parseColor("#D97706"))
            }
            else -> { // INFO or other statuses
                holder.icon.setImageResource(android.R.drawable.ic_dialog_info)
                holder.itemView.setBackgroundColor(Color.parseColor("#F9FAFB")) // Very light gray
                holder.message.setTextColor(Color.parseColor("#6B7280")) // Medium gray
                statusIndicator?.setBackgroundColor(Color.parseColor("#6B7280"))
            }
        }
    }

    override fun getItemCount() = items.size

    fun addItem(item: ResultItem) {
        items.add(0, item) // Add new items at the beginning for LIFO display
        notifyItemInserted(0)
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }
}

// Add this extension function for simpler logging
fun Log.printStackTrace(tag: String, message: String, throwable: Throwable? = null) {
    this.e(tag, message, throwable)
}
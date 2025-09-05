package com.turjaun.instacheck

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : ComponentActivity() {

    private val viewModel = MainViewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {

            val context = LocalContext.current
            var tabIndex by remember { mutableStateOf(0) } // 0 = file, 1 = text
            var textInput by remember { mutableStateOf(TextFieldValue("")) }
            var filename by remember { mutableStateOf("usernames") }

            val scope = rememberCoroutineScope()

            val pickFileLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent()
            ) { uri: Uri? ->
                uri?.let { processFileUri(it, context) { name -> filename = name } }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Text(
                    text = "Instagram Username Checker",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color(0xFF6366F1),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Check many usernames quickly — mobile optimized",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                Spacer(Modifier.height(16.dp))

                // Tabs
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { tabIndex = 0 },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if(tabIndex==0) Color(0xFFDDE6FF) else Color(0xFFF3F4F6),
                            contentColor = if(tabIndex==0) Color(0xFF6366F1) else Color.Black
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text("Upload File") }
                    Button(
                        onClick = { tabIndex = 1 },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if(tabIndex==1) Color(0xFFDDE6FF) else Color(0xFFF3F4F6),
                            contentColor = if(tabIndex==1) Color(0xFF6366F1) else Color.Black
                        ),
                        modifier = Modifier.weight(1f)
                    ) { Text("Enter Text") }
                }

                Spacer(Modifier.height(12.dp))

                // File or Text Input
                if(tabIndex == 0) {
                    Button(
                        onClick = { pickFileLauncher.launch("*/*") },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Pick JSON/TXT File") }
                } else {
                    BasicTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Start button
                Button(
                    onClick = {
                        val usernames = if(tabIndex==0) viewModel.results.map{ it.username } else textInput.text.lines().filter { it.isNotBlank() }
                        if(usernames.isEmpty()) {
                            Toast.makeText(context,"No usernames found!",Toast.LENGTH_SHORT).show()
                        } else {
                            viewModel.startChecking(usernames, filename)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Start Checking") }

                Spacer(Modifier.height(12.dp))

                // Progress bar + stats
                LinearProgressIndicator(progress = viewModel.progress, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text("Processed: ${viewModel.processedCount}/${viewModel.totalCount}", style = MaterialTheme.typography.bodySmall)

                Spacer(Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard("Active", viewModel.stats.activeCount, Color(0xFFFFE6E6))
                    StatCard("Available", viewModel.stats.availableCount, Color(0xFFE6FFE6))
                    StatCard("Error", viewModel.stats.errorCount, Color(0xFFFFFFE6))
                    StatCard("Total", viewModel.totalCount, Color(0xFFF3F4F6))
                }

                Spacer(Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.cancel() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B3B)),
                        modifier = Modifier.weight(1f)
                    ) { Text("Cancel") }

                    Button(
                        onClick = {
                            if(PermissionChecker.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)==PermissionChecker.PERMISSION_GRANTED) {
                                viewModel.saveResults(context, filename)
                                Toast.makeText(context,"Saved to Downloads/Insta_Saver",Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context,"Storage permission required!",Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)),
                        modifier = Modifier.weight(1f)
                    ) { Text("Download") }
                }

                Spacer(Modifier.height(8.dp))

                // Results
                LazyColumn(modifier = Modifier.fillMaxHeight()) {
                    items(viewModel.results.reversed()) { item ->
                        ResultCard(item)
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }

    private fun processFileUri(uri: Uri, context: android.content.Context, callback: (String)->Unit) {
        try {
            val cursor = contentResolver.query(uri, null, null, null, null)
            var name = "usernames"
            cursor?.use {
                if(it.moveToFirst()) {
                    name = it.getString(it.getColumnIndex(OpenableColumns.DISPLAY_NAME))
                }
            }
            callback(name.substringBeforeLast("."))

            val inputStream = contentResolver.openInputStream(uri)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val text = reader.readText()
            reader.close()

            val usernames = mutableListOf<String>()
            if(name.endsWith(".json", true)) {
                val array = JSONArray(text)
                for(i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    obj.optString("username")?.takeIf { it.isNotBlank() }?.let { usernames.add(it) }
                }
            } else {
                text.lines().forEach { line -> if(line.isNotBlank()) usernames.add(line.trim()) }
            }

            viewModel.results.clear()
            usernames.forEach { viewModel.results.add(UsernameResult(it, UsernameStatus.AVAILABLE)) }

        } catch (e: Exception) {
            Toast.makeText(context, "Failed to read file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun StatCard(title: String, count: Int, bg: Color) {
    Column(
        modifier = Modifier
            .weight(1f)
            .background(bg, RoundedCornerShape(8.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(count.toString(), style = MaterialTheme.typography.titleMedium)
        Text(title, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun ResultCard(result: UsernameResult) {
    val (bg, textColor) = when(result.status) {
        UsernameStatus.ACTIVE -> Color(0xFFFFEBEB) to Color(0xFFB91C1C)
        UsernameStatus.AVAILABLE -> Color(0xFFE6FFE6) to Color(0xFF166534)
        UsernameStatus.ERROR -> Color(0xFFFFFBE6) to Color(0xFF92400E)
        UsernameStatus.CANCELLED -> Color(0xFFE5E7EB) to Color(0xFF374151)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(8.dp))
            .padding(8.dp)
            .border(1.dp, Color.Gray, RoundedCornerShape(8.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(result.username, color = textColor)
    }
}

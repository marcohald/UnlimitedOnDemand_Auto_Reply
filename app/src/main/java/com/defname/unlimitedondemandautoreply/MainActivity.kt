/*
 * Copyright (C) 2025 defname
 *
 * This file is part of UnlimitedOnDemand Auto Reply.
 *
 * UnlimitedOnDemand_Auto_Reply is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * UnlimitedOnDemand Auto Reply is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with UnlimitedOnDemand_Auto_Reply. If not, see <https://www.gnu.org/licenses/>.
 */

package com.defname.unlimitedondemandautoreply

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.net.Uri
import android.provider.Settings
import android.provider.Telephony
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.defname.unlimitedondemandautoreply.ui.theme.SmsTestAppTheme

private const val REQUEST_CODE_POST_NOTIFICATIONS = 1001

/**
 * MainActivity for the app.
 * Display the status of the permissions with a button to request them.
 * Display the status of the notification listener with a button to enable it.
 * Configure the settings for the app.
 */
class MainActivity : ComponentActivity() {
    /**
     * initialize all ui event handler
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            SmsTestAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(this)
                }
            }
        }
    }

    fun checkSMSPermissions(): Boolean {
        return checkSelfPermission(android.Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
    }

    fun requestSMSPermissions() {
        Log.d("MainActivity", "request permissions")
        requestPermissions(arrayOf(android.Manifest.permission.SEND_SMS), 1002)
    }

    fun checkNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.d("SmsTestApp", "SDK < Tiramisu")
            return true
        }

        return (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_CODE_POST_NOTIFICATIONS
            )
        }
    }

    fun checkNotificationServiceEnabled(): Boolean {
        val cn = ComponentName(this, MyNotificationListenerService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(cn.flattenToString()) == true
    }

    fun requestNotificationService() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    fun checkBatteryOptimization(): Boolean {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    fun requestBatteryOptimization() {
        if (!checkBatteryOptimization()) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    fun sendTestSms(number: String, msg: String) {
        try {
            val phone = number.filter { it.isDigit() || it == '+' }
            if (phone.isEmpty()) {
                android.widget.Toast.makeText(this, "Phone number is empty", android.widget.Toast.LENGTH_SHORT).show()
                return
            }

            val smsManager = getSystemService(android.telephony.SmsManager::class.java)
            if (smsManager == null) {
                android.widget.Toast.makeText(this, "SmsManager service not available", android.widget.Toast.LENGTH_SHORT).show()
                return
            }

            // Immediately record this manual SMS event so the data page resets its tracking
            DataManager.recordSmsSent(this)

            // Reusing the same "SMS_SENT" intent action from the Notification Listener so it triggers the same log receiver
            val intent = android.content.Intent("SMS_SENT")
            intent.setPackage(packageName)

            val sentIntent = android.app.PendingIntent.getBroadcast(
                this,
                0,
                intent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    android.app.PendingIntent.FLAG_IMMUTABLE
                else 0
            )

            smsManager.sendTextMessage(phone, null, msg, sentIntent, null)
            android.widget.Toast.makeText(this, "Test SMS queued", android.widget.Toast.LENGTH_SHORT).show()
            LogManager.addLog("Manual Test SMS triggered for $phone")

        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Test SMS Error: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
            LogManager.addLog("Test SMS Error: ${e.localizedMessage}")
        }
    }

    fun saveSetting(key: String, value: String) {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        prefs.edit() { putString(key, value) }
    }

    fun getSetting(key: String, defaultValue: String = ""): String {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        return prefs.getString(key, defaultValue) ?: defaultValue
    }

    fun getInstalledApps(): List<ApplicationInfo> {
        val pm = packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
    }
}

data class AppItem(val appName: String, val packageName: String)

@Composable
fun AppSelectionDialog(
    installedApps: List<ApplicationInfo>,
    packageManager: PackageManager,
    onAppSelected: (String) -> Unit,
    onDismissRequest: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    // Map installed apps to AppItem only once when installedApps changes
    val appItems = remember(installedApps) {
        installedApps.map { appInfo ->
            AppItem(
                appName = packageManager.getApplicationLabel(appInfo).toString(),
                packageName = appInfo.packageName
            )
        }
    }

    // Filter the mapped items
    val filteredApps = remember(searchQuery, appItems) {
        appItems.filter { appItem ->
            appItem.appName.contains(searchQuery, ignoreCase = true) ||
            appItem.packageName.contains(searchQuery, ignoreCase = true)
        }.sortedBy { it.appName.lowercase() }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Column {
                Text(stringResource(R.string.select_app_title))
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text(stringResource(R.string.search_app_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        text = {
            LazyColumn {
                items(filteredApps) { appItem ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onAppSelected(appItem.packageName)
                            }
                            .padding(vertical = 8.dp)
                    ) {
                        Text(text = appItem.appName, style = MaterialTheme.typography.bodyLarge)
                        Text(text = appItem.packageName, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    androidx.compose.material3.HorizontalDivider(color = Color.LightGray, thickness = 0.5.dp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun SettingsScreen(
    onSaveSetting: (String, String) -> Unit,
    onGetSetting: (String, String) -> String,
    onRequestSMSPermissions: () -> Unit,
    checkSMSPermissions: () -> Boolean,
    onRequestNotificationPermission: () -> Unit,
    checkNotificationPermission: () -> Boolean,
    onRequestNotificationService: () -> Unit,
    checkNotificationServiceEnabled: () -> Boolean,
    getDefaultSmsPackage: () -> String?,
    getInstalledApps: () -> List<ApplicationInfo>,
    packageManager: PackageManager,
    checkBatteryOptimization: () -> Boolean,
    onRequestBatteryOptimization: () -> Unit,
    refreshTrigger: Int,
    onSendTestSms: (String, String) -> Unit
) {
    var smsPermissionGranted by remember { mutableStateOf(checkSMSPermissions()) }
    var notificationPermissionGranted by remember { mutableStateOf(checkNotificationPermission()) }
    var notificationServiceEnabled by remember { mutableStateOf(checkNotificationServiceEnabled()) }
    var batteryOptimizationGranted by remember { mutableStateOf(checkBatteryOptimization()) }

    var smsAppPackage by remember { mutableStateOf(onGetSetting("sms_app", "")) }
    var titleMatch by remember { mutableStateOf(onGetSetting("title_match", "80112")) }
    var bodyMatch by remember { mutableStateOf(onGetSetting("body_match", "WEITER")) }
    var number by remember { mutableStateOf(onGetSetting("number", "80112")) }
    var answer by remember { mutableStateOf(onGetSetting("answer", "WEITER")) }
    var minDelay by remember { mutableStateOf(onGetSetting("min_delay", "15")) }
    var maxDelay by remember { mutableStateOf(onGetSetting("max_delay", "300")) }

    var showAppSelectionDialog by remember { mutableStateOf(false) }

    // Aktualisieren Sie den Status, wenn die Composable-Funktion neu zusammengesetzt wird (z. B. nach onResume)
    LaunchedEffect(refreshTrigger) {
        smsPermissionGranted = checkSMSPermissions()
        notificationPermissionGranted = checkNotificationPermission()
        notificationServiceEnabled = checkNotificationServiceEnabled()
        batteryOptimizationGranted = checkBatteryOptimization()
        // Laden Sie die Einstellungen neu, falls sie extern geändert wurden
        smsAppPackage = onGetSetting("sms_app", "")
        titleMatch = onGetSetting("title_match", "80112")
        bodyMatch = onGetSetting("body_match", "WEITER")
        number = onGetSetting("number", "80112")
        answer = onGetSetting("answer", "WEITER")
        minDelay = onGetSetting("min_delay", "15")
        maxDelay = onGetSetting("max_delay", "300")
    }


    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // SMS Permission Status
            Text(
                text = if (smsPermissionGranted) stringResource(R.string.sms_permission_granted)
                else stringResource(R.string.sms_permission_denied),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            androidx.compose.material3.Button(
                onClick = {
                    onRequestSMSPermissions()
                },
                enabled = !smsPermissionGranted
            ) {
                Text(stringResource(R.string.request_sms_permission_btn))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Notification Permission Status
            Text(
                text = if (notificationPermissionGranted) stringResource(R.string.notification_permission_granted)
                else stringResource(R.string.notification_permission_denied),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            androidx.compose.material3.Button(
                onClick = {
                    onRequestNotificationPermission()
                },
                enabled = !notificationPermissionGranted
            ) {
                Text(stringResource(R.string.request_notification_permission_btn))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Notification Listener Status
            Text(
                text = if (notificationServiceEnabled) stringResource(R.string.notification_listener_active)
                else stringResource(R.string.notification_listener_inactive),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )
            androidx.compose.material3.Button(onClick = {
                onRequestNotificationService()
            }) {
                Text(stringResource(R.string.enable_notification_listener_btn))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Battery Optimization Status
            Text(
                text = if (batteryOptimizationGranted) stringResource(R.string.battery_optimization_granted)
                else stringResource(R.string.battery_optimization_denied),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            androidx.compose.material3.Button(
                onClick = {
                    onRequestBatteryOptimization()
                },
                enabled = !batteryOptimizationGranted
            ) {
                Text(stringResource(R.string.request_battery_optimization_btn))
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Input: App Package
            OutlinedTextField(
                value = smsAppPackage,
                onValueChange = {
                    smsAppPackage = it
                    onSaveSetting("sms_app", it)
                },
                label = { Text(stringResource(R.string.edit_app_package_caption)) },
                placeholder = { Text(stringResource(R.string.edit_app_package_hint)) },
                modifier = Modifier.fillMaxWidth()
            )
            Row {
                androidx.compose.material3.Button(
                    onClick = {
                        getDefaultSmsPackage()?.let {
                            smsAppPackage = it
                            onSaveSetting("sms_app", it)
                        }
                    },
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text(stringResource(R.string.button_sms_app_caption))
                }

                androidx.compose.material3.Button(
                    onClick = { showAppSelectionDialog = true }
                ) {
                    Text(stringResource(R.string.select_app_btn))
                }
            }


            Spacer(modifier = Modifier.height(16.dp))

            // Input: Title to match
            OutlinedTextField(
                value = titleMatch,
                onValueChange = {
                    titleMatch = it
                    onSaveSetting("title_match", it)
                },
                label = { Text(stringResource(R.string.edit_title_caption)) },
                placeholder = { Text(stringResource(R.string.edit_title_hint)) },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Input: Content to match
            OutlinedTextField(
                value = bodyMatch,
                onValueChange = {
                    bodyMatch = it
                    onSaveSetting("body_match", it)
                },
                label = { Text(stringResource(R.string.edit_content_caption)) },
                placeholder = { Text(stringResource(R.string.edit_content_hint)) },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Input: Number
            OutlinedTextField(
                value = number,
                onValueChange = {
                    number = it
                    onSaveSetting("number", it)
                },
                label = { Text(stringResource(R.string.edit_number_caption)) },
                placeholder = { Text(stringResource(R.string.edit_number_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Input: Answer
            OutlinedTextField(
                value = answer,
                onValueChange = {
                    answer = it
                    onSaveSetting("answer", it)
                },
                label = { Text(stringResource(R.string.edit_answer_caption)) },
                placeholder = { Text(stringResource(R.string.edit_answer_hint)) },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            androidx.compose.material3.Button(
                onClick = { onSendTestSms(number, answer) },
                enabled = smsPermissionGranted && number.isNotEmpty() && answer.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.send_test_sms_btn))
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(stringResource(R.string.edit_delay_caption), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 16.dp))
            Row(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = minDelay,
                    onValueChange = {
                        minDelay = it
                        onSaveSetting("min_delay", it)
                    },
                    label = { Text("Min (s)") },
                    placeholder = { Text("5") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                )
                OutlinedTextField(
                    value = maxDelay,
                    onValueChange = {
                        maxDelay = it
                        onSaveSetting("max_delay", it)
                    },
                    label = { Text("Max (s)") },
                    placeholder = { Text("30") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    if (showAppSelectionDialog) {
        AppSelectionDialog(
            installedApps = getInstalledApps(),
            packageManager = packageManager,
            onAppSelected = {
                smsAppPackage = it
                onSaveSetting("sms_app", it)
                showAppSelectionDialog = false
            },
            onDismissRequest = { showAppSelectionDialog = false }
        )
    }
}

@Composable
fun MainScreen(activity: MainActivity) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    val tabs = listOf("Einstellungen", "Logs", "Data")

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Nutzt Safe Drawing Insets, um Notch/Kamera auszuweichen
            .padding(WindowInsets.safeDrawing.asPaddingValues())
    ) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = {
                        selectedTab = index
                        if (index == 0) refreshTrigger++
                    },
                    text = { Text(title) }
                )
            }
        }

        if (selectedTab != 2) { // Hide refresh button on Data tab as it auto-refreshes
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                androidx.compose.material3.Button(onClick = { refreshTrigger++ }) {
                    Text(if (selectedTab == 0) stringResource(R.string.refresh_btn) else stringResource(R.string.refresh_logs_btn))
                }
            }
        }

        when (selectedTab) {
            0 -> SettingsScreen(
                onSaveSetting = activity::saveSetting,
                onGetSetting = activity::getSetting,
                onRequestSMSPermissions = activity::requestSMSPermissions,
                checkSMSPermissions = activity::checkSMSPermissions,
                onRequestNotificationPermission = activity::requestNotificationPermission,
                checkNotificationPermission = activity::checkNotificationPermission,
                onRequestNotificationService = activity::requestNotificationService,
                checkNotificationServiceEnabled = activity::checkNotificationServiceEnabled,
                getDefaultSmsPackage = { Telephony.Sms.getDefaultSmsPackage(activity) },
                getInstalledApps = activity::getInstalledApps,
                packageManager = activity.packageManager,
                checkBatteryOptimization = activity::checkBatteryOptimization,
                onRequestBatteryOptimization = activity::requestBatteryOptimization,
                refreshTrigger = refreshTrigger,
                onSendTestSms = activity::sendTestSms
            ) // Deine bisherige UI
            1 -> LogScreen(refreshTrigger = refreshTrigger)      // Die neue Log-Ansicht
            2 -> DataScreen(activity)                            // Data Usage tab
        }
    }
}

@Composable
fun DataScreen(activity: MainActivity) {
    var dataUsageStr by remember { mutableStateOf("0 B") }
    var lastSmsTime by remember { mutableStateOf(DataManager.getLastSmsTimeFormatted(activity)) }
    var history by remember { mutableStateOf(DataManager.getHistory(activity)) }

    LaunchedEffect(Unit) {
        while (true) {
            val bytes = DataManager.getDataUsageSinceLastSms(activity)
            dataUsageStr = DataManager.formatBytes(bytes)
            lastSmsTime = DataManager.getLastSmsTimeFormatted(activity)
            history = DataManager.getHistory(activity)
            delay(1000) // Update every second
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Data Used Since Last SMS",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp, top = 32.dp)
        )

        Text(
            text = dataUsageStr,
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        Text(
            text = "Last SMS Sent:",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Gray
        )

        Text(
            text = lastSmsTime,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "This total includes both downloaded and uploaded data for your mobile connection. It updates automatically in real-time.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = 32.dp, end = 32.dp, bottom = 32.dp)
        )

        androidx.compose.material3.HorizontalDivider(color = Color.LightGray, thickness = 1.dp)

        Text(
            text = "Data History",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 16.dp).align(Alignment.Start)
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            items(history) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
                ) {
                    Text(text = entry.formattedTime, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = entry.formattedBytes,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                androidx.compose.material3.HorizontalDivider(color = Color.LightGray, thickness = 0.5.dp)
            }
            if (history.isEmpty()) {
                item {
                    Text(
                        text = "No history available yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun LogScreen(refreshTrigger: Int) {
    val logs = remember(refreshTrigger) { LogManager.logs.toList() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        items(logs) { entry ->
            Row(modifier = Modifier.padding(vertical = 4.dp)) {
                Text(
                    text = "[${entry.timestamp}]",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = entry.message,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            androidx.compose.material3.HorizontalDivider(color = Color.LightGray, thickness = 0.5.dp)
        }
    }
}


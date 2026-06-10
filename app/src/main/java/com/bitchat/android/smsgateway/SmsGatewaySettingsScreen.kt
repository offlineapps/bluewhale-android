package com.bitchat.android.smsgateway

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bitchat.android.nostr.NostrClient
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsGatewaySettingsScreen(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Read state from preference manager
    var isEnabled by remember { mutableStateOf(SmsGatewayPreferenceManager.isGatewayEnabled.value) }
    var webhookUrl by remember { mutableStateOf(SmsGatewayPreferenceManager.webhookUrl.value) }
    var gatewayName by remember { mutableStateOf(SmsGatewayPreferenceManager.gatewayName.value) }
    var phoneNumber by remember { mutableStateOf(SmsGatewayPreferenceManager.phoneNumber.value) }
    var publishToNostr by remember { mutableStateOf(SmsGatewayPreferenceManager.publishToNostr.value) }

    val nostrClient = remember { NostrClient.getInstance(context) }
    
    LaunchedEffect(nostrClient) {
        if (!nostrClient.isInitialized.value) {
            nostrClient.initialize()
        }
    }
    
    // Permission launcher for RECEIVE_SMS
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            isEnabled = true
            SmsGatewayPreferenceManager.setGatewayEnabled(context, true)
        } else {
            isEnabled = false
            Toast.makeText(context, "SMS Permission is required to run a Gateway", Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SMS Webhook Gateway") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Turn your phone into a SMS gateway. Incoming SMS messages will be forwarded to your configured Webhook URL.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Enable toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(
                    text = "Enable Webhook Gateway",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Switch(
                    checked = isEnabled,
                    onCheckedChange = { checked ->
                        if (checked) {
                            permissionLauncher.launch(Manifest.permission.RECEIVE_SMS)
                        } else {
                            isEnabled = false
                            SmsGatewayPreferenceManager.setGatewayEnabled(context, false)
                        }
                    }
                )
            }
            
            HorizontalDivider()
            
            // Webhook Configuration
            OutlinedTextField(
                value = webhookUrl,
                onValueChange = { 
                    webhookUrl = it
                    SmsGatewayPreferenceManager.setWebhookUrl(context, it)
                },
                label = { Text("Webhook URL") },
                placeholder = { Text("https://api.your-server.com/sms") },
                modifier = Modifier.fillMaxWidth(),
                enabled = isEnabled,
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Nostr Directory Public Broadcasting
            Text(
                text = "Public Directory Listing (Optional)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = "Publish your gateway on Nostr.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            OutlinedTextField(
                value = gatewayName,
                onValueChange = { 
                    gatewayName = it
                    SmsGatewayPreferenceManager.setGatewayName(context, it)
                },
                label = { Text("") },
                placeholder = { Text("My SMS Gateway") },
                modifier = Modifier.fillMaxWidth(),
                enabled = isEnabled
            )
            
            OutlinedTextField(
                value = phoneNumber,
                onValueChange = { 
                    phoneNumber = it
                    SmsGatewayPreferenceManager.setPhoneNumber(context, it)
                },
                label = { Text("") },
                placeholder = { Text("+1234567890") },
                modifier = Modifier.fillMaxWidth(),
                enabled = isEnabled
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(
                    text = "Publish to Nostr Directory",
                    style = MaterialTheme.typography.bodyLarge
                )
                Switch(
                    checked = publishToNostr,
                    enabled = isEnabled && gatewayName.isNotBlank() && webhookUrl.isNotBlank(),
                    onCheckedChange = { checked ->
                        publishToNostr = checked
                        SmsGatewayPreferenceManager.setPublishToNostr(context, checked)
                        
                        // Immediately publish if turning on
                        if (checked) {
                            scope.launch {
                                nostrClient.publishSmsGatewayPresence(
                                    gatewayName = gatewayName,
                                    webhookUrl = webhookUrl,
                                    phoneNumber = phoneNumber,
                                    onSuccess = {
                                        Toast.makeText(context, "Published to Nostr", Toast.LENGTH_SHORT).show()
                                    },
                                    onError = { err ->
                                        publishToNostr = false
                                        SmsGatewayPreferenceManager.setPublishToNostr(context, false)
                                        Toast.makeText(context, "Failed to publish: $err", Toast.LENGTH_LONG).show()
                                    }
                                )
                            }
                        }
                    }
                )
            }
        }
    }
}

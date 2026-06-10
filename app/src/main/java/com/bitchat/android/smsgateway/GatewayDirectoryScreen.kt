package com.bitchat.android.smsgateway

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bitchat.android.nostr.NostrClient
import com.bitchat.android.smsgateway.SmsGatewayRegistry
import com.bitchat.android.smsgateway.DiscoveredSmsGateway
import kotlinx.coroutines.launch

/**
 * Screen displaying the list of all SMS Gateways discovered via Nostr
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GatewayDirectoryScreen(
    onDismiss: () -> Unit
) {
    val gateways by SmsGatewayRegistry.availableGateways.collectAsState()
    val sortedGateways = remember(gateways) {
        gateways.values.sortedByDescending { it.lastSeenTimestamp }
    }
    
    val context = LocalContext.current
    val nostrClient = remember { NostrClient.getInstance(context) }
    
    LaunchedEffect(nostrClient) {
        if (!nostrClient.isInitialized.value) {
            nostrClient.initialize()
        }
    }
    
    // Subscribe when the screen is shown and unsubscribe when it's closed
    DisposableEffect(nostrClient) {
        nostrClient.subscribeToSmsGateways()
        onDispose {
            nostrClient.unsubscribeFromSmsGateways()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SMS Gateway Directory") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }
            )
        }
    ) { padding ->
        if (sortedGateways.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.padding(bottom = 16.dp))
                    Text(
                        text = "Searching Nostr for active SMS gateways...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(sortedGateways) { gateway ->
                    GatewayCard(gateway)
                }
            }
        }
    }
}

@Composable
private fun GatewayCard(gateway: DiscoveredSmsGateway) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = gateway.name.ifBlank { "Anonymous Gateway" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            if (gateway.phoneNumber.isNotBlank()) {
                Text(
                    text = "Phone: ${gateway.phoneNumber}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            
            if (gateway.webhookUrl.isNotBlank()) {
                Text(
                    text = "Webhook: ${gateway.webhookUrl}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

package com.bitchat.android.smsgateway

import android.util.Log
import com.bitchat.android.nostr.NostrEvent
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Data class representing a discovered SMS webhook gateway from Nostr
 */
data class DiscoveredSmsGateway(
    val pubkey: String,
    val name: String,
    val webhookUrl: String,
    val phoneNumber: String,
    val lastSeenTimestamp: Int
)

/**
 * Registry that holds the currently known SMS Webhook Gateways discovered via Nostr
 */
object SmsGatewayRegistry {
    private const val TAG = "SmsGatewayRegistry"
    
    // Map of pubkey to gateway info to handle replacements
    private val _gateways = MutableStateFlow<Map<String, DiscoveredSmsGateway>>(emptyMap())
    
    /**
     * Observable flow of available gateways, sorted by recently seen
     */
    val availableGateways: StateFlow<Map<String, DiscoveredSmsGateway>> = _gateways.asStateFlow()
    
    /**
     * Process an incoming kind 30030 event from a Nostr relay
     */
    fun processGatewayDiscoveryEvent(event: NostrEvent) {
        if (!event.isValidSignature()) {
            Log.w(TAG, "Rejecting invalid signature for SMS gateway event: ${event.id}")
            return
        }
        
        try {
            // Parse JSON content
            val jsonElement = JsonParser.parseString(event.content)
            if (!jsonElement.isJsonObject) {
                Log.w(TAG, "SMS gateway event content is not JSON object: ${event.id}")
                return
            }
            
            val jsonObj = jsonElement.asJsonObject
            val name = jsonObj.get("name")?.asString ?: "Unknown Gateway"
            val webhookUrl = jsonObj.get("webhook_url")?.asString ?: ""
            val phoneNumber = jsonObj.get("phone_number")?.asString ?: ""
            
            val newGateway = DiscoveredSmsGateway(
                pubkey = event.pubkey,
                name = name,
                webhookUrl = webhookUrl,
                phoneNumber = phoneNumber,
                lastSeenTimestamp = event.createdAt
            )
            
            _gateways.update { currentMap ->
                val existing = currentMap[event.pubkey]
                // Only replace if the new event is actually newer
                if (existing == null || event.createdAt > existing.lastSeenTimestamp) {
                    Log.d(TAG, "Registered SMS Gateway for pubkey: ${event.pubkey}")
                    currentMap + (event.pubkey to newGateway)
                } else {
                    currentMap
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing SMS gateway event: ${e.message}")
        }
    }
    
    fun clearRegistry() {
        _gateways.value = emptyMap()
    }
}

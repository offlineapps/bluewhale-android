package com.bluewhale.android

import android.app.Application
import com.bluewhale.android.nostr.RelayDirectory
import com.bluewhale.android.ui.theme.ThemePreferenceManager
import com.bluewhale.android.ui.theme.TextColorPreferenceManager
import com.bluewhale.android.net.ArtiTorManager

/**
 * Main application class for bitchat Android
 */
class BluewhaleApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize Tor first so any early network goes over Tor
        try {
            val torProvider = ArtiTorManager.getInstance()
            torProvider.init(this)
        } catch (_: Exception){}

        // Initialize relay directory (loads assets/nostr_relays.csv)
        RelayDirectory.initialize(this)

        // Initialize LocationNotesManager dependencies early so sheet subscriptions can start immediately
        try { com.bluewhale.android.nostr.LocationNotesInitializer.initialize(this) } catch (_: Exception) { }

        // Initialize favorites persistence early so MessageRouter/NostrTransport can use it on startup
        try {
            com.bluewhale.android.favorites.FavoritesPersistenceService.initialize(this)
        } catch (_: Exception) { }

        // Warm up Nostr identity to ensure npub is available for favorite notifications
        try {
            com.bluewhale.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(this)
        } catch (_: Exception) { }

        // Initialize theme preference
        ThemePreferenceManager.init(this)
        TextColorPreferenceManager.init(this)

        // Initialize debug preference manager (persists debug toggles)
        try { com.bluewhale.android.ui.debug.DebugPreferenceManager.init(this) } catch (_: Exception) { }

        // Initialize Geohash Registries for persistence
        try {
            com.bluewhale.android.nostr.GeohashAliasRegistry.initialize(this)
            com.bluewhale.android.nostr.GeohashConversationRegistry.initialize(this)
        } catch (_: Exception) { }

        // Initialize mesh service preferences
        try { com.bluewhale.android.service.MeshServicePreferences.init(this) } catch (_: Exception) { }

        // Initialize SMS gateway preferences so saved settings survive app restarts
        try { com.bluewhale.android.smsgateway.SmsGatewayPreferenceManager.init(this) } catch (_: Exception) { }

        // Proactively start the foreground service to keep mesh alive
        try { com.bluewhale.android.service.MeshForegroundService.start(this) } catch (_: Exception) { }

        // TorManager already initialized above
    }
}

package com.bluewhale.android.ui

import com.bluewhale.android.ai.LlmEngine
import com.bluewhale.android.mesh.BluetoothMeshService
import com.bluewhale.android.model.BluewhaleMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Handles processing of IRC-style commands
 */
class CommandProcessor(
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val channelManager: ChannelManager,
    private val privateChatManager: PrivateChatManager,
    private val llmEngine: LlmEngine?,
    private val coroutineScope: CoroutineScope
) {

    // Available commands list
    private val baseCommands = listOf(
        CommandSuggestion("/ai", emptyList(), "<prompt>", "ask the offline ai model"),
        CommandSuggestion("/block", emptyList(), "[nickname]", "block or list blocked peers"),
        CommandSuggestion("/channels", emptyList(), null, "show all discovered channels"),
        CommandSuggestion("/clear", emptyList(), null, "clear chat messages"),
        CommandSuggestion("/hug", emptyList(), "<nickname>", "send someone a warm hug"),
        CommandSuggestion("/j", listOf("/join"), "<channel>", "join or create a channel"),
        CommandSuggestion("/m", listOf("/msg"), "<nickname> [message]", "send private message"),
        CommandSuggestion("/slap", emptyList(), "<nickname>", "slap someone with a trout"),
        CommandSuggestion("/unblock", emptyList(), "<nickname>", "unblock a peer"),
        CommandSuggestion("/w", emptyList(), null, "see who's online")
    )
    
    // MARK: - Command Processing
    
    fun processCommand(command: String, meshService: BluetoothMeshService, myPeerID: String, onSendMessage: (String, List<String>, String?) -> Unit, viewModel: ChatViewModel? = null): Boolean {
        if (!command.startsWith("/")) return false
        
        val parts = command.split(" ")
        val cmd = parts.first().lowercase()
        when (cmd) {
            "/ai" -> handleAiCommand(parts, meshService, myPeerID, onSendMessage)
            "/j", "/join" -> handleJoinCommand(parts, myPeerID)
            "/m", "/msg" -> handleMessageCommand(parts, meshService)
            "/w" -> handleWhoCommand(meshService, viewModel)
            "/clear" -> handleClearCommand()
            "/pass" -> handlePassCommand(parts, myPeerID)
            "/block" -> handleBlockCommand(parts, meshService)
            "/unblock" -> handleUnblockCommand(parts, meshService)
            "/hug" -> handleActionCommand(parts, "gives", "a warm hug 🫂", meshService, myPeerID, onSendMessage)
            "/slap" -> handleActionCommand(parts, "slaps", "around a bit with a large trout 🐟", meshService, myPeerID, onSendMessage)
            "/channels" -> handleChannelsCommand()
            else -> handleUnknownCommand(cmd)
        }
        
        return true
    }
    
    private fun handleJoinCommand(parts: List<String>, myPeerID: String) {
        if (parts.size > 1) {
            val channelName = parts[1]
            val channel = if (channelName.startsWith("#")) channelName else "#$channelName"
            val password = if (parts.size > 2) parts[2] else null
            val success = channelManager.joinChannel(channel, password, myPeerID)
            if (success) {
                val systemMessage = BluewhaleMessage(
                    sender = "system",
                    content = "joined channel $channel",
                    timestamp = Date(),
                    isRelay = false
                )
                messageManager.addMessage(systemMessage)
            }
        } else {
            val systemMessage = BluewhaleMessage(
                sender = "system",
                content = "usage: /join <channel>",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
        }
    }
    
    private fun handleMessageCommand(parts: List<String>, meshService: BluetoothMeshService) {
        if (parts.size > 1) {
            val targetName = parts[1].removePrefix("@")
            val peerID = getPeerIDForNickname(targetName, meshService)
            
            if (peerID != null) {
                val success = privateChatManager.startPrivateChat(peerID, meshService)
                
                if (success) {
                    if (parts.size > 2) {
                        val messageContent = parts.drop(2).joinToString(" ")
                        val recipientNickname = getPeerNickname(peerID, meshService)
                        privateChatManager.sendPrivateMessage(
                            messageContent, 
                            peerID, 
                            recipientNickname,
                            state.getNicknameValue(),
                            getMyPeerID(meshService)
                        ) { content, peerIdParam, recipientNicknameParam, messageId ->
                            // This would trigger the actual mesh service send
                            sendPrivateMessageVia(meshService, content, peerIdParam, recipientNicknameParam, messageId)
                        }
                    } else {
                        val systemMessage = BluewhaleMessage(
                            sender = "system",
                            content = "started private chat with $targetName",
                            timestamp = Date(),
                            isRelay = false
                        )
                        messageManager.addMessage(systemMessage)
                    }
                }
            } else {
                val systemMessage = BluewhaleMessage(
                    sender = "system",
                    content = "user '$targetName' not found. they may be offline or using a different nickname.",
                    timestamp = Date(),
                    isRelay = false
                )
                messageManager.addMessage(systemMessage)
            }
        } else {
            val systemMessage = BluewhaleMessage(
                sender = "system",
                content = "usage: /msg <nickname> [message]",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
        }
    }
    
    private fun handleWhoCommand(meshService: BluetoothMeshService, viewModel: ChatViewModel? = null) {
        // Channel-aware who command (matches iOS behavior)
        val (peerList, contextDescription) = if (viewModel != null) {
            when (val selectedChannel = viewModel.selectedLocationChannel.value) {
                is com.bluewhale.android.geohash.ChannelID.Mesh,
                null -> {
                    // Mesh channel: show Bluetooth-connected peers
                    val connectedPeers = state.getConnectedPeersValue()
                    val peerList = connectedPeers.joinToString(", ") { peerID ->
                        getPeerNickname(peerID, meshService)
                    }
                    Pair(peerList, "online users")
                }
                
                is com.bluewhale.android.geohash.ChannelID.Location -> {
                    // Location channel: show geohash participants
                    val geohashPeople = viewModel.geohashPeople.value ?: emptyList()
                    val currentNickname = state.getNicknameValue()
                    
                    val participantList = geohashPeople.mapNotNull { person ->
                        val displayName = person.displayName
                        // Exclude self from list
                        if (displayName.startsWith("${currentNickname}#")) {
                            null
                        } else {
                            displayName
                        }
                    }.joinToString(", ")
                    
                    Pair(participantList, "participants in ${selectedChannel.channel.geohash}")
                }
            }
        } else {
            // Fallback to mesh behavior
            val connectedPeers = state.getConnectedPeersValue()
            val peerList = connectedPeers.joinToString(", ") { peerID ->
                getPeerNickname(peerID, meshService)
            }
            Pair(peerList, "online users")
        }
        
        val systemMessage = BluewhaleMessage(
            sender = "system",
            content = if (peerList.isEmpty()) {
                "no one else is around right now."
            } else {
                "$contextDescription: $peerList"
            },
            timestamp = Date(),
            isRelay = false
        )
        messageManager.addMessage(systemMessage)
    }
    
    private fun handleClearCommand() {
        when {
            state.getSelectedPrivateChatPeerValue() != null -> {
                // Clear private chat
                val peerID = state.getSelectedPrivateChatPeerValue()!!
                messageManager.clearPrivateMessages(peerID)
            }
            state.getCurrentChannelValue() != null -> {
                // Clear channel messages
                val channel = state.getCurrentChannelValue()!!
                messageManager.clearChannelMessages(channel)
            }
            else -> {
                // Clear main messages
                messageManager.clearMessages()
            }
        }
    }

    private fun handlePassCommand(parts: List<String>, peerID: String) {
        val currentChannel = state.getCurrentChannelValue()

        if (currentChannel == null) {
            val systemMessage = BluewhaleMessage(
                sender = "system",
                content = "you must be in a channel to set a password.",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
            return
        }

        if (parts.size == 2){
            if(!channelManager.isChannelCreator(channel = currentChannel, peerID = peerID)){
                val systemMessage = BluewhaleMessage(
                    sender = "system",
                    content = "you must be the channel creator to set a password.",
                    timestamp = Date(),
                    isRelay = false
                )
                channelManager.addChannelMessage(currentChannel,systemMessage,null)
                return
            }
            val newPassword = parts[1]
            channelManager.setChannelPassword(currentChannel, newPassword)
            val systemMessage = BluewhaleMessage(
                sender = "system",
                content = "password changed for channel $currentChannel",
                timestamp = Date(),
                isRelay = false
            )
            channelManager.addChannelMessage(currentChannel,systemMessage,null)
        }
        else{
            val systemMessage = BluewhaleMessage(
                sender = "system",
                content = "usage: /pass <password>",
                timestamp = Date(),
                isRelay = false
            )
            channelManager.addChannelMessage(currentChannel,systemMessage,null)
        }
    }
    
    private fun handleBlockCommand(parts: List<String>, meshService: BluetoothMeshService) {
        if (parts.size > 1) {
            val targetName = parts[1].removePrefix("@")
            privateChatManager.blockPeerByNickname(targetName, meshService)
        } else {
            // List blocked users
            val blockedInfo = privateChatManager.listBlockedUsers()
            val systemMessage = BluewhaleMessage(
                sender = "system",
                content = blockedInfo,
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
        }
    }
    
    private fun handleUnblockCommand(parts: List<String>, meshService: BluetoothMeshService) {
        if (parts.size > 1) {
            val targetName = parts[1].removePrefix("@")
            privateChatManager.unblockPeerByNickname(targetName, meshService)
        } else {
            val systemMessage = BluewhaleMessage(
                sender = "system",
                content = "usage: /unblock <nickname>",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
        }
    }
    
    private fun handleActionCommand(
        parts: List<String>, 
        verb: String, 
        object_: String, 
        meshService: BluetoothMeshService,
        myPeerID: String,
        onSendMessage: (String, List<String>, String?) -> Unit
    ) {
        if (parts.size > 1) {
            val targetName = parts[1].removePrefix("@")
            val actionMessage = "* ${state.getNicknameValue() ?: "someone"} $verb $targetName $object_ *"
            sendAsSelf(actionMessage, meshService, myPeerID, onSendMessage)
        } else {
            val systemMessage = BluewhaleMessage(
                sender = "system",
                content = "usage: /${parts[0].removePrefix("/")} <nickname>",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
        }
    }
    
    private fun handleChannelsCommand() {
        val allChannels = channelManager.getJoinedChannelsList()
        val channelList = if (allChannels.isEmpty()) {
            "no channels joined"
        } else {
            "joined channels: ${allChannels.joinToString(", ")}"
        }
        
        val systemMessage = BluewhaleMessage(
            sender = "system",
            content = channelList,
            timestamp = Date(),
            isRelay = false
        )
        messageManager.addMessage(systemMessage)
    }
    
    private fun handleAiCommand(
        parts: List<String>,
        meshService: BluetoothMeshService,
        myPeerID: String,
        onSendMessage: (String, List<String>, String?) -> Unit
    ) {
        val prompt = parts.drop(1).joinToString(" ").trim()
        if (prompt.isEmpty()) {
            postSystemMessage("usage: /ai <prompt>")
            return
        }

        if (llmEngine == null || !llmEngine.isModelInstalled()) {
            val path = llmEngine?.modelPath ?: "the model directory"
            postSystemMessage("no offline model installed. copy a mediapipe .task model to $path")
            return
        }

        // Progress and failures stay on this device; only a successful answer is sent to peers.
        postSystemMessage("ai: thinking…")
        coroutineScope.launch {
            val reply = try {
                llmEngine.complete(prompt)
            } catch (e: Exception) {
                postSystemMessage("ai failed: ${e.message ?: e::class.java.simpleName}")
                return@launch
            }

            if (reply.isBlank()) {
                postSystemMessage("ai returned an empty response.")
                return@launch
            }

            sendAsSelf(formatAiMessage(prompt, reply), meshService, myPeerID, onSendMessage)
        }
    }

    /** Peers cannot tell generated text from typed text, so mark it. */
    private fun formatAiMessage(prompt: String, reply: String): String = "[ai] \"$prompt\": $reply"

    /** Sends content to the open conversation as if the user had typed it. */
    private fun sendAsSelf(
        content: String,
        meshService: BluetoothMeshService,
        myPeerID: String,
        onSendMessage: (String, List<String>, String?) -> Unit
    ) {
        // If we're in a geohash location channel, don't add a local echo here.
        // GeohashViewModel.sendGeohashMessage() will add the local echo with proper metadata.
        val isInLocationChannel = state.selectedLocationChannel.value is com.bluewhale.android.geohash.ChannelID.Location

        if (state.getSelectedPrivateChatPeerValue() != null) {
            val peerID = state.getSelectedPrivateChatPeerValue()!!
            privateChatManager.sendPrivateMessage(
                content,
                peerID,
                getPeerNickname(peerID, meshService),
                state.getNicknameValue(),
                myPeerID
            ) { messageContent, peerIdParam, recipientNicknameParam, messageId ->
                sendPrivateMessageVia(meshService, messageContent, peerIdParam, recipientNicknameParam, messageId)
            }
        } else if (isInLocationChannel) {
            // Let the transport layer add the echo; just send it out
            onSendMessage(content, emptyList(), null)
        } else {
            val message = BluewhaleMessage(
                sender = state.getNicknameValue() ?: myPeerID,
                content = content,
                timestamp = Date(),
                isRelay = false,
                senderPeerID = myPeerID,
                channel = state.getCurrentChannelValue()
            )

            if (state.getCurrentChannelValue() != null) {
                channelManager.addChannelMessage(state.getCurrentChannelValue()!!, message, myPeerID)
                onSendMessage(content, emptyList(), state.getCurrentChannelValue())
            } else {
                messageManager.addMessage(message)
                onSendMessage(content, emptyList(), null)
            }
        }
    }

    /** Posts a local-only system message into whatever conversation is on screen. */
    private fun postSystemMessage(content: String) {
        val message = BluewhaleMessage(
            sender = "system",
            content = content,
            timestamp = Date(),
            isRelay = false
        )

        val privatePeer = state.getSelectedPrivateChatPeerValue()
        val channel = state.getCurrentChannelValue()
        when {
            privatePeer != null -> messageManager.addPrivateMessage(privatePeer, message)
            channel != null -> channelManager.addChannelMessage(channel, message, null)
            else -> messageManager.addMessage(message)
        }
    }

    private fun handleUnknownCommand(cmd: String) {
        val systemMessage = BluewhaleMessage(
            sender = "system",
            content = "unknown command: $cmd. type / to see available commands.",
            timestamp = Date(),
            isRelay = false
        )
        messageManager.addMessage(systemMessage)
    }
    
    // MARK: - Command Autocomplete

    fun updateCommandSuggestions(input: String) {
        if (!input.startsWith("/")) {
            state.setShowCommandSuggestions(false)
            state.setCommandSuggestions(emptyList())
            return
        }
        
        // Get all available commands based on context
        val allCommands = getAllAvailableCommands()
        
        // Filter commands based on input
        val filteredCommands = filterCommands(allCommands, input.lowercase())
        
        if (filteredCommands.isNotEmpty()) {
            state.setCommandSuggestions(filteredCommands)
            state.setShowCommandSuggestions(true)
        } else {
            state.setShowCommandSuggestions(false)
            state.setCommandSuggestions(emptyList())
        }
    }
    
    private fun getAllAvailableCommands(): List<CommandSuggestion> {
        // Add channel-specific commands if in a channel
        val channelCommands = if (state.getCurrentChannelValue() != null) {
            listOf(
                CommandSuggestion("/pass", emptyList(), "[password]", "change channel password"),
                CommandSuggestion("/save", emptyList(), null, "save channel messages locally"),
                CommandSuggestion("/transfer", emptyList(), "<nickname>", "transfer channel ownership")
            )
        } else {
            emptyList()
        }
        
        return baseCommands + channelCommands
    }
    
    private fun filterCommands(commands: List<CommandSuggestion>, input: String): List<CommandSuggestion> {
        return commands.filter { command ->
            // Check primary command
            command.command.startsWith(input) ||
            // Check aliases
            command.aliases.any { it.startsWith(input) }
        }.sortedBy { it.command }
    }
    
    fun selectCommandSuggestion(suggestion: CommandSuggestion): String {
        state.setShowCommandSuggestions(false)
        state.setCommandSuggestions(emptyList())
        return "${suggestion.command} "
    }
    
    // MARK: - Mention Autocomplete
    
    fun updateMentionSuggestions(input: String, meshService: BluetoothMeshService, viewModel: ChatViewModel? = null) {
        // Check if input contains @ and we're at the end of a word or at the end of input
        val atIndex = input.lastIndexOf('@')
        if (atIndex == -1) {
            state.setShowMentionSuggestions(false)
            state.setMentionSuggestions(emptyList())
            return
        }
        
        // Get the text after the @ symbol
        val textAfterAt = input.substring(atIndex + 1)
        
        // If there's a space after @, don't show suggestions
        if (textAfterAt.contains(' ')) {
            state.setShowMentionSuggestions(false)
            state.setMentionSuggestions(emptyList())
            return
        }
        
        // Get peer candidates based on active channel (matches iOS logic exactly)
        val peerCandidates: List<String> = if (viewModel != null) {
            when (val selectedChannel = viewModel.selectedLocationChannel.value) {
                is com.bluewhale.android.geohash.ChannelID.Mesh,
                null -> {
                    // Mesh channel: use Bluetooth mesh peer nicknames
                    meshService.getPeerNicknames().values.filter { it != meshService.getPeerNicknames()[meshService.myPeerID] }
                }
                
                is com.bluewhale.android.geohash.ChannelID.Location -> {
                    // Location channel: use geohash participants with collision-resistant suffixes
                    val geohashPeople = viewModel.geohashPeople.value ?: emptyList()
                    val currentNickname = state.getNicknameValue()
                    
                    geohashPeople.mapNotNull { person ->
                        val displayName = person.displayName
                        // Exclude self from suggestions
                        if (displayName.startsWith("${currentNickname}#")) {
                            null
                        } else {
                            displayName
                        }
                    }
                }
            }
        } else {
            // Fallback to mesh peers if no viewModel available
            meshService.getPeerNicknames().values.filter { it != meshService.getPeerNicknames()[meshService.myPeerID] }
        }
        
        // Filter nicknames based on the text after @
        val filteredNicknames = peerCandidates.filter { nickname ->
            nickname.startsWith(textAfterAt, ignoreCase = true)
        }.sorted()
        
        if (filteredNicknames.isNotEmpty()) {
            state.setMentionSuggestions(filteredNicknames)
            state.setShowMentionSuggestions(true)
        } else {
            state.setShowMentionSuggestions(false)
            state.setMentionSuggestions(emptyList())
        }
    }
    
    fun selectMentionSuggestion(nickname: String, currentText: String): String {
        state.setShowMentionSuggestions(false)
        state.setMentionSuggestions(emptyList())
        
        // Find the last @ symbol position
        val atIndex = currentText.lastIndexOf('@')
        if (atIndex == -1) {
            return "$currentText@$nickname "
        }
        
        // Replace the text from the @ symbol to the end with the mention
        val textBeforeAt = currentText.substring(0, atIndex)
        return "$textBeforeAt@$nickname "
    }
    
    // MARK: - Utility Functions
    
    private fun getPeerIDForNickname(nickname: String, meshService: BluetoothMeshService): String? {
        return meshService.getPeerNicknames().entries.find { it.value == nickname }?.key
    }
    
    private fun getPeerNickname(peerID: String, meshService: BluetoothMeshService): String {
        return meshService.getPeerNicknames()[peerID] ?: peerID
    }
    
    private fun getMyPeerID(meshService: BluetoothMeshService): String {
        return meshService.myPeerID
    }
    
    private fun sendPrivateMessageVia(meshService: BluetoothMeshService, content: String, peerID: String, recipientNickname: String, messageId: String) {
        meshService.sendPrivateMessage(content, peerID, recipientNickname, messageId)
    }
}

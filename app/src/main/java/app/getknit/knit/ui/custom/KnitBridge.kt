@file:Suppress(
    "LargeClass",
    "LongMethod",
    "LongParameterList",
    "CyclomaticComplexMethod",
    "TooManyFunctions",
    "ReturnCount",
    "MagicNumber",
    "TooGenericExceptionCaught",
)

package app.getknit.knit.ui.custom

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import app.getknit.knit.BuildConfig
import app.getknit.knit.call.CallKind
import app.getknit.knit.call.CallManager
import app.getknit.knit.call.CallPhase
import app.getknit.knit.call.CallRefusal
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.AvatarStore
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GallerySaver
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.LocalMessageDelete
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerDirectory
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.VoiceAudio
import app.getknit.knit.data.decodeOrientedBounded
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.groupTitle
import app.getknit.knit.data.group.toGroupInfo
import app.getknit.knit.data.message.ConversationKind
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.PeerRename
import app.getknit.knit.data.message.StatusNotices
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.ui.theme.ThemeMode
import app.getknit.knit.identity.Alias
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.displayNameFor
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.crypto.AttachmentCrypto
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.ui.share.ShareInbox
import app.getknit.knit.ui.signout.SignOut
import app.getknit.knit.ui.voice.VoicePlayer
import app.getknit.knit.ui.voice.VoiceRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class UiUserProfile(
    val nodeId: String,
    val alias: String,
    val name: String,
    val bio: String,
    val avatar: String? = null,
    val openToChat: Boolean = false,
)

@Serializable
data class UiPeer(
    val id: String,
    val name: String,
    val status: String,
    val online: Boolean,
    val avatar: String? = null,
    val verified: Boolean = false,
    val isRoom: Boolean = false,
    val isGroup: Boolean = false,
)

@Serializable
data class UiMessage(
    val id: String,
    val senderId: String,
    val senderName: String = "",
    val ts: Long,
    val text: String,
    val type: String = "text",
    val data: String? = null,
    val attachmentHash: String? = null,
    val blobAvailable: Boolean = false,
    val fileName: String? = null,
    val fileSize: Long = 0,
    val duration: Int = 0,
    val viewOnce: Boolean = false,
    val viewed: Boolean = false,
    val isTiny: Boolean = false,
    val tinyScale: Int = 80,
    val replyTo: String? = null,
    val pending: Boolean = false,
    val delivered: Boolean = false,
    val read: Boolean = false,
    val failed: Boolean = false,
    val reactions: List<String> = emptyList(),
)

@Serializable
data class UiConversation(
    val id: String,
    val contactId: String,
    val title: String = "",
    val isGroup: Boolean = false,
    val isRoom: Boolean = false,
    val messages: List<UiMessage>,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val muted: Boolean = false,
    val unread: Int = 0,
)

@Serializable
data class UiInitialData(
    val currentUser: UiUserProfile,
    val contacts: List<UiPeer>,
    val conversations: List<UiConversation>,
    val settings: Map<String, String> = emptyMap(),
    val activeConversationId: String? = null,
    val forceOpenConversationId: String? = null,
    val requestCount: Int = 0,
    val meshEnabled: Boolean = true,
)

/**
 * Full bidirectional bridge between the Custom HTML/JS UI and Knit's authentic P2P Mesh Engine.
 * Preserves 100% of Knit's database, mesh, relay, LoRa, attachment, group, reaction, voice, and call capabilities.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KnitBridge(
    private val context: Context,
    private val webView: WebView,
    private val identity: Identity,
    private val settingsStore: SettingsStore,
    private val messageRepository: MessageRepository,
    private val peerRepository: PeerRepository,
    private val groupRepository: GroupRepository,
    private val reactionRepository: ReactionRepository,
    private val meshController: MeshController,
    private val attachmentStore: AttachmentStore,
    private val avatarStore: AvatarStore,
    private val blobs: BlobRepository,
    private val localDelete: LocalMessageDelete,
    private val gallerySaver: GallerySaver? = null,
    private val shareInbox: ShareInbox? = null,
    private val callManager: CallManager? = null,
    private val initialConversationId: String? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private val voiceRecorder = VoiceRecorder(context, scope)
    private val voicePlayer = VoicePlayer(context, blobs)
    @Volatile
    private var isDestroyed = false

    @Volatile
    private var pageLoaded = false

    @Volatile
    private var jsReady = false

    @Volatile
    private var activeConversationIdState: String = initialConversationId ?: Conversations.NEARBY

    @Volatile
    private var shouldOpenMobileChat: Boolean = !initialConversationId.isNullOrBlank()

    private var meshStatusJob: Job? = null
    private var peerDirectoryJob: Job? = null
    private var conversationsWatchJob: Job? = null
    private var typingWatchJob: Job? = null
    private var arrivalsWatchJob: Job? = null
    private val conversationMessageJobs = ConcurrentHashMap<String, Job>()
    private val knownMessageStates = ConcurrentHashMap<String, MessageDeliverySnapshot>()

    data class StagedAttachment(
        val uri: Uri,
        val name: String,
        val size: Long,
        val mime: String,
    )

    private val stagedAttachments = ConcurrentHashMap<String, StagedAttachment>()

    // Callbacks wired by CustomMessengerScreen to KnitApp's NavHost
    var onOpenPicker: ((String) -> Unit)? = null
    var onNavigateRoute: ((String, String?) -> Unit)? = null
    var onRequestAudioPermission: (() -> Unit)? = null

    private data class MessageDeliverySnapshot(
        val pending: Boolean,
        val delivered: Boolean,
        val read: Boolean = false,
        val failed: Boolean,
        val blobAvailable: Boolean = false,
        val hasBlobData: Boolean = false,
        val reactionsKey: String = "",
    )

    init {
        debugLog("KnitBridge init (initialConversationId=$initialConversationId)")
        startObservingMeshEvents()
    }

    private fun debugLog(msg: String) {
        if (BuildConfig.DEBUG) {
            Log.d(KNIT_UI_TAG, msg)
        }
    }

    fun onPageReady() {
        pageLoaded = true
        val target = if (shouldOpenMobileChat) activeConversationIdState else null
        debugLog("onPageReady -> shouldOpenMobileChat=$shouldOpenMobileChat target=$target")
        getInitialDataInternal(forceOpenId = target)
    }

    fun onResumeFromKotlin() {
        if (!jsReady) {
            debugLog("onResumeFromKotlin skipped because jsReady=false")
            return
        }
        val target = if (shouldOpenMobileChat) activeConversationIdState else null
        debugLog("onResumeFromKotlin -> shouldOpenMobileChat=$shouldOpenMobileChat target=$target")
        getInitialDataInternal(forceOpenId = target)
    }

    fun openConversationFromNative(conversationId: String) {
        if (conversationId.isBlank()) return
        debugLog("[NATIVE->KT] openConversationFromNative(conversationId=$conversationId)")
        activeConversationIdState = conversationId
        shouldOpenMobileChat = true
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { settingsStore.accept(conversationId) }
                if (Conversations.kindFor(conversationId) == ConversationKind.DM) {
                    runCatching { meshController.importContact(conversationId) }
                }
            }
            debugLog("[KT->ENGINE] accept & importContact completed for $conversationId")
            if (!conversationMessageJobs.containsKey(conversationId)) {
                attachConversationCollector(conversationId)
            }
            markConversationRead(conversationId)

            val shared = shareInbox?.consume()
            if (shared != null) {
                val uriStr = shared.imageUri ?: shared.fileUri
                if (!uriStr.isNullOrBlank()) {
                    runCatching { handleSelectedUri(uriStr.toUri()) }
                }
            }

            if (pageLoaded || jsReady) {
                getInitialDataInternal(forceOpenId = conversationId)
                debugLog("[KT->JS] firing onOpenConversation($conversationId)")
                evaluateJs(
                    "if (window.KnitBridge && window.KnitBridge.onOpenConversation) " +
                        "window.KnitBridge.onOpenConversation('${escapeJs(conversationId)}');",
                )
            }
        }
    }

    @JavascriptInterface
    fun getInitialData() {
        jsReady = true
        val target = if (shouldOpenMobileChat) activeConversationIdState else null
        debugLog("[JS->KT] getInitialData() shouldOpenMobileChat=$shouldOpenMobileChat target=$target")
        getInitialDataInternal(forceOpenId = target)
    }

    @JavascriptInterface
    fun onMobileChatClosed() {
        debugLog("[JS->KT] onMobileChatClosed()")
        shouldOpenMobileChat = false
    }

    @JavascriptInterface
    fun startOrOpenConversation(peerId: String) {
        if (peerId.isBlank()) return
        debugLog("[JS->KT] startOrOpenConversation(peerId=$peerId)")
        activeConversationIdState = peerId
        shouldOpenMobileChat = true
        scope.launch {
            withContext(Dispatchers.IO) {
                if (peerId != Conversations.NEARBY) {
                    runCatching { settingsStore.accept(peerId) }
                }
                if (Conversations.kindFor(peerId) == ConversationKind.DM) {
                    runCatching { meshController.importContact(peerId) }
                }
            }
            debugLog("[KT->ENGINE] startOrOpenConversation accept+importContact done for $peerId")
            if (!conversationMessageJobs.containsKey(peerId)) {
                attachConversationCollector(peerId)
            }
            markConversationRead(peerId)
            getInitialDataInternal(forceOpenId = peerId)
            debugLog("[KT->JS] firing onOpenConversation($peerId)")
            evaluateJs(
                "if (window.KnitBridge && window.KnitBridge.onOpenConversation) " +
                    "window.KnitBridge.onOpenConversation('${escapeJs(peerId)}');",
            )
        }
    }

    private fun getInitialDataInternal(forceOpenId: String? = null) {
        scope.launch {
            if (isDestroyed) return@launch
            try {
                val myNodeId = identity.nodeId()
                val myAlias = Alias.aliasFor(myNodeId)
                val storedName = withContext(Dispatchers.IO) { settingsStore.displayName.first() }
                val myName = displayNameFor(storedName, myNodeId)
                val myStatus = withContext(Dispatchers.IO) { settingsStore.status.first() }
                val openToChat = withContext(Dispatchers.IO) { settingsStore.openToChat.first() }
                val myAvatarHash = withContext(Dispatchers.IO) { settingsStore.ownAvatarHash.first() }
                val myAvatarBytes = withContext(Dispatchers.IO) { myAvatarHash?.let { blobs.bytes(it) } }
                val myAvatarB64 =
                    myAvatarBytes?.let {
                        "data:image/jpeg;base64," + Base64.encodeToString(it, Base64.NO_WRAP)
                    }

                val currentUser =
                    UiUserProfile(
                        nodeId = myNodeId,
                        alias = myAlias,
                        name = myName,
                        bio = myStatus,
                        avatar = myAvatarB64,
                        openToChat = openToChat,
                    )

                val dir = withContext(Dispatchers.IO) { peerRepository.observeDirectory().first() }
                val onlineNeighbors = meshController.neighbors.value.map { it.nodeId }.toSet()
                val reachablePeers = meshController.reachable.value.map { it.nodeId }.toSet()
                val allLivePeerIds = onlineNeighbors + reachablePeers

                val blockedIds = withContext(Dispatchers.IO) { settingsStore.blockedNodeIds.first() }
                val acceptedIds = withContext(Dispatchers.IO) { settingsStore.acceptedConversations.first() }
                val lastReadMap = withContext(Dispatchers.IO) { settingsStore.lastReadAll.first() }
                val activeGroups = withContext(Dispatchers.IO) { groupRepository.active() }
                val groupsById = activeGroups.associateBy { it.groupId }
                val authoredConvs =
                    withContext(Dispatchers.IO) {
                        messageRepository.observeConversationsIAuthoredIn(myNodeId).first().toSet()
                    }
                val groupSenders =
                    withContext(Dispatchers.IO) {
                        messageRepository.observeGroupSenders(blockedIds).first()
                    }
                val verifiedIds = dir.peers.filter { it.verified }.map { it.nodeId }.toSet()

                val uiContacts =
                    buildUiContactsList(
                        myNodeId = myNodeId,
                        dir = dir,
                        onlineNeighbors = allLivePeerIds,
                        blockedIds = blockedIds,
                        activeGroups = activeGroups,
                        acceptedIds = acceptedIds,
                        verifiedIds = verifiedIds,
                        authoredConvs = authoredConvs,
                        groupSenders = groupSenders,
                    )

                // Determine visible conversations vs pending message requests.
                // Include acceptedIds, verifiedIds, and allLivePeerIds in candidateConvs so newly started 1:1 DMs
                // and accepted/verified/discovered contacts persist in the conversation list even at 0 messages.
                val allDbConvs =
                    withContext(Dispatchers.IO) {
                        messageRepository.observeConversations(blockedIds).first().toSet()
                    }
                val candidateConvs =
                    (
                        listOf(Conversations.NEARBY) +
                            activeGroups.map { it.groupId } +
                            allDbConvs +
                            acceptedIds +
                            verifiedIds +
                            allLivePeerIds
                    ).distinct()

                var pendingRequestsCount = 0
                val visibleConvIds = mutableListOf<String>()

                for (cid in candidateConvs) {
                    if (cid.isBlank() || cid == myNodeId || cid in blockedIds) continue
                    val kind = Conversations.kindFor(cid)
                    if (kind == ConversationKind.GROUP && cid !in groupsById) continue

                    val accepted =
                        Conversations.isAccepted(
                            conversationId = cid,
                            accepted = acceptedIds,
                            verifiedNodeIds = verifiedIds,
                            authoredConversationIds = authoredConvs,
                            groupSenders = groupSenders[cid].orEmpty(),
                        ) || (kind == ConversationKind.DM && cid in allLivePeerIds)
                    if (accepted) {
                        visibleConvIds.add(cid)
                    } else if (cid in allDbConvs || cid in groupsById) {
                        pendingRequestsCount++
                    }
                }

                // Ensure activeConversationIdState / forceOpenId is always in visibleConvIds and contacts
                val explicitOpenId = forceOpenId ?: (if (shouldOpenMobileChat) activeConversationIdState else initialConversationId)
                if (!explicitOpenId.isNullOrBlank() && explicitOpenId !in visibleConvIds) {
                    visibleConvIds.add(explicitOpenId)
                    if (uiContacts.none { it.id == explicitOpenId }) {
                        val grp = groupsById[explicitOpenId]
                        if (grp != null) {
                            val memberIds = GroupMembersStore.decode(grp.members)
                            val title = groupTitle(grp.name, memberIds, myNodeId, "مجموعة") { id -> dir.label(id).text }
                            uiContacts.add(
                                UiPeer(
                                    id = grp.groupId,
                                    name = title,
                                    status = "مجموعة (${memberIds.size} أعضاء)",
                                    online = true,
                                    isGroup = true,
                                ),
                            )
                        } else {
                            val label = dir.label(explicitOpenId).text.ifBlank { Alias.aliasFor(explicitOpenId) }
                            uiContacts.add(
                                UiPeer(
                                    id = explicitOpenId,
                                    name = label,
                                    status = "",
                                    online = explicitOpenId in allLivePeerIds,
                                ),
                            )
                        }
                    }
                }

                val uiConversations = mutableListOf<UiConversation>()
                for (convId in visibleConvIds) {
                    if (!conversationMessageJobs.containsKey(convId)) {
                        attachConversationCollector(convId)
                    }

                    val messages =
                        withContext(Dispatchers.IO) {
                            messageRepository.observeNewestMessages(convId, 60).first()
                        }
                    val reactionsInConv =
                        withContext(Dispatchers.IO) {
                            reactionRepository.observeReactionsIn(convId).first()
                        }.groupBy { it.messageId }

                    val maxReadSentAt = messages.filter { m -> m.senderId == myNodeId && reactionsInConv[m.id]?.any { r -> r.emoji == "\u2063READ\u2063" && r.reactorNodeId != myNodeId } == true }.maxOfOrNull { it.sentAt } ?: -1L
                    val uiMsgs =
                        messages
                            .sortedBy { if (it.senderId == myNodeId) it.sentAt else (it.arrivedAt ?: it.sentAt) }
                            .mapNotNull { m ->
                                val rList = reactionsInConv[m.id]?.mapNotNull { it.emoji }?.filter { it != "\u2063READ\u2063" } ?: emptyList()
                                m.toUiMessageOrNull(myNodeId, dir, blobs, rList, isReadByPeer = (m.senderId == myNodeId && m.sentAt <= maxReadSentAt))
                            }

                    for (m in uiMsgs) {
                        knownMessageStates[m.id] =
                            MessageDeliverySnapshot(
                                pending = m.pending,
                                delivered = m.delivered,
                                read = m.read,
                                failed = m.failed,
                                blobAvailable = m.blobAvailable,
                                hasBlobData = m.data != null,
                                reactionsKey = m.reactions.joinToString(","),
                            )
                    }

                    val sinceWatermark = lastReadMap[convId] ?: 0L
                    val unreadCount =
                        withContext(Dispatchers.IO) {
                            if (convId == Conversations.NEARBY) {
                                0
                            } else {
                                messageRepository.countUnreadIn(convId, sinceWatermark, myNodeId, blockedIds)
                            }
                        }

                    val kind = Conversations.kindFor(convId)
                    val title =
                        when (kind) {
                            ConversationKind.NEARBY -> "غرفة الأجهزة المجاورة (Nearby)"
                            ConversationKind.GROUP -> {
                                val g = groupsById[convId]
                                val memberIds = g?.let { GroupMembersStore.decode(it.members) }.orEmpty()
                                groupTitle(g?.name.orEmpty(), memberIds, myNodeId, "مجموعة") { id -> dir.label(id).text }
                            }
                            else -> dir.label(convId).text.ifBlank { Alias.aliasFor(convId) }
                        }

                    uiConversations.add(
                        UiConversation(
                            id = convId,
                            contactId = convId,
                            title = title,
                            isGroup = (kind == ConversationKind.GROUP),
                            isRoom = (kind == ConversationKind.NEARBY || kind == ConversationKind.MESHTASTIC),
                            messages = uiMsgs,
                            pinned = (convId == Conversations.NEARBY),
                            unread = unreadCount,
                        ),
                    )
                }

                val themeMode = withContext(Dispatchers.IO) { settingsStore.themeMode.first() }
                val meshEnabled = withContext(Dispatchers.IO) { settingsStore.meshEnabled.first() }
                val typingEnabled = withContext(Dispatchers.IO) { settingsStore.typingIndicatorsEnabled.first() }
                val contentFiltering = withContext(Dispatchers.IO) { settingsStore.contentFilteringEnabled.first() }

                val settingsMap =
                    mapOf(
                        "theme" to (if (themeMode == ThemeMode.Dark) "dark" else "light"),
                        "meshEnabled" to meshEnabled.toString(),
                        "typingIndicators" to typingEnabled.toString(),
                        "contentFiltering" to contentFiltering.toString(),
                        "openToChat" to openToChat.toString(),
                    )

                val targetActiveId = explicitOpenId ?: activeConversationIdState.ifBlank { Conversations.NEARBY }
                if (!explicitOpenId.isNullOrBlank()) {
                    markConversationRead(explicitOpenId)
                }

                val initialData =
                    UiInitialData(
                        currentUser = currentUser,
                        contacts = uiContacts,
                        conversations = uiConversations,
                        settings = settingsMap,
                        activeConversationId = targetActiveId,
                        forceOpenConversationId = explicitOpenId,
                        requestCount = pendingRequestsCount,
                        meshEnabled = meshEnabled,
                    )

                debugLog(
                    "[KT->JS] onInitialData: contacts=${uiContacts.size} convs=${uiConversations.size} " +
                        "active=$targetActiveId forceOpen=$explicitOpenId requests=$pendingRequestsCount",
                )
                val jsonStr = json.encodeToString(initialData)
                evaluateJs("if (window.KnitBridge) window.KnitBridge.onInitialData($jsonStr);")
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(KNIT_UI_TAG, "Error constructing initial data", e)
                }
            }
        }
    }

    private suspend fun buildUiContactsList(
        myNodeId: String,
        dir: PeerDirectory,
        onlineNeighbors: Set<String>,
        blockedIds: Set<String>,
        activeGroups: List<GroupEntity>,
        acceptedIds: Set<String>,
        verifiedIds: Set<String>,
        authoredConvs: Set<String>,
        groupSenders: Map<String, Set<String>>,
    ): MutableList<UiPeer> {
        val uiContacts = mutableListOf<UiPeer>()
        // 1. Public Nearby room
        uiContacts.add(
            UiPeer(
                id = Conversations.NEARBY,
                name = "غرفة الأجهزة المجاورة (Nearby)",
                status = "البث العام لجميع الأجهزة المجاورة",
                online = true,
                isRoom = true,
            ),
        )

        // 2. Active accepted Groups
        for (g in activeGroups) {
            val isAcceptedGroup =
                Conversations.isAccepted(
                    g.groupId,
                    acceptedIds,
                    verifiedIds,
                    authoredConvs,
                    groupSenders[g.groupId].orEmpty(),
                )
            if (isAcceptedGroup) {
                val memberIds = GroupMembersStore.decode(g.members)
                val title =
                    groupTitle(
                        storedName = g.name,
                        memberIds = memberIds,
                        selfId = myNodeId,
                        fallback = "مجموعة (${memberIds.size})",
                    ) { id -> dir.label(id).text }
                val groupAvatarB64 =
                    g.photoShownHash?.let { h ->
                        withContext(Dispatchers.IO) { blobs.bytes(h) }?.let { b ->
                            "data:image/jpeg;base64," + Base64.encodeToString(b, Base64.NO_WRAP)
                        }
                    }
                val onlineInGroup = memberIds.count { it != myNodeId && it in onlineNeighbors }
                val statusSuffix = if (onlineInGroup > 0) " • $onlineInGroup متصل" else ""
                uiContacts.add(
                    UiPeer(
                        id = g.groupId,
                        name = title,
                        status = "مجموعة (${memberIds.size} أعضاء$statusSuffix)",
                        online = onlineInGroup > 0,
                        avatar = groupAvatarB64,
                        isGroup = true,
                    ),
                )
            }
        }

        // 3. Discovered & stored Peers in PeerRepository
        val addedPeerIds = mutableSetOf<String>()
        for (p in dir.peers) {
            if (p.nodeId != myNodeId && p.nodeId !in blockedIds) {
                addedPeerIds.add(p.nodeId)
                val label = dir.label(p.nodeId).text.ifBlank { displayNameFor(p.name, p.nodeId) }
                val avatarB64 =
                    p.avatarHash?.let { h ->
                        withContext(Dispatchers.IO) { blobs.bytes(h) }?.let { b ->
                            "data:image/jpeg;base64," + Base64.encodeToString(b, Base64.NO_WRAP)
                        }
                    }
                val isOnlineNow = p.nodeId in onlineNeighbors
                uiContacts.add(
                    UiPeer(
                        id = p.nodeId,
                        name = label,
                        status =
                            p.status.ifBlank {
                                when {
                                    isOnlineNow -> "متصل في الجوار الآن"
                                    p.verified -> "جهة اتصال موثقة"
                                    else -> ""
                                }
                            },
                        online = isOnlineNow || p.openToChat,
                        avatar = avatarB64,
                        verified = p.verified,
                    ),
                )
            }
        }

        // 4. Freshly discovered radio neighbors whose profile frame is still in flight (not yet in dir.peers)
        for (neighborId in onlineNeighbors) {
            if (neighborId != myNodeId && neighborId !in blockedIds && neighborId !in addedPeerIds) {
                addedPeerIds.add(neighborId)
                val aliasLabel = Alias.aliasFor(neighborId).ifBlank { displayNameFor("", neighborId) }
                uiContacts.add(
                    UiPeer(
                        id = neighborId,
                        name = aliasLabel,
                        status = "جهاز قريب مكتشف الآن (متصل)",
                        online = true,
                        verified = false,
                    ),
                )
            }
        }

        return uiContacts
    }

    @JavascriptInterface
    fun navigateToNativeScreen(
        screen: String,
        param: String,
    ) {
        debugLog("[JS->KT] navigateToNativeScreen(screen=$screen, param=$param)")
        mainHandler.post {
            onNavigateRoute?.invoke(screen, param.takeIf { it.isNotBlank() })
        }
    }

    @JavascriptInterface
    fun markConversationRead(conversationId: String) {
        if (conversationId.isBlank()) return
        scope.launch(Dispatchers.IO) {
            try {
                val newest = messageRepository.newestSentAt(conversationId) ?: System.currentTimeMillis()
                settingsStore.setLastReadAt(conversationId, maxOf(newest, System.currentTimeMillis()))
                val myNodeId = identity.nodeId(); val msgs = messageRepository.observeNewestMessages(conversationId, 30).first(); val latestIncoming = msgs.filter { it.senderId != myNodeId }.maxByOrNull { it.sentAt }; if (latestIncoming != null && Conversations.kindFor(conversationId) == ConversationKind.DM) { val existingReactions = reactionRepository.observeReactionsIn(conversationId).first(); val alreadyAcked = existingReactions.any { it.messageId == latestIncoming.id && it.reactorNodeId == myNodeId && it.emoji == "\u2063READ\u2063" }; if (!alreadyAcked) { meshController.sendReaction(messageId = latestIncoming.id, emoji = "\u2063READ\u2063", recipientId = conversationId, group = null) } }
            } catch (e: Exception) {
                Log.e(KNIT_UI_TAG, "Error marking conversation read", e)
            }
        }
    }

    @JavascriptInterface
    fun sendTypingIndicator(conversationId: String) {
        if (conversationId.isBlank()) return
        scope.launch(Dispatchers.IO) {
            runCatching { meshController.sendTyping(conversationId) }
        }
    }

    @JavascriptInterface
    fun acceptMessageRequest(conversationId: String) {
        if (conversationId.isBlank()) return
        debugLog("[JS->KT] acceptMessageRequest($conversationId)")
        scope.launch(Dispatchers.IO) {
            runCatching {
                settingsStore.accept(conversationId)
                if (Conversations.kindFor(conversationId) == ConversationKind.DM) {
                    meshController.importContact(conversationId)
                }
                getInitialDataInternal(forceOpenId = conversationId)
            }
        }
    }

    @JavascriptInterface
    fun createGroupChat(
        memberIdsJson: String,
        groupName: String,
    ) {
        debugLog("[JS->KT] createGroupChat(members=$memberIdsJson, name=$groupName)")
        scope.launch(Dispatchers.IO) {
            try {
                val selectedIds =
                    json
                        .decodeFromString<List<String>>(memberIdsJson)
                        .filter { it.isNotBlank() && it != Conversations.NEARBY && !it.startsWith("g-") }
                if (selectedIds.isEmpty()) {
                    evaluateJs("if (window.KnitBridge) window.KnitBridge.onAttachmentError('اختر عضواً واحداً على الأقل لإنشاء المجموعة');")
                    return@launch
                }
                val me = identity.nodeId()
                val members = (selectedIds + me).distinct().take(8)
                val groupId = Conversations.groupIdFor(members)
                val existing = groupRepository.find(groupId)
                val now = System.currentTimeMillis()
                val cleanName = groupName.trim()

                val entity =
                    if (existing == null || existing.left) {
                        val created =
                            GroupEntity(
                                groupId = groupId,
                                name = cleanName,
                                members = GroupMembersStore.encode(members),
                                createdBy = me,
                                createdAt = now,
                                nameUpdatedAt = if (cleanName.isNotBlank()) now else 0L,
                                left = false,
                            )
                        groupRepository.upsert(created)
                        messageRepository.save(StatusNotices.groupCreated(groupId, me, now))
                        created
                    } else if (cleanName.isNotBlank() && existing.name != cleanName) {
                        val updated = existing.copy(name = cleanName, nameUpdatedAt = now)
                        groupRepository.upsert(updated)
                        messageRepository.save(StatusNotices.groupRenamed(groupId, me, cleanName, now))
                        updated
                    } else {
                        existing
                    }

                activeConversationIdState = groupId
                shouldOpenMobileChat = true
                settingsStore.accept(groupId)
                meshController.mintGroupRoots()
                runCatching { meshController.sendGroupUpdate(entity.toGroupInfo()) }

                debugLog("[KT->ENGINE] Group created/updated groupId=$groupId members=${members.size}")
                withContext(Dispatchers.Main) {
                    if (!conversationMessageJobs.containsKey(groupId)) {
                        attachConversationCollector(groupId)
                    }
                    getInitialDataInternal(forceOpenId = groupId)
                    evaluateJs(
                        "if (window.KnitBridge && window.KnitBridge.onGroupCreated) " +
                            "window.KnitBridge.onGroupCreated('${escapeJs(groupId)}');",
                    )
                }
            } catch (e: Exception) {
                evaluateJs("if (window.KnitBridge) window.KnitBridge.onAttachmentError('تعذر إنشاء المجموعة: ${escapeJs(e.message ?: "")}');")
            }
        }
    }

    @JavascriptInterface
    fun clearConversationMessages(conversationId: String) {
        if (conversationId.isBlank()) return
        debugLog("[JS->KT] clearConversationMessages($conversationId)")
        scope.launch(Dispatchers.IO) {
            try {
                messageRepository.deleteByConversation(conversationId)
                getInitialDataInternal()
            } catch (e: Exception) {
                Log.e(KNIT_UI_TAG, "Error clearing conversation messages", e)
            }
        }
    }

    @JavascriptInterface
    fun deleteConversationThread(conversationId: String) {
        if (conversationId.isBlank()) return
        debugLog("[JS->KT] deleteConversationThread($conversationId)")
        if (activeConversationIdState == conversationId) {
            activeConversationIdState = Conversations.NEARBY
            shouldOpenMobileChat = false
        }
        scope.launch(Dispatchers.IO) {
            try {
                when (Conversations.kindFor(conversationId)) {
                    ConversationKind.GROUP -> groupRepository.delete(conversationId)
                    else -> {
                        messageRepository.deleteByConversation(conversationId)
                        runCatching { settingsStore.unaccept(conversationId) }
                    }
                }
                getInitialDataInternal()
            } catch (e: Exception) {
                Log.e(KNIT_UI_TAG, "Error deleting conversation", e)
            }
        }
    }

    @JavascriptInterface
    fun setNativeSetting(
        key: String,
        value: String,
    ) {
        debugLog("[JS->KT] setNativeSetting(key=$key, value=$value)")
        scope.launch(Dispatchers.IO) {
            try {
                when (key) {
                    "theme" -> {
                        val mode = if (value == "dark") ThemeMode.Dark else ThemeMode.Light
                        settingsStore.setThemeMode(mode)
                        mainHandler.post {
                            val isDark = value == "dark"
                            (context as? android.app.Activity)?.window?.let { win ->
                                val barColor = if (isDark) android.graphics.Color.parseColor("#0F172A") else android.graphics.Color.WHITE
                                win.statusBarColor = barColor
                                win.navigationBarColor = barColor
                                androidx.core.view.WindowCompat.getInsetsController(win, win.decorView).apply {
                                    isAppearanceLightStatusBars = !isDark
                                    isAppearanceLightNavigationBars = !isDark
                                }
                            }
                        }
                    }
                    "openToChat" -> {
                        settingsStore.setOpenToChat(value.toBoolean())
                    }
                    "meshEnabled" -> {
                        settingsStore.setMeshEnabled(value.toBoolean())
                    }
                    "typingIndicators" -> {
                        settingsStore.setTypingIndicatorsEnabled(value.toBoolean())
                    }
                    "contentFiltering" -> {
                        settingsStore.setContentFilteringEnabled(value.toBoolean())
                    }
                }
            } catch (e: Exception) {
                Log.e(KNIT_UI_TAG, "Error updating setting $key", e)
            }
        }
    }

    @JavascriptInterface
    fun signOutAndWipe() {
        debugLog("[JS->KT] signOutAndWipe()")
        mainHandler.post {
            SignOut.here(context)
        }
    }

    @JavascriptInterface
    fun sendTextMessage(
        conversationId: String,
        text: String,
        replyTo: String,
        isTiny: Boolean,
        tinyScale: Int,
    ) {
        if (text.isBlank() || conversationId.isBlank()) return
        debugLog("[JS->KT] sendTextMessage(conv=$conversationId, len=${text.length}, replyTo=$replyTo, isTiny=$isTiny, scale=$tinyScale)")
        scope.launch(Dispatchers.IO) {
            try {
                val kind = Conversations.kindFor(conversationId)
                val groupInfo =
                    if (kind == ConversationKind.GROUP) {
                        groupRepository.find(conversationId)?.toGroupInfo()
                    } else {
                        null
                    }
                val recipientId = if (kind == ConversationKind.DM) conversationId else null

                if (kind == ConversationKind.DM || kind == ConversationKind.GROUP) {
                    settingsStore.accept(conversationId)
                }
                if (kind == ConversationKind.DM) {
                    runCatching { meshController.importContact(conversationId) }
                }
                if (!conversationMessageJobs.containsKey(conversationId)) {
                    withContext(Dispatchers.Main) { attachConversationCollector(conversationId) }
                }

                val replyRef =
                    if (replyTo.isNotBlank()) {
                        messageRepository.find(replyTo)?.let { original ->
                            ReplyRef(
                                messageId = original.id,
                                authorId = original.senderId,
                                author = original.replyToAuthor.orEmpty(),
                                snippet = original.body.take(100),
                                hasAttachment = original.attachmentHash != null,
                            )
                        }
                    } else {
                        null
                    }
                val cleanText = text.trim()
                val safeScale = tinyScale.coerceIn(1, 100)
                val payloadText = if (isTiny) "\u2063TINY:$safeScale\u2063$cleanText" else cleanText
                
                val sent =
                    meshController.sendChat(
                        text = payloadText,
                        recipientId = recipientId,
                        group = groupInfo,
                        replyTo = replyRef,
                    )
                debugLog("[KT->ENGINE] meshController.sendChat(conv=$conversationId) -> sent=$sent")
                if (!sent) {
                    evaluateJs(
                        "if (window.KnitBridge) window.KnitBridge.onMessageSendFailed(" +
                            "'${escapeJs(conversationId)}', 'تم رفض الرسالة بواسطة فاحص المحتوى أو تعذر إرسالها');",
                    )
                }
            } catch (e: Exception) {
                Log.e(KNIT_UI_TAG, "sendTextMessage failed", e)
                evaluateJs(
                    "if (window.KnitBridge) window.KnitBridge.onMessageSendFailed(" +
                        "'${escapeJs(conversationId)}', 'فشل الإرسال: ${escapeJs(e.message ?: "")}');",
                )
            }
        }
    }

    @JavascriptInterface
    fun openFilePicker(category: String) {
        debugLog("[JS->KT] openFilePicker(category=$category)")
        mainHandler.post {
            onOpenPicker?.invoke(category)
        }
    }

    fun handleSelectedUri(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            try {
                val contentResolver = context.contentResolver
                val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                var name = "ملف"
                var size = 0L

                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                        if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                    }
                }

                if (size > MAX_ATTACHMENT_BYTES) {
                    debugLog("[KT->JS] File rejected (>8MB): size=$size name=$name")
                    evaluateJs(
                        "if (window.KnitBridge) window.KnitBridge.onAttachmentError(" +
                            "'حجم الملف (${size / (1024 * 1024)}MB) يتجاوز الحد الأقصى المسموح به لشبكة الراديو في Knit (8MB)');",
                    )
                    return@launch
                }

                val token = "staged_" + UUID.randomUUID().toString()
                stagedAttachments[token] = StagedAttachment(uri, name, size, mime)

                var thumbBase64 = ""
                if (mime.startsWith("image/")) {
                    runCatching {
                        val bmp = decodeOrientedBounded(context, uri, THUMB_MAX_DIM)
                        if (bmp != null) {
                            val out = ByteArrayOutputStream()
                            bmp.compress(Bitmap.CompressFormat.JPEG, THUMB_JPEG_QUALITY, out)
                            thumbBase64 = "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                        }
                    }
                }

                debugLog("[KT->JS] onAttachmentStaged(token=$token, name=$name, size=$size, mime=$mime)")
                evaluateJs(
                    "if (window.KnitBridge) window.KnitBridge.onAttachmentStaged(" +
                        "'$token', '${escapeJs(name)}', $size, '${escapeJs(mime)}', '$thumbBase64');",
                )
            } catch (e: Exception) {
                evaluateJs("if (window.KnitBridge) window.KnitBridge.onAttachmentError('تعذر فتح الملف المختار: ${escapeJs(e.message ?: "")}');")
            }
        }
    }

    fun handlePickerCancelled() {
        evaluateJs("if (window.KnitBridge) window.KnitBridge.onAttachmentCancelled();")
    }

    @JavascriptInterface
    fun sendStagedAttachment(
        conversationId: String,
        token: String,
        replyTo: String,
        viewOnce: Boolean,
    ) {
        debugLog("[JS->KT] sendStagedAttachment(conv=$conversationId, token=$token, viewOnce=$viewOnce)")
        val staged =
            stagedAttachments.remove(token) ?: run {
                evaluateJs("if (window.KnitBridge) window.KnitBridge.onAttachmentError('المرفق غير متوفر أو تم إلغاؤه');")
                return
            }

        scope.launch(Dispatchers.IO) {
            try {
                val kind = Conversations.kindFor(conversationId)
                val groupInfo =
                    if (kind == ConversationKind.GROUP) {
                        groupRepository.find(conversationId)?.toGroupInfo()
                    } else {
                        null
                    }
                val recipientId = if (kind == ConversationKind.DM) conversationId else null

                if (kind == ConversationKind.DM || kind == ConversationKind.GROUP) {
                    settingsStore.accept(conversationId)
                }
                if (kind == ConversationKind.DM) {
                    runCatching { meshController.importContact(conversationId) }
                }
                if (kind == ConversationKind.NEARBY && !staged.mime.startsWith("image/")) {
                    evaluateJs(
                        "if (window.KnitBridge) window.KnitBridge.onAttachmentError(" +
                            "'غرفة Nearby العامة تدعم إرسال الصور والرسائل النصية فقط؛ أرسل الملفات في محادثة خاصة أو مجموعة');",
                    )
                    return@launch
                }

                val replyRef =
                    if (replyTo.isNotBlank()) {
                        messageRepository.find(replyTo)?.let { original ->
                            ReplyRef(
                                messageId = original.id,
                                authorId = original.senderId,
                                author = original.replyToAuthor.orEmpty(),
                                snippet = original.body.take(100),
                                hasAttachment = original.attachmentHash != null,
                            )
                        }
                    } else {
                        null
                    }

                val ingestResult =
                    when {
                        staged.mime.startsWith("image/") -> attachmentStore.ingest(staged.uri, screen = false)
                        staged.mime.startsWith("video/") -> attachmentStore.ingestVideo(staged.uri, screen = false)
                        else -> attachmentStore.ingestFile(staged.uri, screen = false)
                    }

                if (ingestResult is AttachmentStore.IngestResult.Success) {
                    val sent =
                        meshController.sendChat(
                            text = "",
                            attachment = ingestResult.ingested,
                            recipientId = recipientId,
                            group = groupInfo,
                            replyTo = replyRef,
                        )
                    debugLog("[KT->ENGINE] sendStagedAttachment sendChat -> sent=$sent hash=${ingestResult.ingested.hash}")
                    if (!sent) {
                        evaluateJs("if (window.KnitBridge) window.KnitBridge.onAttachmentError('تم رفض المرفق بواسطة فاحص المحتوى أو تعذر إرساله');")
                    }
                } else if (ingestResult is AttachmentStore.IngestResult.Failed) {
                    val err =
                        when (ingestResult.reason) {
                            AttachmentStore.IngestResult.Reason.TooLarge -> "حجم الملف يتجاوز الحد المسموح به لشبكة الراديو (8MB)"
                            AttachmentStore.IngestResult.Reason.Installable -> "ملفات التثبيت (APK) محظورة في بروتوكول Knit لأسباب أمنية"
                            AttachmentStore.IngestResult.Reason.Unreadable -> "تعذر قراءة الملف من وحدة التخزين"
                            else -> "فشل استيراد الملف: ${ingestResult.reason}"
                        }
                    evaluateJs("if (window.KnitBridge) window.KnitBridge.onAttachmentError('${escapeJs(err)}');")
                }
            } catch (e: Exception) {
                evaluateJs("if (window.KnitBridge) window.KnitBridge.onAttachmentError('خطأ أثناء إرسال المرفق: ${escapeJs(e.message ?: "")}');")
            }
        }
    }

    @JavascriptInterface
    fun startVoiceRecording() {
        debugLog("[JS->KT] startVoiceRecording()")
        val hasAudioPerm =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasAudioPerm) {
            debugLog("[KT->PERM] RECORD_AUDIO not granted; requesting runtime permission")
            mainHandler.post { onRequestAudioPermission?.invoke() }
            evaluateJs(
                "if (window.KnitBridge && window.KnitBridge.onVoiceRecordingState) " +
                    "window.KnitBridge.onVoiceRecordingState(false, 0, 'يرجى السماح بإذن الميكروفون ثم الضغط للتسجيل مرة أخرى');",
            )
            return
        }
        mainHandler.post {
            val started = voiceRecorder.start()
            debugLog("[KT->VOICE] VoiceRecorder.start() -> started=$started")
            if (started) {
                evaluateJs(
                    "if (window.KnitBridge && window.KnitBridge.onVoiceRecordingState) " +
                        "window.KnitBridge.onVoiceRecordingState(true, 0, '');",
                )
            } else {
                evaluateJs(
                    "if (window.KnitBridge && window.KnitBridge.onVoiceRecordingState) " +
                        "window.KnitBridge.onVoiceRecordingState(false, 0, 'تعذر فتح الميكروفون (قد يكون قيد الاستخدام)');",
                )
            }
        }
    }

    @JavascriptInterface
    fun stopVoiceRecording() {
        debugLog("[JS->KT] stopVoiceRecording()")
        val elapsedMs = voiceRecorder.elapsedMs()
        if (elapsedMs < MIN_VOICE_RECORD_MS) {
            voiceRecorder.cancel()
            debugLog("[KT->VOICE] Recording too short (${elapsedMs}ms < ${MIN_VOICE_RECORD_MS}ms); cancelled")
            evaluateJs(
                "if (window.KnitBridge && window.KnitBridge.onVoiceRecordingState) " +
                    "window.KnitBridge.onVoiceRecordingState(false, 0, 'التسجيل قصير جداً — تحدث لمدة ثانية على الأقل');",
            )
            return
        }
        scope.launch {
            val bytes = voiceRecorder.stop()
            val durationMs = bytes?.let { VoiceAudio.durationMs(it) }
            debugLog("[KT->VOICE] VoiceRecorder.stop() -> bytes=${bytes?.size ?: 0} durationMs=$durationMs")
            if (bytes == null || durationMs == null || durationMs < MIN_VOICE_RECORD_MS) {
                evaluateJs(
                    "if (window.KnitBridge && window.KnitBridge.onVoiceRecordingState) " +
                        "window.KnitBridge.onVoiceRecordingState(false, 0, 'تعذر حفظ التسجيل الصوتي');",
                )
                return@launch
            }
            val durationSec = (durationMs / 1000).coerceAtLeast(1)
            val b64 = "data:${VoiceAudio.MIME};base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            evaluateJs(
                "if (window.KnitBridge && window.KnitBridge.onVoiceRecorded) " +
                    "window.KnitBridge.onVoiceRecorded('$b64', $durationSec, ${bytes.size});",
            )
        }
    }

    @JavascriptInterface
    fun cancelVoiceRecording() {
        debugLog("[JS->KT] cancelVoiceRecording()")
        voiceRecorder.cancel()
        evaluateJs(
            "if (window.KnitBridge && window.KnitBridge.onVoiceRecordingState) " +
                "window.KnitBridge.onVoiceRecordingState(false, 0, '');",
        )
    }
    @Volatile private var lastPlayingMsgId: String? = null
    @Volatile private var activeCallId: String? = null
    @Volatile private var lastPlayingHash: String? = null

    @JavascriptInterface
    fun playVoiceMessage(msgId: String) {
        if (msgId.isBlank()) return
        debugLog("[JS->KT] playVoiceMessage(msgId=$msgId)")
        scope.launch(Dispatchers.IO) {
            val msg = messageRepository.find(msgId) ?: return@launch
            val hash = msg.attachmentHash ?: return@launch
            if (!blobs.exists(hash)) {
                meshController.downloadAttachment(hash)
                evaluateJs(
                    "if (window.KnitBridge && window.KnitBridge.onAttachmentSaved) " +
                        "window.KnitBridge.onAttachmentSaved('${escapeJs(msgId)}', false, 'جاري تنزيل الرسالة الصوتية عبر الشبكة...');",
                )
                return@launch
            }
            lastPlayingMsgId = msgId
            lastPlayingHash = hash
            voicePlayer.play(hash, msg.attachmentKey)
        }
    }

    @JavascriptInterface
    fun playVoice(msgId: String) = playVoiceMessage(msgId)

    @JavascriptInterface
    fun pauseVoice() {
        voicePlayer.pause()
    }

    @JavascriptInterface
    fun seekVoice(msgId: String, positionMs: Int) {
        scope.launch(Dispatchers.IO) {
            val hash = if (lastPlayingMsgId == msgId && lastPlayingHash != null) lastPlayingHash else messageRepository.find(msgId)?.attachmentHash
            if (!hash.isNullOrBlank()) {
                voicePlayer.seek(hash, positionMs)
            }
        }
    }
    @JavascriptInterface
    fun sendAttachment(
        conversationId: String,
        type: String,
        dataBase64: String,
        fileName: String,
        fileSize: Long,
        duration: Int,
        viewOnce: Boolean,
        replyTo: String,
    ) {
        debugLog(
            "[JS->KT] sendAttachment(conv=$conversationId, type=$type, name=$fileName, " +
                "size=$fileSize, dur=$duration, viewOnce=$viewOnce, replyTo=$replyTo)",
        )
        scope.launch(Dispatchers.IO) {
            try {
                val kind = Conversations.kindFor(conversationId)
                val groupInfo =
                    if (kind == ConversationKind.GROUP) {
                        groupRepository.find(conversationId)?.toGroupInfo()
                    } else {
                        null
                    }
                val recipientId = if (kind == ConversationKind.DM) conversationId else null
                if (kind == ConversationKind.DM || kind == ConversationKind.GROUP) {
                    settingsStore.accept(conversationId)
                }
                if (kind == ConversationKind.DM) {
                    runCatching { meshController.importContact(conversationId) }
                }
                val rawBytes = Base64.decode(dataBase64.substringAfter("base64,"), Base64.NO_WRAP)

                val ingestResult =
                    when (type) {
                        "voice" -> attachmentStore.ingestVoice(rawBytes)
                        "image" -> attachmentStore.ingest(rawBytes, sourceMime = "image/jpeg", screen = false)
                        else ->
                            attachmentStore.ingestFileBytes(
                                bytes = rawBytes,
                                name = fileName.ifBlank { "file" },
                                declared = null,
                                screen = false,
                            )
                    }

                if (ingestResult is AttachmentStore.IngestResult.Success) {
                    val replyRef =
                        if (replyTo.isNotBlank()) {
                            messageRepository.find(replyTo)?.let { original ->
                                ReplyRef(
                                    messageId = original.id,
                                    authorId = original.senderId,
                                    author = original.replyToAuthor.orEmpty(),
                                    snippet = original.body.take(100),
                                    hasAttachment = original.attachmentHash != null,
                                )
                            }
                        } else {
                            null
                        }
                    val sent =
                        meshController.sendChat(
                            text = "",
                            attachment = ingestResult.ingested,
                            recipientId = recipientId,
                            group = groupInfo,
                            replyTo = replyRef,
                        )
                    debugLog("[KT->ENGINE] sendAttachment($type) -> sent=$sent hash=${ingestResult.ingested.hash}")
                } else if (ingestResult is AttachmentStore.IngestResult.Failed) {
                    evaluateJs("if (window.KnitBridge) window.KnitBridge.onAttachmentError('فشل استيراد المرفق: ${ingestResult.reason}');")
                }
            } catch (e: Exception) {
                evaluateJs("if (window.KnitBridge) window.KnitBridge.onAttachmentError('خطأ أثناء إرسال المرفق: ${escapeJs(e.message ?: "")}');")
            }
        }
    }

    @JavascriptInterface
    fun saveOrOpenAttachment(msgId: String) {
        if (msgId.isBlank()) return
        debugLog("[JS->KT] saveOrOpenAttachment(msgId=$msgId)")
        scope.launch(Dispatchers.IO) {
            try {
                val msg = messageRepository.find(msgId) ?: return@launch
                val hash = msg.attachmentHash ?: return@launch
                val rawBlob = blobs.bytes(hash)
                if (rawBlob == null) {
                    debugLog("[KT->ENGINE] Blob $hash missing locally; calling downloadAttachment($hash)")
                    meshController.downloadAttachment(hash)
                    evaluateJs(
                        "if (window.KnitBridge && window.KnitBridge.onAttachmentSaved) " +
                            "window.KnitBridge.onAttachmentSaved('${escapeJs(msgId)}', false, 'جاري تنزيل الملف من الطرف الآخر عبر الشبكة...');",
                    )
                    return@launch
                }
                val decrypted =
                    if (!msg.attachmentKey.isNullOrBlank()) {
                        val keyBytes = runCatching { Base64.decode(msg.attachmentKey, Base64.NO_WRAP) }.getOrNull()
                        if (keyBytes != null) AttachmentCrypto.open(rawBlob, keyBytes) else null
                    } else {
                        rawBlob
                    }
                if (decrypted == null) {
                    evaluateJs(
                        "if (window.KnitBridge && window.KnitBridge.onAttachmentSaved) " +
                            "window.KnitBridge.onAttachmentSaved('${escapeJs(msgId)}', false, 'تعذر فك تشفير الملف');",
                    )
                    return@launch
                }
                val mime = msg.attachmentMime ?: "application/octet-stream"
                val fileName = msg.attachmentName?.takeIf { it.isNotBlank() } ?: "knit-$hash"

                val savedOk =
                    when {
                        mime.startsWith("image/") && gallerySaver != null -> gallerySaver.saveToPictures(decrypted, hash, mime)
                        mime.startsWith("video/") && gallerySaver != null -> gallerySaver.saveToMovies(decrypted, hash, mime)
                        else -> saveFileToDownloads(decrypted, fileName, mime)
                    }
                if (savedOk) {
                    evaluateJs(
                        "if (window.KnitBridge && window.KnitBridge.onAttachmentSaved) " +
                            "window.KnitBridge.onAttachmentSaved('${escapeJs(msgId)}', true, 'تم حفظ الملف (${escapeJs(fileName)}) في تنزيلات الهاتف');",
                    )
                } else {
                    evaluateJs(
                        "if (window.KnitBridge && window.KnitBridge.onAttachmentSaved) " +
                            "window.KnitBridge.onAttachmentSaved('${escapeJs(msgId)}', false, 'تعذر حفظ الملف في وحدة التخزين');",
                    )
                }
            } catch (_: Exception) {
                evaluateJs(
                    "if (window.KnitBridge && window.KnitBridge.onAttachmentSaved) " +
                        "window.KnitBridge.onAttachmentSaved('${escapeJs(msgId)}', false, 'خطأ أثناء حفظ الملف');",
                )
            }
        }
    }

    private fun saveFileToDownloads(
        bytes: ByteArray,
        displayName: String,
        mime: String,
    ): Boolean =
        runCatching {
            val values =
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Knit")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            resolver.openOutputStream(uri)?.use { out -> out.write(bytes) } ?: return false
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        }.getOrDefault(false)

    @JavascriptInterface
    fun retryMessage(msgId: String) {
        debugLog("[JS->KT] retryMessage(msgId=$msgId)")
        scope.launch(Dispatchers.IO) {
            val msg = messageRepository.find(msgId)
            if (msg == null) {
                evaluateJs("if (window.KnitBridge) window.KnitBridge.onAttachmentError('الرسالة غير موجودة في قاعدة البيانات');")
                return@launch
            }
            try {
                // If the message is already parked pendingKey, trigger key discovery + mesh heal instead of duplicating the row!
                if (msg.pendingKey) {
                    debugLog("[KT->ENGINE] Message $msgId is pendingKey; triggering importContact(${msg.conversationId}) + heal()")
                    if (Conversations.kindFor(msg.conversationId) == ConversationKind.DM) {
                        runCatching { meshController.importContact(msg.conversationId) }
                    }
                    meshController.heal()
                    return@launch
                }
                val kind = Conversations.kindFor(msg.conversationId)
                val groupInfo =
                    if (kind == ConversationKind.GROUP) {
                        groupRepository.find(msg.conversationId)?.toGroupInfo()
                    } else {
                        null
                    }
                val recipientId = if (kind == ConversationKind.DM) msg.conversationId else null
                val success =
                    meshController.sendChat(
                        text = msg.body,
                        recipientId = recipientId,
                        group = groupInfo,
                    )
                if (!success) {
                    evaluateJs(
                        "if (window.KnitBridge) window.KnitBridge.onMessageStatusChanged(" +
                            "'${escapeJs(msg.conversationId)}', '${escapeJs(msgId)}', 'failed');",
                    )
                }
            } catch (_: Exception) {
                evaluateJs(
                    "if (window.KnitBridge) window.KnitBridge.onMessageStatusChanged(" +
                        "'${escapeJs(msg.conversationId)}', '${escapeJs(msgId)}', 'failed');",
                )
            }
        }
    }

    @JavascriptInterface
    fun deleteMessage(
        msgId: String,
        forEveryone: Boolean,
    ) {
        debugLog("[JS->KT] deleteMessage(msgId=$msgId, forEveryone=$forEveryone)")
        scope.launch(Dispatchers.IO) {
            try {
                val msg = messageRepository.find(msgId) ?: return@launch
                localDelete.delete(msg.id, msg.attachmentHash)
            } catch (e: Exception) {
                Log.e(KNIT_UI_TAG, "Error deleting local message", e)
            }
        }
    }

    @JavascriptInterface
    fun toggleReaction(
        msgId: String,
        emoji: String,
    ) {
        if (emoji.isBlank()) return
        debugLog("[JS->KT] toggleReaction(msgId=$msgId, emoji=$emoji)")
        scope.launch(Dispatchers.IO) {
            try {
                val msg = messageRepository.find(msgId)
                if (msg == null) {
                    evaluateJs("if (window.KnitBridge) window.KnitBridge.onReactionFailed('${escapeJs(msgId)}', 'الرسالة غير موجودة');")
                    return@launch
                }
                val kind = Conversations.kindFor(msg.conversationId)
                val groupInfo =
                    if (kind == ConversationKind.GROUP) {
                        groupRepository.find(msg.conversationId)?.toGroupInfo()
                    } else {
                        null
                    }
                val recipientId = if (kind == ConversationKind.DM) msg.conversationId else null

                meshController.sendReaction(
                    messageId = msg.id,
                    emoji = emoji,
                    recipientId = recipientId,
                    group = groupInfo,
                )
                evaluateJs(
                    "if (window.KnitBridge) window.KnitBridge.onReactionConfirmed(" +
                        "'${escapeJs(msg.conversationId)}', '${escapeJs(msg.id)}', '${escapeJs(emoji)}');",
                )
            } catch (e: Exception) {
                evaluateJs(
                    "if (window.KnitBridge) window.KnitBridge.onReactionFailed(" +
                        "'${escapeJs(msgId)}', '${escapeJs(e.message ?: "فشل إرسال التفاعل")}');",
                )
            }
        }
    }

    @JavascriptInterface
    fun updateProfile(
        name: String,
        bio: String,
        avatarBase64: String,
    ) {
        debugLog("[JS->KT] updateProfile(name=$name, bioLen=${bio.length}, hasAvatar=${avatarBase64.isNotBlank()})")
        scope.launch(Dispatchers.IO) {
            try {
                settingsStore.setProfile(name.trim(), bio.trim())
                var avatarSaved = true
                if (avatarBase64.isNotBlank() && !avatarBase64.startsWith("unchanged")) {
                    val bytes =
                        runCatching {
                            Base64.decode(avatarBase64.substringAfter("base64,"), Base64.NO_WRAP)
                        }.getOrNull()

                    if (bytes != null) {
                        val bitmap = decodeOrientedBounded(bytes, AVATAR_DECODE_MAX_DIM)
                        if (bitmap != null) {
                            val hash = avatarStore.saveOwnAvatar(bitmap)
                            if (hash != null) {
                                settingsStore.setOwnAvatar(hash, System.currentTimeMillis())
                            } else {
                                avatarSaved = false
                            }
                        } else {
                            avatarSaved = false
                        }
                    }
                }
                if (avatarSaved) {
                    evaluateJs(
                        "if (window.KnitBridge) window.KnitBridge.onProfileUpdated(true, 'تم حفظ الملف الشخصي بنجاح وبثه للأجهزة المتصلة');",
                    )
                } else {
                    evaluateJs(
                        "if (window.KnitBridge) window.KnitBridge.onProfileUpdated(false, 'تم حفظ الاسم والحالة لكن تعذر حفظ الصورة');",
                    )
                }
            } catch (e: Exception) {
                evaluateJs(
                    "if (window.KnitBridge) window.KnitBridge.onProfileUpdated(false, '${escapeJs(e.message ?: "فشل تحديث الملف الشخصي")}');",
                )
            }
        }
    }

    @JavascriptInterface
    fun startVoiceCall(peerId: String) {
        debugLog("[JS->KT] startVoiceCall(peerId=$peerId)")
        if (peerId.isBlank() || Conversations.kindFor(peerId) != ConversationKind.DM) {
            evaluateJs("if (window.KnitBridge) window.KnitBridge.onAttachmentError('المكالمات الصوتية متاحة في المحادثات الفردية المباشرة فقط');")
            return
        }
        val hasAudioPerm =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasAudioPerm) {
            mainHandler.post { onRequestAudioPermission?.invoke() }
            evaluateJs("if (window.KnitBridge) window.KnitBridge.onAttachmentError('يرجى السماح بإذن الميكروفون لإجراء المكالمة الصوتية');")
            return
        }
        val calls = callManager
        if (calls == null) {
            evaluateJs("if (window.KnitBridge) window.KnitBridge.onAttachmentError('خدمة المكالمات غير مفعلة في هذا البناء');")
            return
        }
        scope.launch {
            val liveRefusal = calls.start(peerId, CallKind.LIVE) ?: return@launch; val refusal = if (liveRefusal == CallRefusal.NO_LIVE) calls.start(peerId, CallKind.AUTO) else liveRefusal
            debugLog("[KT->CALL] calls.start($peerId, CallKind.AUTO) -> refusal=$refusal")
            if (refusal != null) {
                val msg =
                    when (refusal) {
                        CallRefusal.BUSY -> "توجد مكالمة جارية بالفعل"
                        CallRefusal.SELF -> "لا يمكنك الاتصال بنفسك"
                        CallRefusal.NO_LIVE -> "جهاز الطرف الآخر لا يدعم المكالمات الصوتية أو لم يكتمل تبادل مفاتيحه بعد"
                        CallRefusal.NO_RELAY -> "تعذر بدء المكالمة: يلزم اتصال مباشر أو تفعيل خادم Relay"
                    }
                evaluateJs("if (window.KnitBridge) window.KnitBridge.onAttachmentError('${escapeJs(msg)}');")
                evaluateJs("if (window.KnitBridge) window.KnitBridge.onCallEvent('ended', '${escapeJs(peerId)}');")
            }
        }
    }

    @JavascriptInterface
    fun endVoiceCall() {
        debugLog("[JS->KT] endVoiceCall()")
        val calls = callManager ?: return
        scope.launch {
            runCatching { calls.hangUp() }
        }
    }

    @JavascriptInterface
    fun setCallMuted(muted: Boolean) { (context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager)?.isMicrophoneMute = muted } @JavascriptInterface fun setCallSpeaker(speakerOn: Boolean) { val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return; am.mode = android.media.AudioManager.MODE_IN_COMMUNICATION; @Suppress("DEPRECATION") am.isSpeakerphoneOn = speakerOn } @JavascriptInterface fun acceptVoiceCall() { if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { mainHandler.post { onRequestAudioPermission?.invoke() }; return }
        val calls = callManager ?: return
        val id = activeCallId ?: calls.state.value?.id ?: return
        scope.launch {
            calls.answer(id)
        }
    }

    @JavascriptInterface
    fun declineVoiceCall() {
        val calls = callManager ?: return
        val id = activeCallId ?: calls.state.value?.id
        scope.launch {
            if (id != null) calls.decline(id, 0) else calls.hangUp()
        }
    }

    private fun startObservingMeshEvents() {
        scope.launch {
            var activeMsgId: String? = null
            voicePlayer.nowPlaying.collect { pb ->
                if (pb != null) {
                    val mId = lastPlayingMsgId ?: activeMsgId ?: return@collect
                    activeMsgId = mId
                    evaluateJs("window.KnitBridge && window.KnitBridge.onVoicePlaybackState && window.KnitBridge.onVoicePlaybackState('${escapeJs(mId)}', ${pb.playing}, ${pb.positionMs}, ${pb.durationMs});")
                } else if (activeMsgId != null) {
                    val endedId = activeMsgId ?: return@collect
                    activeMsgId = null
                    evaluateJs("window.KnitBridge && window.KnitBridge.onVoicePlaybackState && window.KnitBridge.onVoicePlaybackState('${escapeJs(endedId)}', false, 0, 0);")
                }
            }
        }
        callManager?.let { calls ->
            scope.launch {
                var lastPhase: CallPhase? = null
                var lastCallId: String? = null
                calls.state.collect { callState ->
                    if (callState == null) {
                        if (lastCallId != null || lastPhase != null) {
                            val endedId = lastCallId ?: activeCallId ?: ""
                            activeCallId = null
                            lastPhase = null
                            lastCallId = null
                            evaluateJs("if (window.KnitBridge && window.KnitBridge.onCallEvent) window.KnitBridge.onCallEvent('ended', '${escapeJs(endedId)}');")
                        }
                        return@collect
                    }
                    activeCallId = callState.id
                    if (callState.phase != lastPhase || callState.id != lastCallId) {
                        lastPhase = callState.phase
                        lastCallId = callState.id
                        val eventName = when (callState.phase) {
                            CallPhase.Alerting -> { if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { mainHandler.post { onRequestAudioPermission?.invoke() } }; "incoming" }
                            CallPhase.Connected -> "connected"
                            CallPhase.Ended -> "ended"
                            else -> null
                        }
                        if (eventName != null) {
                            evaluateJs("if (window.KnitBridge && window.KnitBridge.onCallEvent) window.KnitBridge.onCallEvent('$eventName', '${escapeJs(callState.id)}');")
                        }
                        if (callState.phase == CallPhase.Ended) {
                            activeCallId = null
                        }
                    }
                }
            }
        }
        // 1. Live Neighbor Count
        meshStatusJob =
            scope.launch {
                meshController.neighborCount.collect { count ->
                    if (isDestroyed) return@collect
                    debugLog("[MESH->KT] neighborCount=$count")
                    val statusText =
                        if (count > 0) {
                            "متصل بـ $count أجهزة قريبة"
                        } else {
                            "البحث عن أجهزة قريبة..."
                        }
                    evaluateJs("if (window.KnitBridge) window.KnitBridge.onMeshStateChanged($count, '${escapeJs(statusText)}');")
                }
            }

        // 2. Live Typing Indicators from Peers
        typingWatchJob =
            scope.launch {
                meshController.typing.collect { typingMap ->
                    if (isDestroyed) return@collect
                    val activeTypingConvs = typingMap.filterValues { it.isNotEmpty() }.keys.toList()
                    val jsonArr = json.encodeToString(activeTypingConvs)
                    evaluateJs("if (window.KnitBridge && window.KnitBridge.onTypingChanged) window.KnitBridge.onTypingChanged($jsonArr);")
                }
            }

        // 2b. Live Incoming File Transfer Progress
        arrivalsWatchJob =
            scope.launch {
                meshController.arrivals.collect { arrivingMap ->
                    if (isDestroyed) return@collect
                    for ((hashKey, arr) in arrivingMap) {
                        val pct = arr.fraction?.let { (it * 100f).toInt().coerceIn(0, 100) } ?: 0
                        val total = arr.total ?: 0L
                        evaluateJs(
                            "if (window.KnitBridge && window.KnitBridge.onTransferProgress) " +
                                "window.KnitBridge.onTransferProgress('${escapeJs(hashKey)}', $pct, ${arr.bytes}, $total);",
                        )
                    }
                }
            }

        // 3. Live Peer Directory + Discovered Neighbors + Groups + Presence
        peerDirectoryJob =
            scope.launch {
                combine(
                    peerRepository.observeDirectory(),
                    meshController.neighbors,
                    meshController.reachable,
                    groupRepository.observeGroups(),
                    settingsStore.blockedNodeIds,
                ) { dir, neighbors, reachable, groups, blockedIds ->
                    val liveSet = (neighbors.map { it.nodeId } + reachable.map { it.nodeId }).toSet()
                    Triple(dir, liveSet, groups.filter { !it.left } to blockedIds)
                }.collect { (dir, onlineSet, groupsAndBlocked) ->
                    if (isDestroyed) return@collect
                    try {
                        val (activeGroups, blockedIds) = groupsAndBlocked
                        val myNodeId = identity.nodeId()
                        val acceptedIds = withContext(Dispatchers.IO) { settingsStore.acceptedConversations.first() }
                        val verifiedIds = dir.peers.filter { it.verified }.map { it.nodeId }.toSet()
                        val authoredConvs =
                            withContext(Dispatchers.IO) {
                                messageRepository.observeConversationsIAuthoredIn(myNodeId).first().toSet()
                            }
                        val groupSenders =
                            withContext(Dispatchers.IO) {
                                messageRepository.observeGroupSenders(blockedIds).first()
                            }

                        val uiPeers =
                            buildUiContactsList(
                                myNodeId = myNodeId,
                                dir = dir,
                                onlineNeighbors = onlineSet,
                                blockedIds = blockedIds,
                                activeGroups = activeGroups,
                                acceptedIds = acceptedIds,
                                verifiedIds = verifiedIds,
                                authoredConvs = authoredConvs,
                                groupSenders = groupSenders,
                            )
                        debugLog(
                            "[MESH->KT] peerDirectory updated: dirPeers=${dir.peers.size} " +
                                "liveNeighbors=${onlineSet.size} uiPeers=${uiPeers.size} -> firing onPeersUpdated",
                        )
                        val jsonStr = json.encodeToString(uiPeers)
                        evaluateJs("if (window.KnitBridge) window.KnitBridge.onPeersUpdated($jsonStr);")
                    } catch (e: Exception) {
                        if (e !is CancellationException) {
                            Log.e(KNIT_UI_TAG, "Error in peer directory collector", e)
                        }
                    }
                }
            }

        // 4. Observe Active Conversations (DB + Groups + Accepted DMs) & Attach Real-Time Message + Reaction + Blob Collectors
        conversationsWatchJob =
            scope.launch {
                combine(
                    messageRepository.observeConversations(),
                    groupRepository.observeGroups(),
                    settingsStore.acceptedConversations,
                ) { convList, groups, accepted ->
                    (convList + groups.filter { !it.left }.map { it.groupId } + accepted + Conversations.NEARBY).toSet()
                }.collect { allConvs ->
                    if (isDestroyed) return@collect
                    for (convId in allConvs) {
                        if (convId.isNotBlank() && !conversationMessageJobs.containsKey(convId)) {
                            attachConversationCollector(convId)
                        }
                    }
                }
            }
    }

    private fun attachConversationCollector(convId: String) {
        if (convId.isBlank() || conversationMessageJobs.containsKey(convId)) return
        debugLog("Attaching conversation message collector for convId=$convId")
        val job =
            scope.launch {
                val myNodeId = identity.nodeId()
              var isFirstEmission = true
              combine(
                    messageRepository.observeNewestMessages(convId, 60),
                    reactionRepository.observeReactionsIn(convId),
                ) { messages, reactions ->
                    messages.sortedBy { if (it.senderId == myNodeId) it.sentAt else (it.arrivedAt ?: it.sentAt) } to reactions.groupBy { it.messageId }
                }.flatMapLatest { (messages, reactionsByMsg) ->
                    val hashes = messages.mapNotNull { it.attachmentHash }.toSet()
                    blobs.observeSizes(hashes).map { blobSizes ->
                        Triple(messages, reactionsByMsg, blobSizes)
                    }
                }.collect { (messages, reactionsByMsg, _) ->
                    if (isDestroyed) return@collect
                    val dir = withContext(Dispatchers.IO) { peerRepository.observeDirectory().first() }; val maxReadSentAt = messages.filter { m -> m.senderId == myNodeId && reactionsByMsg[m.id]?.any { r -> r.emoji == "\u2063READ\u2063" && r.reactorNodeId != myNodeId } == true }.maxOfOrNull { it.sentAt } ?: -1L
                    for (m in messages) {
                        val rList = reactionsByMsg[m.id]?.mapNotNull { it.emoji }?.filter { it != "\u2063READ\u2063" } ?: emptyList()
                        val rKey = rList.joinToString(",")
                        val uiMsg = m.toUiMessageOrNull(myNodeId, dir, blobs, rList, isReadByPeer = (m.senderId == myNodeId && m.sentAt <= maxReadSentAt)) ?: continue
                        val hasBlobDataNow = (uiMsg.data != null)
                        val prev = knownMessageStates[m.id]
                        if (prev == null ||
                            prev.pending != uiMsg.pending ||
                            prev.delivered != uiMsg.delivered || prev.read != uiMsg.read ||
                            prev.failed != uiMsg.failed ||
                            prev.blobAvailable != uiMsg.blobAvailable ||
                            prev.hasBlobData != hasBlobDataNow ||
                            prev.reactionsKey != rKey
                        ) {
                            knownMessageStates[m.id] =
                                MessageDeliverySnapshot(
                                    pending = uiMsg.pending,
                                    delivered = uiMsg.delivered, read = uiMsg.read,
                                    failed = uiMsg.failed,
                                    blobAvailable = uiMsg.blobAvailable,
                                    hasBlobData = hasBlobDataNow,
                                    reactionsKey = rKey,
                                )
                            debugLog(
                                "[DB->KT] onMessageReceived conv=$convId msgId=${m.id} " +
                                    "pending=${uiMsg.pending} delivered=${uiMsg.delivered} blob=${uiMsg.blobAvailable}",
                            )
                        if (!isFirstEmission) {
                            val msgJson = json.encodeToString(uiMsg)
                            evaluateJs("if (window.KnitBridge) window.KnitBridge.onMessageReceived('${escapeJs(convId)}', $msgJson);")
                        }}
                        
                    }
                isFirstEmission = false
                }
            }
        conversationMessageJobs[convId] = job
    }

    private suspend fun MessageEntity.toUiMessageOrNull(
        myNodeId: String,
        dir: PeerDirectory,
        blobs: BlobRepository,
        reactionsList: List<String> = emptyList(), isReadByPeer: Boolean = false,
    ): UiMessage? {
        if (kind != MessageEntity.KIND_NORMAL) {
            val actorName = if (senderId == myNodeId) "أنت" else dir.label(senderId).text.ifBlank { Alias.aliasFor(senderId) }
            val systemText =
                when (kind) {
                    MessageEntity.KIND_GROUP_CREATED -> "🔒 قام $actorName بإنشاء المجموعة المشفرة"
                    MessageEntity.KIND_GROUP_RENAMED -> "✏️ قام $actorName بتغيير اسم المجموعة إلى «$body»"
                    MessageEntity.KIND_GROUP_PHOTO -> "📷 قام $actorName بتحديث صورة المجموعة"
                    MessageEntity.KIND_MEMBER_LEFT -> "👋 غادر $actorName المجموعة"
                    MessageEntity.KIND_MEMBER_REJOINED -> "↩️ عاد $actorName إلى المجموعة"
                    MessageEntity.KIND_PEER_RENAMED -> {
                        val rename = PeerRename.decode(body)
                        "ℹ️ غيّر ${rename.from} اسمه إلى ${rename.to ?: actorName}"
                    }
                    else -> return null
                }
            return UiMessage(
                id = id,
                senderId = if (senderId == myNodeId) "me" else senderId,
                senderName = actorName,
                ts = if (senderId == myNodeId) sentAt else (arrivedAt ?: sentAt),
                text = systemText,
                type = "text",
                delivered = true,
                read = true,
                failed = false,
                reactions = emptyList(),
            )
        }

        val isSentByMe = (senderId == myNodeId)
        val msgType =
            if (attachmentHash != null) {
                when {
                    attachmentMime?.startsWith("audio/") == true -> "voice"
                    attachmentMime?.startsWith("image/") == true -> "image"
                    attachmentMime?.startsWith("video/") == true -> "video"
                    else -> "file"
                }
            } else {
                "text"
            }

        val hasBlob =
            attachmentHash?.let { hash ->
                withContext(Dispatchers.IO) { blobs.exists(hash) }
            } ?: false

        val dataBase64 =
            if ((msgType == "image" || msgType == "voice") && hasBlob) {
                attachmentHash?.let { hash ->
                    val rawBlob = withContext(Dispatchers.IO) { blobs.bytes(hash) }
                    if (rawBlob != null) {
                        val decrypted =
                            if (!attachmentKey.isNullOrBlank()) {
                                val keyBytes = runCatching { Base64.decode(attachmentKey, Base64.NO_WRAP) }.getOrNull()
                                if (keyBytes != null) AttachmentCrypto.open(rawBlob, keyBytes) else null
                            } else {
                                rawBlob
                            }
                        if (decrypted != null) {
                            val mime = attachmentMime ?: if (msgType == "voice") VoiceAudio.MIME else "image/jpeg"
                            "data:$mime;base64," + Base64.encodeToString(decrypted, Base64.NO_WRAP)
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                }
            } else {
                null
            }

        val senderLabel = if (isSentByMe) "أنا" else dir.label(senderId).text.ifBlank { Alias.aliasFor(senderId) }

        val tinyMatch = Regex("^\\u2063TINY:(\\d+)\\u2063(.*)$", RegexOption.DOT_MATCHES_ALL).matchEntire(body)
        val parsedIsTiny = tinyMatch != null
        val parsedTinyScale = tinyMatch?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 100) ?: 80
        val cleanBody = tinyMatch?.groupValues?.get(2) ?: body
        return UiMessage(
            id = id,
            senderId = if (isSentByMe) "me" else senderId,
            senderName = senderLabel,
            ts = if (senderId == myNodeId) sentAt else (arrivedAt ?: sentAt),
            text = cleanBody,
            type = msgType,
            data = dataBase64,
            attachmentHash = attachmentHash,
            blobAvailable = hasBlob,
            fileName = attachmentName,
            fileSize = attachmentSize ?: 0L,
            duration = voiceDurationMs?.let { (it / 1000).coerceAtLeast(1) } ?: 0,
            replyTo = replyToId,
            pending = pendingKey,
            delivered = received || isReadByPeer,
            read = isReadByPeer,
            failed = false,
            reactions = reactionsList,
            isTiny = parsedIsTiny,
            tinyScale = parsedTinyScale,
        )
    }

    fun destroy() {
        debugLog("KnitBridge.destroy() cancelling all collectors and stopping recorder/player")
        isDestroyed = true
        runCatching { voiceRecorder.cancel() }
        runCatching { voicePlayer.stop() }
        meshStatusJob?.cancel()
        peerDirectoryJob?.cancel()
        conversationsWatchJob?.cancel()
        typingWatchJob?.cancel()
        arrivalsWatchJob?.cancel()
        conversationMessageJobs.values.forEach { it.cancel() }
        conversationMessageJobs.clear()
        knownMessageStates.clear()
        stagedAttachments.clear()
        scope.cancel()
    }

    private fun escapeJs(str: String): String =
        str
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

    private fun evaluateJs(script: String) {
        if (isDestroyed) return
        mainHandler.post {
            if (!isDestroyed) {
                try {
                    webView.evaluateJavascript(script, null)
                } catch (_: Exception) {
                }
            }
        }
    }

    private companion object {
        const val KNIT_UI_TAG = "KNITUI"
        const val MAX_ATTACHMENT_BYTES = 8L * 1024L * 1024L
        const val THUMB_MAX_DIM = 160
        const val THUMB_JPEG_QUALITY = 65
        const val AVATAR_DECODE_MAX_DIM = 512
        const val MIN_VOICE_RECORD_MS = 500L
    }
}

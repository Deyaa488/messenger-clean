package app.getknit.knit.ui

import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.getknit.knit.BuildConfig
import app.getknit.knit.R
import app.getknit.knit.badge.PeerBadges
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.di.CALL_SETTINGS_WIRED
import app.getknit.knit.di.DEV_VIEW_WIRED
import app.getknit.knit.legal.License
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.MeshService
import app.getknit.knit.mesh.MeshStartGate
import app.getknit.knit.mesh.spool.SpoolUrl
import app.getknit.knit.moderation.MlTextModerator
import app.getknit.knit.notifications.ConversationGate
import app.getknit.knit.review.ReviewPrompter
import app.getknit.knit.ui.about.AboutScreen
import app.getknit.knit.ui.about.LicenseTextScreen
import app.getknit.knit.ui.about.LicensesScreen
import app.getknit.knit.ui.addcontact.AddContactScreen
import app.getknit.knit.ui.addcontact.ContactCardInbox
import app.getknit.knit.ui.backup.BackupScreen
import app.getknit.knit.ui.blocked.BlockedUsersScreen
import app.getknit.knit.ui.callsettings.CallSettingsScreen
import app.getknit.knit.ui.chat.MessageDetailsScreen
import app.getknit.knit.ui.commons.CommonsDetailsScreen
import app.getknit.knit.ui.components.LocalPeerBadges
import app.getknit.knit.ui.contacts.ContactsScreen
import app.getknit.knit.ui.custom.CustomMessengerScreen
import app.getknit.knit.ui.devview.EventConsoleScreen
import app.getknit.knit.ui.devview.MeshHud
import app.getknit.knit.ui.devview.rememberDevViewOn
import app.getknit.knit.ui.diagnostics.CrashLogScreen
import app.getknit.knit.ui.diagnostics.DiagnosticsScreen
import app.getknit.knit.ui.diagnostics.FieldLogScreen
import app.getknit.knit.ui.donate.DonateScreen
import app.getknit.knit.ui.group.GroupDetailsScreen
import app.getknit.knit.ui.lora.LoraRadioScreen
import app.getknit.knit.ui.onboarding.OnboardingScreen
import app.getknit.knit.ui.profile.ProfileDetailsScreen
import app.getknit.knit.ui.profile.ProfileScreen
import app.getknit.knit.ui.relay.InternetRelayScreen
import app.getknit.knit.ui.relay.RelayDetailScreen
import app.getknit.knit.ui.relay.RelayInviteInbox
import app.getknit.knit.ui.requests.MessageRequestsScreen
import app.getknit.knit.ui.review.RateReviewDialog
import app.getknit.knit.ui.review.ReviewPromptInbox
import app.getknit.knit.ui.search.SearchScreen
import app.getknit.knit.ui.settings.ChatSettingsScreen
import app.getknit.knit.ui.settings.SettingsScreen
import app.getknit.knit.ui.share.ShareInbox
import app.getknit.knit.ui.share.ShareTargetScreen
import app.getknit.knit.ui.theme.KnitMotion
import app.getknit.knit.ui.theme.LocalReduceMotion
import app.getknit.knit.ui.whatsnew.WhatsNew
import app.getknit.knit.ui.whatsnew.WhatsNewScreen
import app.getknit.knit.ui.yourmesh.YourMeshScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

private const val NAV_SLIDE_DIVISOR = 24

private object Routes {
    const val ONBOARDING = "onboarding"
    const val CHAT_LIST = "chatlist"
    const val CONTACTS = "contacts"
    const val SETTINGS = "settings"
    const val CHAT_SETTINGS = "chatSettings"
    const val PROFILE = "profile"
    const val DIAGNOSTICS = "diagnostics"
    const val DEV_CONSOLE = "devConsole"
    const val YOUR_MESH = "yourMesh"
    const val CRASH_LOG = "crash"
    const val FIELD_LOG = "fieldlog"
    const val BLOCKED_USERS = "blocked"
    const val MESSAGE_REQUESTS = "requests"
    const val DONATE = "donate"
    const val ADD_CONTACT = "addContact"
    const val INTERNET_RELAYS = "relays"
    const val RELAY_DETAIL = "relays/detail?relay={relay}"

    fun relayDetail(url: String) = "relays/detail?relay=${Uri.encode(SpoolUrl.redact(url))}"

    const val LORA_RADIO = "lora"
    const val CALLS = "calls"
    const val SHARE = "share"
    const val SEARCH = "search?in={in}"

    fun search(inConversation: String? = null) = if (inConversation == null) "search" else "search?in=${Uri.encode(inConversation)}"

    const val SEARCH_HIT = "searchHit"
    const val OPEN_CHAT_ID = "openChatId"
    const val ABOUT = "about"
    const val LICENSES = "licenses"
    const val WHATS_NEW = "whatsNew"
    const val BACKUP = "backup?restore={restore}"

    fun backup(restoreOnly: Boolean = false) = "backup?restore=$restoreOnly"

    const val CHAT = "chat/{conversationId}?messageId={messageId}"

    fun chat(conversationId: String) = "chat/$conversationId"

    fun chat(
        conversationId: String,
        messageId: String,
    ) = "chat/$conversationId?messageId=${Uri.encode(messageId)}"

    const val PROFILE_DETAILS = "profileDetails/{nodeId}"

    fun profileDetails(nodeId: String) = "profileDetails/$nodeId"

    const val GROUP_DETAILS = "groupDetails/{groupId}"

    fun groupDetails(groupId: String) = "groupDetails/$groupId"

    const val COMMONS_DETAILS = "commonsDetails/{conversationId}"

    fun commonsDetails(conversationId: String) = "commonsDetails/$conversationId"

    const val LICENSE = "license/{licenseId}"

    fun license(license: License) = "license/${license.routeId}"

    const val MESSAGE_DETAILS = "messageDetails/{messageId}"

    fun messageDetails(messageId: String) = "messageDetails/$messageId"
}

/**
 * App root: gates on permissions, then hosts the unified Custom Messenger WebView on [Routes.CHAT_LIST]
 * alongside Knit's native utility screens (QR/AddContact, ProfileDetails, GroupDetails, Relays, LoRa,
 * Diagnostics, Backup, Settings, etc.). All chat selections from native screens route back to the single
 * [CustomMessengerScreen] instance on [Routes.CHAT_LIST] via [Routes.OPEN_CHAT_ID] so only one WebView
 * ever exists in memory.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun KnitApp(startRoute: String? = null) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val shareInbox = koinInject<ShareInbox>()
    val pendingShare by shareInbox.pending.collectAsStateWithLifecycle()
    val routeInbox = koinInject<RouteInbox>()
    val pendingRoute by routeInbox.pending.collectAsStateWithLifecycle()
    val conversationGate = koinInject<ConversationGate>()
    val contactCardInbox = koinInject<ContactCardInbox>()
    val pendingCard by contactCardInbox.pending.collectAsStateWithLifecycle()
    val relayInviteInbox = koinInject<RelayInviteInbox>()
    val pendingInvite by relayInviteInbox.pending.collectAsStateWithLifecycle()
    val reviewPrompter = koinInject<ReviewPrompter>()
    val reviewInbox = koinInject<ReviewPromptInbox>()
    val startGate = koinInject<MeshStartGate>()
    val appScope = koinInject<CoroutineScope>()
    val showReviewPrompt by reviewInbox.pending.collectAsStateWithLifecycle()

    val onboarded = BuildConfig.SEED_DEMO || hasRadioPermissions(context)
    val start =
        startRoute
            ?: if (onboarded) Routes.CHAT_LIST else Routes.ONBOARDING

    val backStackEntry by navController.currentBackStackEntryAsState()
    val settings = koinInject<SettingsStore>()
    val meshEnabledKey by settings.meshEnabled.collectAsStateWithLifecycle(initialValue = null)
    val currentRoute by rememberUpdatedState(backStackEntry?.destination?.route)
    LaunchedEffect(backStackEntry?.destination?.route, meshEnabledKey) {
        val route = backStackEntry?.destination?.route
        if (shouldStartMeshFromUi(route != null && route != Routes.ONBOARDING, settings.meshEnabled.first())) {
            startGate.record(MeshService.start(context))
        }
    }

    if (!BuildConfig.SEED_DEMO) {
        val meshManager = koinInject<MeshController>()
        val textModel = koinInject<MlTextModerator>()
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer =
                LifecycleEventObserver { _, event ->
                    if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
                    appScope.launch { textModel.warmUp() }
                    val route = currentRoute
                    appScope.launch {
                        if (shouldStartMeshFromUi(route != null && route != Routes.ONBOARDING, settings.meshEnabled.first())) {
                            startGate.record(MeshService.start(context))
                        }
                    }
                    meshManager.heal()
                }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    LaunchedEffect(pendingShare != null) {
        if (pendingShare == null) return@LaunchedEffect
        if (!onboarded) {
            shareInbox.clear()
            return@LaunchedEffect
        }
        navController.navigate(Routes.SHARE) { launchSingleTop = true }
    }

    LaunchedEffect(pendingRoute) {
        val route = pendingRoute ?: return@LaunchedEffect
        if (!onboarded) {
            routeInbox.clear()
            return@LaunchedEffect
        }
        val conversation = RouteInbox.conversationOf(route)
        if (conversation != null && !conversationGate.isOffered(conversation)) {
            routeInbox.consume()
            navController.popBackStack(Routes.CHAT_LIST, inclusive = false)
            Toast.makeText(context, R.string.chat_gone, Toast.LENGTH_SHORT).show()
            return@LaunchedEffect
        }
        if (conversation != null) {
            appScope.launch { settings.accept(conversation) }
            navController.openChatInMainList(conversation)
        } else {
            navController.navigate(route) { popUpTo(Routes.CHAT_LIST) }
        }
        routeInbox.consume()
    }

    LaunchedEffect(pendingCard != null, onboarded) {
        if (pendingCard == null || !onboarded) return@LaunchedEffect
        navController.navigate(Routes.ADD_CONTACT) { launchSingleTop = true }
    }

    LaunchedEffect(pendingInvite != null, onboarded) {
        if (pendingInvite == null || !onboarded) return@LaunchedEffect
        if (!BuildConfig.INTERNET_PLANE) {
            relayInviteInbox.clear()
            return@LaunchedEffect
        }
        navController.navigate(Routes.INTERNET_RELAYS) {
            popUpTo(Routes.INTERNET_RELAYS)
            launchSingleTop = true
        }
    }

    val reduceMotion = LocalReduceMotion.current
    val fade = KnitMotion.effects<Float>()
    val slide = KnitMotion.spatial<IntOffset>()
    val enter =
        if (reduceMotion) {
            EnterTransition.None
        } else {
            fadeIn(animationSpec = fade) + slideInHorizontally(animationSpec = slide) { it / NAV_SLIDE_DIVISOR }
        }
    val exit =
        if (reduceMotion) {
            ExitTransition.None
        } else {
            fadeOut(animationSpec = fade) + slideOutHorizontally(animationSpec = slide) { -it / NAV_SLIDE_DIVISOR }
        }
    val popEnter =
        if (reduceMotion) {
            EnterTransition.None
        } else {
            fadeIn(animationSpec = fade) + slideInHorizontally(animationSpec = slide) { -it / NAV_SLIDE_DIVISOR }
        }
    val popExit =
        if (reduceMotion) {
            ExitTransition.None
        } else {
            fadeOut(animationSpec = fade) + slideOutHorizontally(animationSpec = slide) { it / NAV_SLIDE_DIVISOR }
        }
    val peerBadges by koinInject<PeerBadges>().shown.collectAsStateWithLifecycle()

    val navigateFromCustomUi: (String, String?) -> Unit = { screen, param ->
        when (screen) {
            "addContact" -> navController.navigate(Routes.ADD_CONTACT)
            "contacts" -> navController.navigate(Routes.CONTACTS)
            "settings" -> navController.navigate(Routes.SETTINGS)
            "chatSettings" -> navController.navigate(Routes.CHAT_SETTINGS)
            "profile" -> navController.navigate(Routes.PROFILE)
            "profileDetails" -> if (!param.isNullOrBlank()) navController.navigate(Routes.profileDetails(param))
            "groupDetails" -> if (!param.isNullOrBlank()) navController.navigate(Routes.groupDetails(param))
            "messageDetails" -> if (!param.isNullOrBlank()) navController.navigate(Routes.messageDetails(param))
            "messageRequests" -> navController.navigate(Routes.MESSAGE_REQUESTS)
            "blockedUsers" -> navController.navigate(Routes.BLOCKED_USERS)
            "backup" -> navController.navigate(Routes.backup(restoreOnly = false))
            "restore" -> navController.navigate(Routes.backup(restoreOnly = true))
            "relays" -> if (BuildConfig.INTERNET_PLANE) navController.navigate(Routes.INTERNET_RELAYS)
            "lora" -> if (BuildConfig.LORA_PLANE) navController.navigate(Routes.LORA_RADIO)
            "yourMesh" -> navController.navigate(Routes.YOUR_MESH)
            "diagnostics" -> navController.navigate(Routes.DIAGNOSTICS)
            "search" -> navController.navigate(Routes.search(param))
            "about" -> navController.navigate(Routes.ABOUT)
        }
    }

    CompositionLocalProvider(LocalPeerBadges provides peerBadges) {
        NavHost(
            navController = navController,
            startDestination = start,
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            enterTransition = { enter },
            exitTransition = { exit },
            popEnterTransition = { popEnter },
            popExitTransition = { popExit },
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    onReady = {
                        navController.navigate(Routes.CHAT_LIST) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                        }
                    },
                    onRestore = { navController.navigate(Routes.backup(restoreOnly = true)) },
                )
            }
            composable(
                Routes.BACKUP,
                arguments = listOf(navArgument("restore") { defaultValue = "false" }),
            ) { entry ->
                BackupScreen(
                    onBack = { navController.popFrom(entry) },
                    restoreOnly = entry.arguments?.getString("restore") == "true",
                )
            }
            composable(Routes.CHAT_LIST) { entry ->
                LaunchedEffect(Unit) { reviewPrompter.maybePrompt() }
                val openConversationId by entry.savedStateHandle
                    .getStateFlow<String?>(Routes.OPEN_CHAT_ID, null)
                    .collectAsStateWithLifecycle()

                CustomMessengerScreen(
                    initialConversationId = null,
                    openConversationId = openConversationId,
                    onConsumedOpenConversation = {
                        entry.savedStateHandle[Routes.OPEN_CHAT_ID] = null
                    },
                    onNavigateRoute = navigateFromCustomUi
                )
            }
            composable(Routes.CONTACTS) { entry ->
                ContactsScreen(
                    onBack = { navController.popFrom(entry) },
                    onAddContact = { navController.navigate(Routes.ADD_CONTACT) },
                    onPick = { conversationId ->
                        appScope.launch { settings.accept(conversationId) }
                        navController.openChatInMainList(conversationId)
                    },
                )
            }
            composable(Routes.SHARE) { entry ->
                val fileOnly = remember(entry) { shareInbox.pending.value?.fileOnly == true }
                ShareTargetScreen(
                    fileOnly = fileOnly,
                    onBack = {
                        navController.ifOnTop(entry) {
                            shareInbox.clear()
                            popBackStack()
                        }
                    },
                    onPick = { conversationId ->
                        appScope.launch { settings.accept(conversationId) }
                        navController.openChatInMainList(conversationId)
                    },
                )
            }
            composable(
                route = Routes.SEARCH,
                arguments =
                    listOf(
                        navArgument("in") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
            ) { entry ->
                val inConversation = entry.arguments?.getString("in")
                SearchScreen(
                    inConversation = inConversation,
                    onBack = { navController.popFrom(entry) },
                    onOpenConversation = { id ->
                        navController.openChatInMainList(id)
                    },
                    onOpenMessage = { id, _ ->
                        navController.openChatInMainList(id)
                    },
                )
            }
            composable(
                route = Routes.CHAT,
                arguments =
                    listOf(
                        navArgument("conversationId") { type = NavType.StringType },
                        navArgument("messageId") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
            ) { backStackEntry ->
                val conversationId =
                    backStackEntry.arguments?.getString("conversationId") ?: Conversations.NEARBY
                val hasMainList = remember(backStackEntry) {
                    runCatching { navController.getBackStackEntry(Routes.CHAT_LIST) }.getOrNull() != null
                }
                if (hasMainList) {
                    LaunchedEffect(conversationId) {
                        appScope.launch { settings.accept(conversationId) }
                        navController.openChatInMainList(conversationId)
                    }
                } else {
                    CustomMessengerScreen(
                        initialConversationId = conversationId,
                        openConversationId = conversationId,
                        onBackFromChat = { navController.popFrom(backStackEntry) },
                        onNavigateRoute = navigateFromCustomUi
                    )
                }
            }
            composable(
                route = Routes.MESSAGE_DETAILS,
                arguments = listOf(navArgument("messageId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val messageId = backStackEntry.arguments?.getString("messageId") ?: return@composable
                MessageDetailsScreen(
                    messageId = messageId,
                    onBack = { navController.popFrom(backStackEntry) },
                    onOpenProfile = { id -> navController.navigate(Routes.profileDetails(id)) },
                )
            }
            composable(
                route = Routes.PROFILE_DETAILS,
                arguments = listOf(navArgument("nodeId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val nodeId = backStackEntry.arguments?.getString("nodeId") ?: return@composable
                ProfileDetailsScreen(
                    nodeId = nodeId,
                    onBack = { navController.popFrom(backStackEntry) },
                    onMessage = { id ->
                        appScope.launch { settings.accept(id) }
                        navController.ifOnTop(backStackEntry) {
                            openChatInMainList(id)
                        }
                    },
                    onOpenGroup = { groupId -> navController.navigate(Routes.groupDetails(groupId)) },
                    onRemoved = {
                        navController.popBackStack(Routes.CHAT_LIST, inclusive = false)
                    },
                )
            }
            composable(
                route = Routes.GROUP_DETAILS,
                arguments = listOf(navArgument("groupId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val groupId = backStackEntry.arguments?.getString("groupId") ?: return@composable
                GroupDetailsScreen(
                    groupId = groupId,
                    onBack = { navController.popFrom(backStackEntry) },
                    onOpenMemberProfile = { id -> navController.navigate(Routes.profileDetails(id)) },
                    onLeft = { navController.popBackStack(Routes.CHAT_LIST, inclusive = false) },
                )
            }
            composable(
                route = Routes.COMMONS_DETAILS,
                arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val conversationId = backStackEntry.arguments?.getString("conversationId") ?: return@composable
                CommonsDetailsScreen(
                    conversationId = conversationId,
                    onBack = { navController.popFrom(backStackEntry) },
                    onOpenMemberProfile = { id -> navController.navigate(Routes.profileDetails(id)) },
                    onLeft = { navController.popBackStack(Routes.CHAT_LIST, inclusive = false) },
                )
            }
            composable(Routes.SETTINGS) { entry ->
                SettingsScreen(
                    onBack = { navController.popFrom(entry) },
                    onOpenProfile = { navController.navigate(Routes.PROFILE) },
                    onOpenChats = { navController.navigate(Routes.CHAT_SETTINGS) },
                    onOpenRelays = { navController.navigate(Routes.INTERNET_RELAYS) },
                    onOpenLora = { navController.navigate(Routes.LORA_RADIO) },
                    onOpenCalls = { navController.navigate(Routes.CALLS) },
                    onOpenAbout = { navController.navigate(Routes.ABOUT) },
                    onOpenLicenses = { navController.navigate(Routes.LICENSES) },
                    onOpenBackup = { navController.navigate(Routes.backup()) },
                    onOpenBlockedUsers = { navController.navigate(Routes.BLOCKED_USERS) },
                    onOpenWhatsNew = { navController.navigate(Routes.WHATS_NEW) },
                )
            }
            composable(Routes.CHAT_SETTINGS) { entry ->
                ChatSettingsScreen(onBack = { navController.popFrom(entry) })
            }
            composable(Routes.WHATS_NEW) { entry ->
                LaunchedEffect(Unit) { WhatsNew.latest?.let { settings.markWhatsNewSeen(it.id) } }
                WhatsNewScreen(onBack = { navController.popFrom(entry) }, onOpenUrl = { openUrl(context, it) })
            }
            composable(Routes.PROFILE) { entry ->
                ProfileScreen(onBack = { navController.popFrom(entry) })
            }
            composable(Routes.ABOUT) { entry ->
                AboutScreen(
                    onBack = { navController.popFrom(entry) },
                    onOpenLicenses = { navController.navigate(Routes.LICENSES) },
                    onOpenLicense = { navController.navigate(Routes.license(it)) },
                )
            }
            composable(Routes.LICENSES) { entry ->
                LicensesScreen(
                    onBack = { navController.popFrom(entry) },
                    onOpenLicense = { navController.navigate(Routes.license(it)) },
                )
            }
            composable(
                route = Routes.LICENSE,
                arguments = listOf(navArgument("licenseId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val license =
                    backStackEntry.arguments?.getString("licenseId")?.let(License::fromRouteId) ?: return@composable
                LicenseTextScreen(license = license, onBack = { navController.popFrom(backStackEntry) })
            }
            if (BuildConfig.INTERNET_PLANE) {
                composable(Routes.INTERNET_RELAYS) { entry ->
                    InternetRelayScreen(
                        onBack = { navController.popFrom(entry) },
                        onOpenRelay = { navController.navigate(Routes.relayDetail(it)) },
                    )
                }
                composable(
                    route = Routes.RELAY_DETAIL,
                    arguments = listOf(navArgument("relay") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val leave = { navController.popBackStack(Routes.RELAY_DETAIL, inclusive = true) }
                    val relayKey = backStackEntry.arguments?.getString("relay")
                    val listEntry =
                        remember(backStackEntry) { runCatching { navController.getBackStackEntry(Routes.INTERNET_RELAYS) }.getOrNull() }
                    if (relayKey == null || listEntry == null) {
                        LaunchedEffect(Unit) { leave() }
                        return@composable
                    }
                    RelayDetailScreen(
                        relayKey = relayKey,
                        viewModel = koinViewModel(viewModelStoreOwner = listEntry),
                        onBack = { leave() },
                    )
                }
            }
            if (BuildConfig.LORA_PLANE) {
                composable(Routes.LORA_RADIO) { entry ->
                    LoraRadioScreen(onBack = { navController.popFrom(entry) })
                }
            }
            if (CALL_SETTINGS_WIRED) {
                composable(Routes.CALLS) { entry ->
                    CallSettingsScreen(onBack = { navController.popFrom(entry) })
                }
            }
            composable(Routes.DIAGNOSTICS) { entry ->
                DiagnosticsScreen(
                    onBack = { navController.popFrom(entry) },
                    onOpenCrashLog = { navController.navigate(Routes.CRASH_LOG) },
                    onOpenFieldLog = { navController.navigate(Routes.FIELD_LOG) },
                )
            }
            if (DEV_VIEW_WIRED) {
                composable(Routes.DEV_CONSOLE) { entry ->
                    EventConsoleScreen(onBack = { navController.popFrom(entry) })
                }
            }
            composable(Routes.CRASH_LOG) { entry ->
                CrashLogScreen(onBack = { navController.popFrom(entry) })
            }
            composable(Routes.FIELD_LOG) { entry ->
                FieldLogScreen(onBack = { navController.popFrom(entry) })
            }
            composable(Routes.YOUR_MESH) { entry ->
                YourMeshScreen(
                    onBack = { navController.popFrom(entry) },
                    onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                )
            }
            composable(Routes.BLOCKED_USERS) { entry ->
                BlockedUsersScreen(onBack = { navController.popFrom(entry) })
            }
            composable(Routes.MESSAGE_REQUESTS) { entry ->
                MessageRequestsScreen(
                    onBack = { navController.popFrom(entry) },
                    onOpenProfile = { navController.navigate(Routes.profileDetails(it)) },
                    onOpenConversation = { id ->
                        appScope.launch { settings.accept(id) }
                        navController.openChatInMainList(id)
                    },
                )
            }
            composable(Routes.DONATE) { entry ->
                DonateScreen(
                    onBack = { navController.popFrom(entry) },
                    rateUrl = remember { reviewPrompter.rateUrl() },
                )
            }
            composable(Routes.ADD_CONTACT) { entry ->
                AddContactScreen(
                    onBack = { navController.popFrom(entry) },
                    onImported = { id ->
                        appScope.launch { settings.accept(id) }
                        navController.openChatInMainList(id)
                    },
                )
            }
        }
        if (rememberDevViewOn()) {
            MeshHud(
                onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) { launchSingleTop = true } },
                onOpenEvents = { navController.navigate(Routes.DEV_CONSOLE) { launchSingleTop = true } },
                modifier =
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .semantics { testTagsAsResourceId = true },
            )
        }
    }

    if (showReviewPrompt) {
        RateReviewDialog(
            onPositive = {
                openUrl(context, reviewPrompter.rateUrl())
                reviewInbox.consume()
            },
            onNegative = {
                openUrl(context, reviewPrompter.feedbackUrl)
                reviewInbox.consume()
            },
            onDismiss = { reviewInbox.consume() },
        )
    }
}

/**
 * Hands [conversationId] to the single [CustomMessengerScreen] living on [Routes.CHAT_LIST] and pops back to it,
 * avoiding duplicate WebView instances on the navigation back stack.
 */
private fun NavController.openChatInMainList(conversationId: String) {
    val listEntry = runCatching { getBackStackEntry(Routes.CHAT_LIST) }.getOrNull()
    if (listEntry != null) {
        listEntry.savedStateHandle[Routes.OPEN_CHAT_ID] = conversationId
        popBackStack(Routes.CHAT_LIST, inclusive = false)
    } else {
        navigate(Routes.CHAT_LIST) {
            launchSingleTop = true
        }
        runCatching { getBackStackEntry(Routes.CHAT_LIST) }.getOrNull()?.let {
            it.savedStateHandle[Routes.OPEN_CHAT_ID] = conversationId
        }
    }
}

internal fun NavController.popFrom(entry: NavBackStackEntry) = ifOnTop(entry) { popBackStack() }

internal inline fun NavController.ifOnTop(
    entry: NavBackStackEntry,
    action: NavController.() -> Unit,
) {
    if (currentBackStackEntry?.id == entry.id) action()
}

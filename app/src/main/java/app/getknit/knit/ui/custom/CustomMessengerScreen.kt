package app.getknit.knit.ui.custom

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.getknit.knit.BuildConfig
import app.getknit.knit.call.CallManager
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.AvatarStore
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GallerySaver
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.LocalMessageDelete
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.di.CALLS_WIRED
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.MeshService
import app.getknit.knit.mesh.MeshStartGate
import app.getknit.knit.ui.hasRadioPermissions
import app.getknit.knit.ui.requiredRadioPermissions
import app.getknit.knit.ui.share.ShareInbox
import app.getknit.knit.ui.shouldStartMeshFromUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.getKoin
import org.koin.compose.koinInject

private const val KNIT_UI_TAG = "KNITUI"

private fun logUi(message: String) {
    if (BuildConfig.DEBUG) {
        Log.d(KNIT_UI_TAG, message)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CustomMessengerScreen(
    initialConversationId: String? = null,
    openConversationId: String? = null,
    onConsumedOpenConversation: () -> Unit = {},
    onNavigateRoute: (screen: String, param: String?) -> Unit = { _, _ -> },
    onBackFromChat: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val koin = getKoin()

    val identity = koinInject<Identity>()
    val messageRepository = koinInject<MessageRepository>()
    val peerRepository = koinInject<PeerRepository>()
    val groupRepository = koinInject<GroupRepository>()
    val reactionRepository = koinInject<ReactionRepository>()
    val meshController = koinInject<MeshController>()
    val attachmentStore = koinInject<AttachmentStore>()
    val avatarStore = koinInject<AvatarStore>()
    val settingsStore = koinInject<SettingsStore>()
    val blobRepository = koinInject<BlobRepository>()
    val localDelete = koinInject<LocalMessageDelete>()
    val gallerySaver = koinInject<GallerySaver>()
    val shareInbox = koinInject<ShareInbox>()
    val startGate = koinInject<MeshStartGate>()
    val appScope = koinInject<CoroutineScope>()
    val callManager = remember(koin) { if (CALLS_WIRED) koin.getOrNull<CallManager>() else null }

    var radioGranted by remember { mutableStateOf(BuildConfig.SEED_DEMO || hasRadioPermissions(context)) }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { _ ->
            radioGranted = BuildConfig.SEED_DEMO || hasRadioPermissions(context)
            logUi("Radio permissions result: radioGranted=$radioGranted")
        }

    var pendingWebPermissionRequest by remember { mutableStateOf<PermissionRequest?>(null) }
    val audioPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            logUi("Audio permission result: granted=$granted")
            val req = pendingWebPermissionRequest
            pendingWebPermissionRequest = null
            if (granted && req != null) {
                req.grant(req.resources)
            } else {
                req?.deny()
            }
        }

    LaunchedEffect(Unit) {
        if (!radioGranted) {
            logUi("Requesting requiredRadioPermissions on startup")
            permissionLauncher.launch(requiredRadioPermissions())
        }
    }

    val meshEnabledKey by settingsStore.meshEnabled.collectAsStateWithLifecycle(initialValue = null)

    LaunchedEffect(radioGranted, meshEnabledKey) {
        if (radioGranted && shouldStartMeshFromUi(pastOnboarding = true, meshEnabled = settingsStore.meshEnabled.first())) {
            logUi("Starting MeshService from CustomMessengerScreen")
            startGate.record(MeshService.start(context))
        }
    }

    var fileUploadCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    val fileChooserLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val uriList = mutableListOf<Uri>()
            result.data?.data?.let { uriList.add(it) }
            result.data?.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) {
                    uriList.add(clip.getItemAt(i).uri)
                }
            }
            fileUploadCallback?.onReceiveValue(if (uriList.isNotEmpty()) uriList.toTypedArray() else null)
            fileUploadCallback = null
        }

    val webView =
        remember {
            logUi("Creating WebView instance (initialConversationId=$initialConversationId)")
            WebView(context).apply {
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    mediaPlaybackRequiresUserGesture = false
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    // Keep mobile CSS pixel width accurate (do not force 980px desktop wide viewport)
                    useWideViewPort = false
                    loadWithOverviewMode = false
                }
            }
        }

    val bridge =
        remember(webView) {
            KnitBridge(
                context = context,
                webView = webView,
                identity = identity,
                settingsStore = settingsStore,
                messageRepository = messageRepository,
                peerRepository = peerRepository,
                groupRepository = groupRepository,
                reactionRepository = reactionRepository,
                meshController = meshController,
                attachmentStore = attachmentStore,
                avatarStore = avatarStore,
                blobs = blobRepository,
                localDelete = localDelete,
                gallerySaver = gallerySaver,
                shareInbox = shareInbox,
                callManager = callManager,
                initialConversationId = initialConversationId,
            )
        }

    // Whenever a native screen (Contacts, ProfileDetails, AddContact, MessageRequests, Notification)
    // requests opening a specific conversation, hand it to KnitBridge immediately.
    LaunchedEffect(openConversationId, bridge) {
        if (!openConversationId.isNullOrBlank()) {
            logUi("LaunchedEffect handoff: openConversationFromNative($openConversationId)")
            bridge.openConversationFromNative(openConversationId)
            onConsumedOpenConversation()
        }
    }

    // Refresh data whenever returning from a native Knit screen
    DisposableEffect(lifecycleOwner, bridge) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    logUi("Lifecycle ON_RESUME -> healing mesh and refreshing bridge")
                    appScope.launch {
                        if (hasRadioPermissions(context) &&
                            shouldStartMeshFromUi(pastOnboarding = true, meshEnabled = settingsStore.meshEnabled.first())
                        ) {
                            startGate.record(MeshService.start(context))
                        }
                    }
                    meshController.heal()
                    bridge.onResumeFromKotlin()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val nativeFilePickerLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            if (uri != null) {
                logUi("Native file picker selected URI: $uri")
                bridge.handleSelectedUri(uri)
            } else {
                logUi("Native file picker cancelled")
                bridge.handlePickerCancelled()
            }
        }

    LaunchedEffect(bridge, onNavigateRoute) {
        bridge.onOpenPicker = { category ->
            val mimeTypes =
                when (category) {
                    "image" -> arrayOf("image/*")
                    "video" -> arrayOf("video/*")
                    else -> arrayOf("*/*")
                }
            nativeFilePickerLauncher.launch(mimeTypes)
        }
        bridge.onNavigateRoute = { screen, param ->
            logUi("Navigating to native route: screen=$screen param=$param")
            onNavigateRoute(screen, param)
        }
        bridge.onRequestAudioPermission = {
            logUi("Requesting RECORD_AUDIO runtime permission from CustomMessengerScreen")
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    BackHandler {
        webView.evaluateJavascript(
            """
            (function() {
                var activeModal = document.querySelector('.modal-overlay.active');
                if (activeModal) {
                    if (typeof closeAllModals === 'function') closeAllModals();
                    else activeModal.classList.remove('active');
                    return true;
                }
                if (typeof closeChatOnMobile === 'function' && document.getElementById('sidebar') && document.getElementById('sidebar').classList.contains('hidden')) {
                    closeChatOnMobile();
                    return true;
                }
                return false;
            })();
            """.trimIndent(),
        ) { handled ->
            logUi("BackHandler JS handled=$handled")
            if (handled != "true") {
                if (onBackFromChat != null) {
                    onBackFromChat()
                } else {
                    (context as? ComponentActivity)?.finish()
                }
            }
        }
    }

    DisposableEffect(bridge) {
        webView.addJavascriptInterface(bridge, "KnitNative")
        webView.webViewClient =
            object : WebViewClient() {
                override fun onPageFinished(
                    view: WebView?,
                    url: String?,
                ) {
                    super.onPageFinished(view, url)
                    logUi("WebView.onPageFinished: url=$url")
                    bridge.onPageReady()
                }
            }
        webView.webChromeClient =
            object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    if (BuildConfig.DEBUG && consoleMessage != null) {
                        Log.d(
                            KNIT_UI_TAG,
                            "[JS Console ${consoleMessage.messageLevel()}] ${consoleMessage.message()} " +
                                "(${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})",
                        )
                    }
                    return super.onConsoleMessage(consoleMessage)
                }

                override fun onShowFileChooser(
                    wv: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?,
                ): Boolean {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = filePathCallback
                    val intent =
                        fileChooserParams?.createIntent() ?: android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                            type = "*/*"
                            addCategory(android.content.Intent.CATEGORY_OPENABLE)
                        }
                    fileChooserLauncher.launch(intent)
                    return true
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    if (request == null) return
                    val needsAudio = request.resources.any { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
                    if (needsAudio &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                    ) {
                        pendingWebPermissionRequest = request
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        request.grant(request.resources)
                    }
                }
            }

        logUi("Loading file:///android_asset/web/index.html")
        webView.loadUrl("file:///android_asset/web/index.html")

        onDispose {
            logUi("Disposing CustomMessengerScreen WebView and KnitBridge")
            bridge.destroy()
            webView.removeJavascriptInterface("KnitNative")
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
    }

    AndroidView(
        factory = { webView },
        modifier = Modifier.fillMaxSize(),
    )
}

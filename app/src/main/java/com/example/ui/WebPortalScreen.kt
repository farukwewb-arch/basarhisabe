package com.example.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ui.components.ExitFullscreenButton
import com.example.ui.components.InitialLoadingOverlay
import com.example.ui.components.WebErrorView
import com.example.ui.components.WebPortalTopBar
import com.example.util.BlobDownloadBridge
import com.example.util.PendingBlobDownload
import com.example.util.WebDownloadHelper
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPortalScreen(
    viewModel: WebPortalViewModel,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val reloadSignal by viewModel.reloadSignal.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    var fileChooserCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var pendingBlob by remember { mutableStateOf<PendingBlobDownload?>(null) }
    var lastBackPressMs by remember { mutableLongStateOf(0L) }

    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileChooserCallback
        fileChooserCallback = null
        if (callback == null) return@rememberLauncherForActivityResult

        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        if (uris != null) {
            callback.onReceiveValue(uris)
        } else {
            val clipData = result.data?.clipData
            if (result.resultCode == Activity.RESULT_OK && clipData != null && clipData.itemCount > 0) {
                val selected = Array(clipData.itemCount) { index -> clipData.getItemAt(index).uri }
                callback.onReceiveValue(selected)
            } else {
                callback.onReceiveValue(null)
            }
        }
    }

    val saveBlobLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { destinationUri ->
        val payload = pendingBlob
        pendingBlob = null
        if (destinationUri == null || payload == null) return@rememberLauncherForActivityResult
        val writeResult = runCatching {
            context.contentResolver.openOutputStream(destinationUri)?.use { output ->
                output.write(payload.bytes)
            } ?: error("Cannot open output stream")
        }
        coroutineScope.launch {
            snackbarHostState.showSnackbar(
                context.getString(
                    if (writeResult.isSuccess) R.string.msg_download_saved else R.string.msg_download_failed
                )
            )
        }
    }

    val webView = remember {
        WebView(context).apply {
            val portalWebView = this
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = true
                allowFileAccess = true
                allowContentAccess = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = userAgentString.replace("; wv", "")
            }

            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(portalWebView, true)
            }

            val blobBridge = BlobDownloadBridge(
                onBlobReadyCallback = { downloaded ->
                    post {
                        pendingBlob = downloaded
                        saveBlobLauncher.launch(downloaded.fileName)
                    }
                },
                onBlobErrorCallback = {
                    post {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(context.getString(R.string.msg_download_failed))
                        }
                    }
                },
            )
            addJavascriptInterface(blobBridge, "AndroidBlobBridge")

            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                when {
                    url.startsWith("blob:", ignoreCase = true) -> {
                        val js = WebDownloadHelper.buildBlobFetchScript(url, contentDisposition, mimeType)
                        evaluateJavascript(js, null)
                    }
                    url.startsWith("data:", ignoreCase = true) -> {
                        val fallbackName = "export_${System.currentTimeMillis()}"
                        WebDownloadHelper.decodeDataUrl(url, fallbackName, mimeType)
                            .onSuccess { decoded ->
                                pendingBlob = decoded
                                saveBlobLauncher.launch(decoded.fileName)
                            }
                            .onFailure {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        context.getString(R.string.msg_download_failed)
                                    )
                                }
                            }
                    }
                    else -> {
                        WebDownloadHelper.enqueueHttpDownload(
                            context = context,
                            url = url,
                            userAgent = userAgent,
                            contentDisposition = contentDisposition,
                            mimeType = mimeType,
                        ).onSuccess { fileName ->
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.msg_download_started, fileName)
                                )
                            }
                        }.onFailure {
                            launchExternalUri(context, Uri.parse(url)) {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(
                                        context.getString(R.string.msg_download_failed)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    viewModel.onPageStarted(url)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    CookieManager.getInstance().flush()
                    viewModel.onPageFinished(url, view?.canGoBack() == true)
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    viewModel.onNavigationHistoryUpdated(view?.canGoBack() == true)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val uri = request?.url ?: return false
                    val scheme = uri.scheme?.lowercase() ?: return false
                    if (scheme == "http" || scheme == "https") {
                        return false
                    }
                    launchExternalUri(context, uri) {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.msg_no_app_for_link)
                            )
                        }
                    }
                    return true
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        viewModel.onMainFrameError(error?.description?.toString())
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    viewModel.onProgressChanged(newProgress)
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    super.onReceivedTitle(view, title)
                    viewModel.onReceivedTitle(title)
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?,
                ): Boolean {
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = filePathCallback
                    val intent = runCatching {
                        fileChooserParams?.createIntent()
                    }.getOrNull() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    return try {
                        fileChooserLauncher.launch(intent)
                        true
                    } catch (_: ActivityNotFoundException) {
                        fileChooserCallback?.onReceiveValue(null)
                        fileChooserCallback = null
                        false
                    }
                }

                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?,
                ): Boolean {
                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                    val tempWebView = WebView(context).apply {
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                tempView: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val targetUri = request?.url ?: return true
                                if (isInAppGoogleHost(targetUri.host)) {
                                    view?.loadUrl(targetUri.toString())
                                } else {
                                    launchExternalUri(context, targetUri) {
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar(
                                                context.getString(R.string.msg_no_app_for_link)
                                            )
                                        }
                                    }
                                }
                                tempView?.destroy()
                                return true
                            }
                        }
                    }
                    transport.webView = tempWebView
                    resultMsg.sendToTarget()
                    return true
                }
            }

            loadUrl(WebPortalUiState.TARGET_URL)
        }
    }

    LaunchedEffect(reloadSignal) {
        if (reloadSignal > 0) {
            if (webView.url.isNullOrBlank() || webView.url == "about:blank") {
                webView.loadUrl(WebPortalUiState.TARGET_URL)
            } else {
                webView.reload()
            }
        }
    }

    DisposableEffect(lifecycleOwner, webView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> webView.onResume()
                Lifecycle.Event.ON_PAUSE -> webView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = null
            webView.destroy()
        }
    }

    BackHandler(enabled = true) {
        when {
            webView.canGoBack() -> webView.goBack()
            uiState.isFullscreen -> viewModel.toggleFullscreen(false)
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBackPressMs < 2000L) {
                    (context as? Activity)?.finish()
                } else {
                    lastBackPressMs = now
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.msg_press_back_again)
                        )
                    }
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        contentWindowInsets = if (uiState.isFullscreen) {
            WindowInsets(0, 0, 0, 0)
        } else {
            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
        },
        topBar = {
            if (!uiState.isFullscreen) {
                WebPortalTopBar(
                    title = uiState.pageTitle,
                    isLoading = uiState.isLoading,
                    progress = uiState.progress,
                    canGoBack = uiState.canGoBack,
                    isOnline = uiState.isOnline,
                    onBackClick = {
                        if (webView.canGoBack()) webView.goBack()
                    },
                    onRefreshClick = { viewModel.triggerReload() },
                    onHomeClick = {
                        webView.loadUrl(WebPortalUiState.TARGET_URL)
                    },
                    onFullscreenClick = { viewModel.toggleFullscreen(true) },
                    onOpenInBrowserClick = {
                        val urlToOpen = uiState.currentUrl.ifBlank { WebPortalUiState.TARGET_URL }
                        launchExternalUri(context, Uri.parse(urlToOpen)) {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.msg_no_app_for_link)
                                )
                            }
                        }
                    },
                    onShareClick = {
                        val urlToShare = uiState.currentUrl.ifBlank { WebPortalUiState.TARGET_URL }
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, urlToShare)
                        }
                        context.startActivity(
                            Intent.createChooser(
                                shareIntent,
                                context.getString(R.string.share_chooser_title),
                            )
                        )
                    },
                    onClearCacheClick = {
                        webView.clearCache(true)
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.msg_cache_cleared)
                            )
                        }
                        viewModel.triggerReload()
                    },
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .then(
                    if (uiState.isFullscreen) {
                        Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                    } else {
                        Modifier
                    }
                )
                .imePadding(),
        ) {
            AndroidView(
                factory = { webView },
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("portal_webview"),
            )

            if (uiState.pageLoadState is PageLoadState.Error) {
                val errorState = uiState.pageLoadState as PageLoadState.Error
                WebErrorView(
                    isOffline = errorState.isOffline,
                    errorMessage = errorState.errorMessage,
                    onRetryClick = { viewModel.triggerReload() },
                    onHomeClick = { webView.loadUrl(WebPortalUiState.TARGET_URL) },
                )
            }

            InitialLoadingOverlay(
                visible = uiState.pageLoadState is PageLoadState.InitialLoading,
            )

            if (uiState.isFullscreen) {
                ExitFullscreenButton(
                    onClick = { viewModel.toggleFullscreen(false) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                )
            }
        }
    }
}

private fun isInAppGoogleHost(host: String?): Boolean {
    if (host.isNullOrBlank()) return false
    val lower = host.lowercase()
    return lower == "script.google.com" ||
        lower.endsWith(".googleusercontent.com") ||
        lower == "accounts.google.com" ||
        lower == "accounts.youtube.com" ||
        lower == "drive.google.com" ||
        lower == "docs.google.com"
}

private fun launchExternalUri(
    context: android.content.Context,
    uri: Uri,
    onNotFound: () -> Unit,
) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        onNotFound()
    }
}

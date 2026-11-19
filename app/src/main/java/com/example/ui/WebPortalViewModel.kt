package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.util.NetworkMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class PageLoadState {
    data object InitialLoading : PageLoadState()
    data object ContentReady : PageLoadState()
    data class Error(
        val isOffline: Boolean,
        val errorMessage: String? = null,
    ) : PageLoadState()
}

data class WebPortalUiState(
    val currentUrl: String = TARGET_URL,
    val pageTitle: String = "",
    val progress: Int = 0,
    val isLoading: Boolean = true,
    val canGoBack: Boolean = false,
    val isFullscreen: Boolean = false,
    val isOnline: Boolean = true,
    val pageLoadState: PageLoadState = PageLoadState.InitialLoading,
) {
    companion object {
        const val TARGET_URL =
            "https://script.google.com/macros/s/AKfycbx3E2hzLnt9OCywEqH-DmqopfG1-cHqh7Unn_ayQgpt5X-jEqJcQjU9zWcOSXFTcp3M/exec"
    }
}

class WebPortalViewModel(application: Application) : AndroidViewModel(application) {

    private val networkMonitor = NetworkMonitor(application)

    private val _uiState = MutableStateFlow(
        WebPortalUiState(
            isOnline = networkMonitor.isCurrentlyOnline(),
            pageLoadState = if (networkMonitor.isCurrentlyOnline()) {
                PageLoadState.InitialLoading
            } else {
                PageLoadState.Error(isOffline = true)
            },
        )
    )
    val uiState: StateFlow<WebPortalUiState> = _uiState.asStateFlow()

    private val _reloadSignal = MutableStateFlow(0)
    val reloadSignal: StateFlow<Int> = _reloadSignal.asStateFlow()

    init {
        viewModelScope.launch {
            networkMonitor.isOnline.collect { online ->
                val wasOfflineError =
                    _uiState.value.pageLoadState is PageLoadState.Error &&
                        (_uiState.value.pageLoadState as PageLoadState.Error).isOffline
                _uiState.update { state ->
                    state.copy(
                        isOnline = online,
                        pageLoadState = if (!online && state.pageLoadState is PageLoadState.InitialLoading) {
                            PageLoadState.Error(isOffline = true)
                        } else {
                            state.pageLoadState
                        },
                    )
                }
                if (online && wasOfflineError) {
                    triggerReload()
                }
            }
        }
    }

    fun onPageStarted(url: String?) {
        _uiState.update { state ->
            state.copy(
                currentUrl = url ?: state.currentUrl,
                isLoading = true,
                pageLoadState = if (state.pageLoadState is PageLoadState.Error) {
                    PageLoadState.InitialLoading
                } else {
                    state.pageLoadState
                },
            )
        }
    }

    fun onProgressChanged(newProgress: Int) {
        _uiState.update { state ->
            val clamped = newProgress.coerceIn(0, 100)
            val ready = clamped >= 85 && state.pageLoadState is PageLoadState.InitialLoading
            state.copy(
                progress = clamped,
                isLoading = clamped < 100,
                pageLoadState = if (ready) PageLoadState.ContentReady else state.pageLoadState,
            )
        }
    }

    fun onPageFinished(url: String?, canGoBack: Boolean) {
        _uiState.update { state ->
            state.copy(
                currentUrl = url ?: state.currentUrl,
                isLoading = false,
                progress = 100,
                canGoBack = canGoBack,
                pageLoadState = if (state.pageLoadState is PageLoadState.Error) {
                    state.pageLoadState
                } else {
                    PageLoadState.ContentReady
                },
            )
        }
    }

    fun onReceivedTitle(rawTitle: String?) {
        val cleaned = rawTitle
            ?.trim()
            ?.takeIf {
                it.isNotEmpty() &&
                    !it.startsWith("http://", ignoreCase = true) &&
                    !it.startsWith("https://", ignoreCase = true) &&
                    !it.equals("about:blank", ignoreCase = true)
            }
            ?: return
        _uiState.update { it.copy(pageTitle = cleaned) }
    }

    fun onNavigationHistoryUpdated(canGoBack: Boolean) {
        _uiState.update { it.copy(canGoBack = canGoBack) }
    }

    fun onMainFrameError(description: String?) {
        val online = networkMonitor.isCurrentlyOnline()
        _uiState.update { state ->
            state.copy(
                isLoading = false,
                isOnline = online,
                pageLoadState = PageLoadState.Error(
                    isOffline = !online,
                    errorMessage = description?.takeIf { it.isNotBlank() },
                ),
            )
        }
    }

    fun toggleFullscreen(fullscreen: Boolean) {
        _uiState.update { it.copy(isFullscreen = fullscreen) }
    }

    fun triggerReload() {
        val online = networkMonitor.isCurrentlyOnline()
        _uiState.update { state ->
            state.copy(
                isOnline = online,
                isLoading = true,
                progress = 10,
                pageLoadState = if (online) {
                    PageLoadState.InitialLoading
                } else {
                    PageLoadState.Error(isOffline = true)
                },
            )
        }
        if (online) {
            _reloadSignal.update { it + 1 }
        }
    }
}

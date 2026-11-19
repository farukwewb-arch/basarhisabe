package com.example.util

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil

data class PendingBlobDownload(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
)

object WebDownloadHelper {

    fun enqueueHttpDownload(
        context: Context,
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimeType: String?,
    ): Result<String> {
        return runCatching {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                .ifBlank { "download_${System.currentTimeMillis()}" }
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                if (!mimeType.isNullOrBlank()) {
                    setMimeType(mimeType)
                }
                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrBlank()) {
                    addRequestHeader("Cookie", cookies)
                }
                addRequestHeader("User-Agent", userAgent)
                setTitle(fileName)
                setDescription(url)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                } else {
                    setDestinationInExternalFilesDir(
                        context,
                        Environment.DIRECTORY_DOWNLOADS,
                        fileName,
                    )
                }
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            fileName
        }
    }

    fun buildBlobFetchScript(
        blobUrl: String,
        contentDisposition: String?,
        mimeType: String?,
    ): String {
        val guessed = URLUtil.guessFileName(blobUrl, contentDisposition, mimeType)
            .ifBlank { "export_${System.currentTimeMillis()}" }
        val safeUrl = escapeJsString(blobUrl)
        val safeName = escapeJsString(guessed)
        val safeMime = escapeJsString(mimeType ?: "application/octet-stream")
        return """
            (function() {
                fetch('$safeUrl')
                    .then(function(res) { return res.blob(); })
                    .then(function(blob) {
                        var reader = new FileReader();
                        reader.onloadend = function() {
                            if (window.AndroidBlobBridge && reader.result) {
                                window.AndroidBlobBridge.onBlobReady(
                                    reader.result,
                                    '$safeName',
                                    blob.type || '$safeMime'
                                );
                            }
                        };
                        reader.readAsDataURL(blob);
                    })
                    .catch(function() {
                        if (window.AndroidBlobBridge) {
                            window.AndroidBlobBridge.onBlobError();
                        }
                    });
            })();
        """.trimIndent()
    }

    fun decodeDataUrl(
        dataUrl: String,
        fallbackFileName: String,
        fallbackMimeType: String?,
    ): Result<PendingBlobDownload> {
        return runCatching {
            val commaIndex = dataUrl.indexOf(',')
            require(commaIndex > 0) { "Invalid data URL" }
            val header = dataUrl.substring(0, commaIndex)
            val payload = dataUrl.substring(commaIndex + 1)
            val detectedMime = header
                .removePrefix("data:")
                .substringBefore(';')
                .ifBlank { fallbackMimeType ?: "application/octet-stream" }
            val isBase64 = header.contains(";base64", ignoreCase = true)
            val bytes = if (isBase64) {
                Base64.decode(payload, Base64.DEFAULT)
            } else {
                Uri.decode(payload).toByteArray(Charsets.UTF_8)
            }
            PendingBlobDownload(
                fileName = fallbackFileName,
                mimeType = detectedMime,
                bytes = bytes,
            )
        }
    }

    private fun escapeJsString(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "")
            .replace("\r", "")
    }
}

class BlobDownloadBridge(
    private val onBlobReadyCallback: (PendingBlobDownload) -> Unit,
    private val onBlobErrorCallback: () -> Unit,
) {
    @JavascriptInterface
    fun onBlobReady(dataUrl: String, fileName: String, mimeType: String) {
        WebDownloadHelper.decodeDataUrl(dataUrl, fileName, mimeType)
            .onSuccess(onBlobReadyCallback)
            .onFailure { onBlobErrorCallback() }
    }

    @JavascriptInterface
    fun onBlobError() {
        onBlobErrorCallback()
    }
}

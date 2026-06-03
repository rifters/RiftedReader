package com.rifters.riftedreader.ui.calibre

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.rifters.riftedreader.R
import com.rifters.riftedreader.data.calibre.CalibreConnectionConfig
import com.rifters.riftedreader.data.calibre.DefaultCalibreConnectionRepository
import com.rifters.riftedreader.data.database.BookDatabase
import com.rifters.riftedreader.data.download.BookDownloadManager
import com.rifters.riftedreader.data.repository.BookRepository
import com.rifters.riftedreader.databinding.FragmentCalibreWebBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.UUID

class CalibreWebFragment : Fragment() {

    private var _binding: FragmentCalibreWebBinding? = null
    private val binding get() = _binding!!

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable { _binding?.navigationOverlay?.isVisible = false }
    private val connectionRepository by lazy { DefaultCalibreConnectionRepository(requireContext()) }
    private lateinit var bookDownloadManager: BookDownloadManager
    private var calibreWebUrl = ""
    private val activeDownloads = mutableListOf<ActiveDownload>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCalibreWebBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.title = getString(R.string.calibre_web_title)
        val database = BookDatabase.getDatabase(requireContext())
        val bookRepository = BookRepository(database.bookMetaDao())
        bookDownloadManager = BookDownloadManager.getInstance(requireContext(), bookRepository)
        configureWebView()
        setupOverlay()
        setupActions()
        loadConfiguredUrl()
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun configureWebView() {
        val webView = binding.calibreWebView
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.pageProgress.progress = newProgress
                binding.pageProgress.isVisible = newProgress in 1 until COMPLETE_PROGRESS
            }
        }
        webView.webViewClient = DownloadInterceptWebViewClient(
            mainHandler = mainHandler,
            onDownload = ::startDownload,
            onPageStarted = {
                binding.pageProgress.isVisible = true
                binding.errorCard.isVisible = false
                binding.calibreWebView.isVisible = true
                updateNavigationState()
            },
            onPageFinished = {
                binding.pageProgress.isVisible = false
                updateNavigationState()
            },
            onMainFrameError = { url, description -> showReachabilityError(url, description) },
        )
        webView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                showOverlayTemporarily()
            }
            false
        }
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        findNavController().popBackStack()
                    }
                }
            }
        )
    }

    private fun setupOverlay() {
        binding.backButton.setOnClickListener {
            if (binding.calibreWebView.canGoBack()) binding.calibreWebView.goBack()
            showOverlayTemporarily()
        }
        binding.forwardButton.setOnClickListener {
            if (binding.calibreWebView.canGoForward()) binding.calibreWebView.goForward()
            showOverlayTemporarily()
        }
        binding.refreshButton.setOnClickListener {
            binding.calibreWebView.reload()
            showOverlayTemporarily()
        }
        binding.homeButton.setOnClickListener {
            if (calibreWebUrl.isNotBlank()) binding.calibreWebView.loadUrl(calibreWebUrl)
            showOverlayTemporarily()
        }
        binding.downloadsButton.setOnClickListener {
            showActiveDownloadsSheet()
            showOverlayTemporarily()
        }
        binding.navigationOverlay.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) showOverlayTemporarily()
            false
        }
        showOverlayTemporarily()
    }

    private fun setupActions() {
        binding.retryButton.setOnClickListener {
            binding.errorCard.isVisible = false
            binding.calibreWebView.isVisible = true
            binding.calibreWebView.reload()
        }
        binding.settingsButton.setOnClickListener {
            findNavController().navigate(R.id.action_calibreWebFragment_to_readerSettingsFragment)
        }
    }

    private fun loadConfiguredUrl() {
        viewLifecycleOwner.lifecycleScope.launch {
            val config = connectionRepository.loadConfig()
            if (!config.calibreWebEnabled || config.calibreWebUrl.isBlank()) {
                showNotConfigured(config)
                return@launch
            }
            calibreWebUrl = config.calibreWebUrl
            binding.calibreWebView.loadUrl(calibreWebUrl)
        }
    }

    private fun showNotConfigured(config: CalibreConnectionConfig) {
        calibreWebUrl = config.calibreWebUrl
        binding.calibreWebView.isVisible = false
        binding.navigationOverlay.isVisible = false
        binding.pageProgress.isVisible = false
        binding.errorCard.isVisible = true
        binding.errorTitle.text = getString(R.string.calibre_web_not_configured)
        binding.errorUrl.text = config.calibreWebUrl
        binding.errorDescription.text = ""
        binding.retryButton.isVisible = false
    }

    private fun showReachabilityError(url: String, description: String) {
        binding.calibreWebView.isVisible = false
        binding.navigationOverlay.isVisible = false
        binding.pageProgress.isVisible = false
        binding.errorCard.isVisible = true
        binding.errorTitle.text = getString(R.string.calibre_web_error_title)
        binding.errorUrl.text = url
        binding.errorDescription.text = description
        binding.retryButton.isVisible = true
    }

    private fun startDownload(request: WebDownloadRequest) {
        val filename = request.filename
        Toast.makeText(requireContext(), getString(R.string.calibre_web_downloading, filename), Toast.LENGTH_SHORT).show()
        val id = UUID.randomUUID().toString()
        val job = viewLifecycleOwner.lifecycleScope.launch {
            bookDownloadManager.downloadFromUrl(
                url = request.url,
                filename = filename,
                headers = request.headers,
            ).fold(
                onSuccess = {
                    Snackbar.make(binding.root, getString(R.string.calibre_web_download_complete, filename), Snackbar.LENGTH_LONG).show()
                },
                onFailure = {
                    Snackbar.make(binding.root, getString(R.string.calibre_web_download_failed, filename), Snackbar.LENGTH_LONG).show()
                }
            )
            activeDownloads.removeAll { it.id == id }
            updateDownloadBadge()
        }
        activeDownloads += ActiveDownload(id, filename, job)
        updateDownloadBadge()
    }

    private fun showActiveDownloadsSheet() {
        val dialog = BottomSheetDialog(requireContext())
        dialog.setTitle(getString(R.string.calibre_web_active_downloads))
        val sheetPadding = resources.getDimensionPixelSize(R.dimen.calibre_web_sheet_padding)
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(sheetPadding, sheetPadding, sheetPadding, sheetPadding)
        }
        TextView(requireContext()).apply {
            text = getString(R.string.calibre_web_active_downloads)
            setTextAppearance(R.style.TextAppearance_RiftedReader_BottomSheetTitle)
            container.addView(this)
        }
        if (activeDownloads.isEmpty()) {
            TextView(requireContext()).apply {
                text = getString(R.string.calibre_web_no_active_downloads)
                setPadding(0, sheetPadding, 0, 0)
                container.addView(this)
            }
        } else {
            activeDownloads.forEach { download ->
                val row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, sheetPadding / 2, 0, 0)
                }
                TextView(requireContext()).apply {
                    text = download.filename
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    row.addView(this)
                }
                MaterialButton(requireContext()).apply {
                    text = getString(R.string.calibre_web_cancel_download)
                    setOnClickListener {
                        download.job.cancel()
                        activeDownloads.removeAll { it.id == download.id }
                        updateDownloadBadge()
                        dialog.dismiss()
                    }
                    row.addView(this)
                }
                container.addView(row)
            }
        }
        dialog.setContentView(container)
        dialog.show()
    }

    private fun updateDownloadBadge() {
        val count = activeDownloads.count { it.job.isActive }
        binding.downloadBadge.isVisible = count > 0
        binding.downloadBadge.text = count.toString()
    }

    private fun updateNavigationState() {
        binding.backButton.isEnabled = binding.calibreWebView.canGoBack()
        binding.forwardButton.isEnabled = binding.calibreWebView.canGoForward()
    }

    private fun showOverlayTemporarily() {
        binding.navigationOverlay.isVisible = binding.errorCard.isVisible.not()
        mainHandler.removeCallbacks(hideOverlayRunnable)
        mainHandler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY_MS)
    }

    override fun onDestroyView() {
        mainHandler.removeCallbacks(hideOverlayRunnable)
        activeDownloads.forEach { it.job.cancel() }
        activeDownloads.clear()
        val webView = binding.calibreWebView
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val COMPLETE_PROGRESS = 100
        private const val OVERLAY_HIDE_DELAY_MS = 3_000L
    }
}

private class DownloadInterceptWebViewClient(
    private val mainHandler: Handler,
    private val onDownload: (WebDownloadRequest) -> Unit,
    private val onPageStarted: () -> Unit,
    private val onPageFinished: () -> Unit,
    private val onMainFrameError: (url: String, description: String) -> Unit,
) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (!request.url.isDownloadUrl()) return null

        val cookies = CookieManager.getInstance().getCookie(request.url.toString())
        val headers = request.requestHeaders.toMutableMap()
        if (!cookies.isNullOrBlank()) {
            headers["Cookie"] = cookies
        }
        val downloadRequest = WebDownloadRequest(
            url = request.url.toString(),
            filename = request.url.downloadFilename(),
            headers = headers,
        )
        mainHandler.post { onDownload(downloadRequest) }
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            200,
            "OK",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(ByteArray(0)),
        )
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        onPageStarted()
    }

    override fun onPageFinished(view: WebView, url: String?) {
        onPageFinished()
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) {
            onMainFrameError(request.url.toString(), error.description?.toString().orEmpty())
        }
    }

    private fun Uri.isDownloadUrl(): Boolean {
        val normalizedPath = path.orEmpty().lowercase(Locale.ROOT)
        return DOWNLOAD_EXTENSIONS.any { normalizedPath.endsWith(it) } ||
            pathSegments.any { it.equals("get", ignoreCase = true) }
    }

    private fun Uri.downloadFilename(): String {
        val segment = lastPathSegment?.takeIf { it.isNotBlank() }.orEmpty()
        return if (segment.substringAfterLast('.', "").isNotBlank()) segment else FALLBACK_FILENAME
    }

    companion object {
        private const val FALLBACK_FILENAME = "book"
        private val DOWNLOAD_EXTENSIONS = setOf(".epub", ".mobi", ".azw", ".azw3", ".pdf", ".fb2", ".cbz", ".cbr")
    }
}

private data class WebDownloadRequest(
    val url: String,
    val filename: String,
    val headers: Map<String, String>,
)

private data class ActiveDownload(
    val id: String,
    val filename: String,
    val job: Job,
)

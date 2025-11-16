package com.rifters.riftedreader.ui.reader

import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rifters.riftedreader.R
import com.rifters.riftedreader.databinding.FragmentReaderPageBinding
import kotlinx.coroutines.launch

class ReaderPageFragment : Fragment() {

    private var _binding: FragmentReaderPageBinding? = null
    private val binding get() = _binding!!

    private val readerViewModel: ReaderViewModel by activityViewModels()

    private val pageIndex: Int by lazy {
        requireArguments().getInt(ARG_PAGE_INDEX)
    }

    private var latestPageText: String = ""
    private var latestPageHtml: String? = null
    private var highlightedRange: IntRange? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReaderPageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Configure WebView for EPUB rendering
        binding.pageWebView.apply {
            settings.javaScriptEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = false
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            webViewClient = WebViewClient()
        }
        
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                readerViewModel.pages.collect { pages ->
                    val page = pages.getOrNull(pageIndex)
                    if (page != null) {
                        latestPageText = page.text
                        latestPageHtml = page.html
                        if (highlightedRange == null) {
                            renderBaseContent()
                        } else {
                            applyHighlight(highlightedRange)
                        }
                    } else {
                        latestPageText = ""
                        latestPageHtml = null
                        highlightedRange = null
                        binding.pageTextView.text = ""
                        binding.pageWebView.loadUrl("about:blank")
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                readerViewModel.highlight.collect { highlight ->
                    if (highlight?.pageIndex == pageIndex) {
                        highlightedRange = highlight.range
                        applyHighlight(highlight.range)
                    } else if (highlightedRange != null && highlight?.pageIndex != pageIndex) {
                        highlightedRange = null
                        renderBaseContent()
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                readerViewModel.readerSettings.collect { settings ->
                    binding.pageTextView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, settings.textSizeSp)
                    binding.pageTextView.setLineSpacing(0f, settings.lineHeightMultiplier)
                    val palette = ReaderThemePaletteResolver.resolve(requireContext(), settings.theme)
                    binding.root.setBackgroundColor(palette.backgroundColor)
                    binding.pageTextView.setTextColor(palette.textColor)
                    binding.pageWebView.setBackgroundColor(palette.backgroundColor)
                    
                    // Re-render content if settings changed
                    if (latestPageText.isNotEmpty() || !latestPageHtml.isNullOrEmpty()) {
                        renderBaseContent()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun applyHighlight(range: IntRange?) {
        if (_binding == null) return
        if (range == null) {
            renderBaseContent()
            return
        }
        
        // For WebView, we'll need JavaScript-based highlighting in the future
        // For now, fall back to TextView for highlighting
        val html = latestPageHtml
        if (!html.isNullOrBlank()) {
            // TODO: Implement WebView-based highlighting using JavaScript
            // For now, use TextView for TTS highlighting
            binding.pageWebView.visibility = View.GONE
            binding.pageTextView.visibility = View.VISIBLE
            
            if (latestPageText.isBlank()) {
                binding.pageTextView.text = latestPageText
                return
            }
            if (range.first < 0 || range.first >= latestPageText.length) {
                binding.pageTextView.text = latestPageText
                return
            }
            val spannable = SpannableString(latestPageText)
            val endExclusive = (range.last + 1).coerceAtMost(spannable.length)
            val highlightColor = ContextCompat.getColor(requireContext(), R.color.reader_tts_highlight)
            spannable.setSpan(
                BackgroundColorSpan(highlightColor),
                range.first,
                endExclusive,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            binding.pageTextView.text = spannable
        } else {
            // Plain text highlighting
            binding.pageWebView.visibility = View.GONE
            binding.pageTextView.visibility = View.VISIBLE
            
            if (latestPageText.isBlank()) {
                binding.pageTextView.text = latestPageText
                return
            }
            if (range.first < 0 || range.first >= latestPageText.length) {
                binding.pageTextView.text = latestPageText
                return
            }
            val spannable = SpannableString(latestPageText)
            val endExclusive = (range.last + 1).coerceAtMost(spannable.length)
            val highlightColor = ContextCompat.getColor(requireContext(), R.color.reader_tts_highlight)
            spannable.setSpan(
                BackgroundColorSpan(highlightColor),
                range.first,
                endExclusive,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            binding.pageTextView.text = spannable
        }
    }

    private fun renderBaseContent() {
        if (_binding == null) return
        val html = latestPageHtml
        
        if (!html.isNullOrBlank()) {
            // Use WebView for rich HTML content (EPUB)
            binding.pageWebView.visibility = View.VISIBLE
            binding.pageTextView.visibility = View.GONE
            
            val settings = readerViewModel.readerSettings.value
            val palette = ReaderThemePaletteResolver.resolve(requireContext(), settings.theme)
            
            // Wrap HTML with proper styling
            val wrappedHtml = wrapHtmlForWebView(html, settings.textSizeSp, settings.lineHeightMultiplier, palette)
            binding.pageWebView.loadDataWithBaseURL(null, wrappedHtml, "text/html", "UTF-8", null)
        } else {
            // Use TextView for plain text content (TXT)
            binding.pageWebView.visibility = View.GONE
            binding.pageTextView.visibility = View.VISIBLE
            binding.pageTextView.text = latestPageText
        }
    }
    
    /**
     * Wrap HTML content with proper styling for WebView display
     */
    private fun wrapHtmlForWebView(
        content: String,
        textSize: Float,
        lineHeight: Float,
        palette: ReaderThemePalette
    ): String {
        val backgroundColor = String.format("#%06X", 0xFFFFFF and palette.backgroundColor)
        val textColor = String.format("#%06X", 0xFFFFFF and palette.textColor)
        
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8"/>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes">
                <style>
                    html, body {
                        margin: 0;
                        padding: 0;
                        background-color: $backgroundColor;
                        color: $textColor;
                        font-size: ${textSize}px;
                        line-height: $lineHeight;
                        font-family: serif;
                    }
                    body {
                        padding: 16px;
                        word-wrap: break-word;
                        overflow-wrap: break-word;
                    }
                    /* Preserve formatting for all block elements */
                    p, div, section, article {
                        margin: 0.8em 0;
                    }
                    h1, h2, h3, h4, h5, h6 {
                        margin: 1em 0 0.5em 0;
                        font-weight: bold;
                        line-height: 1.3;
                    }
                    h1 { font-size: 2em; }
                    h2 { font-size: 1.75em; }
                    h3 { font-size: 1.5em; }
                    h4 { font-size: 1.25em; }
                    h5 { font-size: 1.1em; }
                    h6 { font-size: 1em; }
                    blockquote {
                        margin: 1em 0;
                        padding-left: 1em;
                        border-left: 3px solid $textColor;
                        font-style: italic;
                    }
                    ul, ol {
                        margin: 0.5em 0;
                        padding-left: 2em;
                    }
                    li {
                        margin: 0.3em 0;
                    }
                    img {
                        max-width: 100% !important;
                        height: auto !important;
                        display: block;
                        margin: 1em auto;
                    }
                    pre, code {
                        font-family: monospace;
                        background-color: rgba(128, 128, 128, 0.1);
                        padding: 0.2em 0.4em;
                        border-radius: 3px;
                    }
                    pre {
                        padding: 1em;
                        overflow-x: auto;
                    }
                    /* TTS highlighting support (for future implementation) */
                    .tts-highlight {
                        background-color: rgba(255, 213, 79, 0.35);
                        transition: background-color 0.2s ease-in-out;
                    }
                </style>
            </head>
            <body>
                $content
            </body>
            </html>
        """.trimIndent()
    }

    companion object {
        private const val ARG_PAGE_INDEX = "arg_page_index"

        fun newInstance(pageIndex: Int): ReaderPageFragment {
            return ReaderPageFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_PAGE_INDEX, pageIndex)
                }
            }
        }
    }
}

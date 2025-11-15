package com.rifters.riftedreader.ui.reader

import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
        if (latestPageText.isBlank()) {
            binding.pageTextView.text = latestPageText
            return
        }
        if (range.first < 0 || range.first >= latestPageText.length) {
            renderBaseContent()
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

    private fun renderBaseContent() {
        if (_binding == null) return
        val html = latestPageHtml
        if (!html.isNullOrBlank()) {
            binding.pageTextView.text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
        } else {
            binding.pageTextView.text = latestPageText
        }
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

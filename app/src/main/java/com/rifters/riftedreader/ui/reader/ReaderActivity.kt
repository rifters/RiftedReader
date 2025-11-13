package com.rifters.riftedreader.ui.reader

import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rifters.riftedreader.data.database.BookDatabase
import com.rifters.riftedreader.data.repository.BookRepository
import com.rifters.riftedreader.databinding.ActivityReaderBinding
import com.rifters.riftedreader.domain.parser.ParserFactory
import kotlinx.coroutines.launch
import java.io.File

class ReaderActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityReaderBinding
    private lateinit var viewModel: ReaderViewModel
    private lateinit var gestureDetector: GestureDetector
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Get book information from intent
        val bookId = intent.getStringExtra("BOOK_ID") ?: ""
        val bookPath = intent.getStringExtra("BOOK_PATH") ?: ""
        val bookTitle = intent.getStringExtra("BOOK_TITLE") ?: ""
        
        if (bookPath.isEmpty()) {
            finish()
            return
        }
        
        // Initialize ViewModel
        val database = BookDatabase.getDatabase(this)
        val repository = BookRepository(database.bookMetaDao())
        val bookFile = File(bookPath)
        val parser = ParserFactory.getParser(bookFile)
        
        if (parser == null) {
            finish()
            return
        }
        
        viewModel = ReaderViewModel(bookId, bookFile, parser, repository)
        
        setupGestures()
        setupControls()
        observeViewModel()
        
        // Load initial page
        viewModel.loadCurrentPage()
    }
    
    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleControls()
                return true
            }
        })
        
        binding.contentScrollView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
    }
    
    private fun setupControls() {
        binding.prevButton.setOnClickListener {
            viewModel.previousPage()
        }
        
        binding.nextButton.setOnClickListener {
            viewModel.nextPage()
        }
        
        binding.pageSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                viewModel.goToPage(value.toInt())
            }
        }
    }
    
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.content.collect { content ->
                        binding.contentTextView.text = content
                    }
                }
                
                launch {
                    viewModel.currentPage.collect { page ->
                        updatePageIndicator(page)
                    }
                }
                
                launch {
                    viewModel.totalPages.collect { total ->
                        binding.pageSlider.valueTo = total.toFloat().coerceAtLeast(1f)
                    }
                }
            }
        }
    }
    
    private fun updatePageIndicator(page: Int) {
        val total = viewModel.totalPages.value
        binding.pageIndicator.text = "Page ${page + 1} / $total"
        binding.pageSlider.value = page.toFloat()
    }
    
    private fun toggleControls() {
        binding.controlsOverlay.visibility = if (binding.controlsOverlay.visibility == View.VISIBLE) {
            View.GONE
        } else {
            View.VISIBLE
        }
    }
    
    override fun onPause() {
        super.onPause()
        viewModel.saveProgress()
    }
}

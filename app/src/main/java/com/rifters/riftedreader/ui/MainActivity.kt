package com.rifters.riftedreader.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.rifters.riftedreader.R
import com.rifters.riftedreader.data.calibre.DefaultCalibreConnectionRepository
import com.rifters.riftedreader.databinding.ActivityMainBinding
import com.rifters.riftedreader.util.AppLogger
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private val calibreRepository by lazy { DefaultCalibreConnectionRepository(this) }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.event("MainActivity", "onCreate started", "ui/MainActivity/lifecycle")
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setSupportActionBar(binding.toolbar)
        
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        
        setupActionBarWithNavController(navController)
        binding.bottomNavigation.setupWithNavController(navController)
        binding.bottomNavigation.menu.findItem(R.id.calibreLibraryFragment)?.isVisible = false
        observeCalibreNavigation()
        
        AppLogger.event("MainActivity", "onCreate completed", "ui/MainActivity/lifecycle")
    }

    private fun observeCalibreNavigation() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                calibreRepository.configFlow().collect { config ->
                    val shouldShowCalibre = config.contentServerEnabled || config.calibreWebEnabled
                    binding.bottomNavigation.menu.findItem(R.id.calibreLibraryFragment)?.isVisible = shouldShowCalibre
                    if (!shouldShowCalibre) {
                        val navController = (supportFragmentManager
                            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment).navController
                        if (navController.currentDestination?.id == R.id.calibreLibraryFragment) {
                            navController.navigate(R.id.libraryFragment)
                        }
                    }
                }
            }
        }
    }
    
    override fun onStart() {
        super.onStart()
        AppLogger.event("MainActivity", "onStart", "ui/MainActivity/lifecycle")
    }
    
    override fun onResume() {
        super.onResume()
        AppLogger.event("MainActivity", "onResume", "ui/MainActivity/lifecycle")
    }
    
    override fun onPause() {
        AppLogger.event("MainActivity", "onPause", "ui/MainActivity/lifecycle")
        super.onPause()
    }
    
    override fun onStop() {
        AppLogger.event("MainActivity", "onStop", "ui/MainActivity/lifecycle")
        super.onStop()
    }
    
    override fun onDestroy() {
        AppLogger.event("MainActivity", "onDestroy", "ui/MainActivity/lifecycle")
        super.onDestroy()
    }
    
    override fun onSupportNavigateUp(): Boolean {
        AppLogger.userAction("MainActivity", "Navigate up pressed", "ui/MainActivity/navigation")
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        return navController.navigateUp() || super.onSupportNavigateUp()
    }
}

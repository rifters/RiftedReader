package com.rifters.riftedreader.util

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

fun <T> Flow<T>.collectWhileStarted(owner: LifecycleOwner, collector: suspend (T) -> Unit) {
    owner.lifecycleScope.launch {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            collect { value -> collector(value) }
        }
    }
}

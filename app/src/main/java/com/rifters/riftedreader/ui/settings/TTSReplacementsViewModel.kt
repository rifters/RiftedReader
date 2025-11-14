package com.rifters.riftedreader.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rifters.riftedreader.domain.tts.TTSService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

internal sealed interface TTSReplacementsUiEvent {
    data class ShowMessage(val message: String) : TTSReplacementsUiEvent
    object ShowLoadError : TTSReplacementsUiEvent
}

internal class TTSReplacementsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TTSReplacementRepository(application)

    private val _items = MutableStateFlow<List<TTSReplacementUiItem>>(emptyList())
    val items: StateFlow<List<TTSReplacementUiItem>> = _items.asStateFlow()

    private val _events = MutableSharedFlow<TTSReplacementsUiEvent>()
    val events: SharedFlow<TTSReplacementsUiEvent> = _events

    private val idGenerator = AtomicLong(0L)

    init {
        loadRules()
    }

    fun loadRules() {
        viewModelScope.launch {
            try {
                val rules = repository.loadRules()
                _items.value = rules
                val nextId = (rules.maxOfOrNull { it.id } ?: -1L) + 1L
                idGenerator.set(nextId)
            } catch (t: Throwable) {
                _events.emit(TTSReplacementsUiEvent.ShowLoadError)
            }
        }
    }

    fun toggleEnabled(id: Long, enabled: Boolean) {
        updateItems { current ->
            current.map { item ->
                if (item.id == id) item.copy(enabled = enabled) else item
            }
        }
    }

    fun deleteRule(id: Long) {
        updateItems { current -> current.filterNot { it.id == id } }
    }

    fun submitRule(
        id: Long?,
        pattern: String,
        replacement: String,
        type: TTSReplacementUiType,
        enabled: Boolean
    ) {
        val sanitizedPattern = pattern.trim()
        val sanitizedReplacement = replacement.trim()
        if (sanitizedPattern.isEmpty() || sanitizedReplacement.isEmpty()) {
            return
        }
        updateItems { current ->
            val mutable = current.toMutableList()
            if (id == null) {
                mutable.add(
                    TTSReplacementUiItem(
                        id = idGenerator.getAndIncrement(),
                        pattern = sanitizedPattern,
                        replacement = sanitizedReplacement,
                        type = type,
                        enabled = enabled
                    )
                )
            } else {
                val index = mutable.indexOfFirst { it.id == id }
                if (index >= 0) {
                    mutable[index] = mutable[index].copy(
                        pattern = sanitizedPattern,
                        replacement = sanitizedReplacement,
                        type = type,
                        enabled = enabled
                    )
                }
            }
            mutable
        }
    }

    private fun updateItems(transform: (List<TTSReplacementUiItem>) -> List<TTSReplacementUiItem>) {
        viewModelScope.launch {
            try {
                val updated = transform(_items.value)
                _items.value = updated
                repository.saveRules(updated)
                TTSService.reloadReplacements(getApplication())
            } catch (t: Throwable) {
                _events.emit(TTSReplacementsUiEvent.ShowLoadError)
            }
        }
    }
}

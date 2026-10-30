package com.futura.audio

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class AppViewModel : ViewModel() {

    private val _uiState = MutableLiveData<UiState>(UiState.Idle)
    val uiState: LiveData<UiState> = _uiState

    fun setAlarmScheduled(scheduled: Boolean) {
        _uiState.value = if (scheduled) UiState.AlarmScheduled else UiState.Idle
    }
}

data class UiState(val isAlarmScheduled: Boolean = false) {
    companion object {
        val Idle = UiState(false)
        val AlarmScheduled = UiState(true)
    }
}
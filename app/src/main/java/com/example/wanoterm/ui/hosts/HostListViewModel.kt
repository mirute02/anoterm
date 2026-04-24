package com.example.wanoterm.ui.hosts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.wanoterm.WanotermApp
import com.example.wanoterm.data.db.HostEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HostListUiState(val hosts: List<HostEntity> = emptyList())

class HostListViewModel(app: WanotermApp) : ViewModel() {
  private val dao = app.database.hostDao()
  private val _state = MutableStateFlow(HostListUiState())
  val state: StateFlow<HostListUiState> = _state.asStateFlow()

  init {
    viewModelScope.launch {
      dao.observeAll().collect { _state.value = HostListUiState(hosts = it) }
    }
  }

  fun delete(host: HostEntity) {
    viewModelScope.launch { dao.delete(host) }
  }

  companion object {
    val Factory: ViewModelProvider.Factory =
        viewModelFactory { initializer { HostListViewModel(WanotermApp.get()) } }
  }
}

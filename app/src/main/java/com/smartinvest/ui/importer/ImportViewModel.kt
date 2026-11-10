package com.smartinvest.ui.importer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartinvest.domain.repository.ImportRepository
import com.smartinvest.domain.repository.SplitHomeImportPreview
import com.smartinvest.domain.repository.SplitHomeImportReport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ImportUiState(
    val fileName: String? = null,
    val isLoading: Boolean = false,
    val isImporting: Boolean = false,
    val preview: SplitHomeImportPreview? = null,
    val report: SplitHomeImportReport? = null,
    val error: String? = null,
    val duplicateConfirmed: Boolean = false,
) {
    val canImport: Boolean
        get() {
            val preview = preview ?: return false
            if (report != null || isLoading) return false
            if (!preview.parsed.isValid) return false
            return preview.existingExpenseCount == 0 || duplicateConfirmed
        }
}

@HiltViewModel
class ImportViewModel @Inject constructor(
    private val repository: ImportRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImportUiState())
    val uiState: StateFlow<ImportUiState> = _uiState.asStateFlow()

    private var pendingBytes: ByteArray? = null

    fun onFilePicked(fileName: String, bytes: ByteArray) {
        if (bytes.isEmpty()) {
            _uiState.update { it.copy(error = "El archivo está vacío.", isLoading = false) }
            return
        }
        if (bytes.size > MAX_FILE_BYTES) {
            _uiState.update { it.copy(error = "El archivo supera los 10 MB.", isLoading = false) }
            return
        }
        if (bytes.size < 2 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
            _uiState.update {
                it.copy(error = "Ese archivo no es un .xlsx. Elegí el reporte exportado por SplitHome.")
            }
            return
        }

        pendingBytes = bytes
        _uiState.update {
            it.copy(
                fileName = fileName,
                isLoading = true,
                isImporting = false,
                error = null,
                preview = null,
                report = null,
                duplicateConfirmed = false,
            )
        }

        viewModelScope.launch {
            runCatching { repository.previewSplitHome(bytes) }
                .onSuccess { preview ->
                    _uiState.update { it.copy(preview = preview, isLoading = false) }
                }
                .onFailure { error ->
                    pendingBytes = null
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = error.message ?: "No pude leer el archivo.",
                        )
                    }
                }
        }
    }

    fun setDuplicateConfirmed(value: Boolean) {
        _uiState.update { it.copy(duplicateConfirmed = value) }
    }

    fun importNow() {
        val bytes = pendingBytes ?: return
        val state = _uiState.value
        if (!state.canImport) return

        _uiState.update { it.copy(isLoading = true, isImporting = true, error = null) }
        viewModelScope.launch {
            runCatching { repository.importSplitHome(bytes) }
                .onSuccess { report ->
                    pendingBytes = null
                    _uiState.update { it.copy(report = report, isLoading = false, isImporting = false) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isImporting = false,
                            error = error.message ?: "No pude importar los datos.",
                        )
                    }
                }
        }
    }

    fun reset() {
        pendingBytes = null
        _uiState.value = ImportUiState()
    }

    fun fileError(message: String) {
        _uiState.update { it.copy(error = message, isLoading = false) }
    }

    private companion object {
        const val MAX_FILE_BYTES = 10 * 1024 * 1024
    }
}

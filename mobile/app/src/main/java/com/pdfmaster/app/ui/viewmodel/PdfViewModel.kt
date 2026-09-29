package com.pdfmaster.app.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfmaster.app.model.CompressSettings
import com.pdfmaster.app.model.HistoryItem
import com.pdfmaster.app.model.ImageToPdfSettings
import com.pdfmaster.app.model.OperationResult
import com.pdfmaster.app.model.OperationType
import com.pdfmaster.app.model.PdfFileItem
import com.pdfmaster.app.model.TextToPdfSettings
import com.pdfmaster.app.utils.FileUtils
import com.pdfmaster.app.utils.HistoryManager
import com.pdfmaster.app.utils.PdfEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class UiState {
    object Idle : UiState()
    data class Processing(val progress: Float, val message: String = "Processing PDF...") : UiState()
    data class Success(val result: OperationResult) : UiState()
    data class Error(val message: String) : UiState()
}

class PdfViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context get() = getApplication<Application>().applicationContext
    private val historyManager = HistoryManager(context)

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _selectedFiles = MutableStateFlow<List<PdfFileItem>>(emptyList())
    val selectedFiles: StateFlow<List<PdfFileItem>> = _selectedFiles.asStateFlow()

    private val _selectedImageUris = MutableStateFlow<List<Uri>>(emptyList())
    val selectedImageUris: StateFlow<List<Uri>> = _selectedImageUris.asStateFlow()

    private val _historyList = MutableStateFlow<List<HistoryItem>>(emptyList())
    val historyList: StateFlow<List<HistoryItem>> = _historyList.asStateFlow()

    private val _viewerPdfUri = MutableStateFlow<Uri?>(null)
    val viewerPdfUri: StateFlow<Uri?> = _viewerPdfUri.asStateFlow()

    init {
        loadHistory()
    }

    fun loadHistory() {
        _historyList.value = historyManager.getHistoryItems()
    }

    fun addSelectedFiles(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentList = _selectedFiles.value.toMutableList()
            for (uri in uris) {
                val name = FileUtils.getFileName(context, uri)
                val size = FileUtils.getFileSize(context, uri)
                val pages = FileUtils.getPdfPageCount(context, uri)
                val thumb = FileUtils.renderPdfThumbnail(context, uri)
                currentList.add(
                    PdfFileItem(
                        uri = uri,
                        name = name,
                        sizeBytes = size,
                        pageCount = pages,
                        thumbnail = thumb
                    )
                )
            }
            _selectedFiles.value = currentList
        }
    }

    fun setSelectedImageUris(uris: List<Uri>) {
        _selectedImageUris.value = uris
    }

    fun addImageUris(uris: List<Uri>) {
        _selectedImageUris.value = _selectedImageUris.value + uris
    }

    fun removeImageAt(index: Int) {
        val list = _selectedImageUris.value.toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            _selectedImageUris.value = list
        }
    }

    fun reorderImages(fromIndex: Int, toIndex: Int) {
        val list = _selectedImageUris.value.toMutableList()
        if (fromIndex in list.indices && toIndex in list.indices) {
            val item = list.removeAt(fromIndex)
            list.add(toIndex, item)
            _selectedImageUris.value = list
        }
    }

    fun removeFileAt(index: Int) {
        val list = _selectedFiles.value.toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            _selectedFiles.value = list
        }
    }

    fun reorderFiles(fromIndex: Int, toIndex: Int) {
        val list = _selectedFiles.value.toMutableList()
        if (fromIndex in list.indices && toIndex in list.indices) {
            val item = list.removeAt(fromIndex)
            list.add(toIndex, item)
            _selectedFiles.value = list
        }
    }

    fun clearSelectedFiles() {
        _selectedFiles.value = emptyList()
        _selectedImageUris.value = emptyList()
        _uiState.value = UiState.Idle
    }

    fun resetUiState() {
        _uiState.value = UiState.Idle
    }

    fun openInViewer(uri: Uri) {
        _viewerPdfUri.value = uri
    }

    fun closeViewer() {
        _viewerPdfUri.value = null
    }

    // --- PDF Operations ---

    fun mergePdfs(customOutputName: String?) {
        val files = _selectedFiles.value
        if (files.size < 2) {
            _uiState.value = UiState.Error("Please select at least 2 PDF files to merge.")
            return
        }

        viewModelScope.launch {
            _uiState.value = UiState.Processing(0.0f, "Merging ${files.size} PDF files...")
            val uris = files.map { it.uri }
            
            val result = withContext(Dispatchers.IO) {
                PdfEngine.mergePdfs(
                    context = context,
                    inputUris = uris,
                    customOutputName = customOutputName,
                    onProgress = { p -> _uiState.value = UiState.Processing(p, "Merging PDFs...") }
                )
            }

            if (result.success && result.outputUri != null) {
                saveHistory(OperationType.MERGE, result)
                _uiState.value = UiState.Success(result)
            } else {
                _uiState.value = UiState.Error(result.errorMessage ?: "Merge failed.")
            }
        }
    }

    fun splitPdf(pageRangeStr: String, customOutputName: String?) {
        val files = _selectedFiles.value
        if (files.isEmpty()) {
            _uiState.value = UiState.Error("Please select a PDF file to split.")
            return
        }

        viewModelScope.launch {
            _uiState.value = UiState.Processing(0.0f, "Splitting PDF document...")
            val result = withContext(Dispatchers.IO) {
                PdfEngine.splitPdf(
                    context = context,
                    inputUri = files.first().uri,
                    pageRangeStr = pageRangeStr,
                    customOutputName = customOutputName,
                    onProgress = { p -> _uiState.value = UiState.Processing(p, "Splitting pages...") }
                )
            }

            if (result.success && result.outputUri != null) {
                saveHistory(OperationType.SPLIT, result)
                _uiState.value = UiState.Success(result)
            } else {
                _uiState.value = UiState.Error(result.errorMessage ?: "Split failed.")
            }
        }
    }

    fun compressPdf(settings: CompressSettings, customOutputName: String?) {
        val files = _selectedFiles.value
        if (files.isEmpty()) {
            _uiState.value = UiState.Error("Please select a PDF file to compress.")
            return
        }

        viewModelScope.launch {
            _uiState.value = UiState.Processing(0.0f, "Compressing PDF file...")
            val result = withContext(Dispatchers.IO) {
                PdfEngine.compressPdf(
                    context = context,
                    inputUri = files.first().uri,
                    settings = settings,
                    customOutputName = customOutputName,
                    onProgress = { p -> _uiState.value = UiState.Processing(p, "Compressing pages & graphics...") }
                )
            }

            if (result.success && result.outputUri != null) {
                saveHistory(OperationType.COMPRESS, result)
                _uiState.value = UiState.Success(result)
            } else {
                _uiState.value = UiState.Error(result.errorMessage ?: "Compression failed.")
            }
        }
    }

    fun convertImagesToPdf(settings: ImageToPdfSettings, customOutputName: String?) {
        val images = _selectedImageUris.value
        if (images.isEmpty()) {
            _uiState.value = UiState.Error("Please select at least one image.")
            return
        }

        viewModelScope.launch {
            _uiState.value = UiState.Processing(0.0f, "Converting images to PDF...")
            val result = withContext(Dispatchers.IO) {
                PdfEngine.imagesToPdf(
                    context = context,
                    imageUris = images,
                    settings = settings,
                    customOutputName = customOutputName,
                    onProgress = { p -> _uiState.value = UiState.Processing(p, "Rendering images into PDF pages...") }
                )
            }

            if (result.success && result.outputUri != null) {
                saveHistory(OperationType.IMAGE_TO_PDF, result)
                _uiState.value = UiState.Success(result)
            } else {
                _uiState.value = UiState.Error(result.errorMessage ?: "Image conversion failed.")
            }
        }
    }

    fun convertTextToPdf(textContent: String, settings: TextToPdfSettings, customOutputName: String?) {
        if (textContent.isBlank()) {
            _uiState.value = UiState.Error("Please enter or paste text content.")
            return
        }

        viewModelScope.launch {
            _uiState.value = UiState.Processing(0.0f, "Generating PDF document from text...")
            val result = withContext(Dispatchers.IO) {
                PdfEngine.textToPdf(
                    context = context,
                    textContent = textContent,
                    settings = settings,
                    customOutputName = customOutputName,
                    onProgress = { p -> _uiState.value = UiState.Processing(p, "Formatting pages...") }
                )
            }

            if (result.success && result.outputUri != null) {
                saveHistory(OperationType.TEXT_TO_PDF, result)
                _uiState.value = UiState.Success(result)
            } else {
                _uiState.value = UiState.Error(result.errorMessage ?: "Text conversion failed.")
            }
        }
    }

    fun convertPdfToImages() {
        val files = _selectedFiles.value
        if (files.isEmpty()) {
            _uiState.value = UiState.Error("Please select a PDF file.")
            return
        }

        viewModelScope.launch {
            _uiState.value = UiState.Processing(0.0f, "Extracting page images from PDF...")
            val result = withContext(Dispatchers.IO) {
                PdfEngine.pdfToImages(
                    context = context,
                    pdfUri = files.first().uri,
                    onProgress = { p -> _uiState.value = UiState.Processing(p, "Exporting page images...") }
                )
            }

            if (result.success) {
                saveHistory(OperationType.PDF_TO_IMAGES, result)
                _uiState.value = UiState.Success(result)
            } else {
                _uiState.value = UiState.Error(result.errorMessage ?: "PDF to Images failed.")
            }
        }
    }

    fun extractTextFromPdf() {
        val files = _selectedFiles.value
        if (files.isEmpty()) {
            _uiState.value = UiState.Error("Please select a PDF file.")
            return
        }

        viewModelScope.launch {
            _uiState.value = UiState.Processing(0.0f, "Extracting text content...")
            val result = withContext(Dispatchers.IO) {
                PdfEngine.pdfToText(
                    context = context,
                    pdfUri = files.first().uri,
                    onProgress = { p -> _uiState.value = UiState.Processing(p, "Extracting text from PDF...") }
                )
            }

            if (result.success) {
                saveHistory(OperationType.PDF_TO_TEXT, result)
                _uiState.value = UiState.Success(result)
            } else {
                _uiState.value = UiState.Error(result.errorMessage ?: "Text extraction failed.")
            }
        }
    }

    fun protectPdf(password: String, customOutputName: String?) {
        val files = _selectedFiles.value
        if (files.isEmpty()) {
            _uiState.value = UiState.Error("Please select a PDF file to encrypt.")
            return
        }
        if (password.isBlank()) {
            _uiState.value = UiState.Error("Password cannot be empty.")
            return
        }

        viewModelScope.launch {
            _uiState.value = UiState.Processing(0.0f, "Encrypting PDF document...")
            val result = withContext(Dispatchers.IO) {
                PdfEngine.protectPdf(
                    context = context,
                    pdfUri = files.first().uri,
                    userPass = password,
                    customOutputName = customOutputName,
                    onProgress = { p -> _uiState.value = UiState.Processing(p, "Applying password protection...") }
                )
            }

            if (result.success) {
                saveHistory(OperationType.SECURITY, result)
                _uiState.value = UiState.Success(result)
            } else {
                _uiState.value = UiState.Error(result.errorMessage ?: "Encryption failed.")
            }
        }
    }

    fun unlockPdf(password: String, customOutputName: String?) {
        val files = _selectedFiles.value
        if (files.isEmpty()) {
            _uiState.value = UiState.Error("Please select an encrypted PDF file.")
            return
        }

        viewModelScope.launch {
            _uiState.value = UiState.Processing(0.0f, "Decrypting PDF document...")
            val result = withContext(Dispatchers.IO) {
                PdfEngine.unlockPdf(
                    context = context,
                    pdfUri = files.first().uri,
                    password = password,
                    customOutputName = customOutputName,
                    onProgress = { p -> _uiState.value = UiState.Processing(p, "Removing password protection...") }
                )
            }

            if (result.success) {
                saveHistory(OperationType.SECURITY, result)
                _uiState.value = UiState.Success(result)
            } else {
                _uiState.value = UiState.Error(result.errorMessage ?: "Failed to unlock PDF. Verify password.")
            }
        }
    }

    private fun saveHistory(operation: OperationType, result: OperationResult) {
        val item = HistoryItem(
            title = result.outputName ?: operation.title,
            operationName = operation.title,
            fileUriString = result.outputUri?.toString() ?: "",
            fileName = result.outputName ?: "Output.pdf",
            fileSizeFormatted = FileUtils.formatFileSize(result.outputSizeBytes),
            pageCount = result.pagesCount
        )
        historyManager.addHistoryItem(item)
        loadHistory()
    }

    fun clearHistory() {
        historyManager.clearHistory()
        loadHistory()
    }
}

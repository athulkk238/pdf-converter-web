package com.pdfmaster.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.pdfmaster.app.model.OperationType
import com.pdfmaster.app.ui.components.BottomNavBar
import com.pdfmaster.app.ui.components.NavTab
import com.pdfmaster.app.ui.components.OperationProgressModal
import com.pdfmaster.app.ui.screens.CompressScreen
import com.pdfmaster.app.ui.screens.HistoryScreen
import com.pdfmaster.app.ui.screens.HomeScreen
import com.pdfmaster.app.ui.screens.ImageToPdfScreen
import com.pdfmaster.app.ui.screens.MergeScreen
import com.pdfmaster.app.ui.screens.PdfToImagesScreen
import com.pdfmaster.app.ui.screens.PdfToTextScreen
import com.pdfmaster.app.ui.screens.PdfViewerDialog
import com.pdfmaster.app.ui.screens.SecurityScreen
import com.pdfmaster.app.ui.screens.SplitScreen
import com.pdfmaster.app.ui.screens.TextToPdfScreen
import com.pdfmaster.app.ui.theme.PDFMasterTheme
import com.pdfmaster.app.ui.viewmodel.PdfViewModel
import com.pdfmaster.app.ui.viewmodel.UiState
import com.pdfmaster.app.utils.FileUtils

class MainActivity : ComponentActivity() {

    private val viewModel: PdfViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if opened via PDF file intent from another app
        handleIntent(intent)

        setContent {
            PDFMasterTheme {
                PDFMasterApp(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri: Uri? = intent.data
            if (uri != null) {
                viewModel.openInViewer(uri)
            }
        }
    }
}

@Composable
fun PDFMasterApp(viewModel: PdfViewModel) {
    var currentTab by remember { mutableStateOf(NavTab.HOME) }
    var activeOperation by remember { mutableStateOf<OperationType?>(null) }

    val uiState by viewModel.uiState.collectAsState()
    val viewerPdfUri by viewModel.viewerPdfUri.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var successDialogResult by remember { mutableStateOf<com.pdfmaster.app.model.OperationResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uiState) {
        when (val state = uiState) {
            is UiState.Success -> {
                successDialogResult = state.result
            }
            is UiState.Error -> {
                errorMessage = state.message
            }
            else -> {}
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (activeOperation == null) {
                BottomNavBar(
                    selectedTab = currentTab,
                    onTabSelected = { tab ->
                        currentTab = tab
                        viewModel.clearSelectedFiles()
                    }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val currentOperation = activeOperation
            if (currentOperation != null) {
                when (currentOperation) {
                    OperationType.MERGE -> MergeScreen(
                        viewModel = viewModel,
                        onBack = {
                            activeOperation = null
                            viewModel.clearSelectedFiles()
                        }
                    )
                    OperationType.SPLIT -> SplitScreen(
                        viewModel = viewModel,
                        onBack = {
                            activeOperation = null
                            viewModel.clearSelectedFiles()
                        }
                    )
                    OperationType.COMPRESS -> CompressScreen(
                        viewModel = viewModel,
                        onBack = {
                            activeOperation = null
                            viewModel.clearSelectedFiles()
                        }
                    )
                    OperationType.IMAGE_TO_PDF -> ImageToPdfScreen(
                        viewModel = viewModel,
                        onBack = {
                            activeOperation = null
                            viewModel.clearSelectedFiles()
                        }
                    )
                    OperationType.TEXT_TO_PDF -> TextToPdfScreen(
                        viewModel = viewModel,
                        onBack = {
                            activeOperation = null
                            viewModel.clearSelectedFiles()
                        }
                    )
                    OperationType.PDF_TO_IMAGES -> PdfToImagesScreen(
                        viewModel = viewModel,
                        onBack = {
                            activeOperation = null
                            viewModel.clearSelectedFiles()
                        }
                    )
                    OperationType.PDF_TO_TEXT -> PdfToTextScreen(
                        viewModel = viewModel,
                        onBack = {
                            activeOperation = null
                            viewModel.clearSelectedFiles()
                        }
                    )
                    OperationType.SECURITY -> SecurityScreen(
                        viewModel = viewModel,
                        onBack = {
                            activeOperation = null
                            viewModel.clearSelectedFiles()
                        }
                    )
                }
            } else {
                when (currentTab) {
                    NavTab.HOME -> HomeScreen(
                        viewModel = viewModel,
                        onOperationSelected = { op ->
                            viewModel.clearSelectedFiles()
                            activeOperation = op
                        },
                        onViewHistory = { currentTab = NavTab.HISTORY }
                    )
                    NavTab.TOOLS -> HomeScreen(
                        viewModel = viewModel,
                        onOperationSelected = { op ->
                            viewModel.clearSelectedFiles()
                            activeOperation = op
                        },
                        onViewHistory = { currentTab = NavTab.HISTORY }
                    )
                    NavTab.HISTORY -> HistoryScreen(viewModel = viewModel)
                }
            }
        }
    }

    // Processing Dialog
    (uiState as? UiState.Processing)?.let { state ->
        OperationProgressModal(
            progress = state.progress,
            statusText = state.message
        )
    }

    // Success Dialog
    successDialogResult?.let { result ->
        AlertDialog(
            onDismissRequest = {
                successDialogResult = null
                viewModel.resetUiState()
            },
            title = { Text("Operation Successful 🎉") },
            text = {
                Text(
                    "Output Saved: ${result.outputName ?: "Output File"}\n" +
                    "File Size: ${FileUtils.formatFileSize(result.outputSizeBytes)}" +
                    if (result.originalSizeBytes > 0 && result.originalSizeBytes > result.outputSizeBytes) {
                        "\nSaved ${FileUtils.formatFileSize(result.originalSizeBytes - result.outputSizeBytes)}!"
                    } else ""
                )
            },
            confirmButton = {
                if (result.outputUri != null) {
                    TextButton(
                        onClick = {
                            val uri = result.outputUri
                            successDialogResult = null
                            viewModel.resetUiState()
                            viewModel.openInViewer(uri)
                        }
                    ) {
                        Text("View Document")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        successDialogResult = null
                        viewModel.resetUiState()
                    }
                ) {
                    Text("Done")
                }
            }
        )
    }

    // Error Dialog
    errorMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = {
                errorMessage = null
                viewModel.resetUiState()
            },
            title = { Text("Operation Failed") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(
                    onClick = {
                        errorMessage = null
                        viewModel.resetUiState()
                    }
                ) {
                    Text("OK")
                }
            }
        )
    }

    // PDF Viewer Dialog
    viewerPdfUri?.let { uri ->
        PdfViewerDialog(
            pdfUri = uri,
            onClose = { viewModel.closeViewer() }
        )
    }
}

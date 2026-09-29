package com.pdfmaster.app.model

import android.graphics.Bitmap
import android.net.Uri

data class PdfFileItem(
    val uri: Uri,
    val name: String,
    val path: String? = null,
    val sizeBytes: Long = 0L,
    val pageCount: Int = 0,
    val thumbnail: Bitmap? = null,
    val lastModified: Long = System.currentTimeMillis()
)

enum class OperationType(
    val title: String,
    val description: String,
    val iconName: String,
    val gradientStart: Long,
    val gradientEnd: Long
) {
    MERGE(
        title = "Merge PDF",
        description = "Combine multiple PDFs into one document",
        iconName = "merge",
        gradientStart = 0xFF6366F1,
        gradientEnd = 0xFF8B5CF6
    ),
    SPLIT(
        title = "Split PDF",
        description = "Extract pages or split into multiple files",
        iconName = "split",
        gradientStart = 0xFFEC4899,
        gradientEnd = 0xFFF43F5E
    ),
    COMPRESS(
        title = "Compress PDF",
        description = "Reduce PDF file size while keeping quality",
        iconName = "compress",
        gradientStart = 0xFF10B981,
        gradientEnd = 0xFF059669
    ),
    IMAGE_TO_PDF(
        title = "Image to PDF",
        description = "Convert photos, scans & PNGs to PDF",
        iconName = "image_to_pdf",
        gradientStart = 0xFFF59E0B,
        gradientEnd = 0xFFD97706
    ),
    TEXT_TO_PDF(
        title = "Text to PDF",
        description = "Convert raw text or text files to PDF",
        iconName = "text_to_pdf",
        gradientStart = 0xFF3B82F6,
        gradientEnd = 0xFF1D4ED8
    ),
    PDF_TO_IMAGES(
        title = "PDF to Images",
        description = "Extract high resolution images from PDF",
        iconName = "pdf_to_images",
        gradientStart = 0xFF8B5CF6,
        gradientEnd = 0xFF6D28D9
    ),
    PDF_TO_TEXT(
        title = "PDF to Text",
        description = "Extract readable text content from PDF",
        iconName = "pdf_to_text",
        gradientStart = 0xFF06B6D4,
        gradientEnd = 0xFF0891B2
    ),
    SECURITY(
        title = "Protect & Unlock",
        description = "Encrypt with password or remove protection",
        iconName = "security",
        gradientStart = 0xFF64748B,
        gradientEnd = 0xFF334155
    )
}

enum class CompressionPreset(val label: String, val imageQuality: Int, val dpi: Int, val desc: String) {
    RECOMMENDED("Recommended (Balanced)", 60, 150, "Optimal balance of quality and size"),
    EXTREME("Extreme (Smallest Size)", 35, 96, "Maximum compression, lower image resolution"),
    LOW("Low (High Quality)", 85, 200, "Minor compression, crisp document graphics"),
    CUSTOM("Custom Settings", 70, 150, "Adjust quality and DPI manually")
}

data class CompressSettings(
    val preset: CompressionPreset = CompressionPreset.RECOMMENDED,
    val customQuality: Int = 60,
    val customDpi: Int = 150
)

enum class PageOrientation(val label: String) {
    AUTO("Auto Detect"),
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape")
}

enum class PageMargin(val label: String, val paddingDp: Int) {
    NONE("No Margin", 0),
    SMALL("Small Margin", 12),
    MEDIUM("Medium Margin", 24),
    LARGE("Large Margin", 36)
}

enum class ImageScaleType(val label: String) {
    FIT_CENTER("Fit to Page"),
    FILL_PAGE("Fill Page"),
    CENTER("Original Size")
}

data class ImageToPdfSettings(
    val orientation: PageOrientation = PageOrientation.AUTO,
    val margin: PageMargin = PageMargin.SMALL,
    val scaleType: ImageScaleType = ImageScaleType.FIT_CENTER,
    val compressQuality: Int = 85
)

enum class PdfPageSize(val label: String, val widthPt: Float, val heightPt: Float) {
    A4("A4 (210 x 297 mm)", 595.28f, 841.89f),
    LETTER("Letter (8.5 x 11 in)", 612.0f, 792.0f),
    LEGAL("Legal (8.5 x 14 in)", 612.0f, 1008.0f)
}

data class TextToPdfSettings(
    val pageSize: PdfPageSize = PdfPageSize.A4,
    val fontSizeSp: Float = 14f,
    val margin: PageMargin = PageMargin.MEDIUM,
    val titleText: String = ""
)

data class OperationResult(
    val success: Boolean,
    val outputUri: Uri? = null,
    val outputPath: String? = null,
    val outputName: String? = null,
    val outputSizeBytes: Long = 0L,
    val originalSizeBytes: Long = 0L,
    val pagesCount: Int = 0,
    val extractedImagesCount: Int = 0,
    val extractedText: String? = null,
    val errorMessage: String? = null
)

data class HistoryItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val operationName: String,
    val timeStamp: Long = System.currentTimeMillis(),
    val fileUriString: String,
    val fileName: String,
    val fileSizeFormatted: String,
    val pageCount: Int = 0
)

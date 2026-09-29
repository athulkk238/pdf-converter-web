package com.pdfmaster.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.pdfmaster.app.model.CompressSettings
import com.pdfmaster.app.model.CompressionPreset
import com.pdfmaster.app.model.ImageScaleType
import com.pdfmaster.app.model.ImageToPdfSettings
import com.pdfmaster.app.model.OperationResult
import com.pdfmaster.app.model.PageMargin
import com.pdfmaster.app.model.PageOrientation
import com.pdfmaster.app.model.PdfPageSize
import com.pdfmaster.app.model.TextToPdfSettings
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PdfEngine {

    fun mergePdfs(
        context: Context,
        inputUris: List<Uri>,
        customOutputName: String? = null,
        onProgress: (Float) -> Unit = {}
    ): OperationResult {
        if (inputUris.size < 2) {
            return OperationResult(success = false, errorMessage = "Select at least 2 PDF files to merge.")
        }
        val tempFiles = mutableListOf<File>()
        return try {
            val merger = PDFMergerUtility()
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val name = if (!customOutputName.isNullOrBlank()) {
                if (customOutputName.endsWith(".pdf", ignoreCase = true)) customOutputName else "$customOutputName.pdf"
            } else {
                "Merged_$timeStamp.pdf"
            }
            val outputFile = File(FileUtils.getOutputDir(context), name)
            
            var totalOriginalBytes = 0L
            val totalCount = inputUris.size

            for ((index, uri) in inputUris.withIndex()) {
                totalOriginalBytes += FileUtils.getFileSize(context, uri)
                val tempFile = FileUtils.copyUriToTempFile(context, uri, prefix = "merge_in_$index")
                tempFiles.add(tempFile)
                merger.addSource(tempFile)
                onProgress((index + 1) * 0.4f / totalCount)
            }

            merger.destinationFileName = outputFile.absolutePath
            merger.mergeDocuments(null)

            onProgress(0.9f)
            val resultUri = Uri.fromFile(outputFile)
            val finalPageCount = FileUtils.getPdfPageCount(context, resultUri)
            onProgress(1.0f)

            OperationResult(
                success = true,
                outputUri = resultUri,
                outputPath = outputFile.absolutePath,
                outputName = outputFile.name,
                outputSizeBytes = outputFile.length(),
                originalSizeBytes = totalOriginalBytes,
                pagesCount = finalPageCount
            )
        } catch (e: Exception) {
            e.printStackTrace()
            OperationResult(success = false, errorMessage = e.localizedMessage ?: "Failed to merge PDF files.")
        } finally {
            tempFiles.forEach { it.delete() }
        }
    }

    fun splitPdf(
        context: Context,
        inputUri: Uri,
        pageRangeStr: String,
        customOutputName: String? = null,
        onProgress: (Float) -> Unit = {}
    ): OperationResult {
        var tempInputFile: File? = null
        var document: PDDocument? = null
        var splitDocument: PDDocument? = null
        return try {
            tempInputFile = FileUtils.copyUriToTempFile(context, inputUri, prefix = "split_in")
            document = PDDocument.load(tempInputFile)
            val totalPages = document.numberOfPages
            
            val targetPages = FileUtils.parsePageRanges(pageRangeStr, totalPages)
            if (targetPages.isEmpty()) {
                return OperationResult(success = false, errorMessage = "No valid page range specified.")
            }

            onProgress(0.3f)
            splitDocument = PDDocument()
            for ((idx, pageNum) in targetPages.withIndex()) {
                // 1-based to 0-based
                val pageIndex = pageNum - 1
                if (pageIndex in 0 until totalPages) {
                    splitDocument.addPage(document.getPage(pageIndex))
                }
                onProgress(0.3f + (idx + 1) * 0.5f / targetPages.size)
            }

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val name = if (!customOutputName.isNullOrBlank()) {
                if (customOutputName.endsWith(".pdf", ignoreCase = true)) customOutputName else "$customOutputName.pdf"
            } else {
                "Split_$timeStamp.pdf"
            }

            val outputFile = File(FileUtils.getOutputDir(context), name)
            splitDocument.save(outputFile)

            onProgress(1.0f)
            val resultUri = Uri.fromFile(outputFile)
            OperationResult(
                success = true,
                outputUri = resultUri,
                outputPath = outputFile.absolutePath,
                outputName = outputFile.name,
                outputSizeBytes = outputFile.length(),
                originalSizeBytes = tempInputFile.length(),
                pagesCount = targetPages.size
            )
        } catch (e: Exception) {
            e.printStackTrace()
            OperationResult(success = false, errorMessage = e.localizedMessage ?: "Failed to split PDF file.")
        } finally {
            splitDocument?.close()
            document?.close()
            tempInputFile?.delete()
        }
    }

    fun compressPdf(
        context: Context,
        inputUri: Uri,
        settings: CompressSettings,
        customOutputName: String? = null,
        onProgress: (Float) -> Unit = {}
    ): OperationResult {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        val newPdfDoc = PdfDocument()

        return try {
            val originalSize = FileUtils.getFileSize(context, inputUri)
            pfd = context.contentResolver.openFileDescriptor(inputUri, "r")
                ?: return OperationResult(success = false, errorMessage = "Cannot open input file descriptor.")

            renderer = PdfRenderer(pfd)
            val pageCount = renderer.pageCount
            if (pageCount <= 0) {
                return OperationResult(success = false, errorMessage = "PDF has no pages.")
            }

            val quality = when (settings.preset) {
                CompressionPreset.RECOMMENDED -> 60
                CompressionPreset.EXTREME -> 35
                CompressionPreset.LOW -> 85
                CompressionPreset.CUSTOM -> settings.customQuality
            }

            val targetDpi = when (settings.preset) {
                CompressionPreset.RECOMMENDED -> 150
                CompressionPreset.EXTREME -> 96
                CompressionPreset.LOW -> 200
                CompressionPreset.CUSTOM -> settings.customDpi
            }

            val scaleFactor = targetDpi / 300.0f

            for (i in 0 until pageCount) {
                val page = renderer.openPage(i)
                val pdfPageWidth = page.width
                val pdfPageHeight = page.height

                // Target render dimensions based on scaleFactor
                val renderWidth = (pdfPageWidth * scaleFactor.coerceIn(0.3f, 1.0f)).toInt().coerceAtLeast(100)
                val renderHeight = (pdfPageHeight * scaleFactor.coerceIn(0.3f, 1.0f)).toInt().coerceAtLeast(100)

                val bitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                // Compress bitmap to JPEG byte stream
                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                val compressedJpegBytes = baos.toByteArray()
                bitmap.recycle()

                val compressedBitmap = BitmapFactory.decodeByteArray(compressedJpegBytes, 0, compressedJpegBytes.size)

                // Add to new PDF document with original page size
                val pageInfo = PdfDocument.PageInfo.Builder(pdfPageWidth, pdfPageHeight, i + 1).create()
                val pdfPage = newPdfDoc.startPage(pageInfo)
                val pdfCanvas = pdfPage.canvas

                val matrix = Matrix()
                matrix.postScale(
                    pdfPageWidth.toFloat() / compressedBitmap.width,
                    pdfPageHeight.toFloat() / compressedBitmap.height
                )
                pdfCanvas.drawBitmap(compressedBitmap, matrix, null)
                newPdfDoc.finishPage(pdfPage)
                compressedBitmap.recycle()

                onProgress((i + 1).toFloat() / pageCount * 0.85f)
            }

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val name = if (!customOutputName.isNullOrBlank()) {
                if (customOutputName.endsWith(".pdf", ignoreCase = true)) customOutputName else "$customOutputName.pdf"
            } else {
                "Compressed_$timeStamp.pdf"
            }

            val outputFile = File(FileUtils.getOutputDir(context), name)
            FileOutputStream(outputFile).use { out ->
                newPdfDoc.writeTo(out)
            }

            onProgress(1.0f)
            val resultUri = Uri.fromFile(outputFile)

            OperationResult(
                success = true,
                outputUri = resultUri,
                outputPath = outputFile.absolutePath,
                outputName = outputFile.name,
                outputSizeBytes = outputFile.length(),
                originalSizeBytes = originalSize,
                pagesCount = pageCount
            )
        } catch (e: Exception) {
            e.printStackTrace()
            OperationResult(success = false, errorMessage = e.localizedMessage ?: "Failed to compress PDF.")
        } finally {
            newPdfDoc.close()
            renderer?.close()
            pfd?.close()
        }
    }

    fun imagesToPdf(
        context: Context,
        imageUris: List<Uri>,
        settings: ImageToPdfSettings,
        customOutputName: String? = null,
        onProgress: (Float) -> Unit = {}
    ): OperationResult {
        if (imageUris.isEmpty()) {
            return OperationResult(success = false, errorMessage = "Please select at least one image.")
        }

        val pdfDoc = PdfDocument()
        return try {
            val totalImages = imageUris.size
            var totalInputBytes = 0L

            val pageMarginPts = settings.margin.paddingDp * 2.83465f // convert mm/dp to pts

            for ((index, uri) in imageUris.withIndex()) {
                totalInputBytes += FileUtils.getFileSize(context, uri)

                var bitmap: Bitmap? = null
                context.contentResolver.openInputStream(uri)?.use { input ->
                    bitmap = BitmapFactory.decodeStream(input)
                }

                if (bitmap != null) {
                    val srcWidth = bitmap!!.width.toFloat()
                    val srcHeight = bitmap!!.height.toFloat()

                    // Standard A4 dimensions in points (595 x 842)
                    var pageWidth = 595
                    var pageHeight = 842

                    if (settings.orientation == PageOrientation.LANDSCAPE ||
                        (settings.orientation == PageOrientation.AUTO && srcWidth > srcHeight)
                    ) {
                        pageWidth = 842
                        pageHeight = 595
                    }

                    val availWidth = pageWidth - (2 * pageMarginPts)
                    val availHeight = pageHeight - (2 * pageMarginPts)

                    val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                    val page = pdfDoc.startPage(pageInfo)
                    val canvas = page.canvas
                    canvas.drawColor(Color.WHITE)

                    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

                    val drawMatrix = Matrix()

                    when (settings.scaleType) {
                        ImageScaleType.FIT_CENTER -> {
                            val scale = Math.min(availWidth / srcWidth, availHeight / srcHeight)
                            val destWidth = srcWidth * scale
                            val destHeight = srcHeight * scale
                            val dx = pageMarginPts + (availWidth - destWidth) / 2f
                            val dy = pageMarginPts + (availHeight - destHeight) / 2f
                            drawMatrix.postScale(scale, scale)
                            drawMatrix.postTranslate(dx, dy)
                        }
                        ImageScaleType.FILL_PAGE -> {
                            val scale = Math.max(pageWidth / srcWidth, pageHeight / srcHeight)
                            val dx = (pageWidth - srcWidth * scale) / 2f
                            val dy = (pageHeight - srcHeight * scale) / 2f
                            drawMatrix.postScale(scale, scale)
                            drawMatrix.postTranslate(dx, dy)
                        }
                        ImageScaleType.CENTER -> {
                            val dx = (pageWidth - srcWidth) / 2f
                            val dy = (pageHeight - srcHeight) / 2f
                            drawMatrix.postTranslate(dx, dy)
                        }
                    }

                    canvas.drawBitmap(bitmap!!, drawMatrix, paint)
                    pdfDoc.finishPage(page)
                    bitmap!!.recycle()
                }

                onProgress((index + 1).toFloat() / totalImages * 0.9f)
            }

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val name = if (!customOutputName.isNullOrBlank()) {
                if (customOutputName.endsWith(".pdf", ignoreCase = true)) customOutputName else "$customOutputName.pdf"
            } else {
                "ImgToPdf_$timeStamp.pdf"
            }

            val outputFile = File(FileUtils.getOutputDir(context), name)
            FileOutputStream(outputFile).use { out ->
                pdfDoc.writeTo(out)
            }

            onProgress(1.0f)
            val resultUri = Uri.fromFile(outputFile)

            OperationResult(
                success = true,
                outputUri = resultUri,
                outputPath = outputFile.absolutePath,
                outputName = outputFile.name,
                outputSizeBytes = outputFile.length(),
                originalSizeBytes = totalInputBytes,
                pagesCount = totalImages
            )
        } catch (e: Exception) {
            e.printStackTrace()
            OperationResult(success = false, errorMessage = e.localizedMessage ?: "Failed to convert images to PDF.")
        } finally {
            pdfDoc.close()
        }
    }

    fun textToPdf(
        context: Context,
        textContent: String,
        settings: TextToPdfSettings,
        customOutputName: String? = null,
        onProgress: (Float) -> Unit = {}
    ): OperationResult {
        if (textContent.isBlank()) {
            return OperationResult(success = false, errorMessage = "Text content cannot be empty.")
        }

        val pdfDoc = PdfDocument()
        return try {
            val pageWidth = settings.pageSize.widthPt.toInt()
            val pageHeight = settings.pageSize.heightPt.toInt()
            val marginPts = settings.margin.paddingDp * 2.83465f

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = settings.fontSizeSp * 1.33f
                typeface = Typeface.DEFAULT
            }

            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(30, 41, 59)
                textSize = (settings.fontSizeSp + 6f) * 1.33f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }

            val contentWidth = pageWidth - (marginPts * 2)
            val lineHeight = paint.fontSpacing * 1.3f

            // Break text into wrapped lines
            val rawLines = textContent.split("\n")
            val wrappedLines = mutableListOf<String>()

            for (rawLine in rawLines) {
                if (rawLine.isEmpty()) {
                    wrappedLines.add("")
                    continue
                }
                var currentLine = ""
                val words = rawLine.split(" ")
                for (word in words) {
                    val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
                    if (paint.measureText(testLine) <= contentWidth) {
                        currentLine = testLine
                    } else {
                        if (currentLine.isNotEmpty()) wrappedLines.add(currentLine)
                        currentLine = word
                    }
                }
                if (currentLine.isNotEmpty()) wrappedLines.add(currentLine)
            }

            var currentPageNum = 1
            var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, currentPageNum).create()
            var page = pdfDoc.startPage(pageInfo)
            var canvas = page.canvas
            canvas.drawColor(Color.WHITE)

            var yPos = marginPts + 20f

            // Draw Document Title if present
            if (settings.titleText.isNotBlank()) {
                canvas.drawText(settings.titleText, marginPts, yPos, titlePaint)
                yPos += titlePaint.fontSpacing + 15f
            }

            for ((lineIndex, line) in wrappedLines.withIndex()) {
                if (yPos + lineHeight > pageHeight - marginPts) {
                    // Draw Footer with page number
                    val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.GRAY
                        textSize = 10f * 1.33f
                    }
                    canvas.drawText("Page $currentPageNum", pageWidth - marginPts - 40f, pageHeight - (marginPts / 2), footerPaint)
                    pdfDoc.finishPage(page)

                    currentPageNum++
                    pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, currentPageNum).create()
                    page = pdfDoc.startPage(pageInfo)
                    canvas = page.canvas
                    canvas.drawColor(Color.WHITE)
                    yPos = marginPts + 20f
                }

                if (line.isNotEmpty()) {
                    canvas.drawText(line, marginPts, yPos, paint)
                }
                yPos += lineHeight
                onProgress((lineIndex + 1).toFloat() / wrappedLines.size * 0.9f)
            }

            // Draw final page footer
            val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.GRAY
                textSize = 10f * 1.33f
            }
            canvas.drawText("Page $currentPageNum", pageWidth - marginPts - 40f, pageHeight - (marginPts / 2), footerPaint)
            pdfDoc.finishPage(page)

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val name = if (!customOutputName.isNullOrBlank()) {
                if (customOutputName.endsWith(".pdf", ignoreCase = true)) customOutputName else "$customOutputName.pdf"
            } else {
                "TextToPdf_$timeStamp.pdf"
            }

            val outputFile = File(FileUtils.getOutputDir(context), name)
            FileOutputStream(outputFile).use { out ->
                pdfDoc.writeTo(out)
            }

            onProgress(1.0f)
            val resultUri = Uri.fromFile(outputFile)

            OperationResult(
                success = true,
                outputUri = resultUri,
                outputPath = outputFile.absolutePath,
                outputName = outputFile.name,
                outputSizeBytes = outputFile.length(),
                originalSizeBytes = textContent.toByteArray().size.toLong(),
                pagesCount = currentPageNum
            )
        } catch (e: Exception) {
            e.printStackTrace()
            OperationResult(success = false, errorMessage = e.localizedMessage ?: "Failed to convert text to PDF.")
        } finally {
            pdfDoc.close()
        }
    }

    fun pdfToImages(
        context: Context,
        pdfUri: Uri,
        onProgress: (Float) -> Unit = {}
    ): OperationResult {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            val originalSize = FileUtils.getFileSize(context, pdfUri)
            pfd = context.contentResolver.openFileDescriptor(pdfUri, "r")
                ?: return OperationResult(success = false, errorMessage = "Cannot open input PDF file.")

            renderer = PdfRenderer(pfd)
            val pageCount = renderer.pageCount

            val baseName = FileUtils.getFileName(context, pdfUri).removeSuffix(".pdf")
            val outputFolder = File(FileUtils.getOutputDir(context), "Images_$baseName")
            if (!outputFolder.exists()) outputFolder.mkdirs()

            var savedCount = 0
            for (i in 0 until pageCount) {
                val page = renderer.openPage(i)
                val width = page.width * 2
                val height = page.height * 2

                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                val imageFile = File(outputFolder, "Page_${i + 1}.png")
                FileOutputStream(imageFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                bitmap.recycle()
                savedCount++
                onProgress((i + 1).toFloat() / pageCount * 0.95f)
            }

            onProgress(1.0f)
            OperationResult(
                success = true,
                outputUri = Uri.fromFile(outputFolder),
                outputPath = outputFolder.absolutePath,
                outputName = outputFolder.name,
                outputSizeBytes = outputFolder.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                originalSizeBytes = originalSize,
                pagesCount = pageCount,
                extractedImagesCount = savedCount
            )
        } catch (e: Exception) {
            e.printStackTrace()
            OperationResult(success = false, errorMessage = e.localizedMessage ?: "Failed to extract images from PDF.")
        } finally {
            renderer?.close()
            pfd?.close()
        }
    }

    fun pdfToText(
        context: Context,
        pdfUri: Uri,
        onProgress: (Float) -> Unit = {}
    ): OperationResult {
        var tempFile: File? = null
        var pdDoc: PDDocument? = null
        return try {
            val originalSize = FileUtils.getFileSize(context, pdfUri)
            tempFile = FileUtils.copyUriToTempFile(context, pdfUri, prefix = "pdf_txt_in")

            onProgress(0.3f)
            pdDoc = PDDocument.load(tempFile)
            val pageCount = pdDoc.numberOfPages

            val stripper = PDFTextStripper()
            val text = stripper.getText(pdDoc)

            onProgress(0.8f)

            // Also save extracted text file for easy download/sharing
            val baseName = FileUtils.getFileName(context, pdfUri).removeSuffix(".pdf")
            val textFile = File(FileUtils.getOutputDir(context), "${baseName}_extracted.txt")
            textFile.writeText(text)

            onProgress(1.0f)

            OperationResult(
                success = true,
                outputUri = Uri.fromFile(textFile),
                outputPath = textFile.absolutePath,
                outputName = textFile.name,
                outputSizeBytes = textFile.length(),
                originalSizeBytes = originalSize,
                pagesCount = pageCount,
                extractedText = text
            )
        } catch (e: Exception) {
            e.printStackTrace()
            OperationResult(success = false, errorMessage = e.localizedMessage ?: "Failed to extract text from PDF.")
        } finally {
            pdDoc?.close()
            tempFile?.delete()
        }
    }

    fun protectPdf(
        context: Context,
        pdfUri: Uri,
        userPass: String,
        ownerPass: String = userPass,
        customOutputName: String? = null,
        onProgress: (Float) -> Unit = {}
    ): OperationResult {
        var tempFile: File? = null
        var pdDoc: PDDocument? = null
        return try {
            val originalSize = FileUtils.getFileSize(context, pdfUri)
            tempFile = FileUtils.copyUriToTempFile(context, pdfUri, prefix = "protect_in")
            pdDoc = PDDocument.load(tempFile)

            onProgress(0.4f)
            val ap = AccessPermission()
            val spp = StandardProtectionPolicy(ownerPass, userPass, ap)
            spp.encryptionKeyLength = 128
            spp.permissions = ap

            pdDoc.protect(spp)

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val name = if (!customOutputName.isNullOrBlank()) {
                if (customOutputName.endsWith(".pdf", ignoreCase = true)) customOutputName else "$customOutputName.pdf"
            } else {
                "Protected_$timeStamp.pdf"
            }

            val outputFile = File(FileUtils.getOutputDir(context), name)
            pdDoc.save(outputFile)

            onProgress(1.0f)
            val resultUri = Uri.fromFile(outputFile)

            OperationResult(
                success = true,
                outputUri = resultUri,
                outputPath = outputFile.absolutePath,
                outputName = outputFile.name,
                outputSizeBytes = outputFile.length(),
                originalSizeBytes = originalSize,
                pagesCount = pdDoc.numberOfPages
            )
        } catch (e: Exception) {
            e.printStackTrace()
            OperationResult(success = false, errorMessage = e.localizedMessage ?: "Failed to protect PDF.")
        } finally {
            pdDoc?.close()
            tempFile?.delete()
        }
    }

    fun unlockPdf(
        context: Context,
        pdfUri: Uri,
        password: String,
        customOutputName: String? = null,
        onProgress: (Float) -> Unit = {}
    ): OperationResult {
        var tempFile: File? = null
        var pdDoc: PDDocument? = null
        return try {
            val originalSize = FileUtils.getFileSize(context, pdfUri)
            tempFile = FileUtils.copyUriToTempFile(context, pdfUri, prefix = "unlock_in")
            pdDoc = PDDocument.load(tempFile, password)

            onProgress(0.5f)
            pdDoc.isAllSecurityToBeRemoved = true

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val name = if (!customOutputName.isNullOrBlank()) {
                if (customOutputName.endsWith(".pdf", ignoreCase = true)) customOutputName else "$customOutputName.pdf"
            } else {
                "Unlocked_$timeStamp.pdf"
            }

            val outputFile = File(FileUtils.getOutputDir(context), name)
            pdDoc.save(outputFile)

            onProgress(1.0f)
            val resultUri = Uri.fromFile(outputFile)

            OperationResult(
                success = true,
                outputUri = resultUri,
                outputPath = outputFile.absolutePath,
                outputName = outputFile.name,
                outputSizeBytes = outputFile.length(),
                originalSizeBytes = originalSize,
                pagesCount = pdDoc.numberOfPages
            )
        } catch (e: Exception) {
            e.printStackTrace()
            OperationResult(success = false, errorMessage = e.localizedMessage ?: "Invalid password or cannot decrypt PDF.")
        } finally {
            pdDoc?.close()
            tempFile?.delete()
        }
    }
}

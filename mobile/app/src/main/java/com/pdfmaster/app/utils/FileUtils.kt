package com.pdfmaster.app.utils

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.DecimalFormat

object FileUtils {

    fun getFileName(context: Context, uri: Uri): String {
        var name = "Document.pdf"
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        name = it.getString(nameIndex) ?: "Document.pdf"
                    }
                }
            }
        } else if (uri.scheme == ContentResolver.SCHEME_FILE) {
            uri.path?.let {
                name = File(it).name
            }
        }
        return name
    }

    fun getFileSize(context: Context, uri: Uri): Long {
        var size = 0L
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex != -1) {
                        size = it.getLong(sizeIndex)
                    }
                }
            }
        } else if (uri.scheme == ContentResolver.SCHEME_FILE) {
            uri.path?.let {
                size = File(it).length()
            }
        }
        return size
    }

    fun formatFileSize(sizeBytes: Long): String {
        if (sizeBytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(sizeBytes.toDouble()) / Math.log10(1024.0)).toInt()
        val dec = DecimalFormat("#,##0.#")
        return "${dec.format(sizeBytes / Math.pow(1024.0, digitGroups.toDouble()))} ${units[digitGroups.coerceAtMost(3)]}"
    }

    fun copyUriToTempFile(context: Context, uri: Uri, prefix: String = "input_", suffix: String = ".pdf"): File {
        val tempFile = File.createTempFile(prefix, suffix, context.cacheDir)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }
        return tempFile
    }

    fun getOutputDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "PDFMaster")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getPdfPageCount(context: Context, uri: Uri): Int {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                renderer = PdfRenderer(pfd)
                renderer.pageCount
            } else 0
        } catch (e: Exception) {
            0
        } finally {
            renderer?.close()
            pfd?.close()
        }
    }

    fun renderPdfThumbnail(context: Context, uri: Uri, pageIndex: Int = 0): Bitmap? {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var page: PdfRenderer.Page? = null
        return try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            renderer = PdfRenderer(pfd)
            if (renderer.pageCount <= pageIndex) return null
            page = renderer.openPage(pageIndex)
            
            // Generate width & height for thumbnail
            val width = 240
            val height = (width * page.height.toFloat() / page.width.toFloat()).toInt()
            
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            // Fill background with white before rendering
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        } catch (e: Exception) {
            null
        } finally {
            page?.close()
            renderer?.close()
            pfd?.close()
        }
    }

    fun parsePageRanges(rangeStr: String, maxPage: Int): List<Int> {
        val pages = mutableSetOf<Int>()
        if (rangeStr.isBlank()) {
            return (1..maxPage).toList()
        }
        val parts = rangeStr.split(",")
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.contains("-")) {
                val bounds = trimmed.split("-")
                if (bounds.size == 2) {
                    val start = bounds[0].trim().toIntOrNull() ?: 1
                    val end = bounds[1].trim().toIntOrNull() ?: maxPage
                    for (p in start.coerceAtLeast(1)..end.coerceAtMost(maxPage)) {
                        pages.add(p)
                    }
                }
            } else {
                val page = trimmed.toIntOrNull()
                if (page != null && page in 1..maxPage) {
                    pages.add(page)
                }
            }
        }
        return pages.sorted()
    }
}

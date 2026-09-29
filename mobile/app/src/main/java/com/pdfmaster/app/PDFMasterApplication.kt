package com.pdfmaster.app

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class PDFMasterApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize PDFBox library for Android
        PDFBoxResourceLoader.init(applicationContext)
    }
}

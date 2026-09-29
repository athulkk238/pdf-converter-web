import { PDFDocument, rgb, StandardFonts, PageSizes } from 'pdf-lib';
import * as pdfjsLib from 'pdfjs-dist';

// Configure PDF.js worker
pdfjsLib.GlobalWorkerOptions.workerSrc = `https://cdnjs.cloudflare.com/ajax/libs/pdf.js/${pdfjsLib.version || '3.11.174'}/pdf.worker.min.js`;

export const formatFileSize = (bytes) => {
  if (bytes <= 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
};

export const parsePageRanges = (rangeStr, totalPages) => {
  if (!rangeStr || !rangeStr.trim()) {
    return Array.from({ length: totalPages }, (_, i) => i + 1);
  }
  const pages = new Set();
  const parts = rangeStr.split(',');
  for (const part of parts) {
    const trimmed = part.trim();
    if (trimmed.includes('-')) {
      const [startStr, endStr] = trimmed.split('-');
      const start = parseInt(startStr, 10) || 1;
      const end = parseInt(endStr, 10) || totalPages;
      for (let p = Math.max(1, start); p <= Math.min(totalPages, end); p++) {
        pages.add(p);
      }
    } else {
      const p = parseInt(trimmed, 10);
      if (p >= 1 && p <= totalPages) {
        pages.add(p);
      }
    }
  }
  return Array.from(pages).sort((a, b) => a - b);
};

// --- PDF Operations ---

export async function mergePdfs(files, onProgress) {
  if (!files || files.length < 2) {
    throw new Error('Please select at least 2 PDF files to merge.');
  }

  const mergedPdf = await PDFDocument.create();
  let totalInputBytes = 0;

  for (let i = 0; i < files.length; i++) {
    const file = files[i];
    totalInputBytes += file.size;
    const arrayBuffer = await file.arrayBuffer();
    const pdf = await PDFDocument.load(arrayBuffer);
    const copiedPages = await mergedPdf.copyPages(pdf, pdf.getPageIndices());
    copiedPages.forEach((page) => mergedPdf.addPage(page));

    if (onProgress) onProgress(((i + 1) / files.length) * 0.9);
  }

  const pdfBytes = await mergedPdf.save();
  const blob = new Blob([pdfBytes], { type: 'application/pdf' });

  return {
    blob,
    bytes: pdfBytes,
    outputName: `Merged_${Date.now()}.pdf`,
    outputSize: blob.size,
    originalSize: totalInputBytes,
    pageCount: mergedPdf.getPageCount()
  };
}

export async function splitPdf(file, pageRangeStr, onProgress) {
  if (!file) throw new Error('Please select a PDF file.');

  const arrayBuffer = await file.arrayBuffer();
  const srcPdf = await PDFDocument.load(arrayBuffer);
  const totalPages = srcPdf.getPageCount();

  const targetPages = parsePageRanges(pageRangeStr, totalPages);
  if (targetPages.length === 0) {
    throw new Error('No valid pages selected for splitting.');
  }

  const splitPdf = await PDFDocument.create();
  // 1-based to 0-based indices
  const pageIndices = targetPages.map((p) => p - 1);
  const copiedPages = await splitPdf.copyPages(srcPdf, pageIndices);
  copiedPages.forEach((page) => splitPdf.addPage(page));

  if (onProgress) onProgress(0.9);

  const pdfBytes = await splitPdf.save();
  const blob = new Blob([pdfBytes], { type: 'application/pdf' });

  return {
    blob,
    bytes: pdfBytes,
    outputName: `Split_${file.name.replace(/\.pdf$/i, '')}_${Date.now()}.pdf`,
    outputSize: blob.size,
    originalSize: file.size,
    pageCount: splitPdf.getPageCount()
  };
}

export async function compressPdf(file, qualityLevel = 0.6, targetDpi = 150, onProgress) {
  if (!file) throw new Error('Please select a PDF file.');

  const arrayBuffer = await file.arrayBuffer();
  const pdfDoc = await pdfjsLib.getDocument({ data: arrayBuffer }).promise;
  const numPages = pdfDoc.numPages;

  const newPdf = await PDFDocument.create();
  const scale = Math.min(1.0, targetDpi / 300);

  for (let i = 1; i <= numPages; i++) {
    const page = await pdfDoc.getPage(i);
    const viewport = page.getViewport({ scale: scale > 0.3 ? scale : 0.5 });

    const canvas = document.createElement('canvas');
    const ctx = canvas.getContext('2d');
    canvas.width = viewport.width;
    canvas.height = viewport.height;

    await page.render({ canvasContext: ctx, viewport }).promise;

    const jpegDataUrl = canvas.toDataURL('image/jpeg', qualityLevel);
    const jpegBytes = await fetch(jpegDataUrl).then((res) => res.arrayBuffer());

    const embeddedImage = await newPdf.embedJpg(jpegBytes);
    const origViewport = page.getViewport({ scale: 1.0 });

    const newPage = newPdf.addPage([origViewport.width, origViewport.height]);
    newPage.drawImage(embeddedImage, {
      x: 0,
      y: 0,
      width: origViewport.width,
      height: origViewport.height
    });

    if (onProgress) onProgress((i / numPages) * 0.9);
  }

  const pdfBytes = await newPdf.save();
  const blob = new Blob([pdfBytes], { type: 'application/pdf' });

  return {
    blob,
    bytes: pdfBytes,
    outputName: `Compressed_${file.name.replace(/\.pdf$/i, '')}.pdf`,
    outputSize: blob.size,
    originalSize: file.size,
    pageCount: numPages
  };
}

export async function imagesToPdf(imageFiles, options = {}, onProgress) {
  if (!imageFiles || imageFiles.length === 0) {
    throw new Error('Please select at least one image file.');
  }

  const pdfDoc = await PDFDocument.create();
  const margin = options.margin || 20; // pts
  let totalInputBytes = 0;

  for (let i = 0; i < imageFiles.length; i++) {
    const file = imageFiles[i];
    totalInputBytes += file.size;
    const arrayBuffer = await file.arrayBuffer();

    let embeddedImage;
    if (file.type === 'image/png') {
      embeddedImage = await pdfDoc.embedPng(arrayBuffer);
    } else {
      embeddedImage = await pdfDoc.embedJpg(arrayBuffer);
    }

    const { width: imgWidth, height: imgHeight } = embeddedImage;

    let pageWidth = PageSizes.A4[0];
    let pageHeight = PageSizes.A4[1];

    if (options.orientation === 'landscape' || (options.orientation === 'auto' && imgWidth > imgHeight)) {
      pageWidth = PageSizes.A4[1];
      pageHeight = PageSizes.A4[0];
    }

    const page = pdfDoc.addPage([pageWidth, pageHeight]);

    const availWidth = pageWidth - margin * 2;
    const availHeight = pageHeight - margin * 2;

    const scale = Math.min(availWidth / imgWidth, availHeight / imgHeight);
    const drawWidth = imgWidth * scale;
    const drawHeight = imgHeight * scale;

    const x = margin + (availWidth - drawWidth) / 2;
    const y = margin + (availHeight - drawHeight) / 2;

    page.drawImage(embeddedImage, {
      x,
      y,
      width: drawWidth,
      height: drawHeight
    });

    if (onProgress) onProgress(((i + 1) / imageFiles.length) * 0.9);
  }

  const pdfBytes = await pdfDoc.save();
  const blob = new Blob([pdfBytes], { type: 'application/pdf' });

  return {
    blob,
    bytes: pdfBytes,
    outputName: `Images_${Date.now()}.pdf`,
    outputSize: blob.size,
    originalSize: totalInputBytes,
    pageCount: imageFiles.length
  };
}

export async function textToPdf(textContent, docTitle = '', options = {}, onProgress) {
  if (!textContent || !textContent.trim()) {
    throw new Error('Please enter text content.');
  }

  const pdfDoc = await PDFDocument.create();
  const font = await pdfDoc.embedFont(StandardFonts.Helvetica);
  const boldFont = await pdfDoc.embedFont(StandardFonts.HelveticaBold);

  const fontSize = options.fontSize || 12;
  const margin = options.margin || 36;
  const [pageWidth, pageHeight] = PageSizes.A4;

  const contentWidth = pageWidth - margin * 2;
  const lineHeight = fontSize * 1.4;

  let page = pdfDoc.addPage([pageWidth, pageHeight]);
  let y = pageHeight - margin;

  if (docTitle) {
    page.drawText(docTitle, {
      x: margin,
      y: y - 10,
      size: fontSize + 6,
      font: boldFont,
      color: rgb(0.1, 0.15, 0.25)
    });
    y -= fontSize + 30;
  }

  const rawLines = textContent.split('\n');
  const wrappedLines = [];

  for (const rawLine of rawLines) {
    if (!rawLine.trim()) {
      wrappedLines.add('');
      continue;
    }
    const words = rawLine.split(' ');
    let currentLine = '';
    for (const word of words) {
      const testLine = currentLine ? `${currentLine} ${word}` : word;
      const width = font.widthOfTextAtSize(testLine, fontSize);
      if (width <= contentWidth) {
        currentLine = testLine;
      } else {
        if (currentLine) wrappedLines.push(currentLine);
        currentLine = word;
      }
    }
    if (currentLine) wrappedLines.push(currentLine);
  }

  let currentPageNum = 1;

  for (let i = 0; i < wrappedLines.length; i++) {
    const line = wrappedLines[i];
    if (y < margin + 30) {
      // Draw footer page number
      page.drawText(`Page ${currentPageNum}`, {
        x: pageWidth - margin - 40,
        y: margin / 2,
        size: 9,
        font,
        color: rgb(0.5, 0.5, 0.5)
      });
      page = pdfDoc.addPage([pageWidth, pageHeight]);
      currentPageNum++;
      y = pageHeight - margin;
    }

    if (line) {
      page.drawText(line, {
        x: margin,
        y,
        size: fontSize,
        font,
        color: rgb(0, 0, 0)
      });
    }
    y -= lineHeight;

    if (onProgress) onProgress(((i + 1) / wrappedLines.length) * 0.9);
  }

  // Draw final page footer
  page.drawText(`Page ${currentPageNum}`, {
    x: pageWidth - margin - 40,
    y: margin / 2,
    size: 9,
    font,
    color: rgb(0.5, 0.5, 0.5)
  });

  const pdfBytes = await pdfDoc.save();
  const blob = new Blob([pdfBytes], { type: 'application/pdf' });

  return {
    blob,
    bytes: pdfBytes,
    outputName: `Text_${Date.now()}.pdf`,
    outputSize: blob.size,
    originalSize: textContent.length,
    pageCount: currentPageNum
  };
}

export async function pdfToImages(file, onProgress) {
  if (!file) throw new Error('Please select a PDF file.');

  const arrayBuffer = await file.arrayBuffer();
  const pdfDoc = await pdfjsLib.getDocument({ data: arrayBuffer }).promise;
  const numPages = pdfDoc.numPages;

  const images = [];

  for (let i = 1; i <= numPages; i++) {
    const page = await pdfDoc.getPage(i);
    const viewport = page.getViewport({ scale: 2.0 }); // high resolution

    const canvas = document.createElement('canvas');
    const ctx = canvas.getContext('2d');
    canvas.width = viewport.width;
    canvas.height = viewport.height;

    await page.render({ canvasContext: ctx, viewport }).promise;

    const dataUrl = canvas.toDataURL('image/png');
    images.push({
      pageNum: i,
      dataUrl,
      fileName: `Page_${i}.png`
    });

    if (onProgress) onProgress((i / numPages) * 0.9);
  }

  return {
    images,
    outputName: `Images_${file.name.replace(/\.pdf$/i, '')}`,
    pageCount: numPages
  };
}

export async function pdfToText(file, onProgress) {
  if (!file) throw new Error('Please select a PDF file.');

  const arrayBuffer = await file.arrayBuffer();
  const pdfDoc = await pdfjsLib.getDocument({ data: arrayBuffer }).promise;
  const numPages = pdfDoc.numPages;

  let fullText = '';

  for (let i = 1; i <= numPages; i++) {
    const page = await pdfDoc.getPage(i);
    const textContent = await page.getTextContent();
    const pageText = textContent.items.map((item) => item.str).join(' ');
    fullText += `--- Page ${i} ---\n${pageText}\n\n`;

    if (onProgress) onProgress((i / numPages) * 0.9);
  }

  return {
    extractedText: fullText,
    outputName: `${file.name.replace(/\.pdf$/i, '')}_extracted.txt`,
    pageCount: numPages
  };
}

export async function renderPdfPageToCanvas(arrayBuffer, pageNum, canvas, scale = 1.5) {
  const pdfDoc = await pdfjsLib.getDocument({ data: arrayBuffer }).promise;
  if (pageNum < 1 || pageNum > pdfDoc.numPages) return null;

  const page = await pdfDoc.getPage(pageNum);
  const viewport = page.getViewport({ scale });

  const ctx = canvas.getContext('2d');
  canvas.width = viewport.width;
  canvas.height = viewport.height;

  await page.render({ canvasContext: ctx, viewport }).promise;
  return pdfDoc.numPages;
}

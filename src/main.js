import { createIcons, icons } from 'lucide';
import {
  mergePdfs,
  splitPdf,
  compressPdf,
  imagesToPdf,
  textToPdf,
  pdfToImages,
  pdfToText,
  renderPdfPageToCanvas,
  formatFileSize
} from './pdfEngine.js';

// --- Application State ---
const state = {
  activeTab: 'home',
  activeToolId: null,
  selectedFiles: [],
  history: JSON.parse(localStorage.getItem('pdf_master_web_history') || '[]'),
  totalSavedBytes: parseInt(localStorage.getItem('pdf_master_saved_bytes') || '0', 10),

  // Memory store for recent generated blobs (keyed by history item id)
  recentBlobs: new Map(),

  // Viewer State
  viewer: {
    arrayBuffer: null,
    fileName: '',
    currentPage: 1,
    totalPages: 1,
    scale: 1.25
  },

  // Extracted Images State
  extractedImages: []
};

// Tool Definitions
const TOOLS = [
  {
    id: 'merge',
    title: 'Merge PDF',
    desc: 'Combine multiple PDFs into a single document with custom ordering.',
    icon: 'merge',
    color: 'linear-gradient(135deg, #6366f1, #8b5cf6)'
  },
  {
    id: 'split',
    title: 'Split PDF',
    desc: 'Extract pages or split document into custom page ranges.',
    icon: 'split',
    color: 'linear-gradient(135deg, #ec4899, #f43f5e)'
  },
  {
    id: 'compress',
    title: 'Compress PDF',
    desc: 'Reduce file size with preset quality & resolution options.',
    icon: 'archive',
    color: 'linear-gradient(135deg, #10b981, #059669)'
  },
  {
    id: 'image-to-pdf',
    title: 'Image to PDF',
    desc: 'Convert photos, scans & PNG images into a clean PDF.',
    icon: 'image',
    color: 'linear-gradient(135deg, #f59e0b, #d97706)'
  },
  {
    id: 'text-to-pdf',
    title: 'Text to PDF',
    desc: 'Convert raw text or text files into formatted PDF documents.',
    icon: 'text',
    color: 'linear-gradient(135deg, #06b6d4, #0891b2)'
  },
  {
    id: 'pdf-to-images',
    title: 'PDF to Images',
    desc: 'Extract high-resolution PNG images from PDF pages.',
    icon: 'file-image',
    color: 'linear-gradient(135deg, #8b5cf6, #6d28d9)'
  },
  {
    id: 'pdf-to-text',
    title: 'PDF to Text',
    desc: 'Extract readable text from PDF with copy & save features.',
    icon: 'file-text',
    color: 'linear-gradient(135deg, #3b82f6, #1d4ed8)'
  }
];

// Initialize DOM elements
document.addEventListener('DOMContentLoaded', () => {
  renderIcons();
  updateStats();
  renderToolGrid();
  renderRecentActivity();
  setupEventListeners();
});

function renderIcons() {
  createIcons({ icons });
}

// Stats Update
function updateStats() {
  const processedEl = document.getElementById('stat-processed');
  const savedEl = document.getElementById('stat-saved');

  if (processedEl) processedEl.textContent = state.history.length;
  if (savedEl) savedEl.textContent = formatFileSize(state.totalSavedBytes);
}

// Render Tools Grid
function renderToolGrid(filterText = '') {
  const container = document.getElementById('tools-grid-container');
  if (!container) return;

  const filtered = TOOLS.filter((t) =>
    t.title.toLowerCase().includes(filterText.toLowerCase()) ||
    t.desc.toLowerCase().includes(filterText.toLowerCase())
  );

  container.innerHTML = filtered
    .map(
      (tool) => `
    <div class="tool-card" data-tool-id="${tool.id}">
      <div class="tool-card-icon" style="background: ${tool.color}">
        <i data-lucide="${tool.icon}"></i>
      </div>
      <h3 class="tool-card-title">${tool.title}</h3>
      <p class="tool-card-desc">${tool.desc}</p>
    </div>
  `
    )
    .join('');

  renderIcons();

  // Add click listeners
  container.querySelectorAll('.tool-card').forEach((card) => {
    card.addEventListener('click', () => {
      openToolWorkspace(card.dataset.toolId);
    });
  });
}

// Render Recent Activity
function renderRecentActivity() {
  const container = document.getElementById('recent-activity-container');
  const fullContainer = document.getElementById('full-history-container');
  if (!container) return;

  if (state.history.length === 0) {
    const emptyHtml = `
      <div class="dropzone" style="cursor: default; padding: 2rem;">
        <i data-lucide="inbox" style="width:48px;height:48px;color:var(--text-muted);"></i>
        <h4 style="margin-top:0.75rem;font-weight:600;">No recent operations</h4>
        <p style="font-size:0.875rem;color:var(--text-secondary);">Select any utility above to convert or process PDF files.</p>
      </div>
    `;
    container.innerHTML = emptyHtml;
    if (fullContainer) fullContainer.innerHTML = emptyHtml;
    renderIcons();
    return;
  }

  const renderItems = (items) =>
    items
      .map(
        (item) => `
    <div class="file-item-card">
      <div class="file-item-thumb"><i data-lucide="file-check"></i></div>
      <div class="file-item-info">
        <div class="file-item-name">${escapeHtml(item.title)}</div>
        <div class="file-item-meta">${escapeHtml(item.toolName)} • ${formatFileSize(item.size)} • ${new Date(item.timestamp).toLocaleDateString()}</div>
      </div>
      <div class="file-item-actions">
        ${
          state.recentBlobs.has(item.id)
            ? `<button class="btn btn-secondary btn-sm" onclick="viewHistoryPdf('${item.id}')"><i data-lucide="eye"></i> View</button>`
            : ''
        }
      </div>
    </div>
  `
      )
      .join('');

  container.innerHTML = renderItems(state.history.slice(0, 5));
  if (fullContainer) fullContainer.innerHTML = renderItems(state.history);
  renderIcons();
}

window.viewHistoryPdf = async (id) => {
  const blobInfo = state.recentBlobs.get(id);
  if (!blobInfo) {
    showToast('Document buffer unavailable in session memory', 'error');
    return;
  }
  const arrayBuffer = await blobInfo.blob.arrayBuffer();
  openPdfViewer(arrayBuffer, blobInfo.name);
};

// Open Workspace for Tool
function openToolWorkspace(toolId) {
  const tool = TOOLS.find((t) => t.id === toolId);
  if (!tool) return;

  state.activeToolId = toolId;
  state.selectedFiles = [];

  document.getElementById('workspace-title').textContent = tool.title;
  document.getElementById('workspace-desc').textContent = tool.desc;

  const contentArea = document.getElementById('workspace-content-area');

  let formHtml = '';

  if (toolId === 'merge') {
    formHtml = `
      <div class="workspace-card">
        <div class="dropzone" id="file-dropzone">
          <i data-lucide="folder-plus" class="dropzone-icon"></i>
          <h3 class="dropzone-title">Select or Drag & Drop PDFs to Merge</h3>
          <p class="dropzone-subtitle">Choose 2 or more PDF documents</p>
          <input type="file" id="file-input" multiple accept="application/pdf" style="display:none;" />
          <button class="btn btn-primary" onclick="document.getElementById('file-input').click()">Browse Files</button>
        </div>
        <div class="file-list" id="selected-files-list"></div>
        <div style="margin-top: 1.5rem; text-align: right;">
          <button class="btn btn-primary" id="execute-tool-btn" disabled><i data-lucide="merge"></i> Merge PDF Files</button>
        </div>
      </div>
    `;
  } else if (toolId === 'split') {
    formHtml = `
      <div class="workspace-card">
        <div class="dropzone" id="file-dropzone">
          <i data-lucide="scissors" class="dropzone-icon"></i>
          <h3 class="dropzone-title">Select PDF File to Split</h3>
          <p class="dropzone-subtitle">Choose a PDF file to extract pages</p>
          <input type="file" id="file-input" accept="application/pdf" style="display:none;" />
          <button class="btn btn-primary" onclick="document.getElementById('file-input').click()">Browse File</button>
        </div>
        <div class="file-list" id="selected-files-list"></div>
        <div class="form-group" style="margin-top: 1.5rem;">
          <label class="form-label">Page Ranges to Extract</label>
          <input type="text" class="form-input" id="page-range-input" placeholder="e.g. 1-3, 5, 8-12 (leave blank for all pages)" />
        </div>
        <div style="text-align: right;">
          <button class="btn btn-primary" id="execute-tool-btn" disabled><i data-lucide="split"></i> Split & Extract Pages</button>
        </div>
      </div>
    `;
  } else if (toolId === 'compress') {
    formHtml = `
      <div class="workspace-card">
        <div class="dropzone" id="file-dropzone">
          <i data-lucide="archive" class="dropzone-icon"></i>
          <h3 class="dropzone-title">Select PDF to Compress</h3>
          <p class="dropzone-subtitle">Shrink file size while keeping graphics clean</p>
          <input type="file" id="file-input" accept="application/pdf" style="display:none;" />
          <button class="btn btn-primary" onclick="document.getElementById('file-input').click()">Browse File</button>
        </div>
        <div class="file-list" id="selected-files-list"></div>
        <div class="form-group" style="margin-top: 1.5rem;">
          <label class="form-label">Compression Quality Level</label>
          <select class="form-select" id="compress-level-select">
            <option value="recommended">Recommended (Balanced Quality & Size)</option>
            <option value="extreme">Extreme (Smallest Size, Lower Quality)</option>
            <option value="low">High Quality (Minor Compression)</option>
          </select>
        </div>
        <div style="text-align: right;">
          <button class="btn btn-primary" id="execute-tool-btn" disabled><i data-lucide="compress"></i> Compress PDF</button>
        </div>
      </div>
    `;
  } else if (toolId === 'image-to-pdf') {
    formHtml = `
      <div class="workspace-card">
        <div class="dropzone" id="file-dropzone">
          <i data-lucide="image" class="dropzone-icon"></i>
          <h3 class="dropzone-title">Select Images to Convert</h3>
          <p class="dropzone-subtitle">JPG, PNG, WEBP, GIF, BMP supported</p>
          <input type="file" id="file-input" multiple accept="image/*" style="display:none;" />
          <button class="btn btn-primary" onclick="document.getElementById('file-input').click()">Browse Images</button>
        </div>
        <div class="file-list" id="selected-files-list"></div>
        <div class="form-group" style="margin-top: 1.5rem;">
          <label class="form-label">Page Orientation</label>
          <select class="form-select" id="orientation-select">
            <option value="auto">Auto Detect</option>
            <option value="portrait">Portrait</option>
            <option value="landscape">Landscape</option>
          </select>
        </div>
        <div style="text-align: right;">
          <button class="btn btn-primary" id="execute-tool-btn" disabled><i data-lucide="file-plus"></i> Convert Images to PDF</button>
        </div>
      </div>
    `;
  } else if (toolId === 'text-to-pdf') {
    formHtml = `
      <div class="workspace-card">
        <div class="form-group">
          <label class="form-label">Document Header Title (Optional)</label>
          <input type="text" class="form-input" id="doc-title-input" placeholder="e.g. Project Notes Summary" />
        </div>
        <div class="form-group">
          <label class="form-label">Text Content</label>
          <textarea class="form-textarea" id="text-content-input" rows="8" placeholder="Type or paste text content here..."></textarea>
        </div>
        <div style="text-align: right;">
          <button class="btn btn-primary" id="execute-tool-btn"><i data-lucide="file-text"></i> Generate PDF</button>
        </div>
      </div>
    `;
  } else if (toolId === 'pdf-to-images') {
    formHtml = `
      <div class="workspace-card">
        <div class="dropzone" id="file-dropzone">
          <i data-lucide="images" class="dropzone-icon"></i>
          <h3 class="dropzone-title">Select PDF to Extract Images</h3>
          <p class="dropzone-subtitle">Render all pages to PNG images</p>
          <input type="file" id="file-input" accept="application/pdf" style="display:none;" />
          <button class="btn btn-primary" onclick="document.getElementById('file-input').click()">Browse File</button>
        </div>
        <div class="file-list" id="selected-files-list"></div>
        <div style="margin-top: 1.5rem; text-align: right;">
          <button class="btn btn-primary" id="execute-tool-btn" disabled><i data-lucide="download"></i> Extract Page Images</button>
        </div>
      </div>
    `;
  } else if (toolId === 'pdf-to-text') {
    formHtml = `
      <div class="workspace-card">
        <div class="dropzone" id="file-dropzone">
          <i data-lucide="file-text" class="dropzone-icon"></i>
          <h3 class="dropzone-title">Select PDF to Extract Text</h3>
          <p class="dropzone-subtitle">Extract raw readable text content</p>
          <input type="file" id="file-input" accept="application/pdf" style="display:none;" />
          <button class="btn btn-primary" onclick="document.getElementById('file-input').click()">Browse File</button>
        </div>
        <div class="file-list" id="selected-files-list"></div>
        <div class="form-group" style="margin-top: 1.5rem; display: none;" id="extracted-text-group">
          <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.5rem;">
            <label class="form-label" style="margin-bottom: 0;">Extracted Content</label>
            <div style="display: flex; gap: 0.5rem;">
              <button class="btn btn-secondary btn-sm" id="copy-text-btn"><i data-lucide="copy"></i> Copy Text</button>
              <button class="btn btn-secondary btn-sm" id="download-txt-btn"><i data-lucide="download"></i> Download .txt</button>
            </div>
          </div>
          <textarea class="form-textarea" id="extracted-text-area" rows="8" readonly></textarea>
        </div>
        <div style="margin-top: 1.5rem; text-align: right;">
          <button class="btn btn-primary" id="execute-tool-btn" disabled><i data-lucide="file-search"></i> Extract Text</button>
        </div>
      </div>
    `;
  }

  contentArea.innerHTML = formHtml;
  renderIcons();

  switchView('workspace');
  setupWorkspaceDropzone();
}

// Dropzone & File List Handlers
function setupWorkspaceDropzone() {
  const dropzone = document.getElementById('file-dropzone');
  const fileInput = document.getElementById('file-input');
  const executeBtn = document.getElementById('execute-tool-btn');

  if (fileInput) {
    fileInput.addEventListener('change', (e) => {
      handleFilesSelected(Array.from(e.target.files));
    });
  }

  if (dropzone) {
    ['dragenter', 'dragover'].forEach((eventName) => {
      dropzone.addEventListener(eventName, (e) => {
        e.preventDefault();
        dropzone.classList.add('dragover');
      });
    });

    ['dragleave', 'drop'].forEach((eventName) => {
      dropzone.addEventListener(eventName, (e) => {
        e.preventDefault();
        dropzone.classList.remove('dragover');
      });
    });

    dropzone.addEventListener('drop', (e) => {
      const files = Array.from(e.dataTransfer.files);
      handleFilesSelected(files);
    });
  }

  if (executeBtn) {
    executeBtn.addEventListener('click', handleExecuteOperation);
  }
}

function handleFilesSelected(files) {
  if (!files || files.length === 0) return;

  // Single file tools replace selection, multi file tools append
  if (['split', 'compress', 'pdf-to-images', 'pdf-to-text'].includes(state.activeToolId)) {
    state.selectedFiles = [files[0]];
  } else {
    state.selectedFiles = state.selectedFiles.concat(files);
  }

  renderSelectedFilesList();
}

function renderSelectedFilesList() {
  const listContainer = document.getElementById('selected-files-list');
  const executeBtn = document.getElementById('execute-tool-btn');
  if (!listContainer) return;

  const isReorderable = ['merge', 'image-to-pdf'].includes(state.activeToolId);

  listContainer.innerHTML = state.selectedFiles
    .map(
      (file, idx) => `
    <div class="file-item-card">
      <div class="file-item-thumb"><i data-lucide="${file.type.startsWith('image/') ? 'image' : 'file-text'}"></i></div>
      <div class="file-item-info">
        <div class="file-item-name">${escapeHtml(file.name)}</div>
        <div class="file-item-meta">${formatFileSize(file.size)}</div>
      </div>
      <div class="file-item-actions">
        ${
          isReorderable && idx > 0
            ? `<button class="btn btn-icon btn-icon-sm" onclick="moveSelectedFile(${idx}, -1)" title="Move Up"><i data-lucide="chevron-up"></i></button>`
            : ''
        }
        ${
          isReorderable && idx < state.selectedFiles.length - 1
            ? `<button class="btn btn-icon btn-icon-sm" onclick="moveSelectedFile(${idx}, 1)" title="Move Down"><i data-lucide="chevron-down"></i></button>`
            : ''
        }
        <button class="btn btn-icon btn-icon-sm" onclick="removeSelectedFile(${idx})" title="Remove File"><i data-lucide="x"></i></button>
      </div>
    </div>
  `
    )
    .join('');

  renderIcons();

  if (executeBtn) {
    if (state.activeToolId === 'merge') {
      executeBtn.disabled = state.selectedFiles.length < 2;
    } else {
      executeBtn.disabled = state.selectedFiles.length === 0;
    }
  }
}

window.removeSelectedFile = (idx) => {
  state.selectedFiles.splice(idx, 1);
  renderSelectedFilesList();
};

window.moveSelectedFile = (idx, direction) => {
  const targetIdx = idx + direction;
  if (targetIdx < 0 || targetIdx >= state.selectedFiles.length) return;
  const temp = state.selectedFiles[idx];
  state.selectedFiles[idx] = state.selectedFiles[targetIdx];
  state.selectedFiles[targetIdx] = temp;
  renderSelectedFilesList();
};

// Execute Operation
async function handleExecuteOperation() {
  const toolId = state.activeToolId;
  const progressModal = document.getElementById('progress-modal');
  const progressFill = document.getElementById('progress-bar-fill');
  const progressText = document.getElementById('progress-percentage-text');

  const setProgress = (ratio) => {
    const pct = Math.round(ratio * 100);
    if (progressFill) progressFill.style.width = `${pct}%`;
    if (progressText) progressText.textContent = `${pct}%`;
  };

  try {
    progressModal.classList.add('active');
    setProgress(0.1);

    let result;

    if (toolId === 'merge') {
      result = await mergePdfs(state.selectedFiles, setProgress);
      downloadBlob(result.blob, result.outputName);
    } else if (toolId === 'split') {
      const rangeStr = document.getElementById('page-range-input').value;
      result = await splitPdf(state.selectedFiles[0], rangeStr, setProgress);
      downloadBlob(result.blob, result.outputName);
    } else if (toolId === 'compress') {
      const level = document.getElementById('compress-level-select').value;
      const q = level === 'extreme' ? 0.35 : level === 'low' ? 0.85 : 0.6;
      const dpi = level === 'extreme' ? 96 : level === 'low' ? 200 : 150;

      result = await compressPdf(state.selectedFiles[0], q, dpi, setProgress);
      downloadBlob(result.blob, result.outputName);

      if (result.originalSize > result.outputSize) {
        state.totalSavedBytes += result.originalSize - result.outputSize;
        localStorage.setItem('pdf_master_saved_bytes', state.totalSavedBytes.toString());
      }
    } else if (toolId === 'image-to-pdf') {
      const orientation = document.getElementById('orientation-select').value;
      result = await imagesToPdf(state.selectedFiles, { orientation }, setProgress);
      downloadBlob(result.blob, result.outputName);
    } else if (toolId === 'text-to-pdf') {
      const text = document.getElementById('text-content-input').value;
      const title = document.getElementById('doc-title-input').value;
      result = await textToPdf(text, title, {}, setProgress);
      downloadBlob(result.blob, result.outputName);
    } else if (toolId === 'pdf-to-images') {
      result = await pdfToImages(state.selectedFiles[0], setProgress);
      openExtractedImagesModal(result.images, result.outputName);
    } else if (toolId === 'pdf-to-text') {
      result = await pdfToText(state.selectedFiles[0], setProgress);
      const txtGroup = document.getElementById('extracted-text-group');
      const txtArea = document.getElementById('extracted-text-area');
      if (txtGroup && txtArea) {
        txtGroup.style.display = 'block';
        txtArea.value = result.extractedText;

        document.getElementById('copy-text-btn').onclick = () => {
          navigator.clipboard.writeText(result.extractedText);
          showToast('Text copied to clipboard!', 'success');
        };

        document.getElementById('download-txt-btn').onclick = () => {
          const blob = new Blob([result.extractedText], { type: 'text/plain' });
          downloadBlob(blob, result.outputName);
        };
      }
    }

    setProgress(1.0);

    // Save to history
    const tool = TOOLS.find((t) => t.id === toolId);
    const historyId = Date.now().toString();

    if (result && result.blob) {
      state.recentBlobs.set(historyId, { blob: result.blob, name: result.outputName });
    }

    const historyItem = {
      id: historyId,
      title: result?.outputName || tool.title,
      toolName: tool.title,
      timestamp: Date.now(),
      size: result?.outputSize || 0
    };

    state.history.unshift(historyItem);
    if (state.history.length > 50) state.history.pop();
    localStorage.setItem('pdf_master_web_history', JSON.stringify(state.history));

    updateStats();
    renderRecentActivity();

    showToast('Operation completed successfully!', 'success');

    // Automatically open viewer for PDF output
    if (result && result.bytes) {
      setTimeout(() => {
        openPdfViewer(result.bytes.buffer, result.outputName);
      }, 600);
    }
  } catch (err) {
    showToast(err.message || 'Operation failed.', 'error');
  } finally {
    setTimeout(() => {
      progressModal.classList.remove('active');
    }, 500);
  }
}

// Extracted Images Gallery Modal Logic
function openExtractedImagesModal(images, docTitle) {
  state.extractedImages = images;
  const modal = document.getElementById('images-modal');
  const titleEl = document.getElementById('images-modal-title');
  const countEl = document.getElementById('images-modal-count');
  const grid = document.getElementById('extracted-images-grid');

  if (titleEl) titleEl.textContent = docTitle;
  if (countEl) countEl.textContent = `${images.length} Images`;

  if (grid) {
    grid.innerHTML = images
      .map(
        (img, idx) => `
      <div class="image-grid-card">
        <div class="image-preview-container">
          <img src="${img.dataUrl}" alt="Page ${img.pageNum}" />
        </div>
        <div class="image-card-footer">
          <span class="image-card-title">Page ${img.pageNum}</span>
          <button class="btn btn-secondary btn-sm" onclick="downloadSingleImage(${idx})"><i data-lucide="download"></i> Download</button>
        </div>
      </div>
    `
      )
      .join('');
  }

  renderIcons();
  modal.classList.add('active');
}

window.downloadSingleImage = (idx) => {
  const img = state.extractedImages[idx];
  if (!img) return;
  const link = document.createElement('a');
  link.href = img.dataUrl;
  link.download = img.fileName;
  link.click();
};

function downloadAllExtractedImages() {
  if (!state.extractedImages || state.extractedImages.length === 0) return;
  state.extractedImages.forEach((img, i) => {
    setTimeout(() => {
      const link = document.createElement('a');
      link.href = img.dataUrl;
      link.download = img.fileName;
      link.click();
    }, i * 300);
  });
  showToast('Downloading all page images...', 'success');
}

// Download Helper
function downloadBlob(blob, fileName) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

// Navigation & Event Listeners
function setupEventListeners() {
  document.getElementById('nav-home-btn').addEventListener('click', () => switchView('home'));
  document.getElementById('nav-history-btn').addEventListener('click', () => switchView('history'));
  document.getElementById('view-all-history-btn').addEventListener('click', () => switchView('history'));
  document.getElementById('brand-logo').addEventListener('click', () => switchView('home'));
  document.getElementById('workspace-back-btn').addEventListener('click', () => switchView('home'));

  document.getElementById('tool-search-input').addEventListener('input', (e) => {
    renderToolGrid(e.target.value);
  });

  document.getElementById('clear-history-btn').addEventListener('click', () => {
    state.history = [];
    state.totalSavedBytes = 0;
    state.recentBlobs.clear();
    localStorage.removeItem('pdf_master_web_history');
    localStorage.removeItem('pdf_master_saved_bytes');
    updateStats();
    renderRecentActivity();
    showToast('History cleared', 'success');
  });

  // Viewer Modal Listeners
  document.getElementById('viewer-close-btn').addEventListener('click', closePdfViewer);
  document.getElementById('viewer-prev-page').addEventListener('click', () => changeViewerPage(-1));
  document.getElementById('viewer-next-page').addEventListener('click', () => changeViewerPage(1));
  document.getElementById('viewer-zoom-in').addEventListener('click', () => changeViewerZoom(0.25));
  document.getElementById('viewer-zoom-out').addEventListener('click', () => changeViewerZoom(-0.25));
  document.getElementById('viewer-download-btn').addEventListener('click', downloadCurrentViewerPdf);

  // Extracted Images Modal Listeners
  document.getElementById('images-close-btn').addEventListener('click', () => {
    document.getElementById('images-modal').classList.remove('active');
  });
  document.getElementById('images-download-all-btn').addEventListener('click', downloadAllExtractedImages);
}

function switchView(viewName) {
  document.querySelectorAll('.nav-btn').forEach((btn) => btn.classList.remove('active'));
  document.querySelectorAll('.view-section').forEach((sec) => sec.classList.remove('active'));

  if (viewName === 'home') {
    document.getElementById('nav-home-btn').classList.add('active');
    document.getElementById('tools-view').classList.add('active');
    document.getElementById('hero-banner').style.display = 'block';
  } else if (viewName === 'history') {
    document.getElementById('nav-history-btn').classList.add('active');
    document.getElementById('history-view').classList.add('active');
    document.getElementById('hero-banner').style.display = 'none';
  } else if (viewName === 'workspace') {
    document.getElementById('workspace-view').classList.add('active');
    document.getElementById('hero-banner').style.display = 'none';
  }
}

// PDF Viewer Logic
export async function openPdfViewer(arrayBuffer, fileName = 'Document.pdf') {
  state.viewer.arrayBuffer = arrayBuffer;
  state.viewer.fileName = fileName;
  state.viewer.currentPage = 1;
  state.viewer.scale = 1.25;

  document.getElementById('viewer-doc-name').textContent = fileName;
  document.getElementById('viewer-modal').classList.add('active');

  await renderViewerPage();
}

async function renderViewerPage() {
  const canvas = document.getElementById('pdf-viewer-canvas');
  if (!canvas || !state.viewer.arrayBuffer) return;

  const totalPages = await renderPdfPageToCanvas(
    state.viewer.arrayBuffer,
    state.viewer.currentPage,
    canvas,
    state.viewer.scale
  );

  state.viewer.totalPages = totalPages || 1;
  document.getElementById('viewer-page-counter').textContent = `Page ${state.viewer.currentPage} of ${state.viewer.totalPages}`;
}

async function changeViewerPage(delta) {
  const newPage = state.viewer.currentPage + delta;
  if (newPage >= 1 && newPage <= state.viewer.totalPages) {
    state.viewer.currentPage = newPage;
    await renderViewerPage();
  }
}

async function changeViewerZoom(delta) {
  const newScale = Math.min(3.0, Math.max(0.5, state.viewer.scale + delta));
  if (newScale !== state.viewer.scale) {
    state.viewer.scale = newScale;
    await renderViewerPage();
  }
}

function downloadCurrentViewerPdf() {
  if (!state.viewer.arrayBuffer) return;
  const blob = new Blob([state.viewer.arrayBuffer], { type: 'application/pdf' });
  downloadBlob(blob, state.viewer.fileName || 'Document.pdf');
}

function closePdfViewer() {
  document.getElementById('viewer-modal').classList.remove('active');
}

// Toast Notifications
function showToast(message, type = 'info') {
  const container = document.getElementById('toast-container');
  if (!container) return;

  const toast = document.createElement('div');
  toast.className = `toast ${type}`;
  toast.innerHTML = `
    <i data-lucide="${type === 'success' ? 'check-circle' : 'alert-circle'}"></i>
    <span>${escapeHtml(message)}</span>
  `;

  container.appendChild(toast);
  renderIcons();

  setTimeout(() => {
    toast.remove();
  }, 4000);
}

function escapeHtml(str) {
  return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}


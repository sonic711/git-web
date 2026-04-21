const diffState = {
  ruleId: '',
  diff: null,
  selectedFileIndex: -1,
  patchCache: {},
  confirmed: false,
  loadingCount: 0,
};

async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  const data = await response.json();
  if (!response.ok) {
    const error = new Error(data.error?.message || 'Request failed');
    error.code = data.error?.code;
    throw error;
  }
  return data;
}

function showToast(message, type = 'success') {
  const toast = document.getElementById('toast');
  toast.textContent = message;
  toast.className = `toast ${type}`;
  setTimeout(() => {
    toast.className = 'toast hidden';
  }, 3500);
}

function showLoading(message = '作業中...') {
  diffState.loadingCount += 1;
  document.getElementById('loadingMessage').textContent = message;
  document.getElementById('loadingOverlay').classList.remove('hidden');
}

function hideLoading() {
  diffState.loadingCount = Math.max(0, diffState.loadingCount - 1);
  if (diffState.loadingCount === 0) {
    document.getElementById('loadingOverlay').classList.add('hidden');
    document.getElementById('loadingMessage').textContent = '作業中...';
  }
}

async function withLoading(message, action) {
  showLoading(message);
  try {
    return await action();
  } finally {
    hideLoading();
  }
}

function escapeHtml(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll("'", '&#39;')
    .replaceAll('"', '&quot;');
}

function formatDateTime(value) {
  if (!value) {
    return '-';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return String(value);
  }
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  const hour = String(date.getHours()).padStart(2, '0');
  const minute = String(date.getMinutes()).padStart(2, '0');
  const second = String(date.getSeconds()).padStart(2, '0');
  return `${year}-${month}-${day} ${hour}:${minute}:${second}`;
}

async function loadCachedSummary() {
  try {
    const diff = await withLoading('載入差異快取中...', () =>
      api(`/api/rules/${diffState.ruleId}/diff-cache`)
    );
    applySummary(diff);
    showToast('已載入差異快取', 'success');
  } catch (error) {
    diffState.diff = null;
    diffState.selectedFileIndex = -1;
    diffState.patchCache = {};
    render();
    if (error.code === 'DIFF_CACHE_NOT_FOUND') {
      document.getElementById('diffPageSubtitle').textContent = '尚未建立差異快取，請按「抓取最新差異」';
      document.getElementById('diffPatchView').textContent = '尚未建立差異快取，請按右上角「抓取最新差異」。';
      showToast('尚未建立差異快取', 'error');
      return;
    }
    showToast(error.message, 'error');
    document.getElementById('diffPatchView').textContent = error.message;
  }
}

async function refreshDiffSummary() {
  try {
    const diff = await withLoading('抓取最新差異中...', () =>
      api(`/api/rules/${diffState.ruleId}/diff-cache/refresh`, { method: 'POST' })
    );
    applySummary(diff);
    showToast('最新差異已更新', 'success');
  } catch (error) {
    showToast(error.message, 'error');
  }
}

function applySummary(diff) {
  diffState.diff = diff;
  diffState.confirmed = false;
  diffState.selectedFileIndex = -1;
  diffState.patchCache = {};
  render();
}

function render() {
  renderMeta();
  renderFileList();
  renderPatch();
  renderConfirmButton();
}

function renderMeta() {
  const diff = diffState.diff;
  if (!diff) {
    document.getElementById('diffPageSubtitle').textContent = '尚未載入差異快取';
    document.getElementById('diffMeta').innerHTML = '';
    return;
  }
  document.getElementById('diffPageSubtitle').textContent =
    `${diff.projectName} / ${diff.ruleName} / ${diff.sourceBranch} -> ${diff.targetBranch}`;
  const commitItems = (diff.commits || []).map(item =>
    `<li><code>${escapeHtml(item.id)}</code> ${escapeHtml(item.title)}</li>`
  ).join('');
  document.getElementById('diffMeta').innerHTML = `
    <div class="diff-meta-item"><strong>Project</strong><span>${escapeHtml(diff.projectName || '-')}</span></div>
    <div class="diff-meta-item"><strong>Rule</strong><span>${escapeHtml(diff.ruleName || '-')}</span></div>
    <div class="diff-meta-item"><strong>Source</strong><span>${escapeHtml(diff.sourceBranch || '-')}</span></div>
    <div class="diff-meta-item"><strong>Target</strong><span>${escapeHtml(diff.targetBranch || '-')}</span></div>
    <div class="diff-meta-item"><strong>Remote</strong><span>${escapeHtml(diff.targetRemoteName || '-')}</span></div>
    <div class="diff-meta-item"><strong>Repo</strong><span>${escapeHtml(diff.targetRepoName || '-')}</span></div>
    <div class="diff-meta-item"><strong>Commits Ahead</strong><span>${escapeHtml(diff.summary?.aheadCommits ?? 0)}</span></div>
    <div class="diff-meta-item"><strong>Changed Files</strong><span>${escapeHtml(diff.summary?.changedFiles ?? 0)}</span></div>
    <div class="diff-meta-item"><strong>Cache Status</strong><span>${escapeHtml(diff.cacheStatus || '-')}</span></div>
    <div class="diff-meta-item"><strong>Cached At</strong><span>${escapeHtml(formatDateTime(diff.cachedAt))}</span></div>
    <div class="diff-meta-item diff-meta-wide"><strong>Target URL</strong><span>${escapeHtml(diff.targetRemoteUrl || '-')}</span></div>
    <div class="diff-meta-item diff-meta-wide"><strong>Cache Message</strong><span>${escapeHtml(diff.lastRefreshMessage || '-')}</span></div>
    <div class="diff-meta-item diff-meta-wide"><strong>Commits</strong><ul class="diff-commit-list">${commitItems || '<li>(none)</li>'}</ul></div>
  `;
}

function renderFileList() {
  const root = document.getElementById('diffFileList');
  if (!diffState.diff) {
    root.innerHTML = '<div class="viewer empty">尚未載入差異快取</div>';
    return;
  }
  const files = diffState.diff.files || [];
  if (!files.length) {
    root.innerHTML = '<div class="viewer empty">沒有檔案差異</div>';
    return;
  }
  root.innerHTML = files.map((file, index) => `
    <button type="button"
      class="${index === diffState.selectedFileIndex ? 'diff-file-item active' : 'diff-file-item'}"
      onclick="selectDiffFile(${index})">
      <span class="diff-file-status ${statusClass(file.status)}">${escapeHtml(file.status)}</span>
      <span class="diff-file-path">${escapeHtml(file.displayPath || file.path)}</span>
    </button>
  `).join('');
}

function renderPatch() {
  const title = document.getElementById('diffFileTitle');
  const root = document.getElementById('diffPatchView');
  const files = diffState.diff?.files || [];
  const selected = diffState.selectedFileIndex >= 0 ? files[diffState.selectedFileIndex] : null;
  if (!selected) {
    title.textContent = '請從左側選擇檔案';
    root.textContent = diffState.diff ? '尚未載入檔案差異' : '尚未載入差異快取';
    return;
  }
  title.textContent = selected.displayPath || selected.path;
  const cachedPatch = diffState.patchCache[fileKey(selected)];
  if (cachedPatch == null) {
    root.textContent = '載入檔案差異中...';
    return;
  }
  const lines = String(cachedPatch || '').replace(/\r/g, '').split('\n');
  root.innerHTML = lines.map(line => {
    const escaped = escapeHtml(line || ' ');
    return `<div class="diff-line ${lineClass(line)}"><code>${escaped}</code></div>`;
  }).join('') || '<div class="diff-line diff-line-context"><code>(empty diff)</code></div>';
}

function renderConfirmButton() {
  const button = document.getElementById('confirmReviewButton');
  button.textContent = diffState.confirmed ? '已完成人工確認' : '人工確認本次同步';
  button.disabled = diffState.confirmed;
}

function statusClass(status) {
  if (status === 'A') return 'status-add';
  if (status === 'D') return 'status-remove';
  if (status === 'R') return 'status-rename';
  return 'status-modify';
}

function lineClass(line) {
  if (line.startsWith('+++') || line.startsWith('---') || line.startsWith('diff --git') || line.startsWith('index ')) {
    return 'diff-line-meta';
  }
  if (line.startsWith('@@')) {
    return 'diff-line-hunk';
  }
  if (line.startsWith('+')) {
    return 'diff-line-add';
  }
  if (line.startsWith('-')) {
    return 'diff-line-remove';
  }
  return 'diff-line-context';
}

function fileKey(file) {
  return `${file.oldPath || ''}=>${file.path || ''}`;
}

async function selectDiffFile(index) {
  diffState.selectedFileIndex = index;
  renderFileList();
  renderPatch();
  const file = diffState.diff?.files?.[index];
  if (!file) {
    return;
  }
  const key = fileKey(file);
  if (Object.prototype.hasOwnProperty.call(diffState.patchCache, key)) {
    return;
  }
  try {
    const data = await withLoading('載入檔案差異中...', () =>
      api(`/api/rules/${diffState.ruleId}/diff-cache/file`, {
        method: 'POST',
        body: JSON.stringify({
          path: file.path,
          oldPath: file.oldPath,
        }),
      })
    );
    diffState.patchCache[key] = data.patch || '';
    renderPatch();
  } catch (error) {
    diffState.patchCache[key] = `ERROR: ${error.message}`;
    renderPatch();
    showToast(error.message, 'error');
  }
}

function confirmReview() {
  if (!diffState.ruleId) {
    return;
  }
  diffState.confirmed = true;
  renderConfirmButton();
  if (window.opener && !window.opener.closed) {
    window.opener.postMessage({
      type: 'diff-review-confirmed',
      ruleId: diffState.ruleId,
    }, window.location.origin);
  }
  showToast('已完成人工確認，主畫面可直接同步', 'success');
}

document.getElementById('reloadDiffButton').addEventListener('click', refreshDiffSummary);
document.getElementById('confirmReviewButton').addEventListener('click', confirmReview);

const params = new URLSearchParams(window.location.search);
diffState.ruleId = params.get('ruleId') || '';
if (!diffState.ruleId) {
  document.getElementById('diffPatchView').textContent = '缺少 ruleId';
  showToast('缺少 ruleId', 'error');
} else {
  loadCachedSummary();
}

window.selectDiffFile = selectDiffFile;

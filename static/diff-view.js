const diffState = {
  ruleId: '',
  diff: null,
  selectedCommitId: null,
  commitFileCache: {},
  selectedCommitIds: [],
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

function escapeAttr(value) {
  return escapeHtml(value).replaceAll('`', '&#96;');
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
    clearSummary();
    if (error.code === 'DIFF_CACHE_NOT_FOUND') {
      document.getElementById('diffPageSubtitle').textContent = '尚未建立差異快取，請按「抓取最新差異」';
      document.getElementById('diffCommitFilesView').textContent = '尚未建立差異快取，請按右上角「抓取最新差異」。';
      showToast('尚未建立差異快取', 'error');
      return;
    }
    showToast(error.message, 'error');
    document.getElementById('diffCommitFilesView').textContent = error.message;
  }
}

async function refreshDiffSummary() {
  try {
    const diff = await withLoading('抓取最新差異並建立 commit 快取中...', () =>
      api(`/api/rules/${diffState.ruleId}/diff-cache/refresh`, { method: 'POST' })
    );
    applySummary(diff);
    showToast('最新差異已更新', 'success');
  } catch (error) {
    showToast(error.message, 'error');
  }
}

function clearSummary() {
  diffState.diff = null;
  diffState.selectedCommitId = null;
  diffState.commitFileCache = {};
  diffState.selectedCommitIds = [];
  diffState.confirmed = false;
  render();
}

function applySummary(diff) {
  diffState.diff = diff;
  diffState.selectedCommitId = null;
  diffState.commitFileCache = {};
  diffState.selectedCommitIds = [];
  diffState.confirmed = false;
  document.getElementById('diffForcePushCheckbox').checked = false;
  document.getElementById('diffForcePushCheckbox').disabled = !diff.allowForcePush;
  render();
}

function render() {
  renderMeta();
  renderSelectedSummary();
  renderCommitList();
  renderCommitFiles();
  renderActionButtons();
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
  `;
}

function renderSelectedSummary() {
  const summary = document.getElementById('selectedCommitSummary');
  if (!diffState.selectedCommitIds.length) {
    summary.textContent = '尚未選擇 commit';
    return;
  }
  summary.textContent = `已選擇 ${diffState.selectedCommitIds.length} 筆 commit`;
}

function renderCommitList() {
  const root = document.getElementById('diffCommitList');
  if (!diffState.diff) {
    root.innerHTML = '<div class="viewer empty">尚未載入差異快取</div>';
    return;
  }
  const commits = diffState.diff.commits || [];
  if (!commits.length) {
    root.innerHTML = '<div class="viewer empty">目前沒有 ahead commit</div>';
    return;
  }
  root.innerHTML = commits.map(commit => {
    const active = commit.id === diffState.selectedCommitId;
    const checked = diffState.selectedCommitIds.includes(commit.id);
    return `
      <div class="${active ? 'diff-file-item active' : 'diff-file-item'}">
        <label class="mini-check">
          <input type="checkbox" ${checked ? 'checked' : ''} onchange="toggleCommitSelection('${escapeAttr(commit.id)}', this.checked)">
          選取
        </label>
        <button type="button" class="secondary diff-commit-button" onclick="selectCommit('${escapeAttr(commit.id)}')">
          <div class="stacked-copy">
            <strong><code>${escapeHtml(commit.shortId || commit.id)}</code></strong>
            <span>${escapeHtml(commit.title || '')}</span>
            <span>${escapeHtml(commit.author || '-')} / ${escapeHtml(formatDateTime(commit.committedAt))}</span>
          </div>
        </button>
      </div>
    `;
  }).join('');
}

function renderCommitFiles() {
  const title = document.getElementById('diffFileTitle');
  const root = document.getElementById('diffCommitFilesView');
  if (!diffState.selectedCommitId) {
    title.textContent = '請從左側選擇 commit';
    root.innerHTML = '尚未載入 commit 檔案清單';
    return;
  }
  const commit = (diffState.diff?.commits || []).find(item => item.id === diffState.selectedCommitId);
  title.textContent = `${commit?.shortId || diffState.selectedCommitId} ${commit?.title || ''}`.trim();
  const cached = diffState.commitFileCache[diffState.selectedCommitId];
  if (!cached) {
    root.innerHTML = '載入 commit 檔案清單中...';
    return;
  }
  const files = cached.files || [];
  if (!files.length) {
    root.innerHTML = '<div class="empty">此 commit 沒有檔案異動</div>';
    return;
  }
  root.innerHTML = files.map(file => `
    <div class="diff-commit-file-row">
      <span class="diff-file-status ${statusClass(file.status)}">${escapeHtml(file.status)}</span>
      <span class="diff-file-path">${escapeHtml(file.displayPath || file.path)}</span>
    </div>
  `).join('');
}

function renderActionButtons() {
  const confirmButton = document.getElementById('confirmReviewButton');
  const pushButton = document.getElementById('pushSelectedCommitsButton');
  confirmButton.textContent = diffState.confirmed ? '已完成人工確認' : '人工確認本次同步';
  confirmButton.disabled = diffState.confirmed || !diffState.selectedCommitIds.length;
  pushButton.disabled = !diffState.confirmed || !diffState.selectedCommitIds.length;
}

function statusClass(status) {
  if (status === 'A') return 'status-add';
  if (status === 'D') return 'status-remove';
  if (status === 'R') return 'status-rename';
  return 'status-modify';
}

async function selectCommit(commitId) {
  diffState.selectedCommitId = commitId;
  renderCommitList();
  renderCommitFiles();
  if (Object.prototype.hasOwnProperty.call(diffState.commitFileCache, commitId)) {
    return;
  }
  try {
    const data = await withLoading('載入 commit 檔案清單中...', () =>
      api(`/api/rules/${diffState.ruleId}/diff/commits/${encodeURIComponent(commitId)}/files`)
    );
    diffState.commitFileCache[commitId] = data;
    renderCommitFiles();
  } catch (error) {
    diffState.commitFileCache[commitId] = { files: [], error: error.message };
    document.getElementById('diffCommitFilesView').textContent = error.message;
    showToast(error.message, 'error');
  }
}

function toggleCommitSelection(commitId, checked) {
  if (checked) {
    if (!diffState.selectedCommitIds.includes(commitId)) {
      diffState.selectedCommitIds.push(commitId);
    }
  } else {
    diffState.selectedCommitIds = diffState.selectedCommitIds.filter(item => item !== commitId);
  }
  diffState.confirmed = false;
  render();
}

function confirmReview() {
  if (!diffState.ruleId || !diffState.selectedCommitIds.length) {
    return;
  }
  diffState.confirmed = true;
  renderActionButtons();
  if (window.opener && !window.opener.closed) {
    window.opener.postMessage({
      type: 'diff-review-confirmed',
      ruleId: diffState.ruleId,
      selectedCommitIds: [...diffState.selectedCommitIds],
    }, window.location.origin);
  }
  showToast('已完成人工確認，可直接推送已選 commit', 'success');
}

async function pushSelectedCommits() {
  if (!diffState.ruleId || !diffState.selectedCommitIds.length || !diffState.confirmed) {
    return;
  }
  try {
    const forcePush = document.getElementById('diffForcePushCheckbox').checked;
    const result = await withLoading('同步已選 commit 中...', () =>
      api(`/api/rules/${diffState.ruleId}/sync`, {
        method: 'POST',
        body: JSON.stringify({
          forcePush,
          reviewConfirmed: true,
          selectedCommitIds: diffState.selectedCommitIds,
        }),
      })
    );
    if (window.opener && !window.opener.closed) {
      window.opener.postMessage({
        type: 'diff-sync-completed',
        ruleId: diffState.ruleId,
        selectedCommitIds: [...diffState.selectedCommitIds],
        logPath: result.logPath,
        message: result.message || '同步成功',
      }, window.location.origin);
    }
    showToast(result.message || '同步成功', 'success');
  } catch (error) {
    showToast(error.message, 'error');
  }
}

document.getElementById('reloadDiffButton').addEventListener('click', refreshDiffSummary);
document.getElementById('confirmReviewButton').addEventListener('click', confirmReview);
document.getElementById('pushSelectedCommitsButton').addEventListener('click', pushSelectedCommits);

const params = new URLSearchParams(window.location.search);
diffState.ruleId = params.get('ruleId') || '';
if (!diffState.ruleId) {
  document.getElementById('diffCommitFilesView').textContent = '缺少 ruleId';
  showToast('缺少 ruleId', 'error');
} else {
  loadCachedSummary();
}

window.selectCommit = selectCommit;
window.toggleCommitSelection = toggleCommitSelection;

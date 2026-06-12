const batchState = {
  specs: [],
  job: null,
  pollTimer: null,
  loadingCount: 0,
};

async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  const data = await response.json();
  if (!response.ok) {
    throw new Error(data.error?.message || 'Request failed');
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

function showLoading(message) {
  batchState.loadingCount += 1;
  document.getElementById('loadingMessage').textContent = message;
  document.getElementById('loadingOverlay').classList.remove('hidden');
}

function hideLoading() {
  batchState.loadingCount = Math.max(0, batchState.loadingCount - 1);
  if (batchState.loadingCount === 0) {
    document.getElementById('loadingOverlay').classList.add('hidden');
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
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  const dateParts = [
    date.getFullYear(),
    String(date.getMonth() + 1).padStart(2, '0'),
    String(date.getDate()).padStart(2, '0'),
  ];
  const timeParts = [
    String(date.getHours()).padStart(2, '0'),
    String(date.getMinutes()).padStart(2, '0'),
    String(date.getSeconds()).padStart(2, '0'),
  ];
  return `${dateParts.join('-')} ${timeParts.join(':')}`;
}

function shortHash(value) {
  return value ? String(value).slice(0, 10) : '-';
}

function statusLabel(status) {
  return {
    IDENTICAL: '完全一致',
    CONTENT_IDENTICAL: '內容一致，歷程不同',
    DIFFERENT: '版本不同',
    TARGET_MISSING: '目標分支不存在',
    CHECK_FAILED: '比對失敗',
  }[status] || status || '-';
}

function statusClass(status) {
  if (status === 'IDENTICAL') return 'success';
  if (status === 'CONTENT_IDENTICAL') return 'warning';
  return 'failed';
}

function tagsText(tags) {
  return Array.isArray(tags) && tags.length ? tags.join(', ') : '-';
}

function isMismatch(result) {
  return !['IDENTICAL', 'CONTENT_IDENTICAL'].includes(result.status)
    || result.tagsIdentical === false
    || result.tagsIdentical == null;
}

async function loadSpecs() {
  try {
    batchState.specs = await withLoading('載入同步規格中...', () => api('/api/version-comparison/specs'));
    renderSpecs();
  } catch (error) {
    showToast(error.message, 'error');
  }
}

function renderSpecs() {
  const select = document.getElementById('batchSpecSelect');
  const current = select.value;
  select.innerHTML = batchState.specs.length
    ? batchState.specs.map(spec => `
        <option value="${escapeAttr(spec.key)}">
          ${escapeHtml(spec.sourceBranch)} -> ${escapeHtml(spec.targetRemoteName)} / ${escapeHtml(spec.targetBranch)}
          (${Number(spec.ruleCount || 0)} 個專案)
        </option>
      `).join('')
    : '<option value="">沒有可用的同步規格</option>';
  if (current && batchState.specs.some(spec => spec.key === current)) {
    select.value = current;
  }
  document.getElementById('startBatchButton').disabled = !batchState.specs.length;
}

function selectedSpec() {
  const key = document.getElementById('batchSpecSelect').value;
  return batchState.specs.find(spec => spec.key === key);
}

async function startBatch(specOverride = null) {
  const spec = specOverride || selectedSpec();
  if (!spec?.sourceBranch || !spec?.targetRemoteId || !spec?.targetBranch) {
    showToast('請先選擇同步規格', 'error');
    return;
  }
  try {
    const job = await withLoading('建立批次版本比對工作中...', () =>
      api('/api/version-comparison/jobs', {
        method: 'POST',
        body: JSON.stringify({
          sourceBranch: spec.sourceBranch,
          targetRemoteId: spec.targetRemoteId,
          targetBranch: spec.targetBranch,
        }),
      })
    );
    batchState.job = job;
    history.replaceState(null, '', `?jobId=${encodeURIComponent(job.jobId)}`);
    renderJob();
    startPolling();
  } catch (error) {
    showToast(error.message, 'error');
  }
}

function rerunBatch() {
  const job = batchState.job;
  if (!job || job.status !== 'completed') return;
  startBatch({
    sourceBranch: job.sourceBranch,
    targetRemoteId: job.targetRemoteId,
    targetBranch: job.targetBranch,
  });
}

async function loadJob(jobId, announceError = true) {
  try {
    batchState.job = await api(`/api/version-comparison/jobs/${encodeURIComponent(jobId)}`);
    renderJob();
    if (batchState.job.status === 'completed') {
      stopPolling();
    }
  } catch (error) {
    stopPolling();
    if (announceError) {
      showToast(`${error.message}，請重新執行批次比對`, 'error');
    }
  }
}

function startPolling() {
  stopPolling();
  const poll = async () => {
    if (batchState.job?.jobId) {
      await loadJob(batchState.job.jobId, false);
    }
  };
  batchState.pollTimer = setInterval(poll, 1200);
  poll();
}

function stopPolling() {
  if (batchState.pollTimer) {
    clearInterval(batchState.pollTimer);
    batchState.pollTimer = null;
  }
}

function filteredResults() {
  const results = [...(batchState.job?.results || [])];
  const status = document.getElementById('batchStatusFilter').value;
  const mismatchOnly = document.getElementById('batchMismatchOnly').checked;
  return results.filter(result => (status === 'all' || result.status === status)
    && (!mismatchOnly || isMismatch(result)))
    .sort((left, right) => String(left.projectName).localeCompare(String(right.projectName)));
}

function renderJob() {
  const job = batchState.job;
  const rerunButton = document.getElementById('rerunBatchButton');
  if (!job) {
    rerunButton.disabled = true;
    return;
  }
  const completed = Number(job.completed || 0);
  const total = Number(job.total || 0);
  const percent = total ? Math.round((completed / total) * 100) : 0;
  document.getElementById('batchJobTitle').textContent =
    `${job.sourceBranch} -> ${job.targetRemoteName} / ${job.targetBranch}`;
  document.getElementById('batchJobMeta').textContent =
    `${job.status} / 開始：${formatDateTime(job.startedAt || job.queuedAt)} / 完成：${formatDateTime(job.finishedAt)}`;
  document.getElementById('batchProgressText').textContent = `${completed} / ${total}`;
  document.getElementById('batchProgressBar').style.width = `${percent}%`;
  document.getElementById('batchPageSubtitle').textContent =
    `${job.sourceBranch} -> ${job.targetRemoteName} / ${job.targetBranch}`;
  const matchingSpec = batchState.specs.find(spec =>
    spec.sourceBranch === job.sourceBranch
      && spec.targetRemoteId === job.targetRemoteId
      && spec.targetBranch === job.targetBranch);
  if (matchingSpec) {
    document.getElementById('batchSpecSelect').value = matchingSpec.key;
  }
  rerunButton.disabled = job.status !== 'completed';
  renderResults();
}

function renderResults() {
  const root = document.getElementById('batchResultRows');
  const results = filteredResults();
  const totalResults = batchState.job?.results?.length || 0;
  document.getElementById('batchResultSummary').textContent =
    `顯示 ${results.length} / 已完成 ${totalResults} 筆`;
  if (!results.length) {
    root.innerHTML = '<tr><td colspan="6" class="empty">目前沒有符合條件的比對結果</td></tr>';
    return;
  }
  root.innerHTML = results.map(result => {
    const sourceTags = tagsText(result.sourceTags);
    const targetTags = tagsText(result.targetTags);
    const tagsAvailable = result.tagsIdentical != null;
    const tagsClass = !tagsAvailable ? 'warning' : result.tagsIdentical ? 'success' : 'failed';
    const tagsLabel = !tagsAvailable ? '無法確認' : result.tagsIdentical ? '相同' : '不同';
    const sourceTagText = result.sourceTagCheckStatus === 'FAILED' ? '查詢失敗' : sourceTags;
    const targetTagText = result.targetTagCheckStatus === 'FAILED' ? '查詢失敗' : targetTags;
    const tagCheckMessage = result.tagCheckMessage || '';
    return `
      <tr>
        <td>
          <div class="stacked-copy">
            <strong>${escapeHtml(result.projectName || '-')}</strong>
            <span>${escapeHtml(result.ruleName || result.ruleId || '-')}</span>
          </div>
        </td>
        <td>
          <div class="batch-ref-cell">
            <span>${escapeHtml(result.sourceBranch || '-')}</span>
            <code title="${escapeAttr(result.sourceCommit || '')}">${escapeHtml(shortHash(result.sourceCommit))}</code>
            <span title="${escapeAttr(result.sourceTagCheckStatus === 'FAILED' ? tagCheckMessage : sourceTags)}">
              Tag: ${escapeHtml(sourceTagText)}
            </span>
          </div>
        </td>
        <td>
          <div class="batch-ref-cell">
            <span>${escapeHtml(result.targetBranch || '-')}</span>
            <code title="${escapeAttr(result.targetCommit || '')}">${escapeHtml(shortHash(result.targetCommit))}</code>
            <span title="${escapeAttr(result.targetTagCheckStatus === 'FAILED' ? tagCheckMessage : targetTags)}">
              Tag: ${escapeHtml(targetTagText)}
            </span>
          </div>
        </td>
        <td>
          <div class="stacked-copy">
            <span class="tag ${statusClass(result.status)}">${escapeHtml(statusLabel(result.status))}</span>
            <span>Commit: ${result.commitIdentical ? '相同' : '不同'}</span>
            <span>內容: ${result.contentIdentical ? '相同' : '不同'}</span>
            <span class="tag ${tagsClass}" title="${escapeAttr(tagCheckMessage)}">Tag: ${tagsLabel}</span>
          </div>
        </td>
        <td>${escapeHtml(formatDateTime(result.checkedAt))}</td>
        <td>
          <div class="inline-actions">
            <button type="button" class="secondary" ${result.status === 'CHECK_FAILED' ? 'disabled' : ''}
              onclick="openDiff('${escapeAttr(result.ruleId)}')">查看差異</button>
            <button type="button" onclick="retryRule('${escapeAttr(result.ruleId)}')">重新比對</button>
          </div>
        </td>
      </tr>
    `;
  }).join('');
}

async function retryRule(ruleId) {
  if (!batchState.job?.jobId) return;
  try {
    batchState.job = await withLoading('重新比對單一專案中...', () =>
      api(`/api/version-comparison/jobs/${encodeURIComponent(batchState.job.jobId)}/rules/${encodeURIComponent(ruleId)}`,
        { method: 'POST', body: '{}' })
    );
    renderJob();
    startPolling();
  } catch (error) {
    showToast(error.message, 'error');
  }
}

function openDiff(ruleId) {
  const popup = window.open(`/diff.html?ruleId=${encodeURIComponent(ruleId)}`, `diff-review-${ruleId}`,
    'popup=yes,width=1480,height=960,resizable=yes,scrollbars=yes');
  if (!popup) {
    showToast('瀏覽器阻擋了差異視窗', 'error');
  }
}

document.getElementById('refreshSpecsButton').addEventListener('click', loadSpecs);
document.getElementById('startBatchButton').addEventListener('click', () => startBatch());
document.getElementById('rerunBatchButton').addEventListener('click', rerunBatch);
document.getElementById('batchStatusFilter').addEventListener('change', renderResults);
document.getElementById('batchMismatchOnly').addEventListener('change', renderResults);

window.retryRule = retryRule;
window.openDiff = openDiff;

loadSpecs().then(() => {
  const jobId = new URLSearchParams(window.location.search).get('jobId');
  if (jobId) {
    loadJob(jobId).then(() => {
      if (batchState.job && batchState.job.status !== 'completed') {
        startPolling();
      }
    });
  }
});

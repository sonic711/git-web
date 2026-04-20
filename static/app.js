const state = {
  mappings: [],
  remotes: [],
  selectedRemoteTab: 'all',
  selectedDiffMappingId: null,
  diffConfirmed: false,
  rowForcePush: {},
  autoRefreshTimer: null,
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

function mappingById(id) {
  return state.mappings.find(item => item.id === id);
}

function remoteById(id) {
  return state.remotes.find(item => item.id === id);
}

function remoteDisplayName(remote) {
  return remote?.name || remote?.id || '-';
}

function isReviewReady(mappingId) {
  return state.selectedDiffMappingId === mappingId && state.diffConfirmed;
}

function renderTables() {
  renderMappings();
  renderRemoteTabs();
  renderRemotes();
  renderRemoteOptions();
}

function renderMappings() {
  const root = document.getElementById('mappingTable');
  if (!state.mappings.length) {
    root.innerHTML = '<div class="viewer empty">尚未建立 Mapping</div>';
    return;
  }

  const rows = state.mappings.map(mapping => {
    const statusClass = mapping.lastStatus === 'failed' ? 'failed'
      : mapping.lastStatus === 'success' ? 'success'
      : mapping.lastStatus === 'running' ? 'running' : '';
    const reviewReady = isReviewReady(mapping.id);
    const syncDisabled = mapping.reviewRequired && !reviewReady;
    return `
      <tr>
        <td>
          <div class="stacked-copy">
            <strong>${escapeHtml(mapping.name)}</strong>
          </div>
        </td>
        <td>
          <div class="stacked-copy">
            <span>${escapeHtml(mapping.vendorRepoUrl)}</span>
            <span><code>${escapeHtml(mapping.sourceBranch)}</code> -> <code>${escapeHtml(mapping.targetBranch)}</code></span>
          </div>
        </td>
        <td>
          <div class="stacked-copy">
            <strong>${escapeHtml(mapping.targetRemoteName || mapping.targetRemoteId)}</strong>
            <span>${escapeHtml(mapping.targetRepoName)}</span>
            <span>${escapeHtml(mapping.targetRemoteUrl || '-')}</span>
          </div>
        </td>
        <td>
          ${mapping.manualOnly ? '<span class="tag">Manual Only</span>' : ''}
          ${mapping.reviewRequired ? `<span class="tag ${reviewReady ? 'success' : ''}">Review ${reviewReady ? 'Ready' : 'Required'}</span>` : ''}
          ${mapping.schedule?.enabled ? '<span class="tag">Auto Sync</span>' : ''}
          ${mapping.allowForcePush ? '<span class="tag">Force Push Allowed</span>' : ''}
        </td>
        <td>
          <span class="tag ${statusClass}">${escapeHtml(mapping.lastStatus || 'never')}</span>
          <div class="status-copy">${escapeHtml(mapping.lastRunSource || '-')}</div>
          <div class="status-copy">${escapeHtml(mapping.lastMessage || '')}</div>
        </td>
        <td>${escapeHtml(mapping.nextRunAt || '-')}</td>
        <td>
          <div class="row-controls">
            <label class="mini-check">
              <input type="checkbox"
                ${mapping.allowForcePush ? '' : 'disabled'}
                ${state.rowForcePush[mapping.id] ? 'checked' : ''}
                onchange="setRowForcePush('${escapeAttr(mapping.id)}', this.checked)">
              Force Push
            </label>
            <label class="mini-check">
              <input type="checkbox"
                ${mapping.manualOnly ? 'disabled' : ''}
                ${mapping.schedule?.enabled ? 'checked' : ''}
                onchange="toggleAutoSync('${escapeAttr(mapping.id)}', this.checked)">
              自動同步
            </label>
            <div class="inline-actions">
              <button class="secondary" onclick="editMapping('${escapeAttr(mapping.id)}')">編輯</button>
              <button class="secondary" onclick="showDiff('${escapeAttr(mapping.id)}')">查看差異</button>
              <button ${syncDisabled ? 'disabled' : ''} onclick="runSync('${escapeAttr(mapping.id)}')">同步</button>
              <button class="secondary" onclick="validateMapping('${escapeAttr(mapping.id)}')">驗證</button>
              <button class="secondary" onclick="deleteMapping('${escapeAttr(mapping.id)}')">刪除</button>
            </div>
          </div>
        </td>
      </tr>
    `;
  }).join('');

  root.innerHTML = `
    <table>
      <thead>
        <tr>
          <th>規則</th>
          <th>來源</th>
          <th>目標</th>
          <th>標記</th>
          <th>最後結果</th>
          <th>下次排程</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>${rows}</tbody>
    </table>
  `;
}

function renderRemoteTabs() {
  const root = document.getElementById('remoteTabs');
  const tabs = [{ id: 'all', name: '全部' }, ...state.remotes.map(remote => ({ id: remote.id, name: remote.name }))];
  if (!tabs.some(item => item.id === state.selectedRemoteTab)) {
    state.selectedRemoteTab = 'all';
  }
  root.innerHTML = tabs.map(tab => `
    <button class="${tab.id === state.selectedRemoteTab ? 'tab active' : 'tab'}"
      onclick="selectRemoteTab('${escapeAttr(tab.id)}')">${escapeHtml(tab.name)}</button>
  `).join('');
}

function renderRemotes() {
  const root = document.getElementById('remoteTable');
  if (!state.remotes.length) {
    root.innerHTML = '<div class="viewer empty">尚未建立 Remote Tab</div>';
    return;
  }

  const filtered = state.selectedRemoteTab === 'all'
    ? state.remotes
    : state.remotes.filter(remote => remote.id === state.selectedRemoteTab);

  const rows = filtered.map(remote => {
    const mappingCount = state.mappings.filter(mapping => mapping.targetRemoteId === remote.id).length;
    return `
      <tr>
        <td>
          <div class="stacked-copy">
            <strong>${escapeHtml(remote.name)}</strong>
            <span>${escapeHtml(remote.id)}</span>
          </div>
        </td>
        <td>${escapeHtml(remote.baseUrl)}</td>
        <td>${remote.enabled ? 'enabled' : 'disabled'}</td>
        <td>${mappingCount}</td>
        <td>
          <div class="inline-actions">
            <button class="secondary" onclick="editRemote('${escapeAttr(remote.id)}')">編輯</button>
            <button class="secondary" onclick="deleteRemote('${escapeAttr(remote.id)}')">刪除</button>
          </div>
        </td>
      </tr>
    `;
  }).join('');

  root.innerHTML = `
    <table>
      <thead>
        <tr>
          <th>Remote Tab</th>
          <th>Base URL</th>
          <th>狀態</th>
          <th>Mappings</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>${rows}</tbody>
    </table>
  `;
}

function renderRemoteOptions() {
  const select = document.getElementById('targetRemoteId');
  const currentValue = select.value;
  const options = state.remotes.map(remote =>
    `<option value="${escapeAttr(remote.id)}">${escapeHtml(remote.name)}${remote.enabled ? '' : ' (disabled)'}</option>`
  );
  select.innerHTML = options.join('');
  if (currentValue && state.remotes.some(remote => remote.id === currentValue)) {
    select.value = currentValue;
  }
  updateTargetUrlPreview();
}

function openModal(id) {
  document.getElementById(id).classList.remove('hidden');
  document.body.classList.add('modal-open');
}

function closeModal(id) {
  document.getElementById(id).classList.add('hidden');
  if ([...document.querySelectorAll('.modal')].every(modal => modal.classList.contains('hidden'))) {
    document.body.classList.remove('modal-open');
  }
}

function updateTargetUrlPreview() {
  const remote = remoteById(document.getElementById('targetRemoteId').value);
  const repoName = document.getElementById('targetRepoName').value.trim();
  const preview = document.getElementById('targetUrlPreview');
  if (!remote) {
    preview.textContent = '請先建立或選擇 Remote Tab';
    return;
  }
  if (!repoName) {
    preview.textContent = `${remote.baseUrl}project.git`;
    return;
  }
  const separator = remote.baseUrl.endsWith('/') || remote.baseUrl.endsWith(':') ? '' : '/';
  preview.textContent = `${remote.baseUrl}${separator}${repoName}`;
}

function updateLocalRepoPathPreview() {
  const workspaceRoot = document.getElementById('localWorkspaceRoot').value.trim();
  const projectName = document.getElementById('localProjectName').value.trim();
  const preview = document.getElementById('localRepoPathPreview');
  if (!workspaceRoot || !projectName) {
    preview.textContent = '請先選擇主目錄並輸入專案名稱';
    return;
  }
  const separator = /[\\/]$/.test(workspaceRoot) ? '' : '/';
  preview.textContent = `${workspaceRoot}${separator}${projectName}`;
}

function deriveProjectNameFromUrl(repoUrl) {
  const trimmed = String(repoUrl || '').trim();
  if (!trimmed) {
    return '';
  }
  const normalized = trimmed.split('?')[0].split('#')[0];
  const parts = normalized.split(/[/:]/);
  const last = parts[parts.length - 1] || '';
  return last.endsWith('.git') ? last.slice(0, -4) : last;
}

function updateRemoteExamplePreview() {
  const baseUrl = document.getElementById('remoteBaseUrl').value.trim();
  const preview = document.getElementById('remoteExamplePreview');
  if (!baseUrl) {
    preview.textContent = 'ssh://git@example.com:222/team/project.git';
    return;
  }
  const separator = baseUrl.endsWith('/') || baseUrl.endsWith(':') ? '' : '/';
  preview.textContent = `${baseUrl}${separator}project.git`;
}

function editMapping(id) {
  const mapping = mappingById(id);
  if (!mapping) return;
  document.getElementById('mappingModalTitle').textContent = '編輯 Mapping';
  document.getElementById('mappingId').value = mapping.id;
  document.getElementById('mappingName').value = mapping.name;
  document.getElementById('vendorRepoUrl').value = mapping.vendorRepoUrl;
  document.getElementById('localWorkspaceRoot').value = mapping.localWorkspaceRoot;
  document.getElementById('localProjectName').value = mapping.localProjectName;
  document.getElementById('sourceBranch').value = mapping.sourceBranch;
  document.getElementById('targetRemoteId').value = mapping.targetRemoteId;
  document.getElementById('targetRepoName').value = mapping.targetRepoName || '';
  document.getElementById('targetBranch').value = mapping.targetBranch;
  document.getElementById('sameBranchNameExpected').checked = !!mapping.sameBranchNameExpected;
  document.getElementById('mappingEnabled').checked = !!mapping.enabled;
  document.getElementById('allowForcePush').checked = !!mapping.allowForcePush;
  document.getElementById('manualOnly').checked = !!mapping.manualOnly;
  document.getElementById('reviewRequired').checked = !!mapping.reviewRequired;
  document.getElementById('scheduleEnabled').checked = !!mapping.schedule?.enabled;
  document.getElementById('intervalMinutes').value = mapping.schedule?.intervalMinutes || 30;
  syncMappingFormRules();
  updateLocalRepoPathPreview();
  openModal('mappingModal');
}

function editRemote(id) {
  const remote = remoteById(id);
  if (!remote) return;
  document.getElementById('remoteModalTitle').textContent = '編輯 Remote Tab';
  document.getElementById('remoteOriginalId').value = remote.id;
  document.getElementById('remoteId').value = remote.id;
  document.getElementById('remoteId').disabled = true;
  document.getElementById('remoteName').value = remote.name;
  document.getElementById('remoteBaseUrl').value = remote.baseUrl;
  document.getElementById('remoteEnabled').checked = !!remote.enabled;
  updateRemoteExamplePreview();
  openModal('remoteModal');
}

function newMapping() {
  document.getElementById('mappingModalTitle').textContent = '新增 Mapping';
  document.getElementById('mappingForm').reset();
  document.getElementById('mappingId').value = '';
  document.getElementById('mappingEnabled').checked = true;
  document.getElementById('intervalMinutes').value = 30;
  if (state.selectedRemoteTab !== 'all' && remoteById(state.selectedRemoteTab)) {
    document.getElementById('targetRemoteId').value = state.selectedRemoteTab;
  }
  syncMappingFormRules();
  updateLocalRepoPathPreview();
  updateTargetUrlPreview();
  openModal('mappingModal');
}

function newRemote() {
  document.getElementById('remoteModalTitle').textContent = '新增 Remote Tab';
  document.getElementById('remoteForm').reset();
  document.getElementById('remoteOriginalId').value = '';
  document.getElementById('remoteId').disabled = false;
  document.getElementById('remoteEnabled').checked = true;
  updateRemoteExamplePreview();
  openModal('remoteModal');
}

function syncMappingFormRules() {
  const sameBranch = document.getElementById('sameBranchNameExpected').checked;
  const manualOnly = document.getElementById('manualOnly').checked;
  const sourceBranch = document.getElementById('sourceBranch').value;
  const targetBranch = document.getElementById('targetBranch');
  const scheduleEnabled = document.getElementById('scheduleEnabled');
  if (sameBranch && sourceBranch) {
    targetBranch.value = sourceBranch;
  }
  scheduleEnabled.disabled = manualOnly;
  if (manualOnly) {
    scheduleEnabled.checked = false;
  }
  const vendorRepoUrl = document.getElementById('vendorRepoUrl').value;
  const projectNameInput = document.getElementById('localProjectName');
  if (!projectNameInput.value.trim() && vendorRepoUrl.trim()) {
    projectNameInput.value = deriveProjectNameFromUrl(vendorRepoUrl);
  }
  updateLocalRepoPathPreview();
  updateTargetUrlPreview();
}

async function loadAll() {
  const [mappings, remotes] = await Promise.all([
    api('/api/mappings'),
    api('/api/remotes'),
  ]);
  state.mappings = mappings;
  state.remotes = remotes;
  if (state.selectedRemoteTab !== 'all' && !state.remotes.some(remote => remote.id === state.selectedRemoteTab)) {
    state.selectedRemoteTab = 'all';
  }
  renderTables();
}

async function pickDirectory() {
  try {
    const result = await api('/api/system/select-directory', { method: 'POST', body: '{}' });
    document.getElementById('localWorkspaceRoot').value = result.path || '';
    updateLocalRepoPathPreview();
  } catch (error) {
    showToast(error.message, 'error');
  }
}

async function saveMapping(event) {
  event.preventDefault();
  try {
    const id = document.getElementById('mappingId').value || `map-${Date.now()}`;
    const payload = {
      id,
      name: document.getElementById('mappingName').value,
      vendorRepoUrl: document.getElementById('vendorRepoUrl').value,
      localWorkspaceRoot: document.getElementById('localWorkspaceRoot').value,
      localProjectName: document.getElementById('localProjectName').value,
      sourceBranch: document.getElementById('sourceBranch').value,
      targetRemoteId: document.getElementById('targetRemoteId').value,
      targetRepoName: document.getElementById('targetRepoName').value,
      targetBranch: document.getElementById('targetBranch').value,
      sameBranchNameExpected: document.getElementById('sameBranchNameExpected').checked,
      enabled: document.getElementById('mappingEnabled').checked,
      allowForcePush: document.getElementById('allowForcePush').checked,
      manualOnly: document.getElementById('manualOnly').checked,
      reviewRequired: document.getElementById('reviewRequired').checked,
      schedule: {
        enabled: document.getElementById('scheduleEnabled').checked,
        type: 'fixed-interval',
        intervalMinutes: Number(document.getElementById('intervalMinutes').value || 30),
      },
    };
    await api(`/api/mappings/${id}`, { method: 'PUT', body: JSON.stringify(payload) });
    await loadAll();
    closeModal('mappingModal');
    showToast('Mapping 已儲存', 'success');
  } catch (error) {
    showToast(error.message, 'error');
  }
}

async function saveRemote(event) {
  event.preventDefault();
  try {
    const originalId = document.getElementById('remoteOriginalId').value;
    const payload = {
      id: document.getElementById('remoteId').value,
      name: document.getElementById('remoteName').value,
      baseUrl: document.getElementById('remoteBaseUrl').value,
      enabled: document.getElementById('remoteEnabled').checked,
    };
    if (originalId) {
      await api(`/api/remotes/${originalId}`, { method: 'PUT', body: JSON.stringify(payload) });
      state.selectedRemoteTab = payload.id;
    } else {
      await api('/api/remotes', { method: 'POST', body: JSON.stringify(payload) });
      state.selectedRemoteTab = payload.id;
    }
    await loadAll();
    closeModal('remoteModal');
    showToast('Remote Tab 已儲存', 'success');
  } catch (error) {
    showToast(error.message, 'error');
  }
}

async function toggleAutoSync(id, enabled) {
  try {
    const mapping = mappingById(id);
    if (!mapping) return;
    if (mapping.manualOnly && enabled) {
      throw new Error('Manual Only 規則不可啟用自動同步');
    }
    await api(`/api/mappings/${id}/schedule`, {
      method: 'PUT',
      body: JSON.stringify({
        enabled,
        type: 'fixed-interval',
        intervalMinutes: mapping.schedule?.intervalMinutes || 30,
      }),
    });
    await loadAll();
    showToast(`自動同步已${enabled ? '啟用' : '停用'}`, 'success');
  } catch (error) {
    showToast(error.message, 'error');
  }
}

function setRowForcePush(id, checked) {
  state.rowForcePush[id] = checked;
}

async function validateMapping(id) {
  try {
    const data = await api(`/api/mappings/${id}/validate`, { method: 'POST' });
    document.getElementById('logView').textContent = JSON.stringify(data, null, 2);
    showToast(data.ok ? '驗證成功' : '驗證失敗', data.ok ? 'success' : 'error');
  } catch (error) {
    showToast(error.message, 'error');
  }
}

async function showDiff(id) {
  try {
    const mapping = mappingById(id);
    const data = await api(`/api/mappings/${id}/diff`, { method: 'POST' });
    state.selectedDiffMappingId = id;
    state.diffConfirmed = false;
    const commitLines = data.commits.map(item =>
      `<li><code>${escapeHtml(item.id)}</code> ${escapeHtml(item.title)}</li>`).join('');
    const fileLines = (data.files || []).map(item =>
      `<li><code>${escapeHtml(item.status)}</code> ${escapeHtml(item.path)}</li>`).join('');
    document.getElementById('diffView').innerHTML = `
      <div class="section"><strong>Mapping:</strong> ${escapeHtml(mapping?.name || id)}</div>
      <div class="section"><strong>Source:</strong> ${escapeHtml(data.sourceBranch)} | <strong>Target:</strong> ${escapeHtml(data.targetBranch)}</div>
      <div class="section"><strong>Remote:</strong> ${escapeHtml(data.targetRemoteName || '-')} | <strong>Repo:</strong> ${escapeHtml(data.targetRepoName || '-')}</div>
      <div class="section"><strong>URL:</strong> ${escapeHtml(data.targetRemoteUrl || '-')}</div>
      <div class="section"><strong>Ahead commits:</strong> ${escapeHtml(data.summary.aheadCommits)} | <strong>Changed files:</strong> ${escapeHtml(data.summary.changedFiles)}</div>
      <div class="section"><strong>Commits</strong><ul>${commitLines || '<li>(none)</li>'}</ul></div>
      <div class="section"><strong>Files</strong><ul>${fileLines || '<li>(none)</li>'}</ul></div>
      <div class="section"><button onclick="confirmDiffReview('${escapeAttr(id)}')">人工確認本次同步</button></div>
    `;
    renderMappings();
    showToast('差異已載入', 'success');
  } catch (error) {
    showToast(error.message, 'error');
  }
}

function confirmDiffReview(id) {
  state.selectedDiffMappingId = id;
  state.diffConfirmed = true;
  renderMappings();
  showToast('已完成本次人工確認', 'success');
}

async function runSync(id) {
  try {
    const mapping = mappingById(id);
    const forcePush = !!state.rowForcePush[id] && !!mapping.allowForcePush;
    const reviewConfirmed = !mapping.reviewRequired || isReviewReady(id);
    if (mapping.reviewRequired && !reviewConfirmed) {
      throw new Error('這筆規則需要先查看差異並完成人工確認');
    }
    const result = await api(`/api/mappings/${id}/sync`, {
      method: 'POST',
      body: JSON.stringify({ forcePush, reviewConfirmed }),
    });
    document.getElementById('logView').textContent = JSON.stringify(result, null, 2);
    await loadAll();
    if (result.runId) {
      await loadLog(result.runId);
    }
    showToast(result.message || '同步成功', 'success');
  } catch (error) {
    showToast(error.message, 'error');
  }
}

async function deleteMapping(id) {
  try {
    const mapping = mappingById(id);
    if (!confirm(`確定要刪除 Mapping ${mapping?.name || id} 嗎？`)) {
      return;
    }
    await api(`/api/mappings/${id}`, { method: 'DELETE' });
    if (state.selectedDiffMappingId === id) {
      state.selectedDiffMappingId = null;
      state.diffConfirmed = false;
      document.getElementById('diffView').textContent = '尚未載入差異';
    }
    await loadAll();
    showToast('Mapping 已刪除', 'success');
  } catch (error) {
    showToast(error.message, 'error');
  }
}

async function deleteRemote(id) {
  try {
    if (!confirm(`確定要刪除 Remote Tab ${id} 嗎？`)) {
      return;
    }
    await api(`/api/remotes/${id}`, { method: 'DELETE' });
    if (state.selectedRemoteTab === id) {
      state.selectedRemoteTab = 'all';
    }
    await loadAll();
    showToast('Remote Tab 已刪除', 'success');
  } catch (error) {
    showToast(error.message, 'error');
  }
}

function selectRemoteTab(id) {
  state.selectedRemoteTab = id;
  renderRemoteTabs();
  renderRemotes();
}

function startAutoRefresh() {
  if (state.autoRefreshTimer) {
    clearInterval(state.autoRefreshTimer);
  }
  state.autoRefreshTimer = setInterval(() => {
    loadAll().catch(error => showToast(error.message, 'error'));
  }, 10000);
}

async function loadLog(runId) {
  try {
    const log = await api(`/api/logs/${runId}`);
    document.getElementById('logView').textContent = log.content || '(empty log)';
  } catch (error) {
    showToast(error.message, 'error');
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

document.getElementById('mappingForm').addEventListener('submit', saveMapping);
document.getElementById('remoteForm').addEventListener('submit', saveRemote);
document.getElementById('newMappingButton').addEventListener('click', newMapping);
document.getElementById('newRemoteButton').addEventListener('click', newRemote);
document.getElementById('refreshButton').addEventListener('click', loadAll);
document.getElementById('pickDirectoryButton').addEventListener('click', pickDirectory);
document.getElementById('sameBranchNameExpected').addEventListener('change', syncMappingFormRules);
document.getElementById('vendorRepoUrl').addEventListener('input', syncMappingFormRules);
document.getElementById('sourceBranch').addEventListener('input', syncMappingFormRules);
document.getElementById('manualOnly').addEventListener('change', syncMappingFormRules);
document.getElementById('localWorkspaceRoot').addEventListener('input', updateLocalRepoPathPreview);
document.getElementById('localProjectName').addEventListener('input', updateLocalRepoPathPreview);
document.getElementById('targetRemoteId').addEventListener('change', updateTargetUrlPreview);
document.getElementById('targetRepoName').addEventListener('input', updateTargetUrlPreview);
document.getElementById('remoteBaseUrl').addEventListener('input', updateRemoteExamplePreview);

document.querySelectorAll('[data-close-modal]').forEach(button => {
  button.addEventListener('click', () => closeModal(button.dataset.closeModal));
});

loadAll().catch(error => {
  document.getElementById('logView').textContent = error.message;
  showToast(error.message, 'error');
});
startAutoRefresh();
updateRemoteExamplePreview();
updateLocalRepoPathPreview();

window.editMapping = editMapping;
window.editRemote = editRemote;
window.showDiff = showDiff;
window.runSync = runSync;
window.validateMapping = validateMapping;
window.toggleAutoSync = toggleAutoSync;
window.setRowForcePush = setRowForcePush;
window.selectRemoteTab = selectRemoteTab;
window.deleteMapping = deleteMapping;
window.deleteRemote = deleteRemote;
window.confirmDiffReview = confirmDiffReview;

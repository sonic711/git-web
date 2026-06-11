const state = {
  projects: [],
  remotes: [],
  systemConfig: { localWorkspaceRoot: '' },
  selectedRemoteTab: 'all',
  reviewSelections: {},
  rowForcePush: {},
  collapsedProjects: {},
  systemSettingsDirty: false,
  syncJobPolls: {},
  versionComparison: null,
  autoRefreshTimer: null,
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

function showLoading(message = '作業中...') {
  state.loadingCount += 1;
  document.getElementById('loadingMessage').textContent = message;
  document.getElementById('loadingOverlay').classList.remove('hidden');
}

function hideLoading() {
  state.loadingCount = Math.max(0, state.loadingCount - 1);
  if (state.loadingCount === 0) {
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

function projectById(id) {
  return state.projects.find(project => project.id === id);
}

function ruleSelection(ruleId) {
  for (const project of state.projects) {
    const rule = (project.rules || []).find(item => item.id === ruleId);
    if (rule) {
      return { project, rule };
    }
  }
  return null;
}

function remoteById(id) {
  return state.remotes.find(item => item.id === id);
}

function isReviewReady(ruleId) {
  return !!state.reviewSelections[ruleId]?.confirmed;
}

function selectedCommitIdsFor(ruleId) {
  return state.reviewSelections[ruleId]?.selectedCommitIds || [];
}

function ruleMode(rule) {
  return rule?.mode || 'sync';
}

function isDownloadOnlyRule(rule) {
  return ruleMode(rule) === 'download-only';
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

function render() {
  renderSystemSettings();
  renderProjects();
  renderRemoteTabs();
  renderRemotes();
  renderRemoteOptions();
}

function renderSystemSettings() {
  if (state.systemSettingsDirty) {
    return;
  }
  document.getElementById('globalWorkspaceRoot').value = state.systemConfig.localWorkspaceRoot || '';
}

function renderProjects() {
  const root = document.getElementById('projectList');
  if (!state.projects.length) {
    root.innerHTML = '<div class="viewer empty">尚未建立專案</div>';
    return;
  }

  root.innerHTML = state.projects.map(project => {
    const rules = project.rules || [];
    const isCollapsed = !!state.collapsedProjects[project.id];
    const ruleRows = rules.length
      ? rules.map(rule => renderRuleRow(project, rule)).join('')
      : '<tr><td colspan="6" class="empty">尚未建立同步規則</td></tr>';

    return `
      <section class="project-card">
        <div class="project-head">
          <div>
            <div class="project-title-row">
              <button class="secondary collapse-toggle" onclick="toggleProjectCollapse('${escapeAttr(project.id)}')">
                ${isCollapsed ? '展開規則' : '收合規則'}
              </button>
              <h3>${escapeHtml(project.name)}</h3>
              <span class="tag">${rules.length} 條規則</span>
            </div>
            <div class="project-meta">${escapeHtml(project.vendorRepoUrl)}</div>
          </div>
          <div class="inline-actions">
            <button class="secondary" onclick="editProject('${escapeAttr(project.id)}')">編輯專案</button>
            <button class="secondary" onclick="newRule('${escapeAttr(project.id)}')">新增規則</button>
            <button class="secondary" onclick="deleteProject('${escapeAttr(project.id)}')">刪除專案</button>
          </div>
        </div>
        <div class="${isCollapsed ? 'project-rules hidden' : 'project-rules'}">
          <table>
            <thead>
              <tr>
                <th>規則</th>
                <th>來源 / 目標</th>
                <th>Remote</th>
                <th>最後結果</th>
                <th>下次排程</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>${ruleRows}</tbody>
          </table>
        </div>
      </section>
    `;
  }).join('');
}

function toggleProjectCollapse(projectId) {
  state.collapsedProjects[projectId] = !state.collapsedProjects[projectId];
  renderProjects();
}

function renderRuleRow(project, rule) {
  const downloadOnly = isDownloadOnlyRule(rule);
  const displayStatus = rule.currentJobStatus || rule.lastStatus || 'never';
  const displayTime = rule.currentJobStartedAt || rule.currentJobQueuedAt || rule.lastRunAt;
  const displaySource = rule.currentJobTriggerSource || rule.lastRunSource;
  const displayMessage = rule.currentJobMessage || rule.lastMessage || '';
  const statusClass = displayStatus === 'failed' ? 'failed'
    : displayStatus === 'success' ? 'success'
    : displayStatus === 'running' ? 'running'
    : displayStatus === 'queued' ? 'queued' : '';
  const displayMessageText = displayStatus === 'failed' && displayMessage ? 'Sync Error' : displayMessage;
  const displayMessageHtml = displayStatus === 'failed' && displayMessage
    ? `<div class="status-copy status-tooltip" tabindex="0">
        ${escapeHtml(displayMessageText)}
        <span class="status-tooltip-panel">${escapeHtml(displayMessage)}</span>
      </div>`
    : `<div class="status-copy">${escapeHtml(displayMessageText)}</div>`;
  const reviewReady = isReviewReady(rule.id);
  const selectedCommitCount = selectedCommitIdsFor(rule.id).length;
  const syncDisabled = !!rule.currentJobStatus || (!downloadOnly && rule.reviewRequired && !reviewReady);
  const versionCompareDisabled = downloadOnly || !!rule.currentJobStatus;
  const syncLabel = rule.currentJobStatus === 'queued' ? '排隊中'
    : rule.currentJobStatus === 'running' ? '同步中'
    : downloadOnly ? '下載' : '同步';
  const branchText = downloadOnly
    ? `<code>${escapeHtml(rule.sourceBranch)}</code> -> <code>本地</code>`
    : `<code>${escapeHtml(rule.sourceBranch)}</code> -> <code>${escapeHtml(rule.targetBranch)}</code>`;
  const targetText = downloadOnly
    ? `<strong>只下載到本地</strong><span>${escapeHtml(rule.localRepoPath || project.localRepoPath || '')}</span>`
    : `<strong>${escapeHtml(rule.targetRemoteName || rule.targetRemoteId)}</strong><span>${escapeHtml(rule.targetRepoName)}</span>`;
  return `
    <tr>
      <td>
        <div class="stacked-copy">
          <strong>${escapeHtml(rule.name)}</strong>
          <span>${downloadOnly ? '<span class="tag">Download Only</span>' : ''}${rule.manualOnly ? '<span class="tag">Manual Only</span>' : ''}${rule.schedule?.enabled ? '<span class="tag">Auto Sync</span>' : ''}</span>
        </div>
      </td>
      <td>
        <div class="stacked-copy">
          <span>${branchText}</span>
          <span>${!downloadOnly && rule.reviewRequired ? `<span class="tag ${reviewReady ? 'success' : ''}">Review ${reviewReady ? 'Ready' : 'Required'}</span>` : ''}</span>
          <span>${!downloadOnly && selectedCommitCount ? `<span class="tag success">${selectedCommitCount} commits</span>` : ''}</span>
        </div>
      </td>
      <td>
        <div class="stacked-copy">${targetText}</div>
      </td>
      <td>
        <span class="tag ${statusClass}">${escapeHtml(displayStatus)}</span>
        <div class="status-copy">${escapeHtml(formatDateTime(displayTime))}</div>
        <div class="status-copy">${escapeHtml(displaySource || '-')}</div>
        ${displayMessageHtml}
      </td>
      <td>${escapeHtml(formatDateTime(rule.nextRunAt))}</td>
      <td>
        <div class="row-controls">
          <label class="mini-check">
            <input type="checkbox"
              ${rule.allowForcePush && !downloadOnly ? '' : 'disabled'}
              ${state.rowForcePush[rule.id] ? 'checked' : ''}
              onchange="setRowForcePush('${escapeAttr(rule.id)}', this.checked)">
            Force Push
          </label>
          <label class="mini-check">
            <input type="checkbox"
              ${rule.manualOnly ? 'disabled' : ''}
              ${rule.schedule?.enabled ? 'checked' : ''}
              onchange="toggleAutoSync('${escapeAttr(rule.id)}', this.checked)">
            自動同步
          </label>
          <div class="inline-actions">
            <button class="secondary" onclick="editRule('${escapeAttr(project.id)}', '${escapeAttr(rule.id)}')">編輯</button>
            <button class="secondary" ${downloadOnly ? 'disabled' : ''} onclick="showDiff('${escapeAttr(rule.id)}')">查看差異</button>
            <button class="secondary" ${versionCompareDisabled ? 'disabled' : ''} onclick="compareVersion('${escapeAttr(rule.id)}')">版本比對</button>
            <button ${syncDisabled ? 'disabled' : ''} onclick="runSync('${escapeAttr(rule.id)}')">${syncLabel}</button>
            <button class="secondary" onclick="validateRule('${escapeAttr(rule.id)}')">驗證</button>
            <button class="secondary" onclick="deleteRule('${escapeAttr(project.id)}', '${escapeAttr(rule.id)}')">刪除</button>
          </div>
        </div>
      </td>
    </tr>
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
    const ruleCount = state.projects.flatMap(project => project.rules || []).filter(rule => rule.targetRemoteId === remote.id).length;
    return `
      <tr>
        <td><strong>${escapeHtml(remote.name)}</strong><br>${escapeHtml(remote.id)}</td>
        <td>${escapeHtml(remote.baseUrl)}</td>
        <td>${remote.enabled ? 'enabled' : 'disabled'}</td>
        <td>${ruleCount}</td>
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
      <thead><tr><th>Remote Tab</th><th>Base URL</th><th>狀態</th><th>Rules</th><th>操作</th></tr></thead>
      <tbody>${rows}</tbody>
    </table>
  `;
}

function renderRemoteOptions() {
  const select = document.getElementById('targetRemoteId');
  const currentValue = select.value;
  select.innerHTML = state.remotes.map(remote =>
    `<option value="${escapeAttr(remote.id)}">${escapeHtml(remote.name)}${remote.enabled ? '' : ' (disabled)'}</option>`
  ).join('');
  if (currentValue && state.remotes.some(remote => remote.id === currentValue)) {
    select.value = currentValue;
  }
  updateTargetUrlPreview();
}

function updateLocalRepoPathPreview() {
  const workspaceRoot = document.getElementById('globalWorkspaceRoot').value.trim();
  const projectName = document.getElementById('localProjectName').value.trim();
  const preview = document.getElementById('localRepoPathPreview');
  if (!workspaceRoot || !projectName) {
    preview.textContent = '請先選擇主目錄並輸入專案名稱';
    return;
  }
  const separator = /[\\/]$/.test(workspaceRoot) ? '' : '/';
  preview.textContent = `${workspaceRoot}${separator}${projectName}`;
}

function updateTargetUrlPreview() {
  const mode = document.getElementById('ruleMode')?.value || 'sync';
  if (mode === 'download-only') {
    document.getElementById('targetUrlPreview').textContent = '只下載到本地，不推送到目標 remote';
    return;
  }
  const remote = remoteById(document.getElementById('targetRemoteId').value);
  const repoName = document.getElementById('targetRepoName').value.trim();
  const preview = document.getElementById('targetUrlPreview');
  if (!remote) {
    preview.textContent = '請先建立或選擇 Remote Tab';
    return;
  }
  const separator = remote.baseUrl.endsWith('/') || remote.baseUrl.endsWith(':') ? '' : '/';
  preview.textContent = repoName ? `${remote.baseUrl}${separator}${repoName}` : `${remote.baseUrl}${separator}project.git`;
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

function deriveProjectNameFromUrl(repoUrl) {
  const trimmed = String(repoUrl || '').trim();
  if (!trimmed) return '';
  const normalized = trimmed.split('?')[0].split('#')[0];
  const parts = normalized.split(/[/:]/);
  const last = parts[parts.length - 1] || '';
  return last.endsWith('.git') ? last.slice(0, -4) : last;
}

function syncProjectFormRules() {
  const vendorRepoUrl = document.getElementById('vendorRepoUrl').value;
  const projectNameInput = document.getElementById('localProjectName');
  if (!projectNameInput.value.trim() && vendorRepoUrl.trim()) {
    projectNameInput.value = deriveProjectNameFromUrl(vendorRepoUrl);
  }
  updateLocalRepoPathPreview();
}

function syncRuleFormRules() {
  const mode = document.getElementById('ruleMode').value || 'sync';
  const downloadOnly = mode === 'download-only';
  const sameBranch = document.getElementById('sameBranchNameExpected').checked;
  const manualOnly = document.getElementById('manualOnly').checked;
  const sourceBranch = document.getElementById('sourceBranch').value;
  const targetBranch = document.getElementById('targetBranch');
  const scheduleEnabled = document.getElementById('scheduleEnabled');
  const ruleName = document.getElementById('ruleName');
  const targetRemoteId = document.getElementById('targetRemoteId');
  const targetRepoName = document.getElementById('targetRepoName');
  const sameBranchInput = document.getElementById('sameBranchNameExpected');
  const allowForcePush = document.getElementById('allowForcePush');
  const reviewRequired = document.getElementById('reviewRequired');
  document.querySelectorAll('.sync-only-field').forEach(element => {
    element.classList.toggle('hidden', downloadOnly);
  });
  document.querySelectorAll('.download-only-field').forEach(element => {
    element.classList.toggle('hidden', !downloadOnly);
  });
  targetRemoteId.disabled = downloadOnly;
  targetRemoteId.required = !downloadOnly;
  targetRepoName.disabled = downloadOnly;
  targetRepoName.required = !downloadOnly;
  targetBranch.disabled = downloadOnly;
  targetBranch.required = !downloadOnly;
  sameBranchInput.disabled = downloadOnly;
  allowForcePush.disabled = downloadOnly;
  reviewRequired.disabled = downloadOnly;
  if (downloadOnly) {
    sameBranchInput.checked = false;
    allowForcePush.checked = false;
    reviewRequired.checked = false;
  }
  if (!downloadOnly && sameBranch && sourceBranch) {
    targetBranch.value = sourceBranch;
  }
  if (!ruleName.value.trim() && sourceBranch.trim()) {
    ruleName.value = downloadOnly ? `${sourceBranch} download only` : `${sourceBranch} -> ${targetBranch.value || sourceBranch}`;
  }
  scheduleEnabled.disabled = manualOnly;
  if (manualOnly) {
    scheduleEnabled.checked = false;
  }
  updateTargetUrlPreview();
}

function newProject() {
  document.getElementById('projectModalTitle').textContent = '新增專案';
  document.getElementById('projectForm').reset();
  document.getElementById('projectId').value = '';
  document.getElementById('projectEnabled').checked = true;
  updateLocalRepoPathPreview();
  openModal('projectModal');
}

function editProject(projectId) {
  const project = projectById(projectId);
  if (!project) return;
  document.getElementById('projectModalTitle').textContent = '編輯專案';
  document.getElementById('projectId').value = project.id;
  document.getElementById('projectName').value = project.name;
  document.getElementById('vendorRepoUrl').value = project.vendorRepoUrl;
  document.getElementById('localProjectName').value = project.localProjectName;
  document.getElementById('projectEnabled').checked = !!project.enabled;
  updateLocalRepoPathPreview();
  openModal('projectModal');
}

function newRule(projectId) {
  document.getElementById('ruleModalTitle').textContent = '新增同步規則';
  document.getElementById('ruleForm').reset();
  document.getElementById('ruleProjectId').value = projectId;
  document.getElementById('ruleId').value = '';
  document.getElementById('ruleMode').value = 'sync';
  document.getElementById('ruleEnabled').checked = true;
  document.getElementById('intervalMinutes').value = 30;
  const project = projectById(projectId);
  if (project) {
    document.getElementById('targetRepoName').value = `${project.localProjectName}.git`;
  }
  if (state.selectedRemoteTab !== 'all' && remoteById(state.selectedRemoteTab)) {
    document.getElementById('targetRemoteId').value = state.selectedRemoteTab;
  }
  syncRuleFormRules();
  openModal('ruleModal');
}

function editRule(projectId, ruleId) {
  const selection = ruleSelection(ruleId);
  if (!selection) return;
  const { project, rule } = selection;
  document.getElementById('ruleModalTitle').textContent = '編輯同步規則';
  document.getElementById('ruleProjectId').value = projectId || project.id;
  document.getElementById('ruleId').value = rule.id;
  document.getElementById('ruleName').value = rule.name;
  document.getElementById('ruleMode').value = ruleMode(rule);
  document.getElementById('sourceBranch').value = rule.sourceBranch;
  document.getElementById('targetRemoteId').value = rule.targetRemoteId || '';
  document.getElementById('targetRepoName').value = rule.targetRepoName || '';
  document.getElementById('targetBranch').value = rule.targetBranch || '';
  document.getElementById('downloadWorkspaceRoot').value = rule.downloadWorkspaceRoot || '';
  document.getElementById('sameBranchNameExpected').checked = !!rule.sameBranchNameExpected;
  document.getElementById('ruleEnabled').checked = !!rule.enabled;
  document.getElementById('allowForcePush').checked = !!rule.allowForcePush;
  document.getElementById('manualOnly').checked = !!rule.manualOnly;
  document.getElementById('reviewRequired').checked = !!rule.reviewRequired;
  document.getElementById('scheduleEnabled').checked = !!rule.schedule?.enabled;
  document.getElementById('intervalMinutes').value = rule.schedule?.intervalMinutes || 30;
  syncRuleFormRules();
  openModal('ruleModal');
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

function newRemote() {
  document.getElementById('remoteModalTitle').textContent = '新增 Remote Tab';
  document.getElementById('remoteForm').reset();
  document.getElementById('remoteOriginalId').value = '';
  document.getElementById('remoteId').disabled = false;
  document.getElementById('remoteEnabled').checked = true;
  updateRemoteExamplePreview();
  openModal('remoteModal');
}

async function loadAll(options = {}) {
  const silent = !!options.silent;
  const run = async () => {
    const [systemConfig, projects, remotes] = await Promise.all([
      api('/api/system/config'),
      api('/api/projects'),
      api('/api/remotes'),
    ]);
    state.systemConfig = systemConfig;
    state.projects = projects;
    state.remotes = remotes;
    if (state.selectedRemoteTab !== 'all' && !state.remotes.some(remote => remote.id === state.selectedRemoteTab)) {
      state.selectedRemoteTab = 'all';
    }
    render();
  };
  return silent ? run() : withLoading('重新載入資料中...', run);
}

async function pickGlobalWorkspaceRoot() {
  try {
    const result = await withLoading('開啟資料夾選擇器...', () =>
      api('/api/system/select-directory', { method: 'POST', body: '{}' })
    );
    document.getElementById('globalWorkspaceRoot').value = result.path || '';
    state.systemSettingsDirty = true;
    updateLocalRepoPathPreview();
  } catch (error) {
    showToast(error.message, 'error');
  }
}

async function saveSystemSettings() {
  try {
    const payload = {
      localWorkspaceRoot: document.getElementById('globalWorkspaceRoot').value.trim(),
    };
    await withLoading('儲存全局設定中...', () =>
      api('/api/system/config', { method: 'PUT', body: JSON.stringify(payload) })
    );
    state.systemSettingsDirty = false;
    await loadAll({ silent: true });
    updateLocalRepoPathPreview();
    showToast('全局設定已儲存', 'success');
  } catch (error) {
    showToast(error.message, 'error');
  }
}

async function exportConfig() {
  try {
    const config = await withLoading('匯出設定檔中...', () =>
      api('/api/system/config/export')
    );
    const blob = new Blob([JSON.stringify(config, null, 2)], { type: 'application/json;charset=utf-8' });
    const downloadUrl = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    const now = new Date();
    const yyyy = now.getFullYear();
    const mm = String(now.getMonth() + 1).padStart(2, '0');
    const dd = String(now.getDate()).padStart(2, '0');
    anchor.href = downloadUrl;
    anchor.download = `settings-export-${yyyy}-${mm}-${dd}.json`;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    URL.revokeObjectURL(downloadUrl);
    showToast('設定檔已匯出，內容不包含全局本地主目錄', 'success');
  } catch (error) {
    showToast(error.message, 'error');
  }
}

function promptImportConfig() {
  const input = document.getElementById('importConfigFile');
  input.value = '';
  input.click();
}

async function importConfigFile(event) {
  const file = event.target.files?.[0];
  if (!file) {
    return;
  }
  try {
    const text = await file.text();
    JSON.parse(text);
    const result = await withLoading('匯入設定檔中...', () =>
      api('/api/system/config/import', { method: 'POST', body: text })
    );
    await loadAll({ silent: true });
    if (result.requiresWorkspaceRoot) {
      showToast('設定檔已匯入，請先設定全局本地主目錄後再同步', 'success');
    } else {
      showToast('設定檔已匯入', 'success');
    }
  } catch (error) {
    showToast(error.message || '匯入設定檔失敗', 'error');
  }
}

async function saveProject(event) {
  event.preventDefault();
  try {
    const id = document.getElementById('projectId').value || ModelsId.project();
    const existing = projectById(id);
    const payload = {
      id,
      name: document.getElementById('projectName').value,
      vendorRepoUrl: document.getElementById('vendorRepoUrl').value,
      localProjectName: document.getElementById('localProjectName').value,
      enabled: document.getElementById('projectEnabled').checked,
      rules: existing?.rules?.map(stripRuntimeFieldsFromRule) || [],
    };
    await withLoading('儲存專案中...', () =>
      api(`/api/projects/${id}`, { method: 'PUT', body: JSON.stringify(payload) })
    );
    await loadAll({ silent: true });
    closeModal('projectModal');
    showToast('專案已儲存', 'success');
  } catch (error) {
    showToast(error.message, 'error');
  }
}

async function saveRule(event) {
  event.preventDefault();
  try {
    const projectId = document.getElementById('ruleProjectId').value;
    const ruleId = document.getElementById('ruleId').value || ModelsId.rule();
    const mode = document.getElementById('ruleMode').value || 'sync';
    const downloadOnly = mode === 'download-only';
    const payload = {
      id: ruleId,
      name: document.getElementById('ruleName').value,
      mode,
      sourceBranch: document.getElementById('sourceBranch').value,
      targetRemoteId: downloadOnly ? '' : document.getElementById('targetRemoteId').value,
      targetRepoName: downloadOnly ? '' : document.getElementById('targetRepoName').value,
      targetBranch: downloadOnly ? '' : document.getElementById('targetBranch').value,
      downloadWorkspaceRoot: downloadOnly ? document.getElementById('downloadWorkspaceRoot').value.trim() : '',
      sameBranchNameExpected: !downloadOnly && document.getElementById('sameBranchNameExpected').checked,
      enabled: document.getElementById('ruleEnabled').checked,
      allowForcePush: !downloadOnly && document.getElementById('allowForcePush').checked,
      manualOnly: document.getElementById('manualOnly').checked,
      reviewRequired: !downloadOnly && document.getElementById('reviewRequired').checked,
      schedule: {
        enabled: document.getElementById('scheduleEnabled').checked,
        type: 'fixed-interval',
        intervalMinutes: Number(document.getElementById('intervalMinutes').value || 30),
      },
    };
    await withLoading('儲存同步規則中...', () =>
      api(`/api/projects/${projectId}/rules/${ruleId}`, { method: 'PUT', body: JSON.stringify(payload) })
    );
    await loadAll({ silent: true });
    closeModal('ruleModal');
    showToast('同步規則已儲存', 'success');
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
      await withLoading('儲存 Remote Tab 中...', () =>
        api(`/api/remotes/${originalId}`, { method: 'PUT', body: JSON.stringify(payload) })
      );
      state.selectedRemoteTab = payload.id;
    } else {
      await withLoading('建立 Remote Tab 中...', () =>
        api('/api/remotes', { method: 'POST', body: JSON.stringify(payload) })
      );
      state.selectedRemoteTab = payload.id;
    }
    await loadAll({ silent: true });
    closeModal('remoteModal');
    showToast('Remote Tab 已儲存', 'success');
  } catch (error) {
    showToast(error.message, 'error');
  }
}

async function pickDownloadWorkspaceRoot() {
  try {
    const result = await withLoading('開啟資料夾選擇器...', () =>
      api('/api/system/select-directory', { method: 'POST', body: '{}' })
    );
    document.getElementById('downloadWorkspaceRoot').value = result.path || '';
  } catch (error) {
    showToast(error.message, 'error');
  }
}

async function toggleAutoSync(ruleId, enabled) {
  try {
    const selection = ruleSelection(ruleId);
    if (!selection) return;
    if (selection.rule.manualOnly && enabled) {
      throw new Error('Manual Only 規則不可啟用自動同步');
    }
    await withLoading(`正在${enabled ? '啟用' : '停用'}自動同步...`, () =>
      api(`/api/rules/${ruleId}/schedule`, {
        method: 'PUT',
        body: JSON.stringify({
          enabled,
          type: 'fixed-interval',
          intervalMinutes: selection.rule.schedule?.intervalMinutes || 30,
        }),
      })
    );
    await loadAll({ silent: true });
    showToast(`自動同步已${enabled ? '啟用' : '停用'}`, 'success');
  } catch (error) {
    showToast(error.message, 'error');
  }
}

function setRowForcePush(ruleId, checked) {
  state.rowForcePush[ruleId] = checked;
}

async function validateRule(ruleId) {
  try {
    const data = await withLoading('驗證同步規則中...', () =>
      api(`/api/rules/${ruleId}/validate`, { method: 'POST' })
    );
    document.getElementById('logView').textContent = JSON.stringify(data, null, 2);
    showToast(data.ok ? '驗證成功' : '驗證失敗', data.ok ? 'success' : 'error');
  } catch (error) {
    showToast(error.message, 'error');
  }
}

function versionStatusLabel(status) {
  const labels = {
    IDENTICAL: '完全一致',
    CONTENT_IDENTICAL: '內容一致，歷程不同',
    DIFFERENT: '版本不同',
    TARGET_MISSING: '目標分支不存在',
    CHECK_FAILED: '版本比對失敗',
  };
  return labels[status] || status || '未知';
}

function versionStatusClass(status) {
  if (status === 'IDENTICAL') return 'success';
  if (status === 'CONTENT_IDENTICAL') return 'warning';
  return 'failed';
}

function versionValue(value) {
  return value ? escapeHtml(value) : '-';
}

function renderVersionComparison(data) {
  const root = document.getElementById('versionCompareContent');
  const statusLabel = versionStatusLabel(data.status);
  root.innerHTML = `
    <div class="version-summary ${versionStatusClass(data.status)}">
      <span class="version-summary-label">${escapeHtml(statusLabel)}</span>
      <span>${escapeHtml(data.message || '')}</span>
    </div>
    <div class="version-meta-grid">
      <div class="version-meta-item">
        <span>規則</span>
        <strong>${escapeHtml(data.ruleName || data.ruleId || '-')}</strong>
      </div>
      <div class="version-meta-item">
        <span>比對時間</span>
        <strong>${escapeHtml(formatDateTime(data.checkedAt))}</strong>
      </div>
      <div class="version-meta-item">
        <span>來源 Branch</span>
        <strong>${escapeHtml(data.sourceBranch || '-')}</strong>
      </div>
      <div class="version-meta-item">
        <span>目標 Branch</span>
        <strong>${escapeHtml(data.targetBranch || '-')}</strong>
      </div>
    </div>
    <div class="version-side-grid">
      <section class="version-side">
        <h3>來源</h3>
        <div class="version-hash-row"><span>Commit</span><code>${versionValue(data.sourceCommit)}</code></div>
        <div class="version-hash-row"><span>Tree</span><code>${versionValue(data.sourceTree)}</code></div>
        <div class="version-count">${Number(data.sourceOnlyCommits || 0)} 個來源獨有 commit</div>
      </section>
      <section class="version-side">
        <h3>目標</h3>
        <div class="version-hash-row"><span>Commit</span><code>${versionValue(data.targetCommit)}</code></div>
        <div class="version-hash-row"><span>Tree</span><code>${versionValue(data.targetTree)}</code></div>
        <div class="version-count">${Number(data.targetOnlyCommits || 0)} 個目標獨有 commit</div>
      </section>
    </div>
  `;
  document.getElementById('versionCompareDiffButton').disabled = data.status !== 'DIFFERENT';
  document.getElementById('versionCompareLogButton').disabled = !data.logPath;
}

async function compareVersion(ruleId) {
  try {
    const selection = ruleSelection(ruleId);
    if (!selection) return;
    if (isDownloadOnlyRule(selection.rule)) {
      throw new Error('只下載規則沒有目標 remote，無法進行版本比對');
    }
    const data = await withLoading('比對來源與目標版本中...', () =>
      api(`/api/rules/${ruleId}/version-compare`, { method: 'POST' })
    );
    state.versionComparison = data;
    renderVersionComparison(data);
    openModal('versionCompareModal');
    const isSuccessful = data.status === 'IDENTICAL' || data.status === 'CONTENT_IDENTICAL';
    showToast(versionStatusLabel(data.status), isSuccessful ? 'success' : 'error');
  } catch (error) {
    showToast(error.message, 'error');
  }
}

async function showDiff(ruleId) {
  try {
    const selection = ruleSelection(ruleId);
    if (selection && isDownloadOnlyRule(selection.rule)) {
      throw new Error('只下載規則沒有目標 remote，無法查看差異');
    }
    state.reviewSelections[ruleId] = { confirmed: false, selectedCommitIds: [] };
    const popup = window.open(`/diff.html?ruleId=${encodeURIComponent(ruleId)}`, `diff-review-${ruleId}`,
      'popup=yes,width=1480,height=960,resizable=yes,scrollbars=yes');
    if (!popup) {
      throw new Error('瀏覽器阻擋了差異視窗，請允許彈出視窗後重試');
    }
    document.getElementById('diffView').textContent = '差異已在新視窗開啟，請在新視窗完成檢視與人工確認。';
    renderProjects();
    showToast('差異視窗已開啟', 'success');
  } catch (error) {
    showToast(error.message, 'error');
  }
}

function confirmDiffReview(ruleId, selectedCommitIds = [], showNotice = true) {
  state.reviewSelections[ruleId] = {
    confirmed: true,
    selectedCommitIds: [...selectedCommitIds],
  };
  renderProjects();
  if (showNotice) {
    showToast('已完成本次人工確認', 'success');
  }
}

async function runSync(ruleId) {
  try {
    const selection = ruleSelection(ruleId);
    if (!selection) return;
    if (selection.rule.currentJobStatus) {
      throw new Error('這筆規則已有進行中的手動同步 job');
    }
    const downloadOnly = isDownloadOnlyRule(selection.rule);
    const forcePush = !downloadOnly && !!state.rowForcePush[ruleId] && !!selection.rule.allowForcePush;
    const reviewConfirmed = downloadOnly || !selection.rule.reviewRequired || isReviewReady(ruleId);
    const selectedCommitIds = !downloadOnly && selection.rule.reviewRequired ? selectedCommitIdsFor(ruleId) : [];
    if (!downloadOnly && selection.rule.reviewRequired && !reviewConfirmed) {
      throw new Error('這筆規則需要先查看差異並完成人工確認');
    }
    if (!downloadOnly && selection.rule.reviewRequired && !selectedCommitIds.length) {
      throw new Error('請先在查看差異畫面中選擇至少一筆 commit');
    }
    const result = await withLoading(downloadOnly ? '提交下載工作中...' : '提交同步工作中...', () =>
      api(`/api/rules/${ruleId}/sync`, {
        method: 'POST',
        body: JSON.stringify({ forcePush, reviewConfirmed, selectedCommitIds }),
      })
    );
    document.getElementById('logView').textContent = JSON.stringify(result, null, 2);
    await loadAll({ silent: true });
    if (result.jobId) {
      trackSyncJob(result.jobId);
    }
    showToast(result.message || (downloadOnly ? '下載工作已排入佇列' : '同步工作已排入佇列'), 'success');
  } catch (error) {
    showToast(error.message, 'error');
  }
}

async function trackSyncJob(jobId) {
  if (!jobId || state.syncJobPolls[jobId]) {
    return;
  }
  state.syncJobPolls[jobId] = true;
  try {
    while (true) {
      const job = await api(`/api/sync-jobs/${encodeURIComponent(jobId)}`);
      if (job.status === 'queued' || job.status === 'running') {
        await loadAll({ silent: true });
        await delay(2000);
        continue;
      }
      await loadAll({ silent: true });
      if (job.logPath) {
        await loadLog(job.logPath);
      }
      showToast(job.message || (job.status === 'success' ? '同步成功' : '同步失敗'),
        job.status === 'success' ? 'success' : 'error');
      break;
    }
  } catch (error) {
    showToast(error.message, 'error');
  } finally {
    delete state.syncJobPolls[jobId];
  }
}

function delay(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function deleteProject(projectId) {
  try {
    const project = projectById(projectId);
    if (!confirm(`確定要刪除專案 ${project?.name || projectId} 嗎？`)) {
      return;
    }
    await withLoading('刪除專案中...', () =>
      api(`/api/projects/${projectId}`, { method: 'DELETE' })
    );
    await loadAll({ silent: true });
    showToast('專案已刪除', 'success');
  } catch (error) {
    showToast(error.message, 'error');
  }
}

async function deleteRule(projectId, ruleId) {
  try {
    const selection = ruleSelection(ruleId);
    if (!confirm(`確定要刪除同步規則 ${selection?.rule?.name || ruleId} 嗎？`)) {
      return;
    }
    await withLoading('刪除同步規則中...', () =>
      api(`/api/projects/${projectId}/rules/${ruleId}`, { method: 'DELETE' })
    );
    delete state.reviewSelections[ruleId];
    document.getElementById('diffView').textContent = '尚未載入差異';
    await loadAll({ silent: true });
    showToast('同步規則已刪除', 'success');
  } catch (error) {
    showToast(error.message, 'error');
  }
}

async function deleteRemote(id) {
  try {
    if (!confirm(`確定要刪除 Remote Tab ${id} 嗎？`)) return;
    await withLoading('刪除 Remote Tab 中...', () => api(`/api/remotes/${id}`, { method: 'DELETE' }));
    if (state.selectedRemoteTab === id) state.selectedRemoteTab = 'all';
    await loadAll({ silent: true });
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
    loadAll({ silent: true }).catch(error => showToast(error.message, 'error'));
  }, 10000);
}

async function loadLog(logId) {
  try {
    const log = await withLoading('載入執行紀錄中...', () => api(`/api/logs/${encodeURIComponent(logId)}`));
    document.getElementById('logView').textContent = log.content || '(empty log)';
  } catch (error) {
    showToast(error.message, 'error');
  }
}

function stripRuntimeFieldsFromRule(rule) {
  return {
    id: rule.id,
    name: rule.name,
    mode: ruleMode(rule),
    sourceBranch: rule.sourceBranch,
    targetRemoteId: rule.targetRemoteId,
    targetRepoName: rule.targetRepoName,
    targetBranch: rule.targetBranch,
    downloadWorkspaceRoot: rule.downloadWorkspaceRoot || '',
    sameBranchNameExpected: !!rule.sameBranchNameExpected,
    enabled: !!rule.enabled,
    allowForcePush: !!rule.allowForcePush,
    manualOnly: !!rule.manualOnly,
    reviewRequired: !!rule.reviewRequired,
    schedule: rule.schedule || { enabled: false, type: 'fixed-interval', intervalMinutes: 30 },
  };
}

const ModelsId = {
  project() {
    return `project-${Date.now()}`;
  },
  rule() {
    return `rule-${Date.now()}`;
  },
};

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

document.getElementById('projectForm').addEventListener('submit', saveProject);
document.getElementById('ruleForm').addEventListener('submit', saveRule);
document.getElementById('remoteForm').addEventListener('submit', saveRemote);
document.getElementById('saveSystemSettingsButton').addEventListener('click', saveSystemSettings);
document.getElementById('exportConfigButton').addEventListener('click', exportConfig);
document.getElementById('importConfigButton').addEventListener('click', promptImportConfig);
document.getElementById('importConfigFile').addEventListener('change', importConfigFile);
document.getElementById('newProjectButton').addEventListener('click', newProject);
document.getElementById('newRemoteButton').addEventListener('click', newRemote);
document.getElementById('refreshButton').addEventListener('click', () => loadAll());
document.getElementById('pickGlobalWorkspaceRootButton').addEventListener('click', pickGlobalWorkspaceRoot);
document.getElementById('pickDownloadWorkspaceRootButton').addEventListener('click', pickDownloadWorkspaceRoot);
document.getElementById('vendorRepoUrl').addEventListener('input', syncProjectFormRules);
document.getElementById('globalWorkspaceRoot').addEventListener('input', () => {
  state.systemSettingsDirty = true;
  updateLocalRepoPathPreview();
});
document.getElementById('localProjectName').addEventListener('input', updateLocalRepoPathPreview);
document.getElementById('ruleMode').addEventListener('change', syncRuleFormRules);
document.getElementById('sameBranchNameExpected').addEventListener('change', syncRuleFormRules);
document.getElementById('sourceBranch').addEventListener('input', syncRuleFormRules);
document.getElementById('manualOnly').addEventListener('change', syncRuleFormRules);
document.getElementById('targetRemoteId').addEventListener('change', updateTargetUrlPreview);
document.getElementById('targetRepoName').addEventListener('input', updateTargetUrlPreview);
document.getElementById('targetBranch').addEventListener('input', syncRuleFormRules);
document.getElementById('remoteBaseUrl').addEventListener('input', updateRemoteExamplePreview);
document.getElementById('versionCompareDiffButton').addEventListener('click', () => {
  const ruleId = state.versionComparison?.ruleId;
  if (ruleId) {
    closeModal('versionCompareModal');
    showDiff(ruleId);
  }
});
document.getElementById('versionCompareLogButton').addEventListener('click', () => {
  const logPath = state.versionComparison?.logPath;
  if (logPath) {
    loadLog(logPath);
  }
});

document.querySelectorAll('[data-close-modal]').forEach(button => {
  button.addEventListener('click', () => closeModal(button.dataset.closeModal));
});

window.addEventListener('message', event => {
  if (event.origin !== window.location.origin) {
    return;
  }
  const data = event.data || {};
  if (data.type === 'diff-review-confirmed' && data.ruleId) {
    confirmDiffReview(data.ruleId, data.selectedCommitIds || []);
  }
  if (data.type === 'diff-sync-completed' && data.ruleId) {
    confirmDiffReview(data.ruleId, data.selectedCommitIds || [], false);
    loadAll({ silent: true })
      .then(async () => {
        if (data.logPath) {
          await loadLog(data.logPath);
        }
        showToast(data.message || '同步成功', 'success');
      })
      .catch(error => showToast(error.message, 'error'));
  }
});

loadAll({ silent: true }).catch(error => {
  document.getElementById('logView').textContent = error.message;
  showToast(error.message, 'error');
});
startAutoRefresh();
updateRemoteExamplePreview();
updateLocalRepoPathPreview();

window.editProject = editProject;
window.newRule = newRule;
window.editRule = editRule;
window.toggleProjectCollapse = toggleProjectCollapse;
window.editRemote = editRemote;
window.showDiff = showDiff;
window.compareVersion = compareVersion;
window.runSync = runSync;
window.validateRule = validateRule;
window.toggleAutoSync = toggleAutoSync;
window.setRowForcePush = setRowForcePush;
window.selectRemoteTab = selectRemoteTab;
window.deleteProject = deleteProject;
window.deleteRule = deleteRule;
window.deleteRemote = deleteRemote;
window.confirmDiffReview = confirmDiffReview;

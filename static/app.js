const state = {
  projects: [],
  remotes: [],
  systemConfig: { localWorkspaceRoot: '' },
  selectedRemoteTab: 'all',
  selectedDiffRuleId: null,
  diffConfirmed: false,
  rowForcePush: {},
  collapsedProjects: {},
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
  return state.selectedDiffRuleId === ruleId && state.diffConfirmed;
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
  const statusClass = rule.lastStatus === 'failed' ? 'failed'
    : rule.lastStatus === 'success' ? 'success'
    : rule.lastStatus === 'running' ? 'running' : '';
  const reviewReady = isReviewReady(rule.id);
  const syncDisabled = rule.reviewRequired && !reviewReady;
  return `
    <tr>
      <td>
        <div class="stacked-copy">
          <strong>${escapeHtml(rule.name)}</strong>
          <span>${rule.manualOnly ? '<span class="tag">Manual Only</span>' : ''}${rule.schedule?.enabled ? '<span class="tag">Auto Sync</span>' : ''}</span>
        </div>
      </td>
      <td>
        <div class="stacked-copy">
          <span><code>${escapeHtml(rule.sourceBranch)}</code> -> <code>${escapeHtml(rule.targetBranch)}</code></span>
          <span>${rule.reviewRequired ? `<span class="tag ${reviewReady ? 'success' : ''}">Review ${reviewReady ? 'Ready' : 'Required'}</span>` : ''}</span>
        </div>
      </td>
      <td>
        <div class="stacked-copy">
          <strong>${escapeHtml(rule.targetRemoteName || rule.targetRemoteId)}</strong>
          <span>${escapeHtml(rule.targetRepoName)}</span>
        </div>
      </td>
      <td>
        <span class="tag ${statusClass}">${escapeHtml(rule.lastStatus || 'never')}</span>
        <div class="status-copy">${escapeHtml(formatDateTime(rule.lastRunAt))}</div>
        <div class="status-copy">${escapeHtml(rule.lastRunSource || '-')}</div>
        <div class="status-copy">${escapeHtml(rule.lastMessage || '')}</div>
      </td>
      <td>${escapeHtml(formatDateTime(rule.nextRunAt))}</td>
      <td>
        <div class="row-controls">
          <label class="mini-check">
            <input type="checkbox"
              ${rule.allowForcePush ? '' : 'disabled'}
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
            <button class="secondary" onclick="showDiff('${escapeAttr(rule.id)}')">查看差異</button>
            <button ${syncDisabled ? 'disabled' : ''} onclick="runSync('${escapeAttr(rule.id)}')">同步</button>
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
  const sameBranch = document.getElementById('sameBranchNameExpected').checked;
  const manualOnly = document.getElementById('manualOnly').checked;
  const sourceBranch = document.getElementById('sourceBranch').value;
  const targetBranch = document.getElementById('targetBranch');
  const scheduleEnabled = document.getElementById('scheduleEnabled');
  const ruleName = document.getElementById('ruleName');
  if (sameBranch && sourceBranch) {
    targetBranch.value = sourceBranch;
  }
  if (!ruleName.value.trim() && sourceBranch.trim()) {
    ruleName.value = `${sourceBranch} -> ${targetBranch.value || sourceBranch}`;
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
  document.getElementById('sourceBranch').value = rule.sourceBranch;
  document.getElementById('targetRemoteId').value = rule.targetRemoteId;
  document.getElementById('targetRepoName').value = rule.targetRepoName;
  document.getElementById('targetBranch').value = rule.targetBranch;
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
    await loadAll({ silent: true });
    updateLocalRepoPathPreview();
    showToast('全局設定已儲存', 'success');
  } catch (error) {
    showToast(error.message, 'error');
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
    const payload = {
      id: ruleId,
      name: document.getElementById('ruleName').value,
      sourceBranch: document.getElementById('sourceBranch').value,
      targetRemoteId: document.getElementById('targetRemoteId').value,
      targetRepoName: document.getElementById('targetRepoName').value,
      targetBranch: document.getElementById('targetBranch').value,
      sameBranchNameExpected: document.getElementById('sameBranchNameExpected').checked,
      enabled: document.getElementById('ruleEnabled').checked,
      allowForcePush: document.getElementById('allowForcePush').checked,
      manualOnly: document.getElementById('manualOnly').checked,
      reviewRequired: document.getElementById('reviewRequired').checked,
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

async function showDiff(ruleId) {
  try {
    state.selectedDiffRuleId = ruleId;
    state.diffConfirmed = false;
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

function confirmDiffReview(ruleId, showNotice = true) {
  state.selectedDiffRuleId = ruleId;
  state.diffConfirmed = true;
  renderProjects();
  if (showNotice) {
    showToast('已完成本次人工確認', 'success');
  }
}

async function runSync(ruleId) {
  try {
    const selection = ruleSelection(ruleId);
    if (!selection) return;
    const forcePush = !!state.rowForcePush[ruleId] && !!selection.rule.allowForcePush;
    const reviewConfirmed = !selection.rule.reviewRequired || isReviewReady(ruleId);
    if (selection.rule.reviewRequired && !reviewConfirmed) {
      throw new Error('這筆規則需要先查看差異並完成人工確認');
    }
    const result = await withLoading('同步中，請稍候...', () =>
      api(`/api/rules/${ruleId}/sync`, {
        method: 'POST',
        body: JSON.stringify({ forcePush, reviewConfirmed }),
      })
    );
    document.getElementById('logView').textContent = JSON.stringify(result, null, 2);
    await loadAll({ silent: true });
    if (result.runId) {
      await loadLog(result.runId);
    }
    showToast(result.message || '同步成功', 'success');
  } catch (error) {
    showToast(error.message, 'error');
  }
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
    if (state.selectedDiffRuleId === ruleId) {
      state.selectedDiffRuleId = null;
      state.diffConfirmed = false;
      document.getElementById('diffView').textContent = '尚未載入差異';
    }
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

async function loadLog(runId) {
  try {
    const log = await withLoading('載入執行紀錄中...', () => api(`/api/logs/${runId}`));
    document.getElementById('logView').textContent = log.content || '(empty log)';
  } catch (error) {
    showToast(error.message, 'error');
  }
}

function stripRuntimeFieldsFromRule(rule) {
  return {
    id: rule.id,
    name: rule.name,
    sourceBranch: rule.sourceBranch,
    targetRemoteId: rule.targetRemoteId,
    targetRepoName: rule.targetRepoName,
    targetBranch: rule.targetBranch,
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
document.getElementById('newProjectButton').addEventListener('click', newProject);
document.getElementById('newRemoteButton').addEventListener('click', newRemote);
document.getElementById('refreshButton').addEventListener('click', () => loadAll());
document.getElementById('pickGlobalWorkspaceRootButton').addEventListener('click', pickGlobalWorkspaceRoot);
document.getElementById('vendorRepoUrl').addEventListener('input', syncProjectFormRules);
document.getElementById('globalWorkspaceRoot').addEventListener('input', updateLocalRepoPathPreview);
document.getElementById('localProjectName').addEventListener('input', updateLocalRepoPathPreview);
document.getElementById('sameBranchNameExpected').addEventListener('change', syncRuleFormRules);
document.getElementById('sourceBranch').addEventListener('input', syncRuleFormRules);
document.getElementById('manualOnly').addEventListener('change', syncRuleFormRules);
document.getElementById('targetRemoteId').addEventListener('change', updateTargetUrlPreview);
document.getElementById('targetRepoName').addEventListener('input', updateTargetUrlPreview);
document.getElementById('targetBranch').addEventListener('input', syncRuleFormRules);
document.getElementById('remoteBaseUrl').addEventListener('input', updateRemoteExamplePreview);

document.querySelectorAll('[data-close-modal]').forEach(button => {
  button.addEventListener('click', () => closeModal(button.dataset.closeModal));
});

window.addEventListener('message', event => {
  if (event.origin !== window.location.origin) {
    return;
  }
  const data = event.data || {};
  if (data.type === 'diff-review-confirmed' && data.ruleId) {
    confirmDiffReview(data.ruleId);
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
window.runSync = runSync;
window.validateRule = validateRule;
window.toggleAutoSync = toggleAutoSync;
window.setRowForcePush = setRowForcePush;
window.selectRemoteTab = selectRemoteTab;
window.deleteProject = deleteProject;
window.deleteRule = deleteRule;
window.deleteRemote = deleteRemote;
window.confirmDiffReview = confirmDiffReview;

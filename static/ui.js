/* Shared offline shell and accessible interaction primitives. */
(() => {
  const paths = {
    branch: '<path d="M6 3v12a4 4 0 0 0 4 4h8M6 9h8a4 4 0 0 0 4-4V3"/><circle cx="6" cy="3" r="2"/><circle cx="18" cy="3" r="2"/><circle cx="18" cy="19" r="2"/>',
    projects: '<rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/>',
    compare: '<path d="M3 7h16l-4-4M21 17H5l4 4M19 7l-4 4M5 17l4-4"/>',
    remote: '<rect x="3" y="3" width="18" height="7" rx="2"/><rect x="3" y="14" width="18" height="7" rx="2"/><path d="M7 6.5h.01M7 17.5h.01M15 6.5h3M15 17.5h3"/>',
    activity: '<path d="M4 3h16v18H4zM8 7h8M8 11h8M8 15h5"/>',
    settings: '<path d="M4 6h16M4 12h16M4 18h16"/><circle cx="9" cy="6" r="2"/><circle cx="16" cy="12" r="2"/><circle cx="9" cy="18" r="2"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    refresh: '<path d="M20 7a9 9 0 1 0 1 8M20 3v5h-5"/>',
    push: '<path d="M12 16V3M7 8l5-5 5 5M4 15v6h16v-6"/>',
    chevron: '<path d="m9 5 7 7-7 7"/>',
    folder: '<path d="M3 7V5a2 2 0 0 1 2-2h5l3 3h6a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>',
    dots: '<circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/>',
  };
  function icon(name) {
    return `<svg class="ui-icon" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[name] || paths.branch}</svg>`;
  }

  const isDashboard = document.body.classList.contains('dashboard-page');
  const isBatch = document.body.classList.contains('batch-version-page');
  const navItems = [
    ['projects', '專案同步', '/#projects', 'projects'],
    ['compare', '批次版本比對', '/batch-version.html', 'compare'],
    ['remotes', 'Remote 管理', '/#remotes', 'remote'],
    ['activity', '執行紀錄', '/#activity', 'activity'],
    ['settings', '工作區設定', '/#settings', 'settings'],
  ];
  document.body.insertAdjacentHTML('afterbegin', `
    <aside class="app-sidebar" aria-label="工作區側欄">
      <a class="app-brand" href="/#projects" translate="no"><span class="brand-mark">${icon('branch')}</span><span>Git Web<small>OFFLINE WORKSPACE</small></span></a>
      <div class="nav-label">工作空間</div>
      <nav aria-label="主要導覽">${navItems.map(([key, label, href, glyph]) => `<a href="${href}" class="nav-link" data-nav-view="${key}">${icon(glyph)}<span>${label}</span></a>`).join('')}</nav>
      <div class="sidebar-footer"><span class="sidebar-status"><i class="status-dot success" aria-hidden="true"></i> 本地工作空間</span><small>Java 17 · Git · 無資料庫</small></div>
    </aside>
  `);

  const views = {
    projects: ['專案同步', 'REPOSITORY WORKSPACE', '從來源到目標，掌握每一條同步規則。'],
    remotes: ['Remote 管理', 'DESTINATION TEMPLATES', '依環境組織目標倉庫，重複使用連線設定。'],
    activity: ['執行紀錄', 'ACTIVITY & REVIEW', '檢視最近載入的執行結果與差異審查資訊。'],
    settings: ['工作區設定', 'WORKSPACE SETTINGS', '管理本地儲存位置與可攜式設定檔。'],
  };
  function navigate() {
    const key = isDashboard && Object.hasOwn(views, location.hash.slice(1)) ? location.hash.slice(1) : 'projects';
    const activeKey = isDashboard ? key : isBatch ? 'compare' : '';
    document.querySelectorAll('[data-nav-view]').forEach(link => {
      if (link.dataset.navView === activeKey) link.setAttribute('aria-current', 'page');
      else link.removeAttribute('aria-current');
    });
    if (!isDashboard) return;
    document.querySelectorAll('[data-workspace-view]').forEach(panel => { panel.hidden = panel.dataset.workspaceView !== key; });
    const [title, eyebrow, description] = views[key];
    document.getElementById('workspaceTitle').textContent = title;
    document.getElementById('workspaceBreadcrumb').textContent = title;
    document.getElementById('workspaceEyebrow').textContent = eyebrow;
    document.getElementById('workspaceDescription').textContent = description;
    document.title = `${title} · Git Web`;
  }
  window.addEventListener('hashchange', navigate);
  navigate();

  function decorate(root = document) {
    root.querySelectorAll('[data-icon]:not([data-icon-ready])').forEach(element => {
      element.insertAdjacentHTML('afterbegin', icon(element.dataset.icon));
      element.dataset.iconReady = 'true';
    });
    root.querySelectorAll('input, select').forEach(control => {
      if (!control.name) control.name = control.id || 'selection';
      if (!control.autocomplete) control.autocomplete = 'off';
      if (control.matches('input:not([type="checkbox"]):not([type="file"])')) control.spellcheck = false;
    });
    root.querySelectorAll('th').forEach(cell => cell.scope = 'col');
  }
  decorate();

  const dateFormatter = new Intl.DateTimeFormat('en-CA', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
  });
  function formatDateTime(value) {
    if (!value) return '-';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return String(value);
    const parts = Object.fromEntries(dateFormatter.formatToParts(date).map(part => [part.type, part.value]));
    return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}`;
  }
  let toastTimer;
  function notify(message, type = 'success') {
    clearTimeout(toastTimer);
    const toast = document.getElementById('toast');
    toast.textContent = message;
    toast.className = `toast ${type}`;
    toastTimer = setTimeout(() => { toast.className = 'toast hidden'; }, type === 'error' ? 10000 : 5000);
  }

  let modalOpener = null;
  let activeModal = null;
  function openModal(id) {
    const modal = document.getElementById(id);
    modalOpener = document.activeElement;
    activeModal = modal;
    modal.classList.remove('hidden');
    const card = modal.querySelector('.modal-card');
    card.setAttribute('role', 'dialog');
    card.setAttribute('aria-modal', 'true');
    const title = card.querySelector('h2');
    if (!title.id) title.id = `${id}Heading`;
    card.setAttribute('aria-labelledby', title.id);
    document.body.classList.add('modal-open');
    for (const child of document.body.children) {
      if (child !== modal && !['SCRIPT', 'STYLE'].includes(child.tagName) && !['toast', 'loadingOverlay'].includes(child.id)) child.inert = true;
    }
    const focusTarget = modal.querySelector('input:not([type="hidden"]):not([disabled]), select:not([disabled])')
      || modal.querySelector('button:not([disabled])');
    focusTarget?.focus({ preventScroll: true });
  }
  function closeModal(id) {
    const modal = document.getElementById(id);
    modal.classList.add('hidden');
    if (activeModal !== modal) return;
    document.body.classList.remove('modal-open');
    for (const child of document.body.children) child.inert = false;
    activeModal = null;
    modalOpener?.focus({ preventScroll: true });
  }
  document.addEventListener('keydown', event => {
    if (!activeModal) return;
    if (event.key === 'Escape') { event.preventDefault(); closeModal(activeModal.id); }
    if (event.key !== 'Tab') return;
    const focusable = [...activeModal.querySelectorAll('button, input:not([type="hidden"]), select, a[href], [tabindex="0"]')]
      .filter(element => !element.disabled && element.getClientRects().length);
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
  });
  document.addEventListener('click', event => {
    document.querySelectorAll('details.action-menu[open]').forEach(menu => {
      if (!menu.contains(event.target) || event.target.closest('button, a')) menu.open = false;
    });
  });
  // Fixed positioning keeps menus and errors visible outside horizontally scrolling tables.
  function placeFloating(anchor, panel) {
    const rect = anchor.getBoundingClientRect();
    const width = Math.min(panel.classList.contains('status-tooltip-panel') ? 460 : 160, innerWidth - 24);
    panel.style.width = `${width}px`;
    panel.style.left = `${Math.max(12, Math.min(rect.right - width, innerWidth - width - 12))}px`;
    panel.style.top = `${Math.max(12, Math.min(rect.bottom + 6, innerHeight - panel.offsetHeight - 12))}px`;
  }
  document.addEventListener('toggle', event => {
    if (event.target.matches?.('details.action-menu[open]')) placeFloating(event.target, event.target.querySelector('div'));
  }, true);
  for (const type of ['mouseover', 'focusin']) {
    document.addEventListener(type, event => {
      const tooltip = event.target.closest?.('.status-tooltip');
      if (tooltip) placeFloating(tooltip, tooltip.querySelector('.status-tooltip-panel'));
    });
  }
  document.addEventListener('scroll', () => {
    document.querySelectorAll('details.action-menu[open]').forEach(menu => { menu.open = false; });
  }, true);
  window.GitWebUI = { icon, decorate, openModal, closeModal, formatDateTime, notify };
})();

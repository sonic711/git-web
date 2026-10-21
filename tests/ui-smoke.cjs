// Development-only checks. Production still needs only Java 17 and Git.
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { chromium } = require('playwright');

const baseUrl = process.env.UI_TEST_URL || 'http://127.0.0.1:18081';
const artifactDir = process.env.UI_ARTIFACTS_DIR;
const rule = (id, overrides = {}) => ({
  id, name: 'sit → sit', mode: 'sync', sourceBranch: 'sit', targetBranch: 'sit',
  targetRemoteId: 'sit', targetRemoteName: 'SIT', targetRepoName: 'project.git',
  enabled: true, allowForcePush: true, manualOnly: false, reviewRequired: false,
  schedule: { enabled: true, intervalMinutes: 5 }, lastStatus: 'success',
  lastRunAt: '2026-10-21T14:30:00+08:00', nextRunAt: '2026-10-21T14:35:00+08:00',
  lastRunSource: 'schedule', lastMessage: 'Sync completed', ...overrides,
});
const projects = [
  { id: 'adm', name: 'fsap-adm', vendorRepoUrl: 'https://vendor.example/fsap-adm.git', enabled: true, rules: [
    rule('adm-sit'), rule('adm-uat', { name: 'uat → uat', sourceBranch: 'uat', targetBranch: 'uat',
      manualOnly: true, reviewRequired: true, schedule: { enabled: false }, lastStatus: 'never', lastRunAt: null, nextRunAt: null, lastMessage: '' }),
  ] },
  { id: 'batch', name: 'fsap-batch', vendorRepoUrl: 'https://vendor.example/fsap-batch.git', enabled: true, rules: [
    rule('batch-sit', { lastStatus: 'failed', lastMessage: 'Git command failed: git push sync_target sit:refs/heads/sit\nPermission denied (publickey).', lastLogPath: '2026-10-21.log' }),
  ] },
  { id: 'gateway', name: 'fsap-gateway', vendorRepoUrl: 'https://vendor.example/fsap-gateway.git', enabled: true, rules: [
    rule('gateway-sit', { mode: 'download-only', name: '下載 sit', targetRemoteId: '', targetBranch: '', localRepoPath: '/workspace/fsap-gateway' }),
  ] },
];
const specs = [{ key: 'sit|sit|sit', sourceBranch: 'sit', targetRemoteId: 'sit', targetRemoteName: 'SIT', targetBranch: 'sit',
  ruleCount: 3, projectCount: 3, projects: projects.map(project => ({ projectId: project.id, projectName: project.name, ruleCount: 1 })) }];

(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.UI_TEST_BROWSER || undefined });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1040 }, locale: 'zh-TW', timezoneId: 'Asia/Taipei' });
  const errors = [];
  const submissions = [];
  const unexpectedRequests = [];
  let latestJob;
  let polls = 0;
  context.on('page', page => page.on('pageerror', error => errors.push(error.message)));
  await context.route('**/api/**', async route => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    let body;
    if (pathname === '/api/system/config') body = { localWorkspaceRoot: '/workspace' };
    else if (pathname === '/api/projects') body = projects;
    else if (pathname === '/api/remotes') body = [{ id: 'sit', name: 'SIT', baseUrl: 'ssh://git@git.example/team/', enabled: true }];
    else if (pathname === '/api/version-comparison/specs') body = specs;
    else if (pathname === '/api/version-comparison/jobs' && request.method() === 'POST') {
      const submitted = request.postDataJSON();
      submissions.push(submitted);
      polls = 0;
      latestJob = { ...submitted, jobId: 'ui-test-job', status: 'running', targetRemoteName: 'SIT',
        selectedProjectIds: submitted.projectIds, selectedProjectCount: submitted.projectIds.length,
        total: submitted.projectIds.length, completed: 0, queuedAt: '2026-10-21T14:30:00+08:00', results: [] };
      body = latestJob;
    } else if (pathname === '/api/version-comparison/jobs/ui-test-job') {
      polls += 1;
      if (polls > 1) {
        latestJob = { ...latestJob, status: 'completed', completed: latestJob.total,
          results: latestJob.selectedProjectIds.map(id => ({ projectId: id, projectName: projects.find(project => project.id === id).name,
            ruleId: `${id}-sit`, ruleName: 'sit → sit', sourceBranch: 'sit', targetBranch: 'sit',
            sourceCommit: 'abc1234567890', targetCommit: 'abc1234567890', tagsIdentical: true,
            commitIdentical: true, contentIdentical: true, sourceTags: ['sit-v1'], targetTags: ['sit-v1'],
            checkedAt: '2026-10-21T14:30:00+08:00', status: 'IDENTICAL' })) };
      }
      body = latestJob;
    } else if (pathname.endsWith('/diff-cache')) {
      body = { ruleId: 'adm-sit', projectName: 'fsap-adm', ruleName: 'sit → sit', sourceBranch: 'sit', targetBranch: 'sit',
        targetRemoteName: 'SIT', targetRepoName: 'fsap-adm.git', allowForcePush: true,
        summary: { aheadCommits: 1, changedFiles: 2 }, cacheStatus: 'fresh',
        commits: [{ id: 'abc1234', shortId: 'abc1234', title: 'Update payment validation', author: 'Developer', committedAt: '2026-10-21T14:30:00+08:00' }] };
    } else if (pathname.endsWith('/commits/abc1234/files')) {
      body = { files: [{ status: 'M', path: 'src/main/Payment.java' }, { status: 'A', path: 'src/test/PaymentTest.java' }] };
    } else {
      unexpectedRequests.push(`${request.method()} ${pathname}`);
      return route.fulfill({ status: 400, json: { error: { message: 'Unexpected test request' } } });
    }
    await route.fulfill({ status: 200, json: body });
  });
  try {
    const page = await context.newPage();
    await page.goto(baseUrl);
    await page.locator('#overviewProjects').filter({ hasText: '3' }).waitFor();
    await page.evaluate(() => document.fonts.ready);
    assert.equal(await page.locator('.project-card').count(), 3);
    assert.equal(await page.locator('#overviewFailed').textContent(), '1');
    await page.locator('#newProjectButton').click();
    assert.equal(await page.getByRole('dialog').count(), 1);
    assert.equal(await page.evaluate(() => document.activeElement.id), 'projectName');
    await page.locator('#projectForm button[type="submit"]').focus();
    await page.keyboard.press('Tab');
    assert.equal(await page.evaluate(() => document.activeElement.closest('#projectModal') !== null), true);
    await page.keyboard.press('Escape');
    assert.equal(await page.evaluate(() => document.activeElement.id), 'newProjectButton');
    await page.locator('.project-head .action-menu summary').first().click();
    await page.getByRole('button', { name: '編輯專案', exact: true }).click();
    assert.equal(await page.locator('#projectName').inputValue(), 'fsap-adm');
    await page.keyboard.press('Escape');
    await page.locator('#ruleFilterKeyword').fill('does-not-exist');
    assert.equal(await page.locator('.filter-empty').count(), 1);
    await page.locator('#clearRuleFiltersButton').click();
    assert.equal(await page.locator('.project-card').count(), 3);
    const originalRow = await page.locator('.project-rules tr').nth(1).elementHandle();
    await page.evaluate(() => loadAll({ silent: true }));
    assert.equal(await originalRow.evaluate(element => element.isConnected), true, 'unchanged polling must preserve the table DOM');
    await page.locator('#showFailedRules').click();
    assert.equal(await page.locator('.project-card').count(), 1);
    await page.locator('#clearRuleFiltersButton').click();
    await page.locator('.status-tooltip').hover();
    const tooltip = await page.locator('.status-tooltip-panel').boundingBox();
    assert.ok(tooltip && tooltip.x >= 0 && tooltip.y >= 0 && tooltip.x + tooltip.width <= 1440);
    await page.mouse.move(0, 0);
    await page.getByRole('navigation').getByRole('link', { name: '工作區設定' }).click();
    await page.locator('#globalWorkspaceRoot').waitFor({ state: 'visible' });
    assert.equal(await page.locator('#globalWorkspaceRoot').inputValue(), '/workspace');
    await page.getByRole('navigation').getByRole('link', { name: 'Remote 管理' }).click();
    await page.locator('#remoteTable').waitFor({ state: 'visible' });
    await page.getByRole('navigation').getByRole('link', { name: '專案同步' }).click();
    await page.getByRole('heading', { name: '專案同步', exact: true }).waitFor();
    if (artifactDir) { await fs.mkdir(artifactDir, { recursive: true }); await page.screenshot({ path: path.join(artifactDir, 'projects-desktop.png'), fullPage: true }); }

    await page.goto(`${baseUrl}/batch-version.html`);
    await page.locator('.batch-project-option').first().waitFor();
    await page.locator('#clearBatchProjectsButton').click();
    assert.equal(await page.locator('#startBatchButton').isDisabled(), true);
    await page.locator('#selectAllBatchProjectsButton').click();
    await page.locator('.batch-project-option input[value="gateway"]').uncheck();
    await page.locator('#startBatchButton').click();
    await page.locator('#loadingOverlay').waitFor({ state: 'hidden' });
    assert.deepEqual(submissions[0].projectIds, ['adm', 'batch']);
    await page.locator('.batch-project-option input[value="batch"]').uncheck();
    await page.waitForFunction(() => !document.getElementById('rerunBatchButton').disabled);
    assert.equal(await page.locator('.batch-project-option input[value="batch"]').isChecked(), false, 'polling must preserve selection');
    await page.locator('#rerunBatchButton').click();
    await page.locator('#loadingOverlay').waitFor({ state: 'hidden' });
    assert.deepEqual(submissions[1].projectIds, ['adm', 'batch'], 'rerun must retain original job scope');
    await page.waitForFunction(() => !document.getElementById('rerunBatchButton').disabled);
    assert.equal(await page.locator('#batchProgress').getAttribute('aria-valuenow'), '100');
    if (artifactDir) await page.screenshot({ path: path.join(artifactDir, 'batch-desktop.png'), fullPage: true });

    await page.goto(`${baseUrl}/diff.html?ruleId=adm-sit`);
    await page.locator('.diff-commit-button').click();
    await page.getByText('src/main/Payment.java', { exact: true }).waitFor();
    await page.locator('#diffCommitList input').check();
    await page.locator('#confirmReviewButton').click();
    assert.equal(await page.locator('#pushSelectedCommitsButton').isEnabled(), true);
    if (artifactDir) await page.screenshot({ path: path.join(artifactDir, 'review-desktop.png'), fullPage: true });

    for (const width of [375, 768, 1024, 1440]) {
      await page.setViewportSize({ width, height: 900 });
      for (const url of ['/', '/batch-version.html', '/diff.html?ruleId=adm-sit']) {
        await page.goto(`${baseUrl}${url}`);
        await page.locator('#loadingOverlay').waitFor({ state: 'hidden' });
        const overflow = await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1);
        assert.equal(overflow, false, `page overflow at ${width}px: ${url}`);
      }
    }
    await page.setViewportSize({ width: 375, height: 900 });
    await page.goto(baseUrl);
    await page.locator('.project-card').first().waitFor();
    if (artifactDir) await page.screenshot({ path: path.join(artifactDir, 'projects-mobile.png'), fullPage: true });
    await page.emulateMedia({ reducedMotion: 'reduce' });
    assert.equal(await page.locator('.spinner').evaluate(el => getComputedStyle(el).animationName), 'none');
    const font = await context.request.get(`${baseUrl}/fonts/ibm-plex-sans.ttf`);
    assert.equal(font.headers()['content-type'], 'font/ttf');
    assert.ok((await font.body()).length > 100000);
    assert.deepEqual(errors, []);
    assert.deepEqual(unexpectedRequests, []);
    console.log('UI smoke checks passed: navigation, filtering, modal keyboard focus, batch selection/polling/rerun, commit review, responsive layout, local fonts, reduced motion.');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });

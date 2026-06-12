package app;

import app.Models.AppConfig;
import app.Models.ProjectConfig;
import app.Models.RemoteConfig;
import app.Models.RuleConfig;
import app.Models.RuleRuntimeState;
import app.Models.RuleSelection;
import app.Models.SyncJob;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;
import javax.swing.JFrame;
import javax.swing.JFileChooser;
import javax.swing.UIManager;

final class AppServer implements SchedulerService.SyncOrchestrator {
    private final ConfigService configService;
    private final RuntimeStateService runtimeStateService;
    private final GitService gitService;
    private final LogService logService;
    private final DiffCacheService diffCacheService;
    private final SchedulerService schedulerService;
    private final SyncJobService syncJobService;
    private final BatchVersionComparisonService batchVersionComparisonService;
    private final Path staticDir;
    private final Map<String, ReentrantLock> repoLocks = new ConcurrentHashMap<>();
    private final ExecutorService syncExecutor = Executors.newCachedThreadPool();
    private final ExecutorService versionComparisonExecutor = Executors.newFixedThreadPool(4);
    private HttpServer server;

    AppServer(ConfigService configService, RuntimeStateService runtimeStateService, GitService gitService, LogService logService,
              DiffCacheService diffCacheService, Path staticDir) {
        this.configService = configService;
        this.runtimeStateService = runtimeStateService;
        this.gitService = gitService;
        this.logService = logService;
        this.diffCacheService = diffCacheService;
        this.staticDir = staticDir;
        this.syncJobService = new SyncJobService();
        this.batchVersionComparisonService = new BatchVersionComparisonService();
        this.schedulerService = new SchedulerService(configService, runtimeStateService, this);
    }

    void start(int port) throws IOException {
        runtimeStateService.clearAllRunningStates("Recovered after service restart");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/system", this::handleSystem);
        server.createContext("/api/remotes", this::handleRemotes);
        server.createContext("/api/projects", this::handleProjects);
        server.createContext("/api/rules", this::handleRules);
        server.createContext("/api/sync-jobs", this::handleSyncJobs);
        server.createContext("/api/version-comparison", this::handleVersionComparison);
        server.createContext("/api/schedules", this::handleSchedules);
        server.createContext("/api/logs", this::handleLogs);
        server.createContext("/", this::handleStatic);
        schedulerService.start();
        try {
            schedulerService.refreshScheduleState();
        } catch (IOException exception) {
            throw new IOException("Failed to initialize scheduler state", exception);
        }
        server.start();
    }

    void stop() {
        if (server != null) {
            server.stop(0);
        }
        schedulerService.stop();
        syncExecutor.shutdownNow();
        versionComparisonExecutor.shutdownNow();
    }

    @Override
    public void runScheduledSync(String ruleId) {
        syncExecutor.submit(() -> {
            try {
                runtimeStateService.markRunning(ruleId, true);
                sync(ruleId, false, false, "schedule");
            } catch (Exception exception) {
                exception.printStackTrace();
            }
        });
    }

    private void handleSystem(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod()) && "/api/system/config".equals(path)) {
                AppConfig config = configService.getConfig();
                HttpUtil.sendJson(exchange, 200, Map.of(
                    "localWorkspaceRoot", config.localWorkspaceRoot == null ? "" : config.localWorkspaceRoot
                ));
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && "/api/system/config/export".equals(path)) {
                HttpUtil.sendJson(exchange, 200, configService.exportConfigMap());
                return;
            }
            if ("PUT".equals(exchange.getRequestMethod()) && "/api/system/config".equals(path)) {
                Map<String, Object> body = HttpUtil.readJsonObject(exchange);
                String localWorkspaceRoot = Models.nullableString(body.get("localWorkspaceRoot"));
                configService.updateGlobalWorkspaceRoot(localWorkspaceRoot);
                diffCacheService.markAllStale("Global workspace root updated");
                schedulerService.refreshScheduleState();
                HttpUtil.sendJson(exchange, 200, Map.of(
                    "localWorkspaceRoot", localWorkspaceRoot == null ? "" : localWorkspaceRoot
                ));
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && "/api/system/config/import".equals(path)) {
                Map<String, Object> body = HttpUtil.readJsonObject(exchange);
                configService.importConfig(body);
                var ruleIds = Models.allRuleIds(configService.getConfig());
                runtimeStateService.retainRuleIds(ruleIds);
                diffCacheService.retainRuleIds(ruleIds);
                diffCacheService.markAllStale("Configuration imported");
                schedulerService.refreshScheduleState();
                AppConfig config = configService.getConfig();
                HttpUtil.sendJson(exchange, 200, Map.of(
                    "imported", true,
                    "localWorkspaceRoot", config.localWorkspaceRoot == null ? "" : config.localWorkspaceRoot,
                    "requiresWorkspaceRoot", config.localWorkspaceRoot == null || config.localWorkspaceRoot.isBlank()
                ));
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && "/api/system/select-directory".equals(path)) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("path", selectDirectory());
                HttpUtil.sendJson(exchange, 200, payload);
                return;
            }
            HttpUtil.sendJson(exchange, 404, HttpUtil.error("NOT_FOUND", "Route not found", Map.of("path", path)));
        } catch (Exception exception) {
            HttpUtil.sendJson(exchange, 400, HttpUtil.error("INVALID_REQUEST", exception.getMessage(), Map.of()));
        }
    }

    private void handleRemotes(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod()) && "/api/remotes".equals(path)) {
                List<Object> remotes = new ArrayList<>();
                for (RemoteConfig remote : configService.getConfig().remotes) {
                    remotes.add(remote.toMap());
                }
                HttpUtil.sendJson(exchange, 200, remotes);
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && "/api/remotes".equals(path)) {
                RemoteConfig remote = RemoteConfig.fromMap(HttpUtil.readJsonObject(exchange));
                configService.upsertRemote(remote);
                diffCacheService.markAllStale("Remote tab updated");
                HttpUtil.sendJson(exchange, 200, remote.toMap());
                return;
            }
            if ("PUT".equals(exchange.getRequestMethod()) && path.startsWith("/api/remotes/")) {
                String id = path.substring("/api/remotes/".length());
                Map<String, Object> body = HttpUtil.readJsonObject(exchange);
                body.put("id", id);
                RemoteConfig remote = RemoteConfig.fromMap(body);
                configService.upsertRemote(remote);
                diffCacheService.markAllStale("Remote tab updated");
                HttpUtil.sendJson(exchange, 200, remote.toMap());
                return;
            }
            if ("DELETE".equals(exchange.getRequestMethod()) && path.startsWith("/api/remotes/")) {
                String id = path.substring("/api/remotes/".length());
                configService.deleteRemote(id);
                diffCacheService.markAllStale("Remote tab deleted");
                HttpUtil.sendJson(exchange, 200, Map.of("deleted", true, "id", id));
                return;
            }
            HttpUtil.sendJson(exchange, 404, HttpUtil.error("NOT_FOUND", "Route not found", Map.of("path", path)));
        } catch (Exception exception) {
            HttpUtil.sendJson(exchange, 400, HttpUtil.error("INVALID_REQUEST", exception.getMessage(), Map.of()));
        }
    }

    private void handleProjects(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod()) && "/api/projects".equals(path)) {
                HttpUtil.sendJson(exchange, 200, projectListView());
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && "/api/projects".equals(path)) {
                ProjectConfig project = ProjectConfig.fromMap(HttpUtil.readJsonObject(exchange));
                configService.upsertProject(project);
                diffCacheService.markAllStale("Project updated");
                schedulerService.refreshScheduleState();
                HttpUtil.sendJson(exchange, 200, project.toMap());
                return;
            }

            String[] parts = path.split("/");
            if (parts.length >= 4 && "api".equals(parts[1]) && "projects".equals(parts[2])) {
                String projectId = parts[3];
                if (parts.length == 4 && "PUT".equals(exchange.getRequestMethod())) {
                    Map<String, Object> body = HttpUtil.readJsonObject(exchange);
                    body.put("id", projectId);
                    ProjectConfig project = ProjectConfig.fromMap(body);
                    configService.upsertProject(project);
                    diffCacheService.markAllStale("Project updated");
                    schedulerService.refreshScheduleState();
                    HttpUtil.sendJson(exchange, 200, project.toMap());
                    return;
                }
                if (parts.length == 4 && "DELETE".equals(exchange.getRequestMethod())) {
                    AppConfig config = configService.getConfig();
                    ProjectConfig project = Models.findProject(config, projectId);
                    for (RuleConfig rule : project.rules) {
                        runtimeStateService.delete(rule.id);
                        diffCacheService.deleteRule(rule.id);
                    }
                    configService.deleteProject(projectId);
                    schedulerService.refreshScheduleState();
                    HttpUtil.sendJson(exchange, 200, Map.of("deleted", true, "id", projectId));
                    return;
                }
                if (parts.length == 6 && "rules".equals(parts[4])) {
                    String ruleId = parts[5];
                    if ("PUT".equals(exchange.getRequestMethod())) {
                        RuleConfig existingRule = findRuleById(projectId, ruleId);
                        Map<String, Object> body = HttpUtil.readJsonObject(exchange);
                        body.put("id", ruleId);
                        RuleConfig rule = RuleConfig.fromMap(body);
                        configService.upsertRule(projectId, rule);
                        if (scheduleChanged(existingRule, rule)) {
                            runtimeStateService.clearNextRun(rule.id);
                        }
                        diffCacheService.markStale(rule.id, "Rule updated");
                        schedulerService.refreshScheduleState();
                        HttpUtil.sendJson(exchange, 200, rule.toMap());
                        return;
                    }
                    if ("DELETE".equals(exchange.getRequestMethod())) {
                        configService.deleteRule(projectId, ruleId);
                        runtimeStateService.delete(ruleId);
                        diffCacheService.deleteRule(ruleId);
                        schedulerService.refreshScheduleState();
                        HttpUtil.sendJson(exchange, 200, Map.of("deleted", true, "id", ruleId));
                        return;
                    }
                }
            }
            HttpUtil.sendJson(exchange, 404, HttpUtil.error("NOT_FOUND", "Route not found", Map.of("path", path)));
        } catch (Exception exception) {
            HttpUtil.sendJson(exchange, 400, HttpUtil.error("INVALID_REQUEST", exception.getMessage(), Map.of()));
        }
    }

    private void handleRules(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String[] parts = path.split("/");
            if (parts.length == 5 && "api".equals(parts[1]) && "rules".equals(parts[2])) {
                String ruleId = parts[3];
                RuleSelection selection = Models.findRuleSelection(configService.getConfig(), ruleId);
                if ("validate".equals(parts[4]) && "POST".equals(exchange.getRequestMethod())) {
                    HttpUtil.sendJson(exchange, 200, gitService.validate(configService.getConfig(), selection.project, selection.rule));
                    return;
                }
                if ("version-compare".equals(parts[4]) && "POST".equals(exchange.getRequestMethod())) {
                    HttpUtil.sendJson(exchange, 200, versionCompare(ruleId));
                    return;
                }
                if ("diff-cache".equals(parts[4]) && "GET".equals(exchange.getRequestMethod())) {
                    Map<String, Object> summary = diffCacheService.readSummary(ruleId);
                    if (summary == null) {
                        HttpUtil.sendJson(exchange, 404,
                            HttpUtil.error("DIFF_CACHE_NOT_FOUND", "Diff cache does not exist", Map.of("ruleId", ruleId)));
                        return;
                    }
                    HttpUtil.sendJson(exchange, 200, summary);
                    return;
                }
                if ("diff".equals(parts[4]) && "POST".equals(exchange.getRequestMethod())) {
                    HttpUtil.sendJson(exchange, 200, gitService.diff(configService.getConfig(), selection.project, selection.rule));
                    return;
                }
                if ("diff-file".equals(parts[4]) && "POST".equals(exchange.getRequestMethod())) {
                    Map<String, Object> body = HttpUtil.readJsonObject(exchange);
                    String filePath = Models.stringValue(body.get("path"));
                    String oldPath = Models.nullableString(body.get("oldPath"));
                    Map<String, Object> summary = gitService.diff(configService.getConfig(), selection.project, selection.rule);
                    HttpUtil.sendJson(exchange, 200,
                        gitService.diffFileSnapshot(
                            configService.getConfig(),
                            selection.project,
                            filePath,
                            oldPath,
                            Models.stringValue(summary.get("compareBase")),
                            Models.stringValue(summary.get("compareHead"))));
                    return;
                }
                if ("sync".equals(parts[4]) && "POST".equals(exchange.getRequestMethod())) {
                    Map<String, Object> body = HttpUtil.readJsonObject(exchange);
                    boolean forcePush = Models.booleanValue(body.getOrDefault("forcePush", Boolean.FALSE));
                    boolean reviewConfirmed = Models.booleanValue(body.getOrDefault("reviewConfirmed", Boolean.FALSE));
                    HttpUtil.sendJson(exchange, 202, enqueueManualSync(ruleId, forcePush, reviewConfirmed,
                        parseSelectedCommitIds(body.get("selectedCommitIds"))));
                    return;
                }
                if ("schedule".equals(parts[4]) && "PUT".equals(exchange.getRequestMethod())) {
                    selection.rule.schedule = Models.ScheduleConfig.fromMap(HttpUtil.readJsonObject(exchange));
                    configService.upsertRule(selection.project.id, selection.rule);
                    runtimeStateService.clearNextRun(ruleId);
                    diffCacheService.markStale(ruleId, "Rule schedule updated");
                    schedulerService.refreshScheduleState();
                    HttpUtil.sendJson(exchange, 200, selection.rule.toMap());
                    return;
                }
            }
            if (parts.length == 8 && "api".equals(parts[1]) && "rules".equals(parts[2]) && "diff".equals(parts[4])
                && "commits".equals(parts[5]) && "files".equals(parts[7]) && "GET".equals(exchange.getRequestMethod())) {
                String ruleId = parts[3];
                String commitId = parts[6];
                RuleSelection selection = Models.findRuleSelection(configService.getConfig(), ruleId);
                Map<String, Object> cached = diffCacheService.readCommitFiles(ruleId, commitId);
                if (cached == null) {
                    cached = gitService.commitFiles(configService.getConfig(), selection.project, commitId);
                    cached.put("ruleId", ruleId);
                    diffCacheService.writeCommitFiles(ruleId, commitId, cached);
                }
                HttpUtil.sendJson(exchange, 200, cached);
                return;
            }
            if (parts.length == 6 && "api".equals(parts[1]) && "rules".equals(parts[2]) && "diff-cache".equals(parts[4])) {
                String ruleId = parts[3];
                RuleSelection selection = Models.findRuleSelection(configService.getConfig(), ruleId);
                if ("refresh".equals(parts[5]) && "POST".equals(exchange.getRequestMethod())) {
                    HttpUtil.sendJson(exchange, 200, refreshDiffCache(selection));
                    return;
                }
                if ("file".equals(parts[5]) && "POST".equals(exchange.getRequestMethod())) {
                    Map<String, Object> body = HttpUtil.readJsonObject(exchange);
                    String filePath = Models.stringValue(body.get("path"));
                    String oldPath = Models.nullableString(body.get("oldPath"));
                    String cachedPatch = diffCacheService.readPatch(ruleId, filePath, oldPath);
                    boolean cacheHit = cachedPatch != null;
                    if (!cacheHit) {
                        Map<String, Object> summary = diffCacheService.readSummary(ruleId);
                        Models.require(summary != null, "Diff cache does not exist");
                        String compareBase = Models.nullableString(summary.get("compareBase"));
                        String compareHead = Models.nullableString(summary.get("compareHead"));
                        Models.require(compareBase != null && !compareBase.isBlank(), "Diff cache is missing compareBase");
                        Models.require(compareHead != null && !compareHead.isBlank(), "Diff cache is missing compareHead");
                        Map<String, Object> snapshot = diffCacheService.readSnapshot(ruleId, filePath, oldPath);
                        if (snapshot == null) {
                            snapshot = gitService.collectDiffFileSnapshot(
                                configService.getConfig(),
                                selection.project,
                                filePath,
                                oldPath,
                                compareBase,
                                compareHead);
                            diffCacheService.writeSnapshot(
                                ruleId,
                                filePath,
                                oldPath,
                                Models.booleanValue(snapshot.get("baseExists")),
                                Models.nullableString(snapshot.get("baseContent")),
                                Models.booleanValue(snapshot.get("headExists")),
                                Models.nullableString(snapshot.get("headContent")));
                        }
                        Map<String, Object> patchPayload =
                            gitService.diffFileSnapshotFromCache(filePath, oldPath, snapshot);
                        cachedPatch = Models.nullableString(patchPayload.get("patch"));
                        diffCacheService.writePatch(ruleId, filePath, oldPath, cachedPatch);
                    }
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("ruleId", ruleId);
                    payload.put("path", filePath);
                    payload.put("oldPath", oldPath);
                    payload.put("cacheHit", cacheHit);
                    payload.put("patch", cachedPatch == null ? "" : cachedPatch);
                    HttpUtil.sendJson(exchange, 200, payload);
                    return;
                }
            }
            HttpUtil.sendJson(exchange, 404, HttpUtil.error("NOT_FOUND", "Route not found", Map.of("path", path)));
        } catch (Exception exception) {
            HttpUtil.sendJson(exchange, 400, HttpUtil.error("INVALID_REQUEST", exception.getMessage(), Map.of()));
        }
    }

    private void handleSyncJobs(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (!"GET".equals(exchange.getRequestMethod()) || !path.startsWith("/api/sync-jobs/")) {
                HttpUtil.sendJson(exchange, 404, HttpUtil.error("NOT_FOUND", "Route not found", Map.of("path", path)));
                return;
            }
            String jobId = path.substring("/api/sync-jobs/".length());
            SyncJob job = syncJobService.get(jobId);
            if (job == null) {
                HttpUtil.sendJson(exchange, 404, HttpUtil.error("NOT_FOUND", "Sync job not found", Map.of("jobId", jobId)));
                return;
            }
            HttpUtil.sendJson(exchange, 200, job.toMap());
        } catch (Exception exception) {
            HttpUtil.sendJson(exchange, 400, HttpUtil.error("INVALID_REQUEST", exception.getMessage(), Map.of()));
        }
    }

    private void handleVersionComparison(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("GET".equals(method) && "/api/version-comparison/specs".equals(path)) {
                HttpUtil.sendJson(exchange, 200, versionComparisonSpecs());
                return;
            }
            if ("POST".equals(method) && "/api/version-comparison/jobs".equals(path)) {
                Map<String, Object> body = HttpUtil.readJsonObject(exchange);
                HttpUtil.sendJson(exchange, 202, enqueueBatchVersionComparison(
                    Models.stringValue(body.get("sourceBranch")),
                    Models.stringValue(body.get("targetRemoteId")),
                    Models.stringValue(body.get("targetBranch"))));
                return;
            }

            String[] parts = path.split("/");
            if (parts.length == 5 && "api".equals(parts[1]) && "version-comparison".equals(parts[2])
                && "jobs".equals(parts[3]) && "GET".equals(method)) {
                BatchVersionComparisonService.BatchJob job = batchVersionComparisonService.get(parts[4]);
                if (job == null) {
                    HttpUtil.sendJson(exchange, 404,
                        HttpUtil.error("NOT_FOUND", "Version comparison job not found", Map.of("jobId", parts[4])));
                    return;
                }
                HttpUtil.sendJson(exchange, 200, job.toMap());
                return;
            }
            if (parts.length == 7 && "api".equals(parts[1]) && "version-comparison".equals(parts[2])
                && "jobs".equals(parts[3]) && "rules".equals(parts[5]) && "POST".equals(method)) {
                BatchVersionComparisonService.BatchJob job = batchVersionComparisonService.get(parts[4]);
                if (job == null) {
                    HttpUtil.sendJson(exchange, 404,
                        HttpUtil.error("NOT_FOUND", "Version comparison job not found", Map.of("jobId", parts[4])));
                    return;
                }
                String ruleId = parts[6];
                Models.require(job.isCompleted(), "Batch comparison job is still running");
                Models.require(job.containsRule(ruleId), "Rule does not belong to this batch job");
                job.markRunning();
                versionComparisonExecutor.submit(() -> {
                    compareBatchVersionRule(job, ruleId, true);
                    job.markCompleted();
                });
                HttpUtil.sendJson(exchange, 202, job.toMap());
                return;
            }
            HttpUtil.sendJson(exchange, 404, HttpUtil.error("NOT_FOUND", "Route not found", Map.of("path", path)));
        } catch (Exception exception) {
            HttpUtil.sendJson(exchange, 400, HttpUtil.error("INVALID_REQUEST", exception.getMessage(), Map.of()));
        }
    }

    private List<Object> versionComparisonSpecs() {
        AppConfig config = configService.getConfig();
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (ProjectConfig project : config.projects) {
            if (!project.enabled) {
                continue;
            }
            for (RuleConfig rule : project.rules) {
                if (!rule.enabled || !rule.isSyncMode()) {
                    continue;
                }
                String key = rule.sourceBranch + "|" + rule.targetRemoteId + "|" + rule.targetBranch;
                Map<String, Object> item = grouped.computeIfAbsent(key, ignored -> {
                    RemoteConfig remote = Models.findRemote(config, rule.targetRemoteId);
                    Map<String, Object> spec = new LinkedHashMap<>();
                    spec.put("key", key);
                    spec.put("sourceBranch", rule.sourceBranch);
                    spec.put("targetRemoteId", rule.targetRemoteId);
                    spec.put("targetRemoteName", remote.name);
                    spec.put("targetBranch", rule.targetBranch);
                    spec.put("ruleCount", 0);
                    return spec;
                });
                item.put("ruleCount", Models.intValue(item.get("ruleCount")) + 1);
            }
        }
        return new ArrayList<>(grouped.values());
    }

    private Map<String, Object> enqueueBatchVersionComparison(String sourceBranch, String targetRemoteId,
                                                               String targetBranch) {
        AppConfig config = configService.getConfig();
        RemoteConfig remote = Models.findRemote(config, targetRemoteId);
        List<String> ruleIds = new ArrayList<>();
        for (ProjectConfig project : config.projects) {
            if (!project.enabled) {
                continue;
            }
            for (RuleConfig rule : project.rules) {
                if (rule.enabled && rule.isSyncMode()
                    && Objects.equals(sourceBranch, rule.sourceBranch)
                    && Objects.equals(targetRemoteId, rule.targetRemoteId)
                    && Objects.equals(targetBranch, rule.targetBranch)) {
                    ruleIds.add(rule.id);
                }
            }
        }
        Models.require(!ruleIds.isEmpty(), "No enabled sync rules match this comparison spec");
        BatchVersionComparisonService.BatchJob job = batchVersionComparisonService.create(
            sourceBranch, targetRemoteId, remote.name, targetBranch, ruleIds);
        syncExecutor.submit(() -> runBatchVersionComparison(job));
        return job.toMap();
    }

    private void runBatchVersionComparison(BatchVersionComparisonService.BatchJob job) {
        job.markRunning();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (String ruleId : job.ruleIds()) {
            futures.add(CompletableFuture.runAsync(
                () -> compareBatchVersionRule(job, ruleId, false), versionComparisonExecutor));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        job.markCompleted();
    }

    private void compareBatchVersionRule(BatchVersionComparisonService.BatchJob job, String ruleId, boolean replace) {
        Map<String, Object> result;
        try {
            result = versionCompare(ruleId);
        } catch (Throwable exception) {
            result = failedVersionComparison(ruleId, exception);
        }
        if (replace) {
            job.replaceResult(ruleId, result);
        } else {
            job.addResult(result);
        }
    }

    private Map<String, Object> failedVersionComparison(String ruleId, Throwable exception) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            RuleSelection selection = Models.findRuleSelection(configService.getConfig(), ruleId);
            result.put("projectId", selection.project.id);
            result.put("projectName", selection.project.name);
            result.put("ruleName", selection.rule.name);
            result.put("sourceBranch", selection.rule.sourceBranch);
            result.put("targetBranch", selection.rule.targetBranch);
        } catch (Exception ignored) {
            result.put("projectId", "");
            result.put("projectName", "");
            result.put("ruleName", ruleId);
            result.put("sourceBranch", "");
            result.put("targetBranch", "");
        }
        result.put("ruleId", ruleId);
        result.put("status", "CHECK_FAILED");
        result.put("checkedAt", Models.nowIso());
        result.put("sourceCommit", null);
        result.put("targetCommit", null);
        result.put("sourceTree", null);
        result.put("targetTree", null);
        result.put("sourceTags", List.of());
        result.put("targetTags", List.of());
        result.put("sourceTagCheckStatus", "NOT_CHECKED");
        result.put("targetTagCheckStatus", "NOT_CHECKED");
        result.put("sourceOnlyCommits", 0);
        result.put("targetOnlyCommits", 0);
        result.put("commitIdentical", false);
        result.put("contentIdentical", false);
        result.put("tagsIdentical", null);
        result.put("tagCheckMessage", null);
        result.put("message", "Version comparison failed. See log for details.");
        result.put("error", Models.firstNonBlank(exception.getMessage(), exception.getClass().getName()));
        return result;
    }

    private void handleSchedules(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                HttpUtil.sendJson(exchange, 405, HttpUtil.error("METHOD_NOT_ALLOWED", "Method not allowed", Map.of()));
                return;
            }
            List<Object> schedules = new ArrayList<>();
            AppConfig config = configService.getConfig();
            for (ProjectConfig project : config.projects) {
                for (RuleConfig rule : project.rules) {
                    RuleRuntimeState runtimeState = runtimeStateService.getOrCreate(rule.id);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("projectId", project.id);
                    item.put("projectName", project.name);
                    item.put("id", rule.id);
                    item.put("name", rule.name);
                    item.put("mode", rule.mode);
                    item.put("manualOnly", rule.manualOnly);
                    item.put("reviewRequired", rule.reviewRequired);
                    item.put("schedule", rule.schedule.toMap());
                    item.put("lastRunAt", runtimeState.lastRunAt);
                    item.put("lastStatus", runtimeState.lastStatus);
                    item.put("lastRunSource", runtimeState.lastRunSource);
                    item.put("lastMessage", runtimeState.lastMessage);
                    item.put("lastLogPath", runtimeState.lastLogPath);
                    item.put("nextRunAt", runtimeState.nextRunAt);
                    schedules.add(item);
                }
            }
            HttpUtil.sendJson(exchange, 200, schedules);
        } catch (Exception exception) {
            HttpUtil.sendJson(exchange, 400, HttpUtil.error("INVALID_REQUEST", exception.getMessage(), Map.of()));
        }
    }

    private void handleLogs(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (!"GET".equals(exchange.getRequestMethod()) || !path.startsWith("/api/logs/")) {
                HttpUtil.sendJson(exchange, 404, HttpUtil.error("NOT_FOUND", "Route not found", Map.of("path", path)));
                return;
            }
            String logId = path.substring("/api/logs/".length());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("logId", logId);
            payload.put("content", logService.readLog(logId));
            HttpUtil.sendJson(exchange, 200, payload);
        } catch (Exception exception) {
            HttpUtil.sendJson(exchange, 400, HttpUtil.error("INVALID_REQUEST", exception.getMessage(), Map.of()));
        }
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/")) {
            path = "/index.html";
        }
        Path file = staticDir.resolve(path.substring(1)).normalize();
        if (!file.startsWith(staticDir) || !Files.exists(file) || Files.isDirectory(file)) {
            HttpUtil.sendText(exchange, 404, "text/plain", "Not Found");
            return;
        }
        String contentType = file.getFileName().toString().endsWith(".js") ? "application/javascript"
            : file.getFileName().toString().endsWith(".css") ? "text/css"
            : "text/html";
        HttpUtil.sendText(exchange, 200, contentType, Files.readString(file, StandardCharsets.UTF_8));
    }

    private List<Object> projectListView() {
        List<Object> items = new ArrayList<>();
        AppConfig config = configService.getConfig();
        for (ProjectConfig project : config.projects) {
            Map<String, Object> projectItem = new LinkedHashMap<>(project.toMap());
            projectItem.put("localRepoPath", project.displayLocalRepoPath(config));
            List<Object> rules = new ArrayList<>();
            for (RuleConfig rule : project.rules) {
                RuleRuntimeState state = runtimeStateService.getOrCreate(rule.id);
                Map<String, Object> ruleItem = new LinkedHashMap<>(rule.toMap());
                ruleItem.put("localRepoPath", project.displayLocalRepoPath(config, rule));
                if (rule.isDownloadOnly()) {
                    ruleItem.put("targetRemoteName", "");
                    ruleItem.put("targetRemoteBaseUrl", "");
                    ruleItem.put("targetRemoteUrl", "");
                } else {
                    RemoteConfig remote = Models.findRemote(config, rule.targetRemoteId);
                    ruleItem.put("targetRemoteName", remote.name);
                    ruleItem.put("targetRemoteBaseUrl", remote.baseUrl);
                    ruleItem.put("targetRemoteUrl", gitService.targetRemoteUrl(config, rule));
                }
                ruleItem.put("lastRunAt", state.lastRunAt);
                ruleItem.put("lastStatus", state.lastStatus);
                ruleItem.put("lastRunSource", state.lastRunSource);
                ruleItem.put("lastMessage", state.lastMessage);
                ruleItem.put("lastLogPath", state.lastLogPath);
                ruleItem.put("nextRunAt", state.nextRunAt);
                ruleItem.put("running", state.running);
                SyncJob activeJob = syncJobService.getActiveManualJobForRule(rule.id);
                if (activeJob != null) {
                    ruleItem.put("currentJobId", activeJob.jobId);
                    ruleItem.put("currentJobStatus", activeJob.status);
                    ruleItem.put("currentJobMessage", activeJob.message);
                    ruleItem.put("currentJobQueuedAt", activeJob.queuedAt);
                    ruleItem.put("currentJobStartedAt", activeJob.startedAt);
                    ruleItem.put("currentJobTriggerSource", activeJob.triggerSource);
                }
                rules.add(ruleItem);
            }
            projectItem.put("rules", rules);
            items.add(projectItem);
        }
        return items;
    }

    private Map<String, Object> enqueueManualSync(String ruleId, boolean forcePush, boolean reviewConfirmed,
                                                  List<String> selectedCommitIds)
        throws Exception {
        AppConfig config = configService.getConfig();
        RuleSelection selection = Models.findRuleSelection(config, ruleId);
        ProjectConfig project = selection.project;
        RuleConfig rule = selection.rule;
        SyncJob job = syncJobService.createManualJob(project, rule, forcePush, reviewConfirmed, selectedCommitIds);
        runtimeStateService.markQueued(rule.id, "manual", rule.isDownloadOnly() ? "Download job queued" : "Sync job queued");
        try {
            syncExecutor.submit(() -> runManualSyncJob(job.jobId, forcePush, reviewConfirmed, selectedCommitIds));
        } catch (RuntimeException exception) {
            String message = Models.firstNonBlank(exception.getMessage(), "Failed to submit sync job");
            syncJobService.markFailed(job.jobId, message, runtimeStateService.getOrCreate(rule.id).lastLogPath);
            runtimeStateService.markFinished(rule.id, "failed", "manual", null,
                runtimeStateService.getOrCreate(rule.id).lastLogPath, message);
            throw exception;
        }
        return job.toMap();
    }

    private void runManualSyncJob(String jobId, boolean forcePush, boolean reviewConfirmed, List<String> selectedCommitIds) {
        SyncJob job = syncJobService.get(jobId);
        if (job == null) {
            return;
        }
        try {
            syncJobService.markRunning(jobId);
            Map<String, Object> result = sync(job.ruleId, forcePush, reviewConfirmed, selectedCommitIds, "manual");
            syncJobService.markSuccess(jobId, Models.nullableString(result.get("logPath")),
                Models.firstNonBlank(Models.nullableString(result.get("message")), "Sync completed"));
        } catch (Throwable exception) {
            String logPath = runtimeStateService.getOrCreate(job.ruleId).lastLogPath;
            String message = Models.firstNonBlank(exception.getMessage(), exception.getClass().getName());
            syncJobService.markFailed(jobId, message, logPath);
            try {
                runtimeStateService.markFinished(job.ruleId, "failed", "manual", null, logPath, message);
            } catch (IOException ioException) {
                ioException.printStackTrace();
            }
            exception.printStackTrace();
        }
    }

    private Map<String, Object> sync(String ruleId, boolean forcePush, boolean reviewConfirmed, String triggerSource)
        throws Exception {
        return sync(ruleId, forcePush, reviewConfirmed, List.of(), triggerSource);
    }

    private Map<String, Object> sync(String ruleId, boolean forcePush, boolean reviewConfirmed, List<String> selectedCommitIds,
                                     String triggerSource)
        throws Exception {
        AppConfig config = configService.getConfig();
        RuleSelection selection = Models.findRuleSelection(config, ruleId);
        ProjectConfig project = selection.project;
        RuleConfig rule = selection.rule;
        ReentrantLock lock = repoLocks.computeIfAbsent(project.localRepoPath(config, rule).toAbsolutePath().normalize().toString(),
            ignored -> new ReentrantLock());
        lock.lock();
        try {
            runtimeStateService.markRunning(rule.id, true);
            String runId = logService.createRunId(rule.id);
            try {
                GitService.SyncResult result = gitService.sync(config, project, rule, forcePush, reviewConfirmed,
                    selectedCommitIds, triggerSource);
                String logPath = logService.writeLog(runId, result.asLogText(project.id, rule.id, forcePush, reviewConfirmed,
                    triggerSource));
                String nextRun = rule.manualOnly || !rule.schedule.enabled ? null
                    : java.time.OffsetDateTime.now(java.time.ZoneOffset.ofHours(8))
                        .plusMinutes(rule.schedule.intervalMinutes).toString();
                String message = rule.isDownloadOnly() ? "Download completed" : "Sync completed";
                runtimeStateService.markFinished(rule.id, "success", triggerSource, nextRun, logPath, message);
                diffCacheService.markStale(rule.id, message);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("runId", runId);
                payload.put("projectId", project.id);
                payload.put("projectName", project.name);
                payload.put("ruleId", rule.id);
                payload.put("ruleName", rule.name);
                payload.put("mode", rule.mode);
                payload.put("status", "success");
                payload.put("sourceBranch", rule.sourceBranch);
                payload.put("localRepoPath", project.displayLocalRepoPath(config, rule));
                payload.put("targetRemoteId", rule.targetRemoteId);
                payload.put("targetRepoName", rule.targetRepoName);
                payload.put("targetBranch", rule.targetBranch);
                payload.put("targetRemoteUrl", rule.isDownloadOnly() ? "" : gitService.targetRemoteUrl(config, rule));
                payload.put("forcePush", forcePush);
                payload.put("reviewConfirmed", reviewConfirmed);
                payload.put("selectedCommitIds", selectedCommitIds);
                payload.put("message", message);
                payload.put("logPath", logPath);
                return payload;
            } catch (Exception exception) {
                String logPath = logService.writeLog(runId, "ERROR\n" + exception.getMessage());
                runtimeStateService.markFinished(rule.id, "failed", triggerSource, null, logPath, exception.getMessage());
                throw exception;
            }
        } finally {
            lock.unlock();
        }
    }

    private Map<String, Object> versionCompare(String ruleId) throws Exception {
        AppConfig config = configService.getConfig();
        RuleSelection selection = Models.findRuleSelection(config, ruleId);
        ProjectConfig project = selection.project;
        RuleConfig rule = selection.rule;
        Models.require(rule.isSyncMode(), "Version comparison is only available for sync rules");

        ReentrantLock lock = repoLocks.computeIfAbsent(project.localRepoPath(config, rule).toAbsolutePath().normalize().toString(),
            ignored -> new ReentrantLock());
        lock.lock();
        String runId = logService.createRunId("version-compare-" + rule.id);
        try {
            Map<String, Object> result = gitService.versionCompare(config, project, rule);
            String logPath = logService.writeLog(runId, "VERSION COMPARISON\n" + Json.stringify(result));
            result.put("logPath", logPath);
            return result;
        } catch (Exception exception) {
            String message = Models.firstNonBlank(exception.getMessage(), exception.getClass().getName());
            String logPath = logService.writeLog(runId, "VERSION COMPARISON ERROR\n" + message);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("projectId", project.id);
            result.put("projectName", project.name);
            result.put("ruleId", rule.id);
            result.put("ruleName", rule.name);
            result.put("status", "CHECK_FAILED");
            result.put("checkedAt", Models.nowIso());
            result.put("sourceBranch", rule.sourceBranch);
            result.put("targetBranch", rule.targetBranch);
            result.put("sourceCommit", null);
            result.put("targetCommit", null);
            result.put("sourceTree", null);
            result.put("targetTree", null);
            result.put("sourceTags", List.of());
            result.put("targetTags", List.of());
            result.put("sourceTagCheckStatus", "NOT_CHECKED");
            result.put("targetTagCheckStatus", "NOT_CHECKED");
            result.put("sourceOnlyCommits", 0);
            result.put("targetOnlyCommits", 0);
            result.put("commitIdentical", false);
            result.put("contentIdentical", false);
            result.put("tagsIdentical", null);
            result.put("tagCheckMessage", null);
            result.put("message", "Version comparison failed. See log for details.");
            result.put("logPath", logPath);
            return result;
        } finally {
            lock.unlock();
        }
    }

    private String selectDirectory() throws Exception {
        Models.require(!GraphicsEnvironment.isHeadless(), "Directory chooser is not available in headless mode");
        if (isMacOs()) {
            String selected = selectDirectoryWithNativeDialog();
            if (selected != null && !selected.isBlank()) {
                return selected;
            }
        }
        return selectDirectoryWithSwingChooser();
    }

    private String selectDirectoryWithSwingChooser() throws Exception {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        final String[] selected = new String[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("Select Local Workspace Root");
            frame.setAlwaysOnTop(true);
            frame.setUndecorated(true);
            frame.setType(java.awt.Window.Type.UTILITY);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            frame.toFront();
            frame.requestFocus();
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Select Local Workspace Root");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setAcceptAllFileFilterUsed(false);
            try {
                int result = chooser.showOpenDialog(frame);
                if (result == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
                    selected[0] = chooser.getSelectedFile().getAbsolutePath();
                }
            } finally {
                frame.dispose();
            }
        });
        Models.require(selected[0] != null && !selected[0].isBlank(), "No directory selected");
        return selected[0];
    }

    private String selectDirectoryWithNativeDialog() throws Exception {
        final String[] selected = new String[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            Frame frame = new Frame("Select Local Workspace Root");
            frame.setAlwaysOnTop(true);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            try {
                System.setProperty("apple.awt.fileDialogForDirectories", "true");
                FileDialog dialog = new FileDialog(frame, "Select Local Workspace Root", FileDialog.LOAD);
                dialog.setAlwaysOnTop(true);
                dialog.setLocationRelativeTo(frame);
                dialog.setVisible(true);
                if (dialog.getDirectory() != null && dialog.getFile() != null) {
                    selected[0] = Path.of(dialog.getDirectory(), dialog.getFile()).toAbsolutePath().toString();
                }
            } finally {
                frame.dispose();
            }
        });
        return selected[0];
    }

    private boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    private Map<String, Object> refreshDiffCache(RuleSelection selection) throws Exception {
        try {
            Map<String, Object> summary = gitService.diff(configService.getConfig(), selection.project, selection.rule);
            return diffCacheService.writeSummary(selection.rule.id, summary, "Manual refresh completed");
        } catch (Exception exception) {
            diffCacheService.markFailed(selection.rule.id, exception.getMessage());
            throw exception;
        }
    }

    private List<String> parseSelectedCommitIds(Object raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> commitIds = new ArrayList<>();
        for (Object item : Json.asList(raw)) {
            commitIds.add(Models.stringValue(item));
        }
        return commitIds;
    }

    private RuleConfig findRuleById(String projectId, String ruleId) {
        ProjectConfig project = Models.findProject(configService.getConfig(), projectId);
        for (RuleConfig rule : project.rules) {
            if (ruleId.equals(rule.id)) {
                return rule;
            }
        }
        return null;
    }

    private boolean scheduleChanged(RuleConfig existingRule, RuleConfig updatedRule) {
        if (existingRule == null) {
            return false;
        }
        if (existingRule.manualOnly != updatedRule.manualOnly) {
            return true;
        }
        if (existingRule.schedule.enabled != updatedRule.schedule.enabled) {
            return true;
        }
        if (!Objects.equals(existingRule.schedule.type, updatedRule.schedule.type)) {
            return true;
        }
        return existingRule.schedule.intervalMinutes != updatedRule.schedule.intervalMinutes;
    }
}

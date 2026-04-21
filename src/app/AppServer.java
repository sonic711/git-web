package app;

import app.Models.AppConfig;
import app.Models.ProjectConfig;
import app.Models.RemoteConfig;
import app.Models.RuleConfig;
import app.Models.RuleRuntimeState;
import app.Models.RuleSelection;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.ReentrantLock;
import javax.swing.JFileChooser;
import javax.swing.UIManager;

final class AppServer implements SchedulerService.SyncOrchestrator {
    private final ConfigService configService;
    private final RuntimeStateService runtimeStateService;
    private final GitService gitService;
    private final LogService logService;
    private final SchedulerService schedulerService;
    private final Path staticDir;
    private final Map<String, ReentrantLock> repoLocks = new ConcurrentHashMap<>();
    private final ExecutorService scheduledSyncExecutor = Executors.newCachedThreadPool();
    private HttpServer server;

    AppServer(ConfigService configService, RuntimeStateService runtimeStateService, GitService gitService, LogService logService,
              Path staticDir) {
        this.configService = configService;
        this.runtimeStateService = runtimeStateService;
        this.gitService = gitService;
        this.logService = logService;
        this.staticDir = staticDir;
        this.schedulerService = new SchedulerService(configService, runtimeStateService, this);
    }

    void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/system", this::handleSystem);
        server.createContext("/api/remotes", this::handleRemotes);
        server.createContext("/api/projects", this::handleProjects);
        server.createContext("/api/rules", this::handleRules);
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
        scheduledSyncExecutor.shutdownNow();
    }

    @Override
    public void runScheduledSync(String ruleId) {
        scheduledSyncExecutor.submit(() -> {
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
            if ("PUT".equals(exchange.getRequestMethod()) && "/api/system/config".equals(path)) {
                Map<String, Object> body = HttpUtil.readJsonObject(exchange);
                String localWorkspaceRoot = Models.nullableString(body.get("localWorkspaceRoot"));
                configService.updateGlobalWorkspaceRoot(localWorkspaceRoot);
                schedulerService.refreshScheduleState();
                HttpUtil.sendJson(exchange, 200, Map.of(
                    "localWorkspaceRoot", localWorkspaceRoot == null ? "" : localWorkspaceRoot
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
                HttpUtil.sendJson(exchange, 200, remote.toMap());
                return;
            }
            if ("PUT".equals(exchange.getRequestMethod()) && path.startsWith("/api/remotes/")) {
                String id = path.substring("/api/remotes/".length());
                Map<String, Object> body = HttpUtil.readJsonObject(exchange);
                body.put("id", id);
                RemoteConfig remote = RemoteConfig.fromMap(body);
                configService.upsertRemote(remote);
                HttpUtil.sendJson(exchange, 200, remote.toMap());
                return;
            }
            if ("DELETE".equals(exchange.getRequestMethod()) && path.startsWith("/api/remotes/")) {
                String id = path.substring("/api/remotes/".length());
                configService.deleteRemote(id);
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
                    schedulerService.refreshScheduleState();
                    HttpUtil.sendJson(exchange, 200, project.toMap());
                    return;
                }
                if (parts.length == 4 && "DELETE".equals(exchange.getRequestMethod())) {
                    AppConfig config = configService.getConfig();
                    ProjectConfig project = Models.findProject(config, projectId);
                    for (RuleConfig rule : project.rules) {
                        runtimeStateService.delete(rule.id);
                    }
                    configService.deleteProject(projectId);
                    schedulerService.refreshScheduleState();
                    HttpUtil.sendJson(exchange, 200, Map.of("deleted", true, "id", projectId));
                    return;
                }
                if (parts.length == 6 && "rules".equals(parts[4])) {
                    String ruleId = parts[5];
                    if ("PUT".equals(exchange.getRequestMethod())) {
                        Map<String, Object> body = HttpUtil.readJsonObject(exchange);
                        body.put("id", ruleId);
                        RuleConfig rule = RuleConfig.fromMap(body);
                        configService.upsertRule(projectId, rule);
                        schedulerService.refreshScheduleState();
                        HttpUtil.sendJson(exchange, 200, rule.toMap());
                        return;
                    }
                    if ("DELETE".equals(exchange.getRequestMethod())) {
                        configService.deleteRule(projectId, ruleId);
                        runtimeStateService.delete(ruleId);
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
                if ("diff".equals(parts[4]) && "POST".equals(exchange.getRequestMethod())) {
                    HttpUtil.sendJson(exchange, 200, gitService.diff(configService.getConfig(), selection.project, selection.rule));
                    return;
                }
                if ("diff-file".equals(parts[4]) && "POST".equals(exchange.getRequestMethod())) {
                    Map<String, Object> body = HttpUtil.readJsonObject(exchange);
                    String filePath = Models.stringValue(body.get("path"));
                    String oldPath = Models.nullableString(body.get("oldPath"));
                    HttpUtil.sendJson(exchange, 200,
                        gitService.diffFile(configService.getConfig(), selection.project, selection.rule, filePath, oldPath));
                    return;
                }
                if ("sync".equals(parts[4]) && "POST".equals(exchange.getRequestMethod())) {
                    Map<String, Object> body = HttpUtil.readJsonObject(exchange);
                    boolean forcePush = Models.booleanValue(body.getOrDefault("forcePush", Boolean.FALSE));
                    boolean reviewConfirmed = Models.booleanValue(body.getOrDefault("reviewConfirmed", Boolean.FALSE));
                    HttpUtil.sendJson(exchange, 200, sync(ruleId, forcePush, reviewConfirmed, "manual"));
                    return;
                }
                if ("schedule".equals(parts[4]) && "PUT".equals(exchange.getRequestMethod())) {
                    selection.rule.schedule = Models.ScheduleConfig.fromMap(HttpUtil.readJsonObject(exchange));
                    configService.upsertRule(selection.project.id, selection.rule);
                    schedulerService.refreshScheduleState();
                    HttpUtil.sendJson(exchange, 200, selection.rule.toMap());
                    return;
                }
            }
            HttpUtil.sendJson(exchange, 404, HttpUtil.error("NOT_FOUND", "Route not found", Map.of("path", path)));
        } catch (Exception exception) {
            HttpUtil.sendJson(exchange, 400, HttpUtil.error("INVALID_REQUEST", exception.getMessage(), Map.of()));
        }
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
            String runId = path.substring("/api/logs/".length());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("runId", runId);
            payload.put("content", logService.readLog(runId));
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
                RemoteConfig remote = Models.findRemote(config, rule.targetRemoteId);
                ruleItem.put("targetRemoteName", remote.name);
                ruleItem.put("targetRemoteBaseUrl", remote.baseUrl);
                ruleItem.put("targetRemoteUrl", gitService.targetRemoteUrl(config, rule));
                ruleItem.put("lastRunAt", state.lastRunAt);
                ruleItem.put("lastStatus", state.lastStatus);
                ruleItem.put("lastRunSource", state.lastRunSource);
                ruleItem.put("lastMessage", state.lastMessage);
                ruleItem.put("lastLogPath", state.lastLogPath);
                ruleItem.put("nextRunAt", state.nextRunAt);
                ruleItem.put("running", state.running);
                rules.add(ruleItem);
            }
            projectItem.put("rules", rules);
            items.add(projectItem);
        }
        return items;
    }

    private Map<String, Object> sync(String ruleId, boolean forcePush, boolean reviewConfirmed, String triggerSource)
        throws Exception {
        AppConfig config = configService.getConfig();
        RuleSelection selection = Models.findRuleSelection(config, ruleId);
        ProjectConfig project = selection.project;
        RuleConfig rule = selection.rule;
        ReentrantLock lock = repoLocks.computeIfAbsent(project.localRepoPath(config).toAbsolutePath().normalize().toString(),
            ignored -> new ReentrantLock());
        lock.lock();
        try {
            runtimeStateService.markRunning(rule.id, true);
            String runId = logService.createRunId(rule.id);
            try {
                GitService.SyncResult result = gitService.sync(config, project, rule, forcePush, reviewConfirmed, triggerSource);
                String logPath = logService.writeLog(runId, result.asLogText(project.id, rule.id, forcePush, reviewConfirmed,
                    triggerSource));
                String nextRun = rule.manualOnly || !rule.schedule.enabled ? null
                    : java.time.OffsetDateTime.now(java.time.ZoneOffset.ofHours(8))
                        .plusMinutes(rule.schedule.intervalMinutes).toString();
                runtimeStateService.markFinished(rule.id, "success", triggerSource, nextRun, logPath, "Sync completed");
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("runId", runId);
                payload.put("projectId", project.id);
                payload.put("projectName", project.name);
                payload.put("ruleId", rule.id);
                payload.put("ruleName", rule.name);
                payload.put("status", "success");
                payload.put("sourceBranch", rule.sourceBranch);
                payload.put("localRepoPath", project.displayLocalRepoPath(config));
                payload.put("targetRemoteId", rule.targetRemoteId);
                payload.put("targetRepoName", rule.targetRepoName);
                payload.put("targetBranch", rule.targetBranch);
                payload.put("targetRemoteUrl", gitService.targetRemoteUrl(config, rule));
                payload.put("forcePush", forcePush);
                payload.put("reviewConfirmed", reviewConfirmed);
                payload.put("message", "Sync completed");
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

    private String selectDirectory() throws Exception {
        Models.require(!GraphicsEnvironment.isHeadless(), "Directory chooser is not available in headless mode");
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        final String[] selected = new String[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Select Local Workspace Root");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setAcceptAllFileFilterUsed(false);
            int result = chooser.showOpenDialog(null);
            if (result == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
                selected[0] = chooser.getSelectedFile().getAbsolutePath();
            }
        });
        Models.require(selected[0] != null && !selected[0].isBlank(), "No directory selected");
        return selected[0];
    }
}

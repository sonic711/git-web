package app;

import app.Models.AppConfig;
import app.Models.MappingConfig;
import app.Models.MappingRuntimeState;
import app.Models.RemoteConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
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
        server.createContext("/api/mappings", this::handleMappings);
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
    }

    @Override
    public void runScheduledSync(String mappingId) {
        try {
            sync(mappingId, false, false, "schedule");
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    private void handleSystem(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
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

    private void handleMappings(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod()) && "/api/mappings".equals(path)) {
                HttpUtil.sendJson(exchange, 200, mappingListView());
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && "/api/mappings".equals(path)) {
                MappingConfig mapping = MappingConfig.fromMap(HttpUtil.readJsonObject(exchange));
                configService.upsertMapping(mapping);
                schedulerService.refreshScheduleState();
                HttpUtil.sendJson(exchange, 200, mapping.toMap());
                return;
            }

            String[] parts = path.split("/");
            if (parts.length >= 4 && "api".equals(parts[1]) && "mappings".equals(parts[2])) {
                String mappingId = parts[3];
                if (parts.length == 4 && "PUT".equals(exchange.getRequestMethod())) {
                    Map<String, Object> body = HttpUtil.readJsonObject(exchange);
                    body.put("id", mappingId);
                    MappingConfig mapping = MappingConfig.fromMap(body);
                    configService.upsertMapping(mapping);
                    schedulerService.refreshScheduleState();
                    HttpUtil.sendJson(exchange, 200, mapping.toMap());
                    return;
                }
                if (parts.length == 4 && "DELETE".equals(exchange.getRequestMethod())) {
                    configService.deleteMapping(mappingId);
                    runtimeStateService.delete(mappingId);
                    schedulerService.refreshScheduleState();
                    HttpUtil.sendJson(exchange, 200, Map.of("deleted", true, "id", mappingId));
                    return;
                }
                if (parts.length == 5 && "validate".equals(parts[4]) && "POST".equals(exchange.getRequestMethod())) {
                    MappingConfig mapping = Models.findMapping(configService.getConfig(), mappingId);
                    HttpUtil.sendJson(exchange, 200, gitService.validate(configService.getConfig(), mapping));
                    return;
                }
                if (parts.length == 5 && "diff".equals(parts[4]) && "POST".equals(exchange.getRequestMethod())) {
                    MappingConfig mapping = Models.findMapping(configService.getConfig(), mappingId);
                    HttpUtil.sendJson(exchange, 200, gitService.diff(configService.getConfig(), mapping));
                    return;
                }
                if (parts.length == 5 && "sync".equals(parts[4]) && "POST".equals(exchange.getRequestMethod())) {
                    Map<String, Object> body = HttpUtil.readJsonObject(exchange);
                    boolean forcePush = Models.booleanValue(body.getOrDefault("forcePush", Boolean.FALSE));
                    boolean reviewConfirmed = Models.booleanValue(body.getOrDefault("reviewConfirmed", Boolean.FALSE));
                    HttpUtil.sendJson(exchange, 200, sync(mappingId, forcePush, reviewConfirmed, "manual"));
                    return;
                }
                if (parts.length == 5 && "schedule".equals(parts[4]) && "PUT".equals(exchange.getRequestMethod())) {
                    Map<String, Object> body = HttpUtil.readJsonObject(exchange);
                    MappingConfig mapping = Models.findMapping(configService.getConfig(), mappingId);
                    mapping.schedule = Models.ScheduleConfig.fromMap(body);
                    configService.upsertMapping(mapping);
                    schedulerService.refreshScheduleState();
                    HttpUtil.sendJson(exchange, 200, mapping.toMap());
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
            for (MappingConfig mapping : config.mappings) {
                MappingRuntimeState runtimeState = runtimeStateService.getOrCreate(mapping.id);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", mapping.id);
                item.put("name", mapping.name);
                item.put("manualOnly", mapping.manualOnly);
                item.put("reviewRequired", mapping.reviewRequired);
                item.put("schedule", mapping.schedule.toMap());
                item.put("lastRunAt", runtimeState.lastRunAt);
                item.put("lastStatus", runtimeState.lastStatus);
                item.put("lastRunSource", runtimeState.lastRunSource);
                item.put("lastMessage", runtimeState.lastMessage);
                item.put("lastLogPath", runtimeState.lastLogPath);
                item.put("nextRunAt", runtimeState.nextRunAt);
                schedules.add(item);
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

    private List<Object> mappingListView() {
        List<Object> items = new ArrayList<>();
        AppConfig config = configService.getConfig();
        for (MappingConfig mapping : config.mappings) {
            MappingRuntimeState state = runtimeStateService.getOrCreate(mapping.id);
            Map<String, Object> item = new LinkedHashMap<>(mapping.toMap());
            RemoteConfig remote = Models.findRemote(config, mapping.targetRemoteId);
            item.put("localRepoPath", mapping.displayLocalRepoPath());
            item.put("targetRemoteName", remote.name);
            item.put("targetRemoteBaseUrl", remote.baseUrl);
            item.put("targetRemoteUrl", gitService.targetRemoteUrl(config, mapping));
            item.put("lastRunAt", state.lastRunAt);
            item.put("lastStatus", state.lastStatus);
            item.put("lastRunSource", state.lastRunSource);
            item.put("lastMessage", state.lastMessage);
            item.put("lastLogPath", state.lastLogPath);
            item.put("nextRunAt", state.nextRunAt);
            item.put("running", state.running);
            items.add(item);
        }
        return items;
    }

    private Map<String, Object> sync(String mappingId, boolean forcePush, boolean reviewConfirmed, String triggerSource)
        throws Exception {
        AppConfig config = configService.getConfig();
        MappingConfig mapping = Models.findMapping(config, mappingId);
        ReentrantLock lock = repoLocks.computeIfAbsent(mapping.localRepoPath().toAbsolutePath().normalize().toString(),
            ignored -> new ReentrantLock());
        lock.lock();
        try {
            runtimeStateService.markRunning(mappingId, true);
            String runId = logService.createRunId(mappingId);
            try {
                GitService.SyncResult result = gitService.sync(config, mapping, forcePush, reviewConfirmed, triggerSource);
                String logPath = logService.writeLog(runId, result.asLogText(mappingId, forcePush, reviewConfirmed, triggerSource));
                String nextRun = mapping.manualOnly || !mapping.schedule.enabled ? null
                    : java.time.OffsetDateTime.now(java.time.ZoneOffset.ofHours(8))
                        .plusMinutes(mapping.schedule.intervalMinutes).toString();
                runtimeStateService.markFinished(mappingId, "success", triggerSource, nextRun, logPath, "Sync completed");
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("runId", runId);
                payload.put("mappingId", mappingId);
                payload.put("status", "success");
                payload.put("sourceBranch", mapping.sourceBranch);
                payload.put("localRepoPath", mapping.displayLocalRepoPath());
                payload.put("targetRemoteId", mapping.targetRemoteId);
                payload.put("targetRepoName", mapping.targetRepoName);
                payload.put("targetBranch", mapping.targetBranch);
                payload.put("targetRemoteUrl", gitService.targetRemoteUrl(config, mapping));
                payload.put("forcePush", forcePush);
                payload.put("reviewConfirmed", reviewConfirmed);
                payload.put("message", "Sync completed");
                payload.put("logPath", logPath);
                return payload;
            } catch (Exception exception) {
                String logPath = logService.writeLog(runId, "ERROR\n" + exception.getMessage());
                runtimeStateService.markFinished(mappingId, "failed", triggerSource, null, logPath, exception.getMessage());
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

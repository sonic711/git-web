package app;

import app.Models.AppConfig;
import app.Models.ProjectConfig;
import app.Models.RuleConfig;
import app.Models.RuleRuntimeState;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class SchedulerService {
    private final ConfigService configService;
    private final RuntimeStateService runtimeStateService;
    private final SyncOrchestrator syncOrchestrator;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    SchedulerService(ConfigService configService, RuntimeStateService runtimeStateService, SyncOrchestrator syncOrchestrator) {
        this.configService = configService;
        this.runtimeStateService = runtimeStateService;
        this.syncOrchestrator = syncOrchestrator;
    }

    void start() {
        executor.scheduleAtFixedRate(this::tick, 5, 30, TimeUnit.SECONDS);
    }

    void stop() {
        executor.shutdownNow();
    }

    void refreshScheduleState() throws IOException {
            AppConfig config = configService.getConfig();
            for (ProjectConfig project : config.projects) {
                for (RuleConfig rule : project.rules) {
                    RuleRuntimeState state = runtimeStateService.getOrCreate(rule.id);
                    String nextRun = computeNextRun(config, project, rule, state);
                    runtimeStateService.setNextRun(rule.id, nextRun);
                }
            }
    }

    private void tick() {
        try {
            AppConfig config = configService.getConfig();
            for (ProjectConfig project : config.projects) {
                if (!project.enabled) {
                    continue;
                }
                for (RuleConfig rule : project.rules) {
                    if (!project.hasEffectiveWorkspaceRoot(config, rule)) {
                        continue;
                    }
                    if (!rule.enabled || rule.manualOnly || !rule.schedule.enabled) {
                        continue;
                    }
                    RuleRuntimeState state = runtimeStateService.getOrCreate(rule.id);
                    if (state.running) {
                        continue;
                    }
                    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.ofHours(8));
                    OffsetDateTime nextRun = parseTime(state.nextRunAt);
                    if (nextRun == null || !now.isBefore(nextRun)) {
                        syncOrchestrator.runScheduledSync(rule.id);
                    }
                }
            }
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    private String computeNextRun(AppConfig config, ProjectConfig project, RuleConfig rule, RuleRuntimeState state) {
        if (rule.manualOnly || !rule.schedule.enabled || !project.hasEffectiveWorkspaceRoot(config, rule)) {
            return null;
        }
        OffsetDateTime existingNextRun = parseTime(state.nextRunAt);
        if (existingNextRun != null) {
            return existingNextRun.toString();
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.ofHours(8));
        if (state.lastRunAt == null || state.lastRunAt.isBlank()) {
            return now.toString();
        }
        OffsetDateTime lastRun = parseTime(state.lastRunAt);
        if (lastRun == null) {
            return now.toString();
        }
        return lastRun.plusMinutes(rule.schedule.intervalMinutes).toString();
    }

    private OffsetDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return OffsetDateTime.parse(value);
    }

    interface SyncOrchestrator {
        void runScheduledSync(String mappingId);
    }
}

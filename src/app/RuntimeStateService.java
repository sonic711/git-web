package app;

import app.Models.RuleRuntimeState;
import app.Models.RuntimeState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

final class RuntimeStateService {
    private final Path statePath;
    private RuntimeState current;

    RuntimeStateService(Path statePath) throws IOException {
        this.statePath = statePath;
        ensureDefaultFile();
        this.current = load();
    }

    synchronized RuntimeState getState() {
        return current;
    }

    synchronized RuleRuntimeState getOrCreate(String mappingId) {
        return current.mappingStates.computeIfAbsent(mappingId, ignored -> new RuleRuntimeState());
    }

    synchronized void save() throws IOException {
        Files.writeString(statePath, Json.stringify(current.toMap()), StandardCharsets.UTF_8);
    }

    synchronized void markRunning(String mappingId, boolean running) throws IOException {
        RuleRuntimeState state = getOrCreate(mappingId);
        state.running = running;
        if (running) {
            state.lastStatus = "running";
            state.lastMessage = "Running";
        }
        save();
    }

    synchronized void markQueued(String mappingId, String triggerSource, String lastMessage) throws IOException {
        RuleRuntimeState state = getOrCreate(mappingId);
        state.running = true;
        state.lastStatus = "queued";
        state.lastRunSource = triggerSource;
        state.lastMessage = lastMessage;
        save();
    }

    synchronized void markFinished(String mappingId, String status, String triggerSource, String nextRunAt, String logPath,
                                   String lastMessage)
        throws IOException {
        RuleRuntimeState state = getOrCreate(mappingId);
        state.running = false;
        state.lastStatus = status;
        state.lastRunAt = Models.nowIso();
        state.lastRunSource = triggerSource;
        state.nextRunAt = nextRunAt;
        state.lastLogPath = logPath;
        state.lastMessage = lastMessage;
        save();
    }

    synchronized void setNextRun(String mappingId, String nextRunAt) throws IOException {
        RuleRuntimeState state = getOrCreate(mappingId);
        state.nextRunAt = nextRunAt;
        save();
    }

    synchronized void clearNextRun(String mappingId) throws IOException {
        RuleRuntimeState state = getOrCreate(mappingId);
        state.nextRunAt = null;
        save();
    }

    synchronized void recoverInterruptedRun(String mappingId, String lastMessage) throws IOException {
        RuleRuntimeState state = getOrCreate(mappingId);
        if (!state.running) {
            return;
        }
        state.running = false;
        state.lastStatus = "interrupted";
        state.lastMessage = lastMessage;
        save();
    }

    synchronized void clearAllRunningStates(String lastMessage) throws IOException {
        boolean changed = false;
        for (RuleRuntimeState state : current.mappingStates.values()) {
            if (!state.running && !"queued".equals(state.lastStatus)) {
                continue;
            }
            state.running = false;
            state.lastStatus = "interrupted";
            state.lastMessage = lastMessage;
            changed = true;
        }
        if (changed) {
            save();
        }
    }

    synchronized void delete(String mappingId) throws IOException {
        current.mappingStates.remove(mappingId);
        save();
    }

    synchronized void retainRuleIds(Set<String> ruleIds) throws IOException {
        if (current.mappingStates.keySet().removeIf(ruleId -> !ruleIds.contains(ruleId))) {
            save();
        }
    }

    private RuntimeState load() throws IOException {
        String content = Files.readString(statePath, StandardCharsets.UTF_8);
        return RuntimeState.fromMap(Json.asObject(Json.parse(content)));
    }

    private void ensureDefaultFile() throws IOException {
        Files.createDirectories(statePath.getParent());
        if (!Files.exists(statePath)) {
            RuntimeState state = new RuntimeState();
            Files.writeString(statePath, Json.stringify(state.toMap()), StandardCharsets.UTF_8);
        }
    }
}

package app;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class Models {
    private Models() {
    }

    static String nowIso() {
        return OffsetDateTime.now(ZoneOffset.ofHours(8)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    static final class AppConfig {
        int version = 1;
        String updatedAt = nowIso();
        String localWorkspaceRoot;
        List<RemoteConfig> remotes = new ArrayList<>();
        List<ProjectConfig> projects = new ArrayList<>();

        static AppConfig fromMap(Map<String, Object> map) {
            AppConfig config = new AppConfig();
            config.version = intValue(map.getOrDefault("version", 1));
            config.updatedAt = stringValue(map.getOrDefault("updatedAt", nowIso()));
            config.localWorkspaceRoot = nullableString(map.get("localWorkspaceRoot"));
            for (Object item : Json.asList(map.getOrDefault("remotes", List.of()))) {
                config.remotes.add(RemoteConfig.fromMap(Json.asObject(item)));
            }
            for (Object item : Json.asList(map.getOrDefault("projects", List.of()))) {
                config.projects.add(ProjectConfig.fromMap(Json.asObject(item)));
            }
            for (Object item : Json.asList(map.getOrDefault("mappings", List.of()))) {
                config.projects.add(ProjectConfig.fromLegacyMappingMap(Json.asObject(item)));
            }
            return config;
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("version", version);
            map.put("updatedAt", updatedAt);
            map.put("localWorkspaceRoot", localWorkspaceRoot);
            List<Object> remoteList = new ArrayList<>();
            for (RemoteConfig remote : remotes) {
                remoteList.add(remote.toMap());
            }
            map.put("remotes", remoteList);
            List<Object> projectList = new ArrayList<>();
            for (ProjectConfig project : projects) {
                projectList.add(project.toMap());
            }
            map.put("projects", projectList);
            return map;
        }
    }

    static final class RemoteConfig {
        String id;
        String name;
        String baseUrl;
        boolean enabled;
        String legacyUrl;

        static RemoteConfig fromMap(Map<String, Object> map) {
            RemoteConfig config = new RemoteConfig();
            config.id = stringValue(map.get("id"));
            String legacyGroup = nullableString(map.get("group"));
            String configuredName = nullableString(map.get("name"));
            config.name = firstNonBlank(configuredName, legacyGroup, config.id);
            config.legacyUrl = nullableString(map.get("url"));
            config.baseUrl = firstNonBlank(nullableString(map.get("baseUrl")), deriveBaseUrl(config.legacyUrl));
            config.enabled = booleanValue(map.getOrDefault("enabled", Boolean.TRUE));
            return config;
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("name", name);
            map.put("baseUrl", baseUrl);
            map.put("enabled", enabled);
            return map;
        }
    }

    static final class ProjectConfig {
        String id;
        String name;
        String vendorRepoUrl;
        String localWorkspaceRoot;
        String localProjectName;
        String legacyLocalRepoPath;
        boolean enabled = true;
        List<RuleConfig> rules = new ArrayList<>();

        static ProjectConfig fromMap(Map<String, Object> map) {
            ProjectConfig config = new ProjectConfig();
            config.id = stringValue(map.get("id"));
            config.name = stringValue(map.get("name"));
            config.vendorRepoUrl = stringValue(map.get("vendorRepoUrl"));
            config.legacyLocalRepoPath = nullableString(map.get("localRepoPath"));
            config.localWorkspaceRoot = nullableString(map.get("localWorkspaceRoot"));
            config.localProjectName = nullableString(map.get("localProjectName"));
            config.enabled = booleanValue(map.getOrDefault("enabled", Boolean.TRUE));
            for (Object item : Json.asList(map.getOrDefault("rules", List.of()))) {
                config.rules.add(RuleConfig.fromMap(Json.asObject(item)));
            }
            return config;
        }

        static ProjectConfig fromLegacyMappingMap(Map<String, Object> map) {
            LegacyMappingConfig legacy = LegacyMappingConfig.fromMap(map);
            ProjectConfig project = new ProjectConfig();
            project.id = firstNonBlank(nullableString(map.get("projectId")),
                slugify(firstNonBlank(nullableString(map.get("localProjectName")),
                    stripGitSuffix(extractRepoName(legacy.vendorRepoUrl)), legacy.id)));
            project.name = firstNonBlank(nullableString(map.get("projectName")), legacy.name, project.id);
            project.vendorRepoUrl = legacy.vendorRepoUrl;
            project.localWorkspaceRoot = legacy.localWorkspaceRoot;
            project.localProjectName = legacy.localProjectName;
            project.legacyLocalRepoPath = legacy.legacyLocalRepoPath;
            project.enabled = legacy.enabled;
            project.rules.add(RuleConfig.fromLegacyMapping(legacy));
            return project;
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("name", name);
            map.put("vendorRepoUrl", vendorRepoUrl);
            map.put("localProjectName", localProjectName);
            map.put("enabled", enabled);
            List<Object> ruleList = new ArrayList<>();
            for (RuleConfig rule : rules) {
                ruleList.add(rule.toMap());
            }
            map.put("rules", ruleList);
            return map;
        }

        Path localRepoPath(AppConfig appConfig) {
            String workspaceRoot = effectiveWorkspaceRoot(appConfig);
            require(workspaceRoot != null && !workspaceRoot.isBlank(), "localWorkspaceRoot is required");
            return Path.of(workspaceRoot).resolve(localProjectName);
        }

        String displayLocalRepoPath(AppConfig appConfig) {
            String workspaceRoot = effectiveWorkspaceRoot(appConfig);
            if (workspaceRoot == null || workspaceRoot.isBlank()) {
                return "";
            }
            return Path.of(workspaceRoot).resolve(localProjectName).toString();
        }

        String effectiveWorkspaceRoot(AppConfig appConfig) {
            return firstNonBlank(appConfig.localWorkspaceRoot, localWorkspaceRoot);
        }

        boolean hasEffectiveWorkspaceRoot(AppConfig appConfig) {
            String workspaceRoot = effectiveWorkspaceRoot(appConfig);
            return workspaceRoot != null && !workspaceRoot.isBlank();
        }
    }

    static final class RuleConfig {
        String id;
        String name;
        String sourceBranch;
        String targetRemoteId;
        String targetRepoName;
        String targetBranch;
        boolean sameBranchNameExpected;
        boolean enabled = true;
        boolean allowForcePush;
        boolean manualOnly;
        boolean reviewRequired;
        ScheduleConfig schedule = new ScheduleConfig();

        static RuleConfig fromMap(Map<String, Object> map) {
            RuleConfig config = new RuleConfig();
            config.id = stringValue(map.get("id"));
            config.name = stringValue(map.getOrDefault("name", map.get("id")));
            config.sourceBranch = stringValue(map.get("sourceBranch"));
            config.targetRemoteId = stringValue(map.get("targetRemoteId"));
            config.targetRepoName = nullableString(map.get("targetRepoName"));
            config.targetBranch = stringValue(map.get("targetBranch"));
            config.sameBranchNameExpected = booleanValue(map.getOrDefault("sameBranchNameExpected", Boolean.FALSE));
            config.enabled = booleanValue(map.getOrDefault("enabled", Boolean.TRUE));
            config.allowForcePush = booleanValue(map.getOrDefault("allowForcePush", Boolean.FALSE));
            config.manualOnly = booleanValue(map.getOrDefault("manualOnly", Boolean.FALSE));
            config.reviewRequired = booleanValue(map.getOrDefault("reviewRequired", Boolean.FALSE));
            Object scheduleObject = map.get("schedule");
            if (scheduleObject instanceof Map<?, ?> scheduleMap) {
                config.schedule = ScheduleConfig.fromMap(Json.asObject(scheduleMap));
            }
            return config;
        }

        static RuleConfig fromLegacyMapping(LegacyMappingConfig legacy) {
            RuleConfig rule = new RuleConfig();
            rule.id = legacy.id;
            rule.name = firstNonBlank(legacy.ruleName, legacy.sourceBranch + " -> " + legacy.targetBranch, legacy.id);
            rule.sourceBranch = legacy.sourceBranch;
            rule.targetRemoteId = legacy.targetRemoteId;
            rule.targetRepoName = legacy.targetRepoName;
            rule.targetBranch = legacy.targetBranch;
            rule.sameBranchNameExpected = legacy.sameBranchNameExpected;
            rule.enabled = legacy.enabled;
            rule.allowForcePush = legacy.allowForcePush;
            rule.manualOnly = legacy.manualOnly;
            rule.reviewRequired = legacy.reviewRequired;
            rule.schedule = legacy.schedule;
            return rule;
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("name", name);
            map.put("sourceBranch", sourceBranch);
            map.put("targetRemoteId", targetRemoteId);
            map.put("targetRepoName", targetRepoName);
            map.put("targetBranch", targetBranch);
            map.put("sameBranchNameExpected", sameBranchNameExpected);
            map.put("enabled", enabled);
            map.put("allowForcePush", allowForcePush);
            map.put("manualOnly", manualOnly);
            map.put("reviewRequired", reviewRequired);
            map.put("schedule", schedule.toMap());
            return map;
        }
    }

    static final class LegacyMappingConfig {
        String id;
        String name;
        String vendorRepoUrl;
        String localWorkspaceRoot;
        String localProjectName;
        String legacyLocalRepoPath;
        String sourceBranch;
        String targetRemoteId;
        String targetRepoName;
        String targetBranch;
        boolean sameBranchNameExpected;
        boolean enabled = true;
        boolean allowForcePush;
        boolean manualOnly;
        boolean reviewRequired;
        ScheduleConfig schedule = new ScheduleConfig();
        String ruleName;

        static LegacyMappingConfig fromMap(Map<String, Object> map) {
            LegacyMappingConfig config = new LegacyMappingConfig();
            config.id = stringValue(map.get("id"));
            config.name = stringValue(map.get("name"));
            config.ruleName = nullableString(map.get("ruleName"));
            config.vendorRepoUrl = stringValue(map.get("vendorRepoUrl"));
            config.legacyLocalRepoPath = nullableString(map.get("localRepoPath"));
            config.localWorkspaceRoot = nullableString(map.get("localWorkspaceRoot"));
            config.localProjectName = nullableString(map.get("localProjectName"));
            config.sourceBranch = stringValue(map.get("sourceBranch"));
            config.targetRemoteId = stringValue(map.get("targetRemoteId"));
            config.targetRepoName = nullableString(map.get("targetRepoName"));
            config.targetBranch = stringValue(map.get("targetBranch"));
            config.sameBranchNameExpected = booleanValue(map.getOrDefault("sameBranchNameExpected", Boolean.FALSE));
            config.enabled = booleanValue(map.getOrDefault("enabled", Boolean.TRUE));
            config.allowForcePush = booleanValue(map.getOrDefault("allowForcePush", Boolean.FALSE));
            config.manualOnly = booleanValue(map.getOrDefault("manualOnly", Boolean.FALSE));
            config.reviewRequired = booleanValue(map.getOrDefault("reviewRequired", Boolean.FALSE));
            Object scheduleObject = map.get("schedule");
            if (scheduleObject instanceof Map<?, ?> scheduleMap) {
                config.schedule = ScheduleConfig.fromMap(Json.asObject(scheduleMap));
            }
            return config;
        }
    }

    static final class ScheduleConfig {
        boolean enabled;
        String type = "fixed-interval";
        int intervalMinutes = 30;

        static ScheduleConfig fromMap(Map<String, Object> map) {
            ScheduleConfig config = new ScheduleConfig();
            config.enabled = booleanValue(map.getOrDefault("enabled", Boolean.FALSE));
            config.type = stringValue(map.getOrDefault("type", "fixed-interval"));
            config.intervalMinutes = intValue(map.getOrDefault("intervalMinutes", 30));
            return config;
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("enabled", enabled);
            map.put("type", type);
            map.put("intervalMinutes", intervalMinutes);
            return map;
        }
    }

    static final class RuntimeState {
        Map<String, RuleRuntimeState> mappingStates = new LinkedHashMap<>();

        static RuntimeState fromMap(Map<String, Object> map) {
            RuntimeState state = new RuntimeState();
            Object rawStates = map.getOrDefault("mappingStates", Map.of());
            if (rawStates instanceof Map<?, ?> stateMap) {
                for (Map.Entry<String, Object> entry : Json.asObject(stateMap).entrySet()) {
                    state.mappingStates.put(entry.getKey(), RuleRuntimeState.fromMap(Json.asObject(entry.getValue())));
                }
            }
            return state;
        }

        Map<String, Object> toMap() {
            Map<String, Object> root = new LinkedHashMap<>();
            Map<String, Object> states = new LinkedHashMap<>();
            for (Map.Entry<String, RuleRuntimeState> entry : mappingStates.entrySet()) {
                states.put(entry.getKey(), entry.getValue().toMap());
            }
            root.put("mappingStates", states);
            return root;
        }
    }

    static final class RuleRuntimeState {
        String lastRunAt;
        String lastStatus = "never";
        String lastRunSource;
        String nextRunAt;
        boolean running;
        String lastLogPath;
        String lastMessage;

        static RuleRuntimeState fromMap(Map<String, Object> map) {
            RuleRuntimeState state = new RuleRuntimeState();
            state.lastRunAt = nullableString(map.get("lastRunAt"));
            state.lastStatus = stringValue(map.getOrDefault("lastStatus", "never"));
            state.lastRunSource = nullableString(map.get("lastRunSource"));
            state.nextRunAt = nullableString(map.get("nextRunAt"));
            state.running = booleanValue(map.getOrDefault("running", Boolean.FALSE));
            state.lastLogPath = nullableString(map.get("lastLogPath"));
            state.lastMessage = nullableString(map.get("lastMessage"));
            return state;
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("lastRunAt", lastRunAt);
            map.put("lastStatus", lastStatus);
            map.put("lastRunSource", lastRunSource);
            map.put("nextRunAt", nextRunAt);
            map.put("running", running);
            map.put("lastLogPath", lastLogPath);
            map.put("lastMessage", lastMessage);
            return map;
        }
    }

    static final class RuleSelection {
        final ProjectConfig project;
        final RuleConfig rule;

        RuleSelection(ProjectConfig project, RuleConfig rule) {
            this.project = project;
            this.rule = rule;
        }
    }

    static int intValue(Object value) {
        if (value instanceof Integer number) {
            return number;
        }
        if (value instanceof Long number) {
            return number.intValue();
        }
        if (value instanceof Double number) {
            return number.intValue();
        }
        if (value instanceof String string && !string.isBlank()) {
            return Integer.parseInt(string);
        }
        throw new IllegalArgumentException("Expected integer but got " + value);
    }

    static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String string) {
            return Boolean.parseBoolean(string);
        }
        throw new IllegalArgumentException("Expected boolean but got " + value);
    }

    static String stringValue(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Expected string but got null");
        }
        String result = String.valueOf(value);
        if (result.isBlank()) {
            throw new IllegalArgumentException("String value must not be blank");
        }
        return result;
    }

    static String nullableString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    static String deriveBaseUrl(String gitUrl) {
        if (gitUrl == null || gitUrl.isBlank()) {
            return null;
        }
        int slashIndex = gitUrl.lastIndexOf('/');
        int colonIndex = gitUrl.startsWith("ssh://") ? -1 : gitUrl.lastIndexOf(':');
        int splitIndex = Math.max(slashIndex, colonIndex);
        return splitIndex >= 0 ? gitUrl.substring(0, splitIndex + 1) : gitUrl;
    }

    static String extractRepoName(String gitUrl) {
        if (gitUrl == null || gitUrl.isBlank()) {
            return null;
        }
        int slashIndex = gitUrl.lastIndexOf('/');
        int colonIndex = gitUrl.startsWith("ssh://") ? -1 : gitUrl.lastIndexOf(':');
        int splitIndex = Math.max(slashIndex, colonIndex);
        return splitIndex >= 0 && splitIndex + 1 < gitUrl.length() ? gitUrl.substring(splitIndex + 1) : gitUrl;
    }

    static String buildGitUrl(String baseUrl, String repoName) {
        require(baseUrl != null && !baseUrl.isBlank(), "baseUrl is required");
        require(repoName != null && !repoName.isBlank(), "repoName is required");
        if (baseUrl.endsWith("/") || baseUrl.endsWith(":")) {
            return baseUrl + repoName;
        }
        return baseUrl + "/" + repoName;
    }

    static String stripGitSuffix(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value.endsWith(".git") ? value.substring(0, value.length() - 4) : value;
    }

    static String slugify(String value) {
        String normalized = String.valueOf(firstNonBlank(value, "project")).trim().toLowerCase();
        normalized = normalized.replaceAll("[^a-z0-9]+", "-");
        normalized = normalized.replaceAll("(^-+|-+$)", "");
        return normalized.isBlank() ? "project" : normalized;
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    static RemoteConfig findRemote(AppConfig config, String remoteId) {
        return config.remotes.stream()
            .filter(remote -> Objects.equals(remote.id, remoteId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Remote not found: " + remoteId));
    }

    static ProjectConfig findProject(AppConfig config, String projectId) {
        return config.projects.stream()
            .filter(project -> Objects.equals(project.id, projectId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
    }

    static RuleSelection findRuleSelection(AppConfig config, String ruleId) {
        for (ProjectConfig project : config.projects) {
            for (RuleConfig rule : project.rules) {
                if (Objects.equals(rule.id, ruleId)) {
                    return new RuleSelection(project, rule);
                }
            }
        }
        throw new IllegalArgumentException("Rule not found: " + ruleId);
    }

    static java.util.Set<String> allRuleIds(AppConfig config) {
        java.util.Set<String> ruleIds = new java.util.LinkedHashSet<>();
        for (ProjectConfig project : config.projects) {
            for (RuleConfig rule : project.rules) {
                ruleIds.add(rule.id);
            }
        }
        return ruleIds;
    }
}

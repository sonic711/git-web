package app;

import app.Models.AppConfig;
import app.Models.ProjectConfig;
import app.Models.RemoteConfig;
import app.Models.RuleConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class ConfigService {
    private final Path configPath;
    private AppConfig current;

    ConfigService(Path configPath) throws IOException {
        this.configPath = configPath;
        ensureDefaultFile();
        this.current = load();
    }

    synchronized AppConfig getConfig() {
        return current;
    }

    synchronized void reload() throws IOException {
        current = load();
    }

    synchronized void replaceConfig(AppConfig config) throws IOException {
        normalizeLegacyConfig(config);
        validate(config);
        config.updatedAt = Models.nowIso();
        save(config);
        current = config;
    }

    synchronized void upsertRemote(RemoteConfig remote) throws IOException {
        AppConfig config = current;
        boolean replaced = false;
        for (int i = 0; i < config.remotes.size(); i++) {
            if (config.remotes.get(i).id.equals(remote.id)) {
                config.remotes.set(i, remote);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            config.remotes.add(remote);
        }
        replaceConfig(config);
    }

    synchronized void deleteRemote(String remoteId) throws IOException {
        AppConfig config = current;
        for (ProjectConfig project : config.projects) {
            for (RuleConfig rule : project.rules) {
                Models.require(!rule.targetRemoteId.equals(remoteId), "Remote is still used by rule: " + rule.id);
            }
        }
        config.remotes.removeIf(remote -> remote.id.equals(remoteId));
        replaceConfig(config);
    }

    synchronized void upsertProject(ProjectConfig project) throws IOException {
        AppConfig config = current;
        boolean replaced = false;
        for (int i = 0; i < config.projects.size(); i++) {
            if (config.projects.get(i).id.equals(project.id)) {
                config.projects.set(i, project);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            config.projects.add(project);
        }
        replaceConfig(config);
    }

    synchronized void deleteProject(String projectId) throws IOException {
        AppConfig config = current;
        config.projects.removeIf(project -> project.id.equals(projectId));
        replaceConfig(config);
    }

    synchronized void upsertRule(String projectId, RuleConfig rule) throws IOException {
        AppConfig config = current;
        ProjectConfig project = Models.findProject(config, projectId);
        boolean replaced = false;
        for (int i = 0; i < project.rules.size(); i++) {
            if (project.rules.get(i).id.equals(rule.id)) {
                project.rules.set(i, rule);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            project.rules.add(rule);
        }
        replaceConfig(config);
    }

    synchronized void deleteRule(String projectId, String ruleId) throws IOException {
        AppConfig config = current;
        ProjectConfig project = Models.findProject(config, projectId);
        project.rules.removeIf(rule -> rule.id.equals(ruleId));
        replaceConfig(config);
    }

    private AppConfig load() throws IOException {
        String content = Files.readString(configPath, StandardCharsets.UTF_8);
        Object parsed = Json.parse(content);
        AppConfig config = AppConfig.fromMap(Json.asObject(parsed));
        normalizeLegacyConfig(config);
        validate(config);
        return config;
    }

    private void save(AppConfig config) throws IOException {
        Files.writeString(configPath, Json.stringify(config.toMap()), StandardCharsets.UTF_8);
    }

    private void ensureDefaultFile() throws IOException {
        Files.createDirectories(configPath.getParent());
        if (!Files.exists(configPath)) {
            AppConfig defaultConfig = new AppConfig();
            Files.writeString(configPath, Json.stringify(defaultConfig.toMap()), StandardCharsets.UTF_8);
        }
    }

    private void normalizeLegacyConfig(AppConfig config) {
        for (RemoteConfig remote : config.remotes) {
            if (remote.name == null || remote.name.isBlank()) {
                remote.name = remote.id;
            }
            if ((remote.baseUrl == null || remote.baseUrl.isBlank()) && remote.legacyUrl != null && !remote.legacyUrl.isBlank()) {
                remote.baseUrl = Models.deriveBaseUrl(remote.legacyUrl);
            }
        }

        Map<String, ProjectConfig> merged = new LinkedHashMap<>();
        for (ProjectConfig project : config.projects) {
            if ((project.localWorkspaceRoot == null || project.localWorkspaceRoot.isBlank()) && project.legacyLocalRepoPath != null
                && !project.legacyLocalRepoPath.isBlank()) {
                Path legacyPath = Path.of(project.legacyLocalRepoPath);
                Path parent = legacyPath.getParent();
                project.localWorkspaceRoot = parent != null ? parent.toString() : legacyPath.toString();
                project.localProjectName = legacyPath.getFileName() != null ? legacyPath.getFileName().toString() : project.localProjectName;
            }
            if (project.localProjectName == null || project.localProjectName.isBlank()) {
                project.localProjectName = Models.stripGitSuffix(Models.extractRepoName(project.vendorRepoUrl));
            }
            if (project.id == null || project.id.isBlank()) {
                project.id = Models.slugify(project.localProjectName);
            }
            if (project.name == null || project.name.isBlank()) {
                project.name = project.localProjectName;
            }
            String projectKey = projectKey(project);
            ProjectConfig mergedProject = merged.computeIfAbsent(projectKey, ignored -> {
                ProjectConfig item = new ProjectConfig();
                item.id = project.id;
                item.name = project.name;
                item.vendorRepoUrl = project.vendorRepoUrl;
                item.localWorkspaceRoot = project.localWorkspaceRoot;
                item.localProjectName = project.localProjectName;
                item.enabled = project.enabled;
                return item;
            });
            mergedProject.enabled = mergedProject.enabled || project.enabled;
            for (RuleConfig rule : project.rules) {
                if (rule.name == null || rule.name.isBlank()) {
                    rule.name = rule.sourceBranch + " -> " + rule.targetBranch;
                }
                if (rule.targetRepoName == null || rule.targetRepoName.isBlank()) {
                    RemoteConfig remote = config.remotes.stream()
                        .filter(item -> item.id.equals(rule.targetRemoteId))
                        .findFirst()
                        .orElse(null);
                    if (remote != null && remote.legacyUrl != null && !remote.legacyUrl.isBlank()) {
                        rule.targetRepoName = Models.extractRepoName(remote.legacyUrl);
                    }
                }
                boolean exists = mergedProject.rules.stream().anyMatch(item -> item.id.equals(rule.id));
                if (!exists) {
                    mergedProject.rules.add(rule);
                }
            }
        }
        config.projects = new java.util.ArrayList<>(merged.values());
    }

    private void validate(AppConfig config) {
        Models.require(config.version == 1, "version must be 1");
        Set<String> remoteIds = new HashSet<>();
        for (RemoteConfig remote : config.remotes) {
            Models.require(remote.id != null && !remote.id.isBlank(), "remote id is required");
            Models.require(remoteIds.add(remote.id), "duplicate remote id: " + remote.id);
            Models.require(remote.name != null && !remote.name.isBlank(), "remote name is required");
            Models.require(remote.baseUrl != null && !remote.baseUrl.isBlank(), "remote baseUrl is required");
            Models.require(remote.baseUrl.startsWith("git@") || remote.baseUrl.startsWith("ssh://"),
                "remote baseUrl must be SSH");
        }

        Set<String> projectIds = new HashSet<>();
        Set<String> ruleIds = new HashSet<>();
        for (ProjectConfig project : config.projects) {
            Models.require(project.id != null && !project.id.isBlank(), "project id is required");
            Models.require(projectIds.add(project.id), "duplicate project id: " + project.id);
            Models.require(project.name != null && !project.name.isBlank(), "project name is required");
            Models.require(project.vendorRepoUrl != null && !project.vendorRepoUrl.isBlank(), "vendorRepoUrl is required");
            Models.require(project.vendorRepoUrl.startsWith("https://") || project.vendorRepoUrl.startsWith("http://"),
                "vendorRepoUrl must be HTTP/HTTPS");
            Models.require(project.localWorkspaceRoot != null && !project.localWorkspaceRoot.isBlank(),
                "localWorkspaceRoot is required");
            Models.require(project.localProjectName != null && !project.localProjectName.isBlank(),
                "localProjectName is required");
            for (RuleConfig rule : project.rules) {
                Models.require(rule.id != null && !rule.id.isBlank(), "rule id is required");
                Models.require(ruleIds.add(rule.id), "duplicate rule id: " + rule.id);
                Models.require(rule.name != null && !rule.name.isBlank(), "rule name is required");
                Models.require(rule.sourceBranch != null && !rule.sourceBranch.isBlank(), "sourceBranch is required");
                Models.require(rule.targetRepoName != null && !rule.targetRepoName.isBlank(), "targetRepoName is required");
                Models.require(rule.targetRepoName.endsWith(".git"), "targetRepoName must end with .git");
                Models.require(rule.targetBranch != null && !rule.targetBranch.isBlank(), "targetBranch is required");
                Models.require(remoteIds.contains(rule.targetRemoteId), "targetRemoteId does not exist: " + rule.targetRemoteId);
                Models.require(rule.schedule.intervalMinutes > 0, "intervalMinutes must be > 0");
                if (rule.manualOnly) {
                    Models.require(!rule.schedule.enabled, "manualOnly rules must not enable schedule");
                }
            }
        }
    }

    private String projectKey(ProjectConfig project) {
        return project.vendorRepoUrl + "|" + project.localWorkspaceRoot + "|" + project.localProjectName;
    }

    synchronized Map<String, Object> configAsMap() {
        return current.toMap();
    }
}

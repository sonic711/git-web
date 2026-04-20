package app;

import app.Models.AppConfig;
import app.Models.MappingConfig;
import app.Models.RemoteConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
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
        for (Models.MappingConfig mapping : config.mappings) {
            Models.require(!mapping.targetRemoteId.equals(remoteId), "Remote is still used by mapping: " + mapping.id);
        }
        config.remotes.removeIf(remote -> remote.id.equals(remoteId));
        replaceConfig(config);
    }

    synchronized void upsertMapping(MappingConfig mapping) throws IOException {
        AppConfig config = current;
        boolean replaced = false;
        for (int i = 0; i < config.mappings.size(); i++) {
            if (config.mappings.get(i).id.equals(mapping.id)) {
                config.mappings.set(i, mapping);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            config.mappings.add(mapping);
        }
        replaceConfig(config);
    }

    synchronized void deleteMapping(String mappingId) throws IOException {
        AppConfig config = current;
        config.mappings.removeIf(mapping -> mapping.id.equals(mappingId));
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
        Set<String> mappingIds = new HashSet<>();
        for (MappingConfig mapping : config.mappings) {
            Models.require(mappingIds.add(mapping.id), "duplicate mapping id: " + mapping.id);
            Models.require(!mapping.vendorRepoUrl.isBlank(), "vendorRepoUrl is required");
            Models.require(mapping.vendorRepoUrl.startsWith("https://") || mapping.vendorRepoUrl.startsWith("http://"),
                "vendorRepoUrl must be HTTP/HTTPS");
            Models.require(mapping.localWorkspaceRoot != null && !mapping.localWorkspaceRoot.isBlank(), "localWorkspaceRoot is required");
            Models.require(mapping.localProjectName != null && !mapping.localProjectName.isBlank(), "localProjectName is required");
            Models.require(!mapping.sourceBranch.isBlank(), "sourceBranch is required");
            Models.require(mapping.targetRepoName != null && !mapping.targetRepoName.isBlank(), "targetRepoName is required");
            Models.require(mapping.targetRepoName.endsWith(".git"), "targetRepoName must end with .git");
            Models.require(!mapping.targetBranch.isBlank(), "targetBranch is required");
            Models.require(remoteIds.contains(mapping.targetRemoteId), "targetRemoteId does not exist: " + mapping.targetRemoteId);
            Models.require(mapping.schedule.intervalMinutes > 0, "intervalMinutes must be > 0");
            if (mapping.manualOnly) {
                Models.require(!mapping.schedule.enabled, "manualOnly mappings must not enable schedule");
            }
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
        for (MappingConfig mapping : config.mappings) {
            if ((mapping.localWorkspaceRoot == null || mapping.localWorkspaceRoot.isBlank()) && mapping.legacyLocalRepoPath != null
                && !mapping.legacyLocalRepoPath.isBlank()) {
                Path legacyPath = Path.of(mapping.legacyLocalRepoPath);
                Path parent = legacyPath.getParent();
                mapping.localWorkspaceRoot = parent != null ? parent.toString() : legacyPath.toString();
                mapping.localProjectName = legacyPath.getFileName() != null ? legacyPath.getFileName().toString() : mapping.localProjectName;
            }
            if (mapping.localProjectName == null || mapping.localProjectName.isBlank()) {
                mapping.localProjectName = Models.stripGitSuffix(Models.extractRepoName(mapping.vendorRepoUrl));
            }
            if (mapping.targetRepoName != null && !mapping.targetRepoName.isBlank()) {
                continue;
            }
            RemoteConfig remote = config.remotes.stream()
                .filter(item -> item.id.equals(mapping.targetRemoteId))
                .findFirst()
                .orElse(null);
            if (remote != null && remote.legacyUrl != null && !remote.legacyUrl.isBlank()) {
                mapping.targetRepoName = Models.extractRepoName(remote.legacyUrl);
            }
        }
    }

    synchronized Map<String, Object> configAsMap() {
        return current.toMap();
    }
}

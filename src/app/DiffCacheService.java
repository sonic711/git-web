package app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class DiffCacheService {
    private final Path cacheRoot;

    DiffCacheService(Path cacheRoot) throws IOException {
        this.cacheRoot = cacheRoot;
        Files.createDirectories(cacheRoot);
    }

    synchronized Map<String, Object> readSummary(String ruleId) throws IOException {
        Path summaryPath = summaryPath(ruleId);
        if (!Files.exists(summaryPath)) {
            return null;
        }
        Map<String, Object> summary = readJson(summaryPath);
        Map<String, Object> meta = readMeta(ruleId);
        return mergeSummaryAndMeta(summary, meta);
    }

    synchronized Map<String, Object> writeSummary(String ruleId, Map<String, Object> summary, String message) throws IOException {
        Files.createDirectories(ruleRoot(ruleId));
        writeJson(summaryPath(ruleId), summary);
        clearPatchCache(ruleId);
        clearSnapshotCache(ruleId);
        clearCommitFileCache(ruleId);
        Map<String, Object> meta = defaultMeta();
        meta.put("cachedAt", Models.nowIso());
        meta.put("cacheStatus", "fresh");
        meta.put("lastRefreshMessage", message);
        writeJson(metaPath(ruleId), meta);
        return mergeSummaryAndMeta(summary, meta);
    }

    synchronized String readPatch(String ruleId, String path, String oldPath) throws IOException {
        Path patchPath = patchPath(ruleId, path, oldPath);
        if (!Files.exists(patchPath)) {
            return null;
        }
        return Files.readString(patchPath, StandardCharsets.UTF_8);
    }

    synchronized void writePatch(String ruleId, String path, String oldPath, String patch) throws IOException {
        Path patchPath = patchPath(ruleId, path, oldPath);
        Files.createDirectories(patchPath.getParent());
        Files.writeString(patchPath, patch == null ? "" : patch, StandardCharsets.UTF_8);
    }

    synchronized Map<String, Object> readSnapshot(String ruleId, String path, String oldPath) throws IOException {
        Path snapshotPath = snapshotPath(ruleId, path, oldPath);
        if (!Files.exists(snapshotPath)) {
            return null;
        }
        return readJson(snapshotPath);
    }

    synchronized void writeSnapshot(String ruleId, String path, String oldPath,
                                    boolean baseExists, String baseContent,
                                    boolean headExists, String headContent) throws IOException {
        Path snapshotPath = snapshotPath(ruleId, path, oldPath);
        Files.createDirectories(snapshotPath.getParent());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("path", path);
        payload.put("oldPath", oldPath);
        payload.put("baseExists", baseExists);
        payload.put("baseContent", baseContent == null ? "" : baseContent);
        payload.put("headExists", headExists);
        payload.put("headContent", headContent == null ? "" : headContent);
        writeJson(snapshotPath, payload);
    }

    synchronized Map<String, Object> readCommitFiles(String ruleId, String commitId) throws IOException {
        Path path = commitFilesPath(ruleId, commitId);
        if (!Files.exists(path)) {
            return null;
        }
        return readJson(path);
    }

    synchronized void writeCommitFiles(String ruleId, String commitId, Map<String, Object> payload) throws IOException {
        writeJson(commitFilesPath(ruleId, commitId), payload);
    }

    synchronized void markStale(String ruleId, String message) throws IOException {
        if (!Files.exists(metaPath(ruleId)) && !Files.exists(summaryPath(ruleId))) {
            return;
        }
        Map<String, Object> meta = readMeta(ruleId);
        meta.put("cacheStatus", "stale");
        meta.put("lastRefreshMessage", message);
        writeJson(metaPath(ruleId), meta);
    }

    synchronized void markFailed(String ruleId, String message) throws IOException {
        Map<String, Object> meta = readMeta(ruleId);
        meta.put("cacheStatus", "failed");
        meta.put("lastRefreshMessage", message);
        writeJson(metaPath(ruleId), meta);
    }

    synchronized void markAllStale(String message) throws IOException {
        if (!Files.exists(cacheRoot)) {
            return;
        }
        try (var stream = Files.list(cacheRoot)) {
            for (Path ruleDir : stream.toList()) {
                if (Files.isDirectory(ruleDir)) {
                    markStale(ruleDir.getFileName().toString(), message);
                }
            }
        }
    }

    synchronized void deleteRule(String ruleId) throws IOException {
        Path ruleRoot = ruleRoot(ruleId);
        if (!Files.exists(ruleRoot)) {
            return;
        }
        try (var walk = Files.walk(ruleRoot)) {
            for (Path path : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    synchronized void retainRuleIds(Set<String> ruleIds) throws IOException {
        if (!Files.exists(cacheRoot)) {
            return;
        }
        try (var stream = Files.list(cacheRoot)) {
            for (Path ruleDir : stream.toList()) {
                if (!Files.isDirectory(ruleDir)) {
                    continue;
                }
                String ruleId = ruleDir.getFileName().toString();
                if (!ruleIds.contains(ruleId)) {
                    deleteRule(ruleId);
                }
            }
        }
    }

    private Map<String, Object> mergeSummaryAndMeta(Map<String, Object> summary, Map<String, Object> meta) {
        Map<String, Object> merged = new LinkedHashMap<>(summary);
        merged.put("cacheStatus", meta.getOrDefault("cacheStatus", "fresh"));
        merged.put("cachedAt", meta.get("cachedAt"));
        merged.put("lastRefreshMessage", meta.get("lastRefreshMessage"));
        return merged;
    }

    private Map<String, Object> readMeta(String ruleId) throws IOException {
        Path metaPath = metaPath(ruleId);
        if (!Files.exists(metaPath)) {
            return defaultMeta();
        }
        Map<String, Object> meta = readJson(metaPath);
        if (!meta.containsKey("cacheStatus")) {
            meta.put("cacheStatus", "fresh");
        }
        return meta;
    }

    private Map<String, Object> defaultMeta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("cachedAt", null);
        meta.put("cacheStatus", "fresh");
        meta.put("lastRefreshMessage", null);
        return meta;
    }

    private Map<String, Object> readJson(Path path) throws IOException {
        return Json.asObject(Json.parse(Files.readString(path, StandardCharsets.UTF_8)));
    }

    private void writeJson(Path path, Map<String, Object> payload) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, Json.stringify(payload), StandardCharsets.UTF_8);
    }

    private void clearPatchCache(String ruleId) throws IOException {
        Path patchesDir = patchesDir(ruleId);
        if (!Files.exists(patchesDir)) {
            return;
        }
        try (var walk = Files.walk(patchesDir)) {
            for (Path path : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void clearSnapshotCache(String ruleId) throws IOException {
        Path snapshotsDir = snapshotsDir(ruleId);
        if (!Files.exists(snapshotsDir)) {
            return;
        }
        try (var walk = Files.walk(snapshotsDir)) {
            for (Path path : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void clearCommitFileCache(String ruleId) throws IOException {
        Path commitsDir = commitsDir(ruleId);
        if (!Files.exists(commitsDir)) {
            return;
        }
        try (var walk = Files.walk(commitsDir)) {
            for (Path path : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private Path ruleRoot(String ruleId) {
        return cacheRoot.resolve(ruleId);
    }

    private Path summaryPath(String ruleId) {
        return ruleRoot(ruleId).resolve("summary.json");
    }

    private Path metaPath(String ruleId) {
        return ruleRoot(ruleId).resolve("meta.json");
    }

    private Path patchesDir(String ruleId) {
        return ruleRoot(ruleId).resolve("files");
    }

    private Path snapshotsDir(String ruleId) {
        return ruleRoot(ruleId).resolve("snapshots");
    }

    private Path commitsDir(String ruleId) {
        return ruleRoot(ruleId).resolve("commits");
    }

    private Path patchPath(String ruleId, String path, String oldPath) {
        return patchesDir(ruleId).resolve(hashKey((oldPath == null ? "" : oldPath) + "=>" + path) + ".patch");
    }

    private Path snapshotPath(String ruleId, String path, String oldPath) {
        return snapshotsDir(ruleId).resolve(hashKey((oldPath == null ? "" : oldPath) + "=>" + path) + ".json");
    }

    private Path commitFilesPath(String ruleId, String commitId) {
        return commitsDir(ruleId).resolve(hashKey(commitId) + ".json");
    }

    private String hashKey(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}

package app;

import app.GitCommandRunner.GitCommandResult;
import app.Models.AppConfig;
import app.Models.MappingConfig;
import app.Models.RemoteConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class GitService {
    private final GitCommandRunner runner;

    GitService(GitCommandRunner runner) {
        this.runner = runner;
    }

    Map<String, Object> validate(AppConfig config, MappingConfig mapping) throws IOException, InterruptedException {
        List<Object> checks = new ArrayList<>();
        boolean ok = true;
        RemoteConfig remote = Models.findRemote(config, mapping.targetRemoteId);

        ok &= addCheck(checks, "mapping_enabled", mapping.enabled);
        boolean repoExists = Files.exists(mapping.localRepoPath());
        ok &= addCheck(checks, "repo_path_exists", repoExists);
        boolean repoReady = repoExists && isGitRepo(mapping.localRepoPath());
        ok &= addCheck(checks, "repo_ready", repoReady || !repoExists);
        if (repoReady) {
            ok &= addCheck(checks, "branch_exists", branchExists(mapping.localRepoPath(), mapping.sourceBranch));
        }
        ok &= addCheck(checks, "target_remote_template_exists", remote.enabled && remote.baseUrl != null && !remote.baseUrl.isBlank());
        ok &= addCheck(checks, "target_repo_name_valid", mapping.targetRepoName != null && mapping.targetRepoName.endsWith(".git"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mappingId", mapping.id);
        result.put("ok", ok);
        result.put("checks", checks);
        return result;
    }

    Map<String, Object> diff(AppConfig config, MappingConfig mapping) throws IOException, InterruptedException {
        ensureRepoReady(mapping);
        ensureTargetRemote(config, mapping);
        RemoteConfig remote = Models.findRemote(config, mapping.targetRemoteId);
        String internalRemote = internalRemoteName(mapping.id);
        runChecked(mapping.localRepoPath(), List.of("git", "fetch", internalRemote, mapping.targetBranch));

        String targetRef = internalRemote + "/" + mapping.targetBranch;
        boolean targetExists = branchExists(mapping.localRepoPath(), targetRef);
        String range = targetExists ? targetRef + ".." + mapping.sourceBranch : mapping.sourceBranch;
        GitCommandResult commits = runChecked(mapping.localRepoPath(),
            List.of("git", "log", "--oneline", "--no-merges", range));
        GitCommandResult files = runChecked(mapping.localRepoPath(),
            targetExists
                ? List.of("git", "diff", "--name-status", targetRef, mapping.sourceBranch)
                : List.of("git", "diff-tree", "--no-commit-id", "--name-status", "-r", mapping.sourceBranch));

        List<Object> commitList = new ArrayList<>();
        String[] commitLines = commits.stdout.strip().isEmpty() ? new String[0] : commits.stdout.strip().split("\\R");
        for (String line : commitLines) {
            int firstSpace = line.indexOf(' ');
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", firstSpace > 0 ? line.substring(0, firstSpace) : line);
            item.put("title", firstSpace > 0 ? line.substring(firstSpace + 1) : "");
            commitList.add(item);
        }

        List<Object> changedFileList = new ArrayList<>();
        String[] fileLines = files.stdout.strip().isEmpty() ? new String[0] : files.stdout.strip().split("\\R");
        for (String line : fileLines) {
            String[] parts = line.split("\\t", 2);
            Map<String, Object> fileItem = new LinkedHashMap<>();
            fileItem.put("status", parts.length > 0 ? parts[0] : "M");
            fileItem.put("path", parts.length > 1 ? parts[1] : line);
            changedFileList.add(fileItem);
        }

        int changedFiles = changedFileList.size();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("aheadCommits", commitList.size());
        summary.put("changedFiles", changedFiles);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("mappingId", mapping.id);
        response.put("sourceBranch", mapping.sourceBranch);
        response.put("targetBranch", mapping.targetBranch);
        response.put("targetRepoName", mapping.targetRepoName);
        response.put("targetRemoteName", remote.name);
        response.put("targetRemoteUrl", targetRemoteUrl(config, mapping));
        response.put("summary", summary);
        response.put("commits", commitList);
        response.put("files", changedFileList);
        return response;
    }

    SyncResult sync(AppConfig config, MappingConfig mapping, boolean forcePush, boolean reviewConfirmed, String triggerSource)
        throws IOException, InterruptedException {
        Models.require(mapping.enabled, "Mapping is disabled");
        if (forcePush) {
            Models.require(mapping.allowForcePush, "Force push is not allowed");
        }
        if ("schedule".equals(triggerSource)) {
            Models.require(!mapping.manualOnly, "Manual-only mapping cannot be scheduled");
        }
        if (mapping.reviewRequired) {
            Models.require(reviewConfirmed, "Review confirmation is required");
        }

        ensureRepoReady(mapping);
        ensureTargetRemote(config, mapping);
        String internalRemote = internalRemoteName(mapping.id);

        List<GitCommandResult> results = new ArrayList<>();
        results.add(runChecked(mapping.localRepoPath(), List.of("git", "fetch", "--all", "--prune")));
        results.add(runChecked(mapping.localRepoPath(), List.of("git", "rev-parse", "--verify", mapping.sourceBranch)));

        List<String> pushCommand = new ArrayList<>();
        pushCommand.add("git");
        pushCommand.add("push");
        if (forcePush) {
            pushCommand.add("-f");
        }
        pushCommand.add(internalRemote);
        pushCommand.add(mapping.sourceBranch + ":refs/heads/" + mapping.targetBranch);
        results.add(runChecked(mapping.localRepoPath(), pushCommand));

        return new SyncResult(results);
    }

    private void ensureRepoReady(MappingConfig mapping) throws IOException, InterruptedException {
        Path repoPath = mapping.localRepoPath();
        if (!Files.exists(repoPath)) {
            Files.createDirectories(repoPath.getParent());
            runChecked(null, List.of("git", "clone", mapping.vendorRepoUrl, repoPath.toString()));
            return;
        }
        Models.require(isGitRepo(repoPath), "Existing path is not a git repository");
        String originUrl = getRemoteUrl(repoPath, "origin");
        Models.require(originUrl == null || originUrl.equals(mapping.vendorRepoUrl),
            "Existing repository origin does not match vendorRepoUrl");
    }

    private void ensureTargetRemote(AppConfig config, MappingConfig mapping) throws IOException, InterruptedException {
        RemoteConfig remote = Models.findRemote(config, mapping.targetRemoteId);
        String name = internalRemoteName(mapping.id);
        String targetUrl = targetRemoteUrl(config, mapping);
        String existingUrl = getRemoteUrl(mapping.localRepoPath(), name);
        if (existingUrl == null) {
            runChecked(mapping.localRepoPath(), List.of("git", "remote", "add", name, targetUrl));
        } else if (!existingUrl.equals(targetUrl)) {
            runChecked(mapping.localRepoPath(), List.of("git", "remote", "set-url", name, targetUrl));
        }
    }

    String targetRemoteUrl(AppConfig config, MappingConfig mapping) {
        RemoteConfig remote = Models.findRemote(config, mapping.targetRemoteId);
        return Models.buildGitUrl(remote.baseUrl, mapping.targetRepoName);
    }

    private boolean isGitRepo(Path path) throws IOException, InterruptedException {
        GitCommandResult result = runner.run(path, List.of("git", "rev-parse", "--is-inside-work-tree"));
        return result.isSuccess() && result.stdout.strip().equals("true");
    }

    private boolean branchExists(Path repoPath, String branch) throws IOException, InterruptedException {
        GitCommandResult result = runner.run(repoPath, List.of("git", "rev-parse", "--verify", branch));
        return result.isSuccess();
    }

    private String getRemoteUrl(Path repoPath, String name) throws IOException, InterruptedException {
        GitCommandResult result = runner.run(repoPath, List.of("git", "remote", "get-url", name));
        return result.isSuccess() ? result.stdout.strip() : null;
    }

    private GitCommandResult runChecked(Path workDir, List<String> command) throws IOException, InterruptedException {
        GitCommandResult result = runner.run(workDir, command);
        if (!result.isSuccess()) {
            throw new IOException("Git command failed: " + String.join(" ", command) + "\n" + result.stderr);
        }
        return result;
    }

    private boolean addCheck(List<Object> checks, String key, boolean ok) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", key);
        item.put("ok", ok);
        checks.add(item);
        return ok;
    }

    private String internalRemoteName(String mappingId) {
        return "sync_target_" + mappingId;
    }

    static final class SyncResult {
        final List<GitCommandResult> commandResults;

        SyncResult(List<GitCommandResult> commandResults) {
            this.commandResults = commandResults;
        }

        String asLogText(String mappingId, boolean forcePush, boolean reviewConfirmed, String triggerSource) {
            StringBuilder builder = new StringBuilder();
            builder.append("mappingId=").append(mappingId).append('\n');
            builder.append("triggerSource=").append(triggerSource).append('\n');
            builder.append("forcePush=").append(forcePush).append('\n');
            builder.append("reviewConfirmed=").append(reviewConfirmed).append('\n');
            for (GitCommandResult result : commandResults) {
                builder.append("\n$ ").append(String.join(" ", result.command)).append('\n');
                builder.append("exitCode=").append(result.exitCode).append('\n');
                if (!result.stdout.isBlank()) {
                    builder.append("[stdout]\n").append(result.stdout).append('\n');
                }
                if (!result.stderr.isBlank()) {
                    builder.append("[stderr]\n").append(result.stderr).append('\n');
                }
            }
            return builder.toString();
        }
    }
}

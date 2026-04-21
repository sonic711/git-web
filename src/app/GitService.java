package app;

import app.GitCommandRunner.GitCommandResult;
import app.Models.AppConfig;
import app.Models.ProjectConfig;
import app.Models.RemoteConfig;
import app.Models.RuleConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class GitService {
    private static final String EMPTY_TREE_HASH = "4b825dc642cb6eb9a060e54bf8d69288fbee4904";
    private final GitCommandRunner runner;

    GitService(GitCommandRunner runner) {
        this.runner = runner;
    }

    Map<String, Object> validate(AppConfig config, ProjectConfig project, RuleConfig rule) throws IOException, InterruptedException {
        List<Object> checks = new ArrayList<>();
        boolean ok = true;
        RemoteConfig remote = Models.findRemote(config, rule.targetRemoteId);
        Path repoPath = project.localRepoPath(config);

        ok &= addCheck(checks, "project_enabled", project.enabled);
        ok &= addCheck(checks, "rule_enabled", rule.enabled);
        boolean repoExists = Files.exists(repoPath);
        ok &= addCheck(checks, "repo_path_exists", repoExists);
        boolean repoReady = repoExists && isGitRepo(repoPath);
        ok &= addCheck(checks, "repo_ready", repoReady || !repoExists);
        if (repoReady) {
            fetchOrigin(repoPath);
            ok &= addCheck(checks, "vendor_branch_exists", branchExists(repoPath, originRef(rule)));
        }
        ok &= addCheck(checks, "target_remote_template_exists", remote.enabled && remote.baseUrl != null && !remote.baseUrl.isBlank());
        ok &= addCheck(checks, "target_repo_name_valid", rule.targetRepoName != null && rule.targetRepoName.endsWith(".git"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", project.id);
        result.put("ruleId", rule.id);
        result.put("ok", ok);
        result.put("checks", checks);
        return result;
    }

    Map<String, Object> diff(AppConfig config, ProjectConfig project, RuleConfig rule) throws IOException, InterruptedException {
        ensureRepoReady(config, project);
        ensureTargetRemote(config, project, rule);
        RemoteConfig remote = Models.findRemote(config, rule.targetRemoteId);
        String internalRemote = internalRemoteName(rule.id);
        Path repoPath = project.localRepoPath(config);
        fetchOrigin(repoPath);
        runChecked(repoPath, List.of("git", "fetch", internalRemote, rule.targetBranch));

        String sourceRef = originRef(rule);
        String targetRef = internalRemote + "/" + rule.targetBranch;
        boolean targetExists = branchExists(repoPath, targetRef);
        String sourceCommit = resolveRevision(repoPath, sourceRef);
        String targetCommit = targetExists ? resolveRevision(repoPath, targetRef) : EMPTY_TREE_HASH;
        String range = targetExists ? targetRef + ".." + sourceRef : sourceRef;
        GitCommandResult commits = runChecked(repoPath,
            List.of("git", "log", "--oneline", "--no-merges", range));
        GitCommandResult files = runChecked(repoPath,
            targetExists
                ? List.of("git", "diff", "--name-status", "--find-renames", targetRef, sourceRef)
                : List.of("git", "diff-tree", "--no-commit-id", "--name-status", "-r", "--find-renames", sourceRef));

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
            String[] parts = line.split("\\t");
            String statusCode = parts.length > 0 ? parts[0] : "M";
            String status = normalizeStatus(statusCode);
            String oldPath = parts.length > 2 ? parts[1] : null;
            String path = parts.length > 2 ? parts[2] : parts.length > 1 ? parts[1] : line;
            Map<String, Object> fileItem = new LinkedHashMap<>();
            fileItem.put("status", status);
            fileItem.put("path", path);
            fileItem.put("oldPath", oldPath);
            fileItem.put("displayPath", oldPath != null ? oldPath + " -> " + path : path);
            changedFileList.add(fileItem);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("aheadCommits", commitList.size());
        summary.put("changedFiles", changedFileList.size());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("projectId", project.id);
        response.put("projectName", project.name);
        response.put("ruleId", rule.id);
        response.put("ruleName", rule.name);
        response.put("sourceBranch", rule.sourceBranch);
        response.put("targetBranch", rule.targetBranch);
        response.put("targetRepoName", rule.targetRepoName);
        response.put("targetRemoteName", remote.name);
        response.put("targetRemoteUrl", targetRemoteUrl(config, rule));
        response.put("compareBase", targetCommit);
        response.put("compareHead", sourceCommit);
        response.put("summary", summary);
        response.put("commits", commitList);
        response.put("files", changedFileList);
        return response;
    }

    Map<String, Object> diffFileSnapshot(AppConfig config, ProjectConfig project, String path, String oldPath,
                                         String compareBase, String compareHead)
        throws IOException, InterruptedException {
        Path repoPath = project.localRepoPath(config);
        Models.require(Files.exists(repoPath), "Local repository does not exist");
        Models.require(isGitRepo(repoPath), "Local path is not a git repository");

        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("diff");
        command.add("--no-color");
        command.add("--find-renames");
        command.add("--unified=3");
        command.add(compareBase);
        command.add(compareHead);
        command.add("--");
        if (oldPath != null && !oldPath.isBlank() && !oldPath.equals(path)) {
            command.add(oldPath);
        }
        command.add(path);

        GitCommandResult patch = runChecked(repoPath, command);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("path", path);
        response.put("oldPath", oldPath);
        response.put("patch", patch.stdout);
        return response;
    }

    SyncResult sync(AppConfig config, ProjectConfig project, RuleConfig rule, boolean forcePush, boolean reviewConfirmed,
                    String triggerSource)
        throws IOException, InterruptedException {
        Models.require(project.enabled, "Project is disabled");
        Models.require(rule.enabled, "Rule is disabled");
        if (forcePush) {
            Models.require(rule.allowForcePush, "Force push is not allowed");
        }
        if ("schedule".equals(triggerSource)) {
            Models.require(!rule.manualOnly, "Manual-only rule cannot be scheduled");
        }
        if (rule.reviewRequired) {
            Models.require(reviewConfirmed, "Review confirmation is required");
        }

        ensureRepoReady(config, project);
        ensureTargetRemote(config, project, rule);
        String internalRemote = internalRemoteName(rule.id);
        Path repoPath = project.localRepoPath(config);

        List<GitCommandResult> results = new ArrayList<>();
        syncVendorBranch(config, project, rule, results);

        List<String> pushCommand = new ArrayList<>();
        pushCommand.add("git");
        pushCommand.add("push");
        if (forcePush) {
            pushCommand.add("-f");
        }
        pushCommand.add(internalRemote);
        pushCommand.add(rule.sourceBranch + ":refs/heads/" + rule.targetBranch);
        results.add(runChecked(repoPath, pushCommand));

        return new SyncResult(results);
    }

    private void syncVendorBranch(AppConfig config, ProjectConfig project, RuleConfig rule, List<GitCommandResult> results)
        throws IOException, InterruptedException {
        Path repoPath = project.localRepoPath(config);
        String originRef = originRef(rule);
        results.add(fetchOrigin(repoPath));
        results.add(runChecked(repoPath, List.of("git", "rev-parse", "--verify", originRef)));
        results.add(runChecked(repoPath, List.of("git", "checkout", "-B", rule.sourceBranch, "origin/" + rule.sourceBranch)));
        results.add(runChecked(repoPath, List.of("git", "reset", "--hard", originRef)));
        results.add(runChecked(repoPath, List.of("git", "pull", "--ff-only", "origin", rule.sourceBranch)));
    }

    private void ensureRepoReady(AppConfig config, ProjectConfig project) throws IOException, InterruptedException {
        Path repoPath = project.localRepoPath(config);
        if (!Files.exists(repoPath)) {
            Files.createDirectories(repoPath.getParent());
            runChecked(null, List.of("git", "clone", project.vendorRepoUrl, repoPath.toString()));
            return;
        }
        Models.require(isGitRepo(repoPath), "Existing path is not a git repository");
        String originUrl = getRemoteUrl(repoPath, "origin");
        Models.require(originUrl == null || originUrl.equals(project.vendorRepoUrl),
            "Existing repository origin does not match vendorRepoUrl");
    }

    private void ensureTargetRemote(AppConfig config, ProjectConfig project, RuleConfig rule) throws IOException, InterruptedException {
        String name = internalRemoteName(rule.id);
        String targetUrl = targetRemoteUrl(config, rule);
        Path repoPath = project.localRepoPath(config);
        String existingUrl = getRemoteUrl(repoPath, name);
        if (existingUrl == null) {
            runChecked(repoPath, List.of("git", "remote", "add", name, targetUrl));
        } else if (!existingUrl.equals(targetUrl)) {
            runChecked(repoPath, List.of("git", "remote", "set-url", name, targetUrl));
        }
    }

    String targetRemoteUrl(AppConfig config, RuleConfig rule) {
        RemoteConfig remote = Models.findRemote(config, rule.targetRemoteId);
        return Models.buildGitUrl(remote.baseUrl, rule.targetRepoName);
    }

    private boolean isGitRepo(Path path) throws IOException, InterruptedException {
        GitCommandResult result = runner.run(path, List.of("git", "rev-parse", "--is-inside-work-tree"));
        return result.isSuccess() && result.stdout.strip().equals("true");
    }

    private boolean branchExists(Path repoPath, String branch) throws IOException, InterruptedException {
        GitCommandResult result = runner.run(repoPath, List.of("git", "rev-parse", "--verify", branch));
        return result.isSuccess();
    }

    private GitCommandResult fetchOrigin(Path repoPath) throws IOException, InterruptedException {
        return runChecked(repoPath, List.of("git", "fetch", "origin", "--prune"));
    }

    private String resolveRevision(Path repoPath, String revision) throws IOException, InterruptedException {
        GitCommandResult result = runChecked(repoPath, List.of("git", "rev-parse", "--verify", revision));
        return result.stdout.strip();
    }

    private String originRef(RuleConfig rule) {
        return "refs/remotes/origin/" + rule.sourceBranch;
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

    private String internalRemoteName(String ruleId) {
        return "sync_target_" + ruleId;
    }

    private String normalizeStatus(String statusCode) {
        if (statusCode == null || statusCode.isBlank()) {
            return "M";
        }
        return String.valueOf(statusCode.charAt(0));
    }

    static final class SyncResult {
        final List<GitCommandResult> commandResults;

        SyncResult(List<GitCommandResult> commandResults) {
            this.commandResults = commandResults;
        }

        String asLogText(String projectId, String ruleId, boolean forcePush, boolean reviewConfirmed, String triggerSource) {
            StringBuilder builder = new StringBuilder();
            builder.append("projectId=").append(projectId).append('\n');
            builder.append("ruleId=").append(ruleId).append('\n');
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

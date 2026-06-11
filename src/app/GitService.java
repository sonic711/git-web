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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class GitService {
    private static final String EMPTY_TREE_HASH = "4b825dc642cb6eb9a060e54bf8d69288fbee4904";
    private final GitCommandRunner runner;

    GitService(GitCommandRunner runner) {
        this.runner = runner;
    }

    Map<String, Object> validate(AppConfig config, ProjectConfig project, RuleConfig rule) throws IOException, InterruptedException {
        List<Object> checks = new ArrayList<>();
        boolean ok = true;
        Path repoPath = project.localRepoPath(config, rule);

        ok &= addCheck(checks, "project_enabled", project.enabled);
        ok &= addCheck(checks, "rule_enabled", rule.enabled);
        ok &= addCheck(checks, "rule_mode_valid", RuleConfig.MODE_SYNC.equals(rule.mode) || RuleConfig.MODE_DOWNLOAD_ONLY.equals(rule.mode));
        boolean repoExists = Files.exists(repoPath);
        ok &= addCheck(checks, "repo_path_exists", repoExists);
        boolean repoReady = repoExists && isGitRepo(repoPath);
        ok &= addCheck(checks, "repo_ready", repoReady || !repoExists);
        if (repoReady) {
            fetchOrigin(repoPath);
            ok &= addCheck(checks, "vendor_branch_exists", branchExists(repoPath, originRef(rule)));
        }
        if (rule.isSyncMode()) {
            RemoteConfig remote = Models.findRemote(config, rule.targetRemoteId);
            ok &= addCheck(checks, "target_remote_template_exists", remote.enabled && remote.baseUrl != null && !remote.baseUrl.isBlank());
            ok &= addCheck(checks, "target_repo_name_valid", rule.targetRepoName != null && rule.targetRepoName.endsWith(".git"));
        } else {
            ok &= addCheck(checks, "download_only_no_target_required", true);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", project.id);
        result.put("ruleId", rule.id);
        result.put("ok", ok);
        result.put("checks", checks);
        return result;
    }

    Map<String, Object> versionCompare(AppConfig config, ProjectConfig project, RuleConfig rule)
        throws IOException, InterruptedException {
        Models.require(rule.isSyncMode(), "Version comparison is only available for sync rules");
        Models.require(project.enabled, "Project is disabled");
        Models.require(rule.enabled, "Rule is disabled");

        ensureRepoReady(config, project, rule);
        ensureTargetRemote(config, project, rule);

        Path repoPath = project.localRepoPath(config, rule);
        String internalRemote = internalRemoteName(rule.id);
        String sourceRef = originRef(rule);
        String targetRef = "refs/remotes/" + internalRemote + "/" + rule.targetBranch;

        runChecked(repoPath, List.of(
            "git", "fetch", "origin",
            "+refs/heads/" + rule.sourceBranch + ":" + sourceRef));

        GitCommandResult targetBranch = runner.run(repoPath,
            List.of("git", "ls-remote", "--exit-code", internalRemote, "refs/heads/" + rule.targetBranch));
        if (targetBranch.exitCode == 2) {
            return versionComparisonResponse(config, project, rule, "TARGET_MISSING",
                null, null, null, null, 0, 0, false, false, "Target branch does not exist");
        }
        if (!targetBranch.isSuccess()) {
            throw gitCommandException(targetBranch);
        }

        runChecked(repoPath, List.of(
            "git", "fetch", internalRemote,
            "+refs/heads/" + rule.targetBranch + ":" + targetRef));

        String sourceCommit = resolveRevision(repoPath, sourceRef);
        String targetCommit = resolveRevision(repoPath, targetRef);
        String sourceTree = resolveRevision(repoPath, sourceRef + "^{tree}");
        String targetTree = resolveRevision(repoPath, targetRef + "^{tree}");
        GitCommandResult commitCounts = runChecked(repoPath,
            List.of("git", "rev-list", "--left-right", "--count", targetRef + "..." + sourceRef));
        int[] counts = parseCommitCounts(commitCounts.stdout);

        boolean commitIdentical = sourceCommit.equals(targetCommit);
        boolean contentIdentical = sourceTree.equals(targetTree);
        String status = commitIdentical && contentIdentical ? "IDENTICAL"
            : contentIdentical ? "CONTENT_IDENTICAL"
            : "DIFFERENT";
        String message = "IDENTICAL".equals(status) ? "Commit and content are identical"
            : "CONTENT_IDENTICAL".equals(status) ? "Content is identical, but commit history differs"
            : "Source and target content differ";

        return versionComparisonResponse(config, project, rule, status,
            sourceCommit, targetCommit, sourceTree, targetTree,
            counts[1], counts[0], commitIdentical, contentIdentical, message);
    }

    Map<String, Object> diff(AppConfig config, ProjectConfig project, RuleConfig rule) throws IOException, InterruptedException {
        Models.require(!rule.isDownloadOnly(), "Diff is not available for download-only rules");
        ensureRepoReady(config, project, rule);
        ensureTargetRemote(config, project, rule);
        RemoteConfig remote = Models.findRemote(config, rule.targetRemoteId);
        String internalRemote = internalRemoteName(rule.id);
        Path repoPath = project.localRepoPath(config, rule);
        fetchOrigin(repoPath);
        runChecked(repoPath, List.of("git", "fetch", internalRemote, rule.targetBranch));

        String sourceRef = originRef(rule);
        String targetRef = internalRemote + "/" + rule.targetBranch;
        boolean targetExists = branchExists(repoPath, targetRef);
        String sourceCommit = resolveRevision(repoPath, sourceRef);
        String targetCommit = targetExists ? resolveRevision(repoPath, targetRef) : EMPTY_TREE_HASH;
        String range = targetExists ? targetRef + ".." + sourceRef : sourceRef;
        GitCommandResult commits = runChecked(repoPath,
            List.of("git", "log", "--reverse", "--format=%H%x1f%h%x1f%an%x1f%aI%x1f%s", "--no-merges", range));
        GitCommandResult files = runChecked(repoPath,
            targetExists
                ? List.of("git", "diff", "--name-status", "--find-renames", targetRef, sourceRef)
                : List.of("git", "diff-tree", "--no-commit-id", "--name-status", "-r", "--find-renames", sourceRef));

        List<Object> commitList = new ArrayList<>();
        String[] commitLines = commits.stdout.strip().isEmpty() ? new String[0] : commits.stdout.strip().split("\\R");
        for (String line : commitLines) {
            Map<String, Object> item = new LinkedHashMap<>();
            String[] parts = line.split("\\u001f", -1);
            item.put("id", parts.length > 0 ? parts[0] : line);
            item.put("shortId", parts.length > 1 ? parts[1] : line);
            item.put("author", parts.length > 2 ? parts[2] : "");
            item.put("committedAt", parts.length > 3 ? parts[3] : "");
            item.put("title", parts.length > 4 ? parts[4] : "");
            item.put("selectable", true);
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
        response.put("allowForcePush", rule.allowForcePush);
        response.put("reviewRequired", rule.reviewRequired);
        response.put("compareBase", targetCommit);
        response.put("compareHead", sourceCommit);
        response.put("summary", summary);
        response.put("commits", commitList);
        response.put("files", changedFileList);
        return response;
    }

    Map<String, Object> commitFiles(AppConfig config, ProjectConfig project, String commitId) throws IOException, InterruptedException {
        Path repoPath = project.localRepoPath(config);
        Models.require(Files.exists(repoPath), "Local repository does not exist");
        Models.require(isGitRepo(repoPath), "Local path is not a git repository");

        GitCommandResult titleResult = runChecked(repoPath, List.of("git", "show", "-s", "--format=%s", commitId));
        GitCommandResult files = runChecked(repoPath,
            List.of("git", "diff-tree", "--root", "--no-commit-id", "--name-status", "-r", "--find-renames", commitId));

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

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("commitId", commitId);
        response.put("title", titleResult.stdout.strip());
        response.put("files", changedFileList);
        return response;
    }

    Map<String, Object> diffFileSnapshot(AppConfig config, ProjectConfig project, String path, String oldPath,
                                         String compareBase, String compareHead)
        throws IOException, InterruptedException {
        Map<String, Object> snapshot = collectDiffFileSnapshot(config, project, path, oldPath, compareBase, compareHead);
        return diffFileSnapshotFromCache(path, oldPath, snapshot);
    }

    Map<String, Object> collectDiffFileSnapshot(AppConfig config, ProjectConfig project, String path, String oldPath,
                                                String compareBase, String compareHead)
        throws IOException, InterruptedException {
        Path repoPath = project.localRepoPath(config);
        Models.require(Files.exists(repoPath), "Local repository does not exist");
        Models.require(isGitRepo(repoPath), "Local path is not a git repository");

        String basePath = oldPath != null && !oldPath.isBlank() ? oldPath : path;
        SnapshotContent baseSnapshot = readRevisionFile(repoPath, compareBase, basePath);
        SnapshotContent headSnapshot = readRevisionFile(repoPath, compareHead, path);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("path", path);
        response.put("oldPath", oldPath);
        response.put("baseExists", baseSnapshot.exists);
        response.put("baseContent", baseSnapshot.content);
        response.put("headExists", headSnapshot.exists);
        response.put("headContent", headSnapshot.content);
        return response;
    }

    Map<String, Object> diffFileSnapshotFromCache(String path, String oldPath, Map<String, Object> snapshot) {
        String basePath = oldPath != null && !oldPath.isBlank() ? oldPath : path;
        SnapshotContent baseSnapshot = new SnapshotContent(
            Models.booleanValue(snapshot.get("baseExists")),
            Models.nullableString(snapshot.get("baseContent")) == null ? "" : Models.nullableString(snapshot.get("baseContent")));
        SnapshotContent headSnapshot = new SnapshotContent(
            Models.booleanValue(snapshot.get("headExists")),
            Models.nullableString(snapshot.get("headContent")) == null ? "" : Models.nullableString(snapshot.get("headContent")));
        String patch = buildUnifiedDiff(basePath, path, baseSnapshot, headSnapshot);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("path", path);
        response.put("oldPath", oldPath);
        response.put("patch", patch);
        return response;
    }

    SyncResult sync(AppConfig config, ProjectConfig project, RuleConfig rule, boolean forcePush, boolean reviewConfirmed,
                    List<String> selectedCommitIds, String triggerSource)
        throws IOException, InterruptedException {
        Models.require(project.enabled, "Project is disabled");
        Models.require(rule.enabled, "Rule is disabled");
        boolean downloadOnly = rule.isDownloadOnly();
        if (!downloadOnly && forcePush) {
            Models.require(rule.allowForcePush, "Force push is not allowed");
        }
        if ("schedule".equals(triggerSource)) {
            Models.require(!rule.manualOnly, "Manual-only rule cannot be scheduled");
        }
        if (!downloadOnly && rule.reviewRequired) {
            Models.require(reviewConfirmed, "Review confirmation is required");
        }
        List<String> requestedCommitIds = selectedCommitIds == null ? List.of() : selectedCommitIds;
        if (!downloadOnly && rule.reviewRequired) {
            Models.require(!requestedCommitIds.isEmpty(), "At least one commit must be selected for review-required sync");
        }

        ensureRepoReady(config, project, rule);
        Path repoPath = project.localRepoPath(config, rule);

        List<GitCommandResult> results = new ArrayList<>();
        syncVendorBranch(config, project, rule, !downloadOnly && forcePush, downloadOnly, results);
        if (downloadOnly) {
            return new SyncResult(results, List.of());
        }

        ensureTargetRemote(config, project, rule);
        String internalRemote = internalRemoteName(rule.id);
        if (!requestedCommitIds.isEmpty()) {
            syncSelectedCommits(repoPath, rule, internalRemote, forcePush, requestedCommitIds, results);
        } else {
            List<String> pushCommand = new ArrayList<>();
            pushCommand.add("git");
            pushCommand.add("push");
            if (forcePush) {
                pushCommand.add("-f");
            }
            pushCommand.add(internalRemote);
            pushCommand.add(rule.sourceBranch + ":refs/heads/" + rule.targetBranch);
            results.add(runChecked(repoPath, pushCommand));
        }
        pushTags(repoPath, internalRemote, forcePush, results);

        return new SyncResult(results, requestedCommitIds);
    }

    private void syncVendorBranch(AppConfig config, ProjectConfig project, RuleConfig rule, boolean forcePush, boolean exactTags,
                                  List<GitCommandResult> results)
        throws IOException, InterruptedException {
        Path repoPath = project.localRepoPath(config, rule);
        String originRef = originRef(rule);
        results.add(fetchOrigin(repoPath, forcePush || exactTags, exactTags));
        results.add(runChecked(repoPath, List.of("git", "rev-parse", "--verify", originRef)));
        results.add(runChecked(repoPath, List.of("git", "checkout", "-B", rule.sourceBranch, "origin/" + rule.sourceBranch)));
        results.add(runChecked(repoPath, List.of("git", "reset", "--hard", originRef)));
        results.add(runChecked(repoPath, List.of("git", "pull", "--ff-only", "origin", rule.sourceBranch)));
    }

    private void syncSelectedCommits(Path repoPath, RuleConfig rule, String internalRemote, boolean forcePush,
                                     List<String> selectedCommitIds, List<GitCommandResult> results)
        throws IOException, InterruptedException {
        results.add(runChecked(repoPath, List.of("git", "fetch", internalRemote, "--prune")));
        String targetRef = internalRemote + "/" + rule.targetBranch;
        Models.require(branchExists(repoPath, targetRef), "Commit-based sync requires an existing target branch");

        List<String> orderedSelectedCommitIds = orderSelectedCommitIds(repoPath, targetRef, rule.sourceBranch, selectedCommitIds);
        String tempBranch = "sync_review_" + rule.id + "_" + System.currentTimeMillis();
        results.add(runChecked(repoPath, List.of("git", "checkout", "-B", tempBranch, targetRef)));

        boolean cherryPickInProgress = false;
        try {
            for (String commitId : orderedSelectedCommitIds) {
                cherryPickInProgress = true;
                results.add(runChecked(repoPath, List.of("git", "cherry-pick", commitId)));
                cherryPickInProgress = false;
            }

            List<String> pushCommand = new ArrayList<>();
            pushCommand.add("git");
            pushCommand.add("push");
            if (forcePush) {
                pushCommand.add("-f");
            }
            pushCommand.add(internalRemote);
            pushCommand.add(tempBranch + ":refs/heads/" + rule.targetBranch);
            results.add(runChecked(repoPath, pushCommand));
        } catch (IOException | InterruptedException exception) {
            if (cherryPickInProgress) {
                runner.run(repoPath, List.of("git", "cherry-pick", "--abort"));
            }
            throw exception;
        } finally {
            runner.run(repoPath, List.of("git", "checkout", rule.sourceBranch));
            runner.run(repoPath, List.of("git", "branch", "-D", tempBranch));
        }
    }

    private void pushTags(Path repoPath, String internalRemote, boolean forcePush, List<GitCommandResult> results)
        throws IOException, InterruptedException {
        List<String> pushTagsCommand = new ArrayList<>();
        pushTagsCommand.add("git");
        pushTagsCommand.add("push");
        if (forcePush) {
            pushTagsCommand.add("-f");
        }
        pushTagsCommand.add(internalRemote);
        pushTagsCommand.add("--tags");
        results.add(runChecked(repoPath, pushTagsCommand));
    }

    private void ensureRepoReady(AppConfig config, ProjectConfig project, RuleConfig rule) throws IOException, InterruptedException {
        Path repoPath = project.localRepoPath(config, rule);
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
        Path repoPath = project.localRepoPath(config, rule);
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
        return fetchOrigin(repoPath, false, false);
    }

    private GitCommandResult fetchOrigin(Path repoPath, boolean forceTags) throws IOException, InterruptedException {
        return fetchOrigin(repoPath, forceTags, false);
    }

    private GitCommandResult fetchOrigin(Path repoPath, boolean forceTags, boolean pruneTags) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("fetch");
        command.add("origin");
        command.add("--prune");
        command.add("--tags");
        if (forceTags) {
            command.add("--force");
        }
        if (pruneTags) {
            command.add("--prune-tags");
        }
        return runChecked(repoPath, command);
    }

    private String resolveRevision(Path repoPath, String revision) throws IOException, InterruptedException {
        GitCommandResult result = runChecked(repoPath, List.of("git", "rev-parse", "--verify", revision));
        return result.stdout.strip();
    }

    private List<String> orderSelectedCommitIds(Path repoPath, String targetRef, String sourceBranch, List<String> selectedCommitIds)
        throws IOException, InterruptedException {
        GitCommandResult history = runChecked(repoPath,
            List.of("git", "rev-list", "--reverse", "--no-merges", targetRef + ".." + sourceBranch));
        List<String> sourceOrder = history.stdout.strip().isEmpty()
            ? List.of()
            : List.of(history.stdout.strip().split("\\R"));

        Set<String> selected = new LinkedHashSet<>();
        for (String commitId : selectedCommitIds) {
            selected.add(resolveRevision(repoPath, commitId));
        }
        Models.require(!selected.isEmpty(), "At least one commit must be selected");

        List<String> ordered = new ArrayList<>();
        for (String commitId : sourceOrder) {
            if (selected.contains(commitId)) {
                ordered.add(commitId);
            }
        }
        Models.require(ordered.size() == selected.size(), "Selected commits must belong to the current source branch diff range");
        return ordered;
    }

    private SnapshotContent readRevisionFile(Path repoPath, String revision, String path) throws IOException, InterruptedException {
        GitCommandResult exists = runner.run(repoPath, List.of("git", "cat-file", "-e", revision + ":" + path));
        if (!exists.isSuccess()) {
            return new SnapshotContent(false, "");
        }
        GitCommandResult content = runChecked(repoPath, List.of("git", "show", revision + ":" + path));
        return new SnapshotContent(true, content.stdout);
    }

    private String buildUnifiedDiff(String basePath, String headPath, SnapshotContent baseSnapshot, SnapshotContent headSnapshot) {
        List<String> baseLines = splitLines(baseSnapshot.content);
        List<String> headLines = splitLines(headSnapshot.content);
        List<DiffOp> ops = buildDiffOps(baseLines, headLines);

        StringBuilder builder = new StringBuilder();
        builder.append("diff --git a/")
            .append(baseSnapshot.exists ? basePath : headPath)
            .append(" b/")
            .append(headSnapshot.exists ? headPath : basePath)
            .append('\n');
        builder.append("--- ")
            .append(baseSnapshot.exists ? "a/" + basePath : "/dev/null")
            .append('\n');
        builder.append("+++ ")
            .append(headSnapshot.exists ? "b/" + headPath : "/dev/null")
            .append('\n');
        builder.append("@@\n");
        for (DiffOp op : ops) {
            builder.append(op.prefix).append(op.line).append('\n');
        }
        return builder.toString();
    }

    private List<DiffOp> buildDiffOps(List<String> baseLines, List<String> headLines) {
        int baseSize = baseLines.size();
        int headSize = headLines.size();
        int[][] lcs = new int[baseSize + 1][headSize + 1];
        for (int i = baseSize - 1; i >= 0; i--) {
            for (int j = headSize - 1; j >= 0; j--) {
                if (baseLines.get(i).equals(headLines.get(j))) {
                    lcs[i][j] = lcs[i + 1][j + 1] + 1;
                } else {
                    lcs[i][j] = Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
        }

        List<DiffOp> ops = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < baseSize && j < headSize) {
            if (baseLines.get(i).equals(headLines.get(j))) {
                ops.add(new DiffOp(' ', baseLines.get(i)));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                ops.add(new DiffOp('-', baseLines.get(i)));
                i++;
            } else {
                ops.add(new DiffOp('+', headLines.get(j)));
                j++;
            }
        }
        while (i < baseSize) {
            ops.add(new DiffOp('-', baseLines.get(i++)));
        }
        while (j < headSize) {
            ops.add(new DiffOp('+', headLines.get(j++)));
        }
        if (ops.isEmpty()) {
            return Collections.singletonList(new DiffOp(' ', "(empty diff)"));
        }
        return ops;
    }

    private List<String> splitLines(String content) {
        if (content == null || content.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(List.of(content.replace("\r", "").split("\n", -1)));
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
            throw gitCommandException(result);
        }
        return result;
    }

    private IOException gitCommandException(GitCommandResult result) {
        return new IOException("Git command failed: " + String.join(" ", result.command) + "\n" + result.stderr);
    }

    private int[] parseCommitCounts(String output) {
        String[] parts = output.strip().split("\\s+");
        Models.require(parts.length == 2, "Unexpected git rev-list count output: " + output);
        return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
    }

    private Map<String, Object> versionComparisonResponse(AppConfig config, ProjectConfig project, RuleConfig rule,
                                                          String status, String sourceCommit, String targetCommit,
                                                          String sourceTree, String targetTree, int sourceOnlyCommits,
                                                          int targetOnlyCommits, boolean commitIdentical,
                                                          boolean contentIdentical, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("projectId", project.id);
        response.put("projectName", project.name);
        response.put("ruleId", rule.id);
        response.put("ruleName", rule.name);
        response.put("status", status);
        response.put("checkedAt", Models.nowIso());
        response.put("sourceBranch", rule.sourceBranch);
        response.put("targetBranch", rule.targetBranch);
        response.put("targetRemoteId", rule.targetRemoteId);
        response.put("targetRemoteUrl", targetRemoteUrl(config, rule));
        response.put("sourceCommit", sourceCommit);
        response.put("targetCommit", targetCommit);
        response.put("sourceTree", sourceTree);
        response.put("targetTree", targetTree);
        response.put("sourceOnlyCommits", sourceOnlyCommits);
        response.put("targetOnlyCommits", targetOnlyCommits);
        response.put("commitIdentical", commitIdentical);
        response.put("contentIdentical", contentIdentical);
        response.put("message", message);
        return response;
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

    private record SnapshotContent(boolean exists, String content) {
    }

    private record DiffOp(char prefix, String line) {
    }

    static final class SyncResult {
        final List<GitCommandResult> commandResults;
        final List<String> selectedCommitIds;

        SyncResult(List<GitCommandResult> commandResults, List<String> selectedCommitIds) {
            this.commandResults = commandResults;
            this.selectedCommitIds = selectedCommitIds;
        }

        String asLogText(String projectId, String ruleId, boolean forcePush, boolean reviewConfirmed, String triggerSource) {
            StringBuilder builder = new StringBuilder();
            builder.append("projectId=").append(projectId).append('\n');
            builder.append("ruleId=").append(ruleId).append('\n');
            builder.append("triggerSource=").append(triggerSource).append('\n');
            builder.append("forcePush=").append(forcePush).append('\n');
            builder.append("reviewConfirmed=").append(reviewConfirmed).append('\n');
            builder.append("selectedCommitIds=").append(selectedCommitIds).append('\n');
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

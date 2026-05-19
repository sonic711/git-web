package app;

import app.Models.ProjectConfig;
import app.Models.RuleConfig;
import app.Models.SyncJob;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class SyncJobService {
    private final Map<String, SyncJob> jobs = new ConcurrentHashMap<>();
    private final Map<String, String> activeManualJobsByRule = new ConcurrentHashMap<>();

    SyncJob createManualJob(ProjectConfig project, RuleConfig rule, boolean forcePush, boolean reviewConfirmed,
                            List<String> selectedCommitIds) {
        if (hasActiveManualJob(rule.id)) {
            throw new IllegalStateException("This rule already has an active manual sync job");
        }
        SyncJob job = new SyncJob();
        job.jobId = createJobId(rule.id);
        job.ruleId = rule.id;
        job.ruleName = rule.name;
        job.projectId = project.id;
        job.projectName = project.name;
        job.triggerSource = "manual";
        job.status = "queued";
        job.forcePush = forcePush;
        job.reviewConfirmed = reviewConfirmed;
        job.selectedCommitIds = new ArrayList<>(selectedCommitIds);
        job.queuedAt = Models.nowIso();
        job.message = "Sync job queued";
        jobs.put(job.jobId, job);
        activeManualJobsByRule.put(rule.id, job.jobId);
        return job;
    }

    SyncJob get(String jobId) {
        return jobs.get(jobId);
    }

    SyncJob getActiveManualJobForRule(String ruleId) {
        String jobId = activeManualJobsByRule.get(ruleId);
        return jobId == null ? null : jobs.get(jobId);
    }

    boolean hasActiveManualJob(String ruleId) {
        SyncJob job = getActiveManualJobForRule(ruleId);
        return job != null && ("queued".equals(job.status) || "running".equals(job.status));
    }

    void markRunning(String jobId) {
        SyncJob job = requireJob(jobId);
        job.status = "running";
        job.startedAt = Models.nowIso();
        job.message = "Sync running";
    }

    void markSuccess(String jobId, String logPath) {
        SyncJob job = requireJob(jobId);
        job.status = "success";
        job.finishedAt = Models.nowIso();
        job.logPath = logPath;
        job.message = "Sync completed";
        clearActiveManualJob(job);
    }

    void markFailed(String jobId, String message, String logPath) {
        SyncJob job = requireJob(jobId);
        job.status = "failed";
        job.finishedAt = Models.nowIso();
        job.logPath = logPath;
        job.message = message;
        clearActiveManualJob(job);
    }

    private void clearActiveManualJob(SyncJob job) {
        activeManualJobsByRule.remove(job.ruleId, job.jobId);
    }

    private SyncJob requireJob(String jobId) {
        SyncJob job = jobs.get(jobId);
        if (job == null) {
            throw new IllegalArgumentException("Sync job not found: " + jobId);
        }
        return job;
    }

    private String createJobId(String ruleId) {
        String timestamp = OffsetDateTime.now(ZoneOffset.ofHours(8))
            .format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS"));
        return "job-" + timestamp + "-" + Models.slugify(ruleId);
    }
}

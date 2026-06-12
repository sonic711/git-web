package app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class BatchVersionComparisonService {
    private final Map<String, BatchJob> jobs = new ConcurrentHashMap<>();

    BatchJob create(String sourceBranch, String targetRemoteId, String targetRemoteName, String targetBranch,
                    List<String> ruleIds) {
        BatchJob job = new BatchJob();
        job.jobId = createJobId();
        job.status = "queued";
        job.sourceBranch = sourceBranch;
        job.targetRemoteId = targetRemoteId;
        job.targetRemoteName = targetRemoteName;
        job.targetBranch = targetBranch;
        job.ruleIds = new ArrayList<>(ruleIds);
        job.total = ruleIds.size();
        job.queuedAt = Models.nowIso();
        jobs.put(job.jobId, job);
        return job;
    }

    BatchJob get(String jobId) {
        return jobs.get(jobId);
    }

    private String createJobId() {
        return "version-job-" + UUID.randomUUID();
    }

    static final class BatchJob {
        private String jobId;
        private String status;
        private String sourceBranch;
        private String targetRemoteId;
        private String targetRemoteName;
        private String targetBranch;
        private List<String> ruleIds = new ArrayList<>();
        private int total;
        private int completed;
        private String queuedAt;
        private String startedAt;
        private String finishedAt;
        private final List<Map<String, Object>> results = new ArrayList<>();

        synchronized void markRunning() {
            status = "running";
            if (startedAt == null) {
                startedAt = Models.nowIso();
            }
            finishedAt = null;
        }

        synchronized void addResult(Map<String, Object> result) {
            results.add(new LinkedHashMap<>(result));
            completed = results.size();
        }

        synchronized void replaceResult(String ruleId, Map<String, Object> result) {
            results.removeIf(item -> ruleId.equals(Models.nullableString(item.get("ruleId"))));
            results.add(new LinkedHashMap<>(result));
            completed = results.size();
        }

        synchronized void markCompleted() {
            status = "completed";
            completed = results.size();
            finishedAt = Models.nowIso();
        }

        synchronized boolean containsRule(String ruleId) {
            return ruleIds.contains(ruleId);
        }

        synchronized boolean isCompleted() {
            return "completed".equals(status);
        }

        synchronized List<String> ruleIds() {
            return new ArrayList<>(ruleIds);
        }

        synchronized Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("jobId", jobId);
            map.put("status", status);
            map.put("sourceBranch", sourceBranch);
            map.put("targetRemoteId", targetRemoteId);
            map.put("targetRemoteName", targetRemoteName);
            map.put("targetBranch", targetBranch);
            map.put("total", total);
            map.put("completed", completed);
            map.put("queuedAt", queuedAt);
            map.put("startedAt", startedAt);
            map.put("finishedAt", finishedAt);
            List<Object> resultCopies = new ArrayList<>();
            for (Map<String, Object> result : results) {
                resultCopies.add(new LinkedHashMap<>(result));
            }
            map.put("results", resultCopies);
            return map;
        }
    }
}

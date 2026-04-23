package app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.stream.Stream;

final class LogService {
    private final Path logsDir;
    private static final ZoneOffset ZONE_OFFSET = ZoneOffset.ofHours(8);
    private static final DateTimeFormatter RUN_ID_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final DateTimeFormatter DAILY_LOG_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    LogService(Path logsDir) throws IOException {
        this.logsDir = logsDir;
        Files.createDirectories(logsDir);
        purgeExpiredLogs();
    }

    String createRunId(String mappingId) {
        String timestamp = OffsetDateTime.now(ZONE_OFFSET).format(RUN_ID_FORMATTER);
        return timestamp + "-" + mappingId;
    }

    String writeLog(String runId, String content) throws IOException {
        purgeExpiredLogs();
        Path path = dailyLogPath();
        StringBuilder builder = new StringBuilder();
        builder.append("\n===== ").append(runId).append(" =====\n");
        builder.append(content);
        if (!content.endsWith("\n")) {
            builder.append('\n');
        }
        Files.writeString(path, builder.toString(), StandardCharsets.UTF_8,
            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        return path.getFileName().toString();
    }

    String readLog(String logId) throws IOException {
        Path path = resolveLogPath(logId);
        if (!Files.exists(path)) {
            return "";
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private void purgeExpiredLogs() throws IOException {
        String todayFileName = dailyLogFileName();
        try (Stream<Path> stream = Files.list(logsDir)) {
            stream
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".log"))
                .filter(path -> !path.getFileName().toString().equals(todayFileName))
                .forEach(this::deleteQuietly);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private Path dailyLogPath() {
        return logsDir.resolve(dailyLogFileName());
    }

    private String dailyLogFileName() {
        LocalDate today = OffsetDateTime.now(ZONE_OFFSET).toLocalDate();
        return today.format(DAILY_LOG_FORMATTER) + ".log";
    }

    private Path resolveLogPath(String logId) {
        String safeName = Path.of(logId).getFileName().toString();
        if (!safeName.endsWith(".log")) {
            safeName = safeName + ".log";
        }
        return logsDir.resolve(safeName).normalize();
    }
}

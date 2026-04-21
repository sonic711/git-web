package app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.stream.Stream;

final class LogService {
    private final Path logsDir;

    LogService(Path logsDir) throws IOException {
        this.logsDir = logsDir;
        Files.createDirectories(logsDir);
        purgeExpiredLogs();
    }

    String createRunId(String mappingId) {
        String timestamp = OffsetDateTime.now(ZoneOffset.ofHours(8))
            .format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"));
        return timestamp + "-" + mappingId;
    }

    String writeLog(String runId, String content) throws IOException {
        purgeExpiredLogs();
        Path path = logsDir.resolve(runId + ".log");
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return logsDir.relativize(path).toString().replace('\\', '/');
    }

    String readLog(String runId) throws IOException {
        Path path = logsDir.resolve(runId + ".log");
        if (!Files.exists(path)) {
            return "";
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private void purgeExpiredLogs() throws IOException {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.ofHours(8)).minus(1, ChronoUnit.DAYS);
        try (Stream<Path> stream = Files.list(logsDir)) {
            stream
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".log"))
                .forEach(path -> deleteIfExpired(path, cutoff));
        }
    }

    private void deleteIfExpired(Path path, OffsetDateTime cutoff) {
        try {
            FileTime lastModified = Files.getLastModifiedTime(path);
            OffsetDateTime fileTime = lastModified.toInstant().atOffset(ZoneOffset.ofHours(8));
            if (fileTime.isBefore(cutoff)) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
        }
    }
}

package app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

final class LogService {
    private final Path logsDir;

    LogService(Path logsDir) throws IOException {
        this.logsDir = logsDir;
        Files.createDirectories(logsDir);
    }

    String createRunId(String mappingId) {
        String timestamp = OffsetDateTime.now(ZoneOffset.ofHours(8))
            .format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"));
        return timestamp + "-" + mappingId;
    }

    String writeLog(String runId, String content) throws IOException {
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
}

package app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class GitCommandRunner {
    GitCommandResult run(Path workDir, List<String> command) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workDir != null) {
            builder.directory(workDir.toFile());
        }
        Process process = builder.start();
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Command timed out: " + String.join(" ", command));
        }
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new GitCommandResult(command, process.exitValue(), stdout, stderr);
    }

    static final class GitCommandResult {
        final List<String> command;
        final int exitCode;
        final String stdout;
        final String stderr;

        GitCommandResult(List<String> command, int exitCode, String stdout, String stderr) {
            this.command = command;
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        boolean isSuccess() {
            return exitCode == 0;
        }
    }
}

package app;

import java.io.IOException;
import java.io.InputStream;
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
        StreamCollector stdoutCollector = new StreamCollector(process.getInputStream());
        StreamCollector stderrCollector = new StreamCollector(process.getErrorStream());
        Thread stdoutThread = collectorThread(stdoutCollector, "git-command-stdout");
        Thread stderrThread = collectorThread(stderrCollector, "git-command-stderr");
        stdoutThread.start();
        stderrThread.start();
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            stdoutThread.join(5_000);
            stderrThread.join(5_000);
            throw new IOException("Command timed out: " + String.join(" ", command));
        }
        stdoutThread.join();
        stderrThread.join();
        stdoutCollector.throwIfFailed();
        stderrCollector.throwIfFailed();
        String stdout = new String(stdoutCollector.bytes, StandardCharsets.UTF_8);
        String stderr = new String(stderrCollector.bytes, StandardCharsets.UTF_8);
        return new GitCommandResult(command, process.exitValue(), stdout, stderr);
    }

    private Thread collectorThread(StreamCollector collector, String name) {
        Thread thread = new Thread(collector, name);
        thread.setDaemon(true);
        return thread;
    }

    private static final class StreamCollector implements Runnable {
        private final InputStream stream;
        private byte[] bytes = new byte[0];
        private IOException failure;

        private StreamCollector(InputStream stream) {
            this.stream = stream;
        }

        @Override
        public void run() {
            try {
                bytes = stream.readAllBytes();
            } catch (IOException exception) {
                failure = exception;
            }
        }

        private void throwIfFailed() throws IOException {
            if (failure != null) {
                throw failure;
            }
        }
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

package app;

import java.nio.file.Path;

public final class App {
    private static final int DEFAULT_PORT = 8080;

    private App() {
    }

    public static void main(String[] args) throws Exception {
        int port = parsePort(args);
        Path root = Path.of("").toAbsolutePath();
        ConfigService configService = new ConfigService(root.resolve("config/settings.json"));
        RuntimeStateService runtimeStateService = new RuntimeStateService(root.resolve("state/runtime-state.json"));
        GitCommandRunner gitCommandRunner = new GitCommandRunner();
        GitService gitService = new GitService(gitCommandRunner);
        LogService logService = new LogService(root.resolve("logs"));
        DiffCacheService diffCacheService = new DiffCacheService(root.resolve("cache/diff"));
        AppServer appServer = new AppServer(configService, runtimeStateService, gitService, logService, diffCacheService,
            root.resolve("static"));
        appServer.start(port);
        System.out.println("Server started at http://127.0.0.1:" + port);
    }

    private static int parsePort(String[] args) {
        if (args == null || args.length == 0) {
            return DEFAULT_PORT;
        }
        String raw = args[0];
        if (raw == null || raw.isBlank()) {
            return DEFAULT_PORT;
        }
        String value = raw.startsWith("--port=") ? raw.substring("--port=".length()) : raw;
        int port = Integer.parseInt(value);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        return port;
    }
}

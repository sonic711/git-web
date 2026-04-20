package app;

import java.nio.file.Path;

public final class App {
    private App() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        ConfigService configService = new ConfigService(root.resolve("config/settings.json"));
        RuntimeStateService runtimeStateService = new RuntimeStateService(root.resolve("state/runtime-state.json"));
        GitCommandRunner gitCommandRunner = new GitCommandRunner();
        GitService gitService = new GitService(gitCommandRunner);
        LogService logService = new LogService(root.resolve("logs"));
        AppServer appServer = new AppServer(configService, runtimeStateService, gitService, logService, root.resolve("static"));
        appServer.start(8080);
        System.out.println("Server started at http://127.0.0.1:8080");
    }
}

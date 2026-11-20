package app;

import app.Models.AppConfig;
import app.Models.ProjectConfig;
import app.Models.RemoteConfig;
import app.Models.RuleConfig;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class TargetProtocolTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("git-web-target-protocol-");
        String ssh = "ssh://git@ssh.example.invalid:222/team/";
        String https = "https://web.example.invalid:8443/team/";
        RemoteConfig legacy = RemoteConfig.fromMap(Map.of("id", "target", "baseUrl", ssh));
        check(ssh.equals(legacy.baseUrlFor("default")), "Legacy default URL changed");
        check(ssh.equals(legacy.baseUrlFor("ssh")), "Legacy SSH URL not inferred");
        check(legacy.baseUrlFor("https") == null, "HTTPS URL must not be guessed");
        RemoteConfig httpsOnly = RemoteConfig.fromMap(Map.of("id", "target", "httpsBaseUrl", https));
        check(https.equals(httpsOnly.baseUrl), "HTTPS-only template needs a default URL");
        check(httpsOnly.baseUrlFor("ssh") == null, "SSH URL must not be guessed");

        AppConfig config = AppConfig.fromMap(Map.of("localWorkspaceRoot", root.toString(),
            "remotes", List.of(Map.of("id", "target", "baseUrl", ssh, "httpsBaseUrl", https)),
            "projects", List.of(Map.of("id", "project", "name", "Project", "vendorRepoUrl", "https://vendor.example.invalid/project.git",
                "localProjectName", "project", "rules", List.of(Map.of("id", "rule", "sourceBranch", "sit",
                    "targetRemoteId", "target", "targetRepoName", "project.git", "targetBranch", "sit"))))));
        ConfigService store = new ConfigService(root.resolve("config/settings.json"));
        store.replaceConfig(config);
        ProjectConfig project = store.getConfig().projects.get(0);
        RuleConfig rule = project.rules.get(0);
        GitCommandRunner runner = new GitCommandRunner();
        GitService service = new GitService(runner);
        check((ssh + "project.git").equals(service.targetRemoteUrl(config, rule)), "Legacy rule default changed");
        rule.targetProtocol = "https";
        store.replaceConfig(config);
        ConfigService reloaded = new ConfigService(root.resolve("config/settings.json"));
        rule = reloaded.getConfig().projects.get(0).rules.get(0);
        check("https".equals(rule.targetProtocol), "Protocol not persisted");
        check((https + "project.git").equals(service.targetRemoteUrl(reloaded.getConfig(), rule)), "Wrong HTTPS host or port");
        Map<String, Object> exported = reloaded.exportConfigMap();
        reloaded.importConfig(exported);
        check("https".equals(reloaded.getConfig().projects.get(0).rules.get(0).targetProtocol), "Protocol lost on import");
        check(https.equals(reloaded.getConfig().remotes.get(0).httpsBaseUrl), "HTTPS URL lost on import");
        String before = Json.stringify(reloaded.configAsMap());
        try {
            reloaded.upsertRemote(legacy);
            throw new AssertionError("Removing an in-use HTTPS URL must be rejected");
        } catch (IllegalArgumentException expected) {
            check(before.equals(Json.stringify(reloaded.configAsMap())), "Rejected remote edit corrupted live config");
        }
        RuleConfig invalidEdit = RuleConfig.fromMap(reloaded.getConfig().projects.get(0).rules.get(0).toMap());
        invalidEdit.targetProtocol = "invalid";
        try {
            reloaded.upsertRule("project", invalidEdit);
            throw new AssertionError("Invalid rule protocol must be rejected");
        } catch (IllegalArgumentException expected) {
            check(before.equals(Json.stringify(reloaded.configAsMap())), "Rejected rule edit corrupted live config");
        }

        // Only modify a temporary local repository; never connect to either URL.
        Path repo = root.resolve("project");
        Files.createDirectories(repo);
        check(runner.run(repo, List.of("git", "init")).isSuccess(), "Temporary repo init failed");
        Method updateRemote = GitService.class.getDeclaredMethod("ensureTargetRemote", AppConfig.class, ProjectConfig.class, RuleConfig.class);
        updateRemote.setAccessible(true);
        config = reloaded.getConfig();
        project = config.projects.get(0);
        rule = project.rules.get(0);
        updateRemote.invoke(service, config, project, rule);
        check((https + "project.git").equals(runner.run(repo, List.of("git", "remote", "get-url", "sync_target_rule")).stdout.strip()), "Git remote not using HTTPS");
        rule.targetProtocol = "ssh";
        updateRemote.invoke(service, config, project, rule);
        check((ssh + "project.git").equals(runner.run(repo, List.of("git", "remote", "get-url", "sync_target_rule")).stdout.strip()), "Git remote did not switch back to SSH");
        rule.targetProtocol = "invalid";
        expectInvalid(store, config);
        rule.targetProtocol = "https";
        config.remotes.set(0, legacy);
        expectInvalid(store, config);
        System.out.println("Target protocol tests passed: legacy, HTTPS-only, persistence/import/export, endpoint validation, local Git remote switching.");
    }

    private static void expectInvalid(ConfigService store, AppConfig config) throws Exception {
        try { store.replaceConfig(config); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected invalid target protocol or missing endpoint to be rejected");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

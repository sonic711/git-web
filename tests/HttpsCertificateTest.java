package app;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

public final class HttpsCertificateTest {
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("git-web-https-test-");
        Path store = root.resolve("server.p12");
        Path pemPath = root.resolve("server.pem");
        GitCommandRunner raw = new GitCommandRunner();
        String keytool = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool").toString();
        check(raw.run(root, List.of(keytool, "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "2", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-ext", "bc=ca:true",
            "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", "changeit", "-noprompt")).isSuccess(), "keytool generation failed");
        check(raw.run(root, List.of(keytool, "-exportcert", "-rfc", "-alias", "server", "-keystore", store.toString(),
            "-storepass", "changeit", "-file", pemPath.toString())).isSuccess(), "keytool export failed");
        byte[] pem = Files.readAllBytes(pemPath);
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) { keys.load(input, "changeit".toCharArray()); }
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, "changeit".toCharArray());
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(managers.getKeyManagers(), null, null);
        HttpsServer https = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(tls));
        https.createContext("/", exchange -> {
            if (exchange.getRequestURI().getPath().startsWith("/redirect/")) {
                exchange.getResponseHeaders().set("Location", "/other/repo.git/info/refs");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            String data = exchange.getRequestURI().getPath().endsWith("/HEAD") ? "ref: refs/heads/main\n"
                : "0123456789012345678901234567890123456789\trefs/heads/main\n";
            byte[] bytes = data.getBytes(StandardCharsets.US_ASCII);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        https.start();
        ConfigService config = new ConfigService(root.resolve("config/settings.json"));
        GitCommandRunner managed = new GitCommandRunner();
        AppServer app = new AppServer(config, new RuntimeStateService(root.resolve("state/runtime.json")),
            new GitService(managed), new LogService(root.resolve("logs")), new DiffCacheService(root.resolve("cache")), root.resolve("static"));
        java.util.concurrent.ExecutorService httpWorkers = null;
        try {
            app.start(0);
            var serverField = AppServer.class.getDeclaredField("server");
            serverField.setAccessible(true);
            int port = ((HttpServer) serverField.get(app)).getAddress().getPort();
            httpWorkers = (java.util.concurrent.ExecutorService) ((HttpServer) serverField.get(app)).getExecutor();
            String api = "http://127.0.0.1:" + port + "/api/system/certificates";
            String host = "https://localhost:" + https.getAddress().getPort();
            String url = host + "/allowed/repo.git";
            check(!raw.run(root, List.of("git", "ls-remote", url)).isSuccess(), "Untrusted TLS unexpectedly succeeded");
            Map<String, Object> upload = Map.of("name", "Local test CA", "urlPrefix", host + "/allowed/", "contentBase64", Base64.getEncoder().encodeToString(pem));
            var imported = request(api, "POST", upload);
            check(imported.statusCode() == 201, "Certificate import failed: " + imported.body());
            String id = Models.stringValue(Json.asObject(Json.parse(imported.body())).get("id"));
            check(!Json.asObject(Json.parse(imported.body())).containsKey("pem"), "Metadata API should not return PEM");
            var trusted = managed.run(root, List.of("git", "ls-remote", url));
            check(trusted.isSuccess(), "Scoped trust did not allow Git HTTPS: " + trusted.stderr);
            check(trusted.stdout.contains("refs/heads/main"), "Git did not read refs");
            check(!managed.run(root, List.of("git", "ls-remote", host + "/other/repo.git")).isSuccess(), "Trust leaked outside URL path prefix");
            check(raw.run(root, List.of("git", "config", "--get-urlmatch", "http.sslCAInfo", url)).stdout.isBlank(), "Trust changed persistent Git config");

            Map<String, Object> exported = config.exportConfigMap();
            check(Json.asList(exported.get("trustedCertificates")).size() == 1, "Export lost certificate");
            config.importConfig(exported);
            ConfigService reloaded = new ConfigService(root.resolve("config/settings.json"));
            GitCommandRunner restarted = new GitCommandRunner();
            restarted.setHttpsTrustService(new HttpsTrustService(reloaded));
            check(restarted.run(root, List.of("git", "ls-remote", url)).isSuccess(), "Trust lost after config import/restart");
            var invalid = request(api, "POST", Map.of("name", "bad", "urlPrefix", "http://localhost/", "contentBase64", Base64.getEncoder().encodeToString(pem)));
            check(invalid.statusCode() == 400, "Non-HTTPS prefix accepted");
            check(request(api, "POST", Map.of("name", "bad", "urlPrefix", host, "contentBase64", Base64.getEncoder().encodeToString("PRIVATE KEY".getBytes(StandardCharsets.UTF_8)))).statusCode() == 400,
                "Private key accepted");
            check(request(api, "POST", Map.of("name", "bad", "urlPrefix", "https://*.example.com", "contentBase64", Base64.getEncoder().encodeToString(pem))).statusCode() == 400,
                "Wildcard prefix accepted");
            check(config.getConfig().trustedCertificates.size() == 1, "Rejected upload corrupted config");

            check(request(api + "/" + id, "PUT", Map.of("name", "Disabled CA", "urlPrefix", host + "/allowed/", "enabled", false)).statusCode() == 200, "Disable failed");
            check(!managed.run(root, List.of("git", "ls-remote", url)).isSuccess(), "Disabled CA still trusted");
            check(request(api + "/" + id, "PUT", Map.of("name", "Moved scope", "urlPrefix", host + "/other/", "enabled", true)).statusCode() == 200, "Edit without file failed");
            check(managed.run(root, List.of("git", "ls-remote", host + "/other/repo.git")).isSuccess(), "Changed scope not applied");
            check(!managed.run(root, List.of("git", "ls-remote", url)).isSuccess(), "Old scope still trusted after edit");

            String wrongHost = "https://127.0.0.1:" + https.getAddress().getPort();
            config.upsertCertificate(new TrustedCertificate("hostname-test", "Hostname verification", wrongHost, pem, true));
            check(!managed.run(root, List.of("git", "ls-remote", wrongHost + "/allowed/repo.git")).isSuccess(), "TLS hostname verification was bypassed");
            config.upsertCertificate(new TrustedCertificate("redirect-test", "Redirect boundary", host + "/redirect/", pem, true));
            check(!managed.run(root, List.of("git", "ls-remote", host + "/redirect/repo.git")).isSuccess(), "Redirect escaped managed scope");
            check(request(api + "/" + id, "DELETE", Map.of()).statusCode() == 200, "Delete failed");
            check(config.getConfig().trustedCertificates.stream().noneMatch(item -> item.id.equals(id)), "Deleted certificate remains");
            check("https://example.com/".equals(TrustedCertificate.normalizePrefix("EXAMPLE.com:443")), "Domain normalization failed");
            byte[] der = keys.getCertificate("server").getEncoded();
            check(new TrustedCertificate("der-test", "DER", host, der, true).pem.contains("BEGIN CERTIFICATE"), "DER certificate not accepted");
            config.upsertCertificate(new TrustedCertificate("bundle-one", "Bundle one", host + "/bundled/", pem, true));
            config.upsertCertificate(new TrustedCertificate("bundle-two", "Bundle two", host + "/bundled/", pem, true));
            check(managed.run(root, List.of("git", "ls-remote", host + "/bundled/repo.git")).isSuccess(), "Same-prefix certificate bundle failed");
            var scopedArgs = new HttpsTrustService(config).gitArguments();
            check(scopedArgs.stream().anyMatch(arg -> arg.endsWith("schannelUseSSLCAInfo=true")), "Windows Schannel setting missing");
            System.out.println("HTTPS certificate tests passed: real self-signed Git HTTPS, prefix boundaries, hostname checks, redirect boundaries, API CRUD, invalid input, import/export/restart, DER/bundles, scoped Schannel settings.");
        } finally {
            app.stop();
            if (httpWorkers != null) httpWorkers.shutdownNow();
            https.stop(0);
        }
    }

    private static HttpResponse<String> request(String url, String method, Map<String, Object> body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(Json.stringify(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

package app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class HttpsTrustService {
    private final ConfigService config;
    private final Path cacheDir;

    HttpsTrustService(ConfigService config) {
        this.config = config;
        this.cacheDir = config.configDirectory().resolve("certificates");
    }

    synchronized List<String> gitArguments() throws IOException {
        Map<String, StringBuilder> bundles = new TreeMap<>();
        for (TrustedCertificate certificate : config.getConfig().trustedCertificates) {
            if (certificate.enabled) bundles.computeIfAbsent(certificate.urlPrefix, ignored -> new StringBuilder()).append(certificate.pem);
        }
        List<String> arguments = new ArrayList<>();
        if (bundles.isEmpty()) return arguments;
        Files.createDirectories(cacheDir);
        for (var entry : bundles.entrySet()) {
            String pem = entry.getValue().toString();
            Path file = cacheDir.resolve(TrustedCertificate.fingerprint(pem) + ".pem").toAbsolutePath();
            if (!Files.exists(file)) {
                Path temporary = Files.createTempFile(cacheDir, "ca-", ".tmp");
                try {
                    Files.writeString(temporary, pem, StandardCharsets.US_ASCII);
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            String scope = "http." + entry.getKey() + ".";
            arguments.addAll(List.of("-c", scope + "sslCAInfo=" + file,
                "-c", scope + "sslVerify=true", "-c", scope + "schannelUseSSLCAInfo=true",
                "-c", scope + "followRedirects=false"));
        }
        return arguments;
    }
}

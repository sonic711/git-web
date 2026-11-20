package app;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class TrustedCertificate {
    static final int MAX_BYTES = 262_144;
    final String id;
    final String name;
    final String urlPrefix;
    final String pem;
    final boolean enabled;
    private final List<X509Certificate> certificates;

    TrustedCertificate(String id, String name, String urlPrefix, byte[] content, boolean enabled) {
        Models.require(id != null && id.matches("[A-Za-z0-9_-]{1,100}"), "Invalid certificate id");
        Models.require(name != null && !name.isBlank(), "Certificate name is required");
        Models.require(content.length > 0 && content.length <= MAX_BYTES, "Certificate file must be 1 byte to 256 KB");
        Models.require(!new String(content, StandardCharsets.ISO_8859_1).contains("PRIVATE KEY"),
            "Import public CA/server certificates only, not private keys");
        this.id = id;
        this.name = name.trim();
        this.urlPrefix = normalizePrefix(urlPrefix);
        this.enabled = enabled;
        try {
            var parsed = CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(content));
            Models.require(!parsed.isEmpty(), "File contains no X.509 certificates");
            certificates = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            for (var certificate : parsed) {
                certificates.add((X509Certificate) certificate);
                text.append("-----BEGIN CERTIFICATE-----\n")
                    .append(Base64.getMimeEncoder(64, new byte[] { '\n' }).encodeToString(certificate.getEncoded()))
                    .append("\n-----END CERTIFICATE-----\n");
            }
            this.pem = text.toString();
            Models.require(pem.getBytes(StandardCharsets.US_ASCII).length <= MAX_BYTES, "Certificate bundle exceeds 256 KB");
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid X.509 certificate; use a PEM or DER .pem/.crt/.cer file", exception);
        }
    }

    static TrustedCertificate fromMap(Map<String, Object> map) {
        return new TrustedCertificate(Models.stringValue(map.get("id")), Models.stringValue(map.get("name")),
            Models.stringValue(map.get("urlPrefix")), Models.stringValue(map.get("pem")).getBytes(StandardCharsets.UTF_8),
            Models.booleanValue(map.getOrDefault("enabled", Boolean.TRUE)));
    }

    static String normalizePrefix(String value) {
        Models.require(value != null && !value.isBlank(), "HTTPS domain / URL prefix is required");
        String raw = value.trim();
        if (!raw.contains("://")) raw = "https://" + raw;
        try {
            URI uri = new URI(raw);
            Models.require("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                && !uri.getHost().contains("*") && uri.getRawUserInfo() == null
                && uri.getRawQuery() == null && uri.getRawFragment() == null
                && (uri.getPort() == -1 || uri.getPort() > 0 && uri.getPort() <= 65535),
                "Use an HTTPS host with optional port/path; credentials, wildcards, query and fragment are not allowed");
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            return new URI("https://" + host + (uri.getPort() == -1 || uri.getPort() == 443 ? "" : ":" + uri.getPort()) + path)
                .normalize().toASCIIString();
        } catch (java.net.URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid HTTPS domain / URL prefix", exception);
        }
    }

    Map<String, Object> toMap(boolean includePem) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("urlPrefix", urlPrefix);
        map.put("enabled", enabled);
        if (includePem) map.put("pem", pem);
        return map;
    }

    Map<String, Object> view() {
        Map<String, Object> map = toMap(false);
        map.put("certificateCount", certificates.size());
        map.put("sha256", fingerprint(pem));
        map.put("subject", certificates.get(0).getSubjectX500Principal().getName());
        map.put("issuer", certificates.get(0).getIssuerX500Principal().getName());
        Instant expires = certificates.stream().map(cert -> cert.getNotAfter().toInstant()).min(Instant::compareTo).orElseThrow();
        Instant starts = certificates.stream().map(cert -> cert.getNotBefore().toInstant()).max(Instant::compareTo).orElseThrow();
        map.put("notBefore", starts.toString());
        map.put("notAfter", expires.toString());
        Instant now = Instant.now();
        map.put("validity", now.isBefore(starts) ? "NOT_YET_VALID" : now.isAfter(expires) ? "EXPIRED" : "VALID");
        return map;
    }

    static String fingerprint(String pem) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pem.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}

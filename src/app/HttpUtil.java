package app;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class HttpUtil {
    private HttpUtil() {
    }

    static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream inputStream = exchange.getRequestBody()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static Map<String, Object> readJsonObject(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        if (body.isBlank()) {
            return new LinkedHashMap<>();
        }
        return Json.asObject(Json.parse(body));
    }

    static void sendJson(HttpExchange exchange, int statusCode, Object payload) throws IOException {
        byte[] bytes = Json.stringify(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    static void sendText(HttpExchange exchange, int statusCode, String contentType, String payload) throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    static Map<String, Object> error(String code, String message, Map<String, Object> details) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("details", details);
        root.put("error", error);
        return root;
    }
}

package com.sendrepute.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Customer-only service transport. No retries; mutations require per-call authorization. */
public final class CustomerServiceClient {
    private final String token;
    private final HttpClient http;
    private final Duration timeout;
    private static final ObjectMapper JSON = new ObjectMapper();
    public CustomerServiceClient(String token, Duration timeout) {
        if (token == null || token.isBlank() || token.contains("\r") || token.contains("\n"))
            throw new IllegalArgumentException("Invalid API token");
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IllegalArgumentException("Timeout must be between zero and five minutes");
        this.token = token;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10)).build();
    }
    public JsonNode request(String operation, JsonNode body, Map<String,String> path,
                            Map<String,String> query, boolean authorizeMutation) {
        try {
            JsonNode metadata = JSON.readTree(CustomerServices.CONTRACT).get(operation);
            if (metadata == null) throw new IllegalArgumentException("Unknown customer operation");
            String method = metadata.get("method").asText();
            if (!method.equals("GET") && !authorizeMutation)
                throw new SendReputeException("consent_required", "Explicit operation authorization required");
            path = path == null ? Map.of() : path;
            query = query == null ? Map.of() : query;
            String route = metadata.get("path").asText();
            Set<String> pathKeys = new HashSet<>();
            for (JsonNode key : metadata.get("path_parameters")) pathKeys.add(key.asText());
            if (!pathKeys.equals(path.keySet())) throw new IllegalArgumentException("Incorrect path parameters");
            for (String key : pathKeys) {
                String value = path.get(key);
                if (value == null || value.isBlank() || value.length() > 256 || value.equals(".") || value.equals(".."))
                    throw new IllegalArgumentException("Invalid path parameter");
                route = route.replace("{" + key + "}", escape(value));
            }
            Set<String> queryKeys = new HashSet<>();
            for (JsonNode key : metadata.get("query")) queryKeys.add(key.asText());
            if (!queryKeys.containsAll(query.keySet())) throw new IllegalArgumentException("Unsupported query parameter");
            if (!query.isEmpty()) route += "?" + query.entrySet().stream()
                .map(p -> escape(p.getKey()) + "=" + escape(p.getValue())).collect(Collectors.joining("&"));
            if (body != null && (method.equals("GET") || !body.isObject()))
                throw new IllegalArgumentException("Invalid request body");
            if (body == null && Set.of("POST", "PUT", "PATCH").contains(method)) body = JSON.createObjectNode();
            byte[] payload = body == null ? new byte[0] : JSON.writeValueAsBytes(body);
            if (payload.length > 1_048_576) throw new IllegalArgumentException("Request exceeds byte limit");
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://www.sendrepute.com/api" + route))
                .timeout(timeout).header("Authorization", "Bearer " + token).header("Accept", "application/json");
            if (body != null) request.header("Content-Type", "application/json");
            HttpResponse<byte[]> response = http.send(request.method(method, HttpRequest.BodyPublishers.ofByteArray(payload)).build(),
                info -> new SendReputeClassifier.BoundedBodySubscriber());
            if (response.statusCode() >= 300 && response.statusCode() < 400)
                throw new SendReputeException("redirect_refused", "Redirect refused");
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new SendReputeException("http_" + response.statusCode(), "Service request rejected");
            return response.statusCode() == 204 ? JSON.nullNode() : JSON.readTree(response.body());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new SendReputeException("request_cancelled", "Service request cancelled");
        } catch (java.io.IOException error) {
            throw new SendReputeException("transport_error", "Service request failed");
        }
    }
    private static String escape(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}

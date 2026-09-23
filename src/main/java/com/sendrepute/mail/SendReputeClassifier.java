package com.sendrepute.mail;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

final class SendReputeClassifier implements Classifier {
    static final URI ENDPOINT = URI.create("https://www.sendrepute.com/api/v1/classify");
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final int MAX_REQUEST_BYTES = 1_048_576;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final SendReputeConfiguration configuration;
    private final HttpClient client;

    SendReputeClassifier(SendReputeConfiguration configuration) {
        this(configuration, HttpClient.newBuilder()
                .connectTimeout(configuration.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    SendReputeClassifier(SendReputeConfiguration configuration, HttpClient client) {
        this.configuration = configuration;
        this.client = client;
    }

    @Override
    public ClassificationResult classify(ExtractedEmail email) {
        try {
            byte[] requestBody = JSON.writeValueAsBytes(new Request(email.sender(), email.subject(), email.body()));
            if (requestBody.length > MAX_REQUEST_BYTES) {
                throw new SendReputeException("malformed_request", "SendRepute request exceeded 1 MiB");
            }
            HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                    .timeout(configuration.requestTimeout())
                    .header("Authorization", "Bearer " + configuration.apiToken())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                    .build();
            HttpResponse<byte[]> response = client.send(request, info -> new BoundedBodySubscriber());
            byte[] bytes = response.body();
            if (response.statusCode() != 200) {
                throw apiFailure(response.statusCode(), bytes);
            }
            return parse(bytes);
        } catch (SendReputeException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SendReputeException("transport", "SendRepute request was interrupted", exception);
        } catch (IOException exception) {
            throw new SendReputeException("transport", "SendRepute request failed", exception);
        }
    }

    static ClassificationResult parse(byte[] bytes) {
        try {
            JsonNode root = JSON.readTree(bytes);
            exactFields(root, Set.of("requestId", "model", "result", "billing"));
            String requestId = boundedText(root, "requestId", 1, 128);
            String model = enumText(root, "model",
                    Set.of("thor", "theos", "athena", "odin", "freya", "hermes", "ares", "apollo"));
            JsonNode result = requiredObject(root, "result");
            JsonNode billing = requiredObject(root, "billing");
            validateResult(result);
            exactFields(billing, Set.of("chargedMillicents", "replayed"));
            String label = enumText(result, "label", Set.of("inbox", "spam"));
            double probability = requiredFiniteNumber(result, "spamProbability");
            if (probability < 0 || probability > 1) {
                throw malformed();
            }
            long charged = requiredNonnegativeInteger(billing, "chargedMillicents");
            JsonNode replayedNode = billing.get("replayed");
            if (replayedNode == null || !replayedNode.isBoolean()) {
                throw malformed();
            }
            return new ClassificationResult(requestId, model, label, probability, charged, replayedNode.booleanValue());
        } catch (SendReputeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SendReputeException("malformed_response", "SendRepute returned malformed JSON", exception);
        }
    }

    private static void validateResult(JsonNode result) {
        exactFields(result, Set.of("label", "spamProbability", "flaggedTermCount", "confidence",
                "reasons", "flaggedTerms", "analyzedFields", "modelVersion", "analyzedAt", "contentAudit"));
        enumText(result, "label", Set.of("inbox", "spam"));
        requiredFiniteNumber(result, "spamProbability");
        enumText(result, "confidence", Set.of("low", "medium", "high"));
        text(result, "modelVersion");
        text(result, "analyzedAt");
        stringArray(result, "flaggedTerms", Integer.MAX_VALUE, Integer.MAX_VALUE);
        stringArray(result, "analyzedFields", Integer.MAX_VALUE, Integer.MAX_VALUE);
        JsonNode reasons = requiredArray(result, "reasons");
        for (JsonNode reason : reasons) {
            exactFields(reason, Set.of("signal", "detail", "weight"));
            text(reason, "signal");
            text(reason, "detail");
            requiredFiniteNumber(reason, "weight");
        }
        if (result.has("flaggedTermCount")) requiredNonnegativeInteger(result, "flaggedTermCount");
        if (result.has("contentAudit")) validateContentAudit(requiredObject(result, "contentAudit"));
    }

    private static void validateContentAudit(JsonNode audit) {
        exactFields(audit, Set.of("score", "grade", "summary", "counts", "totalIssues",
                "criticalCount", "warningCount", "suggestionCount", "issues", "goodPractices",
                "homoglyphTerms", "inputTruncated"));
        boundedInteger(audit, "score", 0, 100);
        enumText(audit, "grade", Set.of("A", "B", "C", "D", "F"));
        enumText(audit, "summary",
                Set.of("fix_critical", "fix_warnings", "review_suggestions", "looks_good"));
        JsonNode counts = requiredObject(audit, "counts");
        exactFields(counts, Set.of("words", "links", "images", "triggerPhrases"));
        for (String field : List.of("words", "links", "images", "triggerPhrases")) {
            requiredNonnegativeInteger(counts, field);
        }
        for (String field : List.of("totalIssues", "criticalCount", "warningCount", "suggestionCount")) {
            requiredNonnegativeInteger(audit, field);
        }
        JsonNode issues = requiredArray(audit, "issues");
        if (issues.size() > 50) throw malformed();
        for (JsonNode issue : issues) {
            exactFields(issue, Set.of("code", "category", "severity", "deduction", "evidence"));
            text(issue, "code");
            enumText(issue, "category", Set.of("subject", "content", "links", "structure", "compliance"));
            enumText(issue, "severity", Set.of("critical", "warning", "suggestion"));
            boundedInteger(issue, "deduction", 0, 100);
            boundedText(issue, "evidence", 0, 200);
        }
        JsonNode practices = requiredArray(audit, "goodPractices");
        if (practices.size() > 20) throw malformed();
        for (JsonNode practice : practices) {
            exactFields(practice, Set.of("code", "category"));
            text(practice, "code");
            enumText(practice, "category", Set.of("subject", "content", "links", "structure", "compliance"));
        }
        if (audit.has("homoglyphTerms")) stringArray(audit, "homoglyphTerms", 20, 120);
        JsonNode truncated = audit.get("inputTruncated");
        if (truncated == null || !truncated.isBoolean()) throw malformed();
    }

    private static SendReputeException apiFailure(int status, byte[] body) {
        String code = "unknown";
        try {
            JsonNode node = JSON.readTree(body);
            JsonNode error = node.path("error").path("code");
            if (error.isTextual() && error.textValue().matches("[A-Za-z0-9_.-]{1,128}")) {
                code = error.textValue();
            }
        } catch (Exception ignored) {
            // Status remains the authoritative, non-secret diagnostic.
        }
        String category = switch (status) {
            case 400, 413 -> "malformed_request";
            case 401, 403 -> "authentication";
            case 402 -> "balance";
            case 409, 429 -> "rate_limit";
            case 503 -> "unavailable";
            default -> "api";
        };
        return new SendReputeException(category, "SendRepute API returned HTTP " + status + " (" + code + ")");
    }

    private static JsonNode requiredObject(JsonNode parent, String field) {
        JsonNode node = parent == null ? null : parent.get(field);
        if (node == null || !node.isObject()) throw malformed();
        return node;
    }

    private static JsonNode requiredArray(JsonNode parent, String field) {
        JsonNode node = parent == null ? null : parent.get(field);
        if (node == null || !node.isArray()) throw malformed();
        return node;
    }

    private static String text(JsonNode parent, String field) {
        JsonNode node = parent == null ? null : parent.get(field);
        if (node == null || !node.isTextual()) throw malformed();
        return node.textValue();
    }

    private static String boundedText(JsonNode parent, String field, int minimum, int maximum) {
        String value = text(parent, field);
        if (value.length() < minimum || value.length() > maximum) throw malformed();
        return value;
    }

    private static String enumText(JsonNode parent, String field, Set<String> values) {
        String value = text(parent, field);
        if (!values.contains(value)) throw malformed();
        return value;
    }

    private static void stringArray(JsonNode parent, String field, int maximumItems, int maximumLength) {
        JsonNode array = requiredArray(parent, field);
        if (array.size() > maximumItems) throw malformed();
        for (JsonNode item : array) {
            if (!item.isTextual() || item.textValue().length() > maximumLength) throw malformed();
        }
    }

    private static double requiredFiniteNumber(JsonNode parent, String field) {
        JsonNode node = parent == null ? null : parent.get(field);
        if (node == null || !node.isNumber() || !Double.isFinite(node.doubleValue())) throw malformed();
        return node.doubleValue();
    }

    private static long requiredNonnegativeInteger(JsonNode parent, String field) {
        return boundedInteger(parent, field, 0, Long.MAX_VALUE);
    }

    private static long boundedInteger(JsonNode parent, String field, long minimum, long maximum) {
        JsonNode node = parent == null ? null : parent.get(field);
        if (node == null || !node.isNumber()) throw malformed();
        double number = node.doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number)
                || number < minimum || number > maximum) throw malformed();
        return (long) number;
    }

    private static void exactFields(JsonNode object, Set<String> allowed) {
        if (object == null || !object.isObject()) throw malformed();
        object.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) throw malformed();
        });
    }

    private static SendReputeException malformed() {
        return new SendReputeException("malformed_response", "SendRepute response did not match the classification contract");
    }

    private record Request(String sender, String subject, String body) {}

    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private int count;

        @Override public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override public void onSubscribe(Flow.Subscription value) {
            if (subscription != null) {
                value.cancel();
                return;
            }
            subscription = value;
            value.request(Long.MAX_VALUE);
        }

        @Override public void onNext(List<ByteBuffer> buffers) {
            try {
                for (ByteBuffer buffer : buffers) {
                    int remaining = buffer.remaining();
                    if (remaining > MAX_RESPONSE_BYTES - count) {
                        subscription.cancel();
                        body.completeExceptionally(new IOException("SendRepute response exceeded 1 MiB"));
                        return;
                    }
                    byte[] bytes = new byte[remaining];
                    buffer.get(bytes);
                    output.write(bytes, 0, bytes.length);
                    count += remaining;
                }
            } catch (RuntimeException exception) {
                subscription.cancel();
                body.completeExceptionally(exception);
            }
        }

        @Override public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override public void onComplete() {
            body.complete(output.toByteArray());
        }
    }
}
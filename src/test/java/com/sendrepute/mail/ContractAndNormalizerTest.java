package com.sendrepute.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContractAndNormalizerTest {
    private static Path repositoryFile(String path) {
        Path candidate = Path.of("../..", path).normalize();
        if (!Files.exists(candidate)) candidate = Path.of(path);
        assertTrue(Files.exists(candidate), "repository contract fixture missing: " + candidate);
        return candidate;
    }

    @Test
    void publicOpenApiContractMatchesAdapter() throws Exception {
        JsonNode api = new ObjectMapper().readTree(repositoryFile(
                "artifacts/api-server/src/customer-api-openapi.json").toFile());
        JsonNode post = api.at("/paths/~1v1~1classify/post");
        assertEquals("classifyCustomerEmail", post.path("operationId").textValue());
        JsonNode input = api.at("/components/schemas/CustomerClassificationInput");
        assertEquals(List.of("sender", "subject", "body"),
                new ObjectMapper().convertValue(input.path("required"), List.class));
        JsonNode response = api.at("/components/schemas/CustomerClassificationResponse/required");
        assertEquals(List.of("requestId", "model", "result", "billing"),
                new ObjectMapper().convertValue(response, List.class));
        assertEquals("https://www.sendrepute.com/api/v1/classify",
                SendReputeClassifier.ENDPOINT.toString());
    }

    @Test
    void framingIsInertAcrossActualNormalizerPassOrder() throws Exception {
        String source = Files.readString(repositoryFile(
                "artifacts/api-server/src/lib/classifier-features.ts"));
        int base64 = source.indexOf("decodeBase64Text(value.slice");
        int quotedPrintable = source.indexOf(".replace(/=\\r?");
        int markup = source.indexOf(".replace(/<!--");
        int css = source.indexOf("[^{}\\r\\n]");
        int entities = source.indexOf(".replace(/&#(");
        assertTrue(base64 >= 0 && quotedPrintable > base64 && markup > quotedPrintable
                && css > markup && entities > css,
                "production visibleEmailText normalization order changed");

        String framed = MimeEmailExtractor.frame(List.of(
                "<style>first", "=3Cscript", "x{display:none}", "A".repeat(100)));
        assertTrue(framed.startsWith("SendRepute displayed alternatives "));
        assertTrue(framed.contains("&#60;style&#62;first"));
        assertTrue(framed.contains("&#61;3Cscript"));
        assertTrue(framed.contains("x&#123;display&#58;none&#125;"));
        assertTrue(framed.contains("alternative 4"));

        Path root = repositoryFile("artifacts/api-server/src/lib/classifier-features.ts")
                .toAbsolutePath().normalize().getParent().getParent().getParent().getParent().getParent();
        Path tsx = root.resolve("scripts/node_modules/.bin/tsx");
        Path bridge = root.resolve("integrations/java/src/test/resources/normalizer-bridge.ts");
        assertTrue(Files.isExecutable(tsx), "repository tsx runtime missing");
        String longBase64LookingText = "QUJD".repeat(100);
        String actualPayload = MimeEmailExtractor.frame(List.of(
                "first promo{display:none} survives",
                longBase64LookingText,
                "Content-Transfer-Encoding: base64\n\nPHN0eWxlPmhpZGRlbjwvc3R5bGU+",
                "last fragment survives"));
        Process process = new ProcessBuilder(tsx.toString(), bridge.toString(), actualPayload)
                .directory(root.toFile()).redirectErrorStream(true).start();
        String normalized = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), normalized);
        assertTrue(normalized.contains("promo{display:none} survives"));
        assertTrue(normalized.contains(longBase64LookingText));
        assertTrue(normalized.contains("Content-Transfer-Encoding: base64"));
        assertTrue(normalized.contains("PHN0eWxlPmhpZGRlbjwvc3R5bGU+"));
        assertTrue(normalized.contains("last fragment survives"));
    }

    @Test
    void offlineClassificationResponseFixturesMatchNestedContract() {
        String fixture = """
                {
                  "requestId":"req_fixture",
                  "model":"thor",
                  "result":{
                    "label":"spam","spamProbability":0.75,"confidence":"high",
                    "reasons":[],"flaggedTerms":[],"analyzedFields":["body"],
                    "modelVersion":"fixture","analyzedAt":"2026-01-01T00:00:00Z"
                  },
                  "billing":{"chargedMillicents":25,"replayed":false}
                }
                """;
        ClassificationResult result = SendReputeClassifier.parse(
                fixture.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("req_fixture", result.requestId());
        assertEquals(.75, result.spamProbability());
        assertEquals(25, result.chargedMillicents());

        String auditFixture = fixture.replace(
                "\"modelVersion\":\"fixture\",\"analyzedAt\":\"2026-01-01T00:00:00Z\"",
                """
                "modelVersion":"fixture","analyzedAt":"2026-01-01T00:00:00Z",
                "contentAudit":{
                  "score":95.0,"grade":"A","summary":"looks_good",
                  "counts":{"words":12.0,"links":0,"images":0,"triggerPhrases":0},
                  "totalIssues":0,"criticalCount":0,"warningCount":0,"suggestionCount":0,
                  "issues":[],"goodPractices":[{"code":"","category":"content"}],
                  "homoglyphTerms":[],"inputTruncated":false
                }
                """.trim());
        SendReputeClassifier.parse(auditFixture.getBytes(StandardCharsets.UTF_8));

        String malformed = fixture.replace("\"spamProbability\":0.75",
                "\"spamProbability\":\"0.75\"");
        assertEquals("malformed_response", assertThrows(SendReputeException.class,
                () -> SendReputeClassifier.parse(
                        malformed.getBytes(java.nio.charset.StandardCharsets.UTF_8))).category());

        for (String invalid : List.of(
                fixture.replace("\"model\":\"thor\"", "\"model\":\"thor-lookalike\""),
                fixture.replace("\"requestId\":\"req_fixture\"", "\"requestId\":\"" + "x".repeat(129) + "\""),
                fixture.replace("\"billing\":{", "\"extra\":true,\"billing\":{"),
                fixture.replace("\"reasons\":[]",
                        "\"reasons\":[{\"signal\":\"x\",\"detail\":\"y\",\"weight\":\"1\"}]"),
                fixture.replace("\"flaggedTerms\":[]", "\"flaggedTerms\":[7]"),
                auditFixture.replace("\"inputTruncated\":false", "\"inputTruncated\":\"false\""),
                fixture + "{}",
                fixture.replace("\"model\":\"thor\"", "\"model\":\"thor\",\"model\":\"thor\""))) {
            assertEquals("malformed_response", assertThrows(SendReputeException.class,
                    () -> SendReputeClassifier.parse(invalid.getBytes(StandardCharsets.UTF_8))).category());
        }
    }
}
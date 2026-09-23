package com.sendrepute.mail;

import java.time.Duration;
import java.util.Objects;

public record SendReputeConfiguration(
        String apiToken,
        boolean enabled,
        boolean paidAnalysisConsent,
        Mode mode,
        FailurePolicy failurePolicy,
        double spamThreshold,
        Duration connectTimeout,
        Duration requestTimeout) {

    public enum Mode { ADVISORY, BLOCKING }
    public enum FailurePolicy { ALLOW, BLOCK }

    public SendReputeConfiguration {
        mode = Objects.requireNonNull(mode, "mode");
        failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy");
        connectTimeout = boundedTimeout(connectTimeout, "connectTimeout");
        requestTimeout = boundedTimeout(requestTimeout, "requestTimeout");
        if (!Double.isFinite(spamThreshold) || spamThreshold < 0 || spamThreshold > 1) {
            throw new IllegalArgumentException("spamThreshold must be finite and between 0 and 1");
        }
        if (enabled && paidAnalysisConsent && (apiToken == null || apiToken.isBlank())) {
            throw new IllegalArgumentException("apiToken is required when paid classification is enabled and consented");
        }
    }

    public static SendReputeConfiguration disabled() {
        return new SendReputeConfiguration(null, false, false, Mode.ADVISORY,
                FailurePolicy.ALLOW, 0.8, Duration.ofSeconds(3), Duration.ofSeconds(10));
    }

    private static Duration boundedTimeout(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative() || value.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException(name + " must be greater than zero and no more than 60 seconds");
        }
        return value;
    }
}
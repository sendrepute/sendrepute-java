package com.sendrepute.mail;

public record ClassificationResult(
        String requestId,
        String model,
        String label,
        double spamProbability,
        long chargedMillicents,
        boolean replayed) {
}
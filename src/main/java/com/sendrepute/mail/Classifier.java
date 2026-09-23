package com.sendrepute.mail;

@FunctionalInterface
interface Classifier {
    ClassificationResult classify(ExtractedEmail email);
}
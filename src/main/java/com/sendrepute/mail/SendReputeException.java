package com.sendrepute.mail;

public class SendReputeException extends RuntimeException {
    private final String category;

    public SendReputeException(String category, String message) {
        super(message);
        this.category = category;
    }

    public SendReputeException(String category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    public String category() {
        return category;
    }
}
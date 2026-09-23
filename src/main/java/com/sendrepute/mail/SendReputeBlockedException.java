package com.sendrepute.mail;

public final class SendReputeBlockedException extends SendReputeException {
    public SendReputeBlockedException(String category, String message) {
        super(category, message);
    }
}
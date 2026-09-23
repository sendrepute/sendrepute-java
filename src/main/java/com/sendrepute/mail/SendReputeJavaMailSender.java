package com.sendrepute.mail;

import jakarta.mail.internet.MimeMessage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMailMessage;
import org.springframework.mail.javamail.MimeMessagePreparator;

/**
 * JavaMailSender decorator. Ordinary JavaMailSender methods are intentionally
 * transparent; only the sendWithSendRepute routes consent a message to analysis.
 */
public final class SendReputeJavaMailSender implements JavaMailSender {
    private final JavaMailSender delegate;
    private final SendReputeConfiguration configuration;
    private final Classifier classifier;
    private final MimeEmailExtractor extractor = new MimeEmailExtractor();

    public SendReputeJavaMailSender(JavaMailSender delegate, SendReputeConfiguration configuration) {
        this(delegate, configuration, new SendReputeClassifier(configuration));
    }

    SendReputeJavaMailSender(JavaMailSender delegate, SendReputeConfiguration configuration,
                            Classifier classifier) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
    }

    public void sendWithSendRepute(SimpleMailMessage message) throws MailException {
        Objects.requireNonNull(message, "message");
        preflight(extract(render(message)));
        delegate.send(message);
    }

    public void sendWithSendRepute(SimpleMailMessage... messages) throws MailException {
        Objects.requireNonNull(messages, "messages");
        List<ExtractedEmail> batch = new ArrayList<>(messages.length);
        for (SimpleMailMessage message : messages) {
            batch.add(extract(render(Objects.requireNonNull(message, "message"))));
        }
        preflight(batch);
        delegate.send(messages);
    }

    public void sendWithSendRepute(MimeMessage message) throws MailException {
        Objects.requireNonNull(message, "message");
        preflight(extract(message));
        delegate.send(message);
    }

    public void sendWithSendRepute(MimeMessage... messages) throws MailException {
        Objects.requireNonNull(messages, "messages");
        List<ExtractedEmail> batch = new ArrayList<>(messages.length);
        for (MimeMessage message : messages) {
            batch.add(extract(Objects.requireNonNull(message, "message")));
        }
        preflight(batch);
        delegate.send(messages);
    }

    public void sendWithSendRepute(MimeMessagePreparator preparator) throws MailException {
        sendWithSendRepute(new MimeMessagePreparator[]{preparator});
    }

    public void sendWithSendRepute(MimeMessagePreparator... preparators) throws MailException {
        Objects.requireNonNull(preparators, "preparators");
        List<MimeMessage> rendered = new ArrayList<>(preparators.length);
        for (MimeMessagePreparator preparator : preparators) {
            MimeMessage message = createMimeMessage();
            try {
                Objects.requireNonNull(preparator, "preparator").prepare(message);
            } catch (Exception exception) {
                throw new SendReputeException("unsupported_message", "MimeMessagePreparator failed", exception);
            }
            rendered.add(message);
        }
        List<ExtractedEmail> batch = new ArrayList<>(rendered.size());
        for (MimeMessage message : rendered) batch.add(extract(message));
        preflight(batch);
        delegate.send(rendered.toArray(MimeMessage[]::new));
    }

    private MimeMessage render(SimpleMailMessage source) {
        MimeMessage rendered = createMimeMessage();
        source.copyTo(new MimeMailMessage(rendered));
        return rendered;
    }

    private ExtractedEmail extract(MimeMessage message) {
        if (!configuration.enabled() || !configuration.paidAnalysisConsent()) return null;
        try {
            return extractor.extract(message);
        } catch (SendReputeException exception) {
            handleFailure(exception);
            return null;
        }
    }

    private void preflight(List<ExtractedEmail> batch) {
        if (!configuration.enabled()) return;
        if (!configuration.paidAnalysisConsent()) {
            handleFailure(new SendReputeException("consent_required", "paid analysis consent is disabled"));
            return;
        }
        for (ExtractedEmail email : batch) preflight(email);
    }

    private void preflight(ExtractedEmail email) {
        if (!configuration.enabled()) return;
        if (!configuration.paidAnalysisConsent()) {
            handleFailure(new SendReputeException("consent_required", "paid analysis consent is disabled"));
            return;
        }
        if (email == null) return;
        try {
            ClassificationResult result = classifier.classify(email);
            if (configuration.mode() == SendReputeConfiguration.Mode.BLOCKING
                    && result.spamProbability() >= configuration.spamThreshold()) {
                throw new SendReputeBlockedException("classification",
                        "message blocked by SendRepute classification " + result.requestId());
            }
        } catch (SendReputeBlockedException exception) {
            throw exception;
        } catch (SendReputeException exception) {
            handleFailure(exception);
        }
    }

    private void handleFailure(SendReputeException exception) {
        if (configuration.failurePolicy() == SendReputeConfiguration.FailurePolicy.BLOCK) {
            throw new SendReputeBlockedException(exception.category(),
                    "message blocked because SendRepute preflight failed: " + exception.category());
        }
    }

    @Override public MimeMessage createMimeMessage() { return delegate.createMimeMessage(); }
    @Override public MimeMessage createMimeMessage(InputStream contentStream) throws MailException {
        return delegate.createMimeMessage(contentStream);
    }
    @Override public void send(MimeMessage mimeMessage) throws MailException { delegate.send(mimeMessage); }
    @Override public void send(MimeMessage... mimeMessages) throws MailException { delegate.send(mimeMessages); }
    @Override public void send(MimeMessagePreparator preparator) throws MailException { delegate.send(preparator); }
    @Override public void send(MimeMessagePreparator... preparators) throws MailException { delegate.send(preparators); }
    @Override public void send(SimpleMailMessage simpleMessage) throws MailException { delegate.send(simpleMessage); }
    @Override public void send(SimpleMailMessage... simpleMessages) throws MailException { delegate.send(simpleMessages); }
}
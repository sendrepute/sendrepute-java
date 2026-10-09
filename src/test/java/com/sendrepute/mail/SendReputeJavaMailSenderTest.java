package com.sendrepute.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Enumeration;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessagePreparator;

class SendReputeJavaMailSenderTest {
    @Test
    void ordinaryMethodsAreTransparentAndNeverClassify() throws Exception {
        FakeSender delegate = new FakeSender();
        AtomicInteger calls = new AtomicInteger();
        SendReputeJavaMailSender sender = adapter(delegate, settings(true,
                SendReputeConfiguration.Mode.BLOCKING,
                SendReputeConfiguration.FailurePolicy.BLOCK, 0),
                email -> {
                    calls.incrementAndGet();
                    return result(1);
                });
        SimpleMailMessage simple = simple();
        MimeMessage mime = mime(delegate, "body");
        MimeMessagePreparator preparator = message -> populate(message, "prepared");

        sender.send(simple);
        sender.send(new SimpleMailMessage[]{simple});
        sender.send(mime);
        MimeMessage[] mimeBatch = {mime};
        sender.send(mimeBatch);
        sender.send(preparator);
        sender.send(new MimeMessagePreparator[]{preparator});

        assertEquals(0, calls.get());
        assertSame(simple, delegate.simpleSingles.get(0));
        assertSame(mime, delegate.mimeSingles.get(0));
        assertSame(mimeBatch, delegate.lastMimeBatch);
        assertEquals(2, delegate.standardPreparators);
    }

    @Test
    void optedInOverloadsClassifyFinalContentAndPreserveDelegatedObjects() throws Exception {
        FakeSender delegate = new FakeSender();
        List<ExtractedEmail> seen = new ArrayList<>();
        SendReputeJavaMailSender sender = adapter(delegate, settings(true,
                SendReputeConfiguration.Mode.ADVISORY,
                SendReputeConfiguration.FailurePolicy.BLOCK, .8),
                email -> {
                    seen.add(email);
                    return result(.99);
                });
        SimpleMailMessage simple = simple();
        sender.sendWithSendRepute(simple);
        SimpleMailMessage[] simpleBatch = {simple(), simple()};
        sender.sendWithSendRepute(simpleBatch);
        MimeMessage mime = mime(delegate, "mime");
        sender.sendWithSendRepute(mime);
        MimeMessage[] mimeBatch = {mime(delegate, "one"), mime(delegate, "two")};
        sender.sendWithSendRepute(mimeBatch);
        AtomicInteger prepared = new AtomicInteger();
        MimeMessagePreparator one = message -> {
            prepared.incrementAndGet();
            populate(message, "prepared one");
        };
        sender.sendWithSendRepute(one);
        MimeMessagePreparator[] preparatorBatch = {
                message -> { prepared.incrementAndGet(); populate(message, "prepared two"); },
                message -> { prepared.incrementAndGet(); populate(message, "prepared three"); }
        };
        sender.sendWithSendRepute(preparatorBatch);

        assertEquals(9, seen.size());
        assertEquals(3, prepared.get());
        assertSame(simple, delegate.simpleSingles.get(0));
        assertSame(simpleBatch, delegate.lastSimpleBatch);
        assertSame(mime, delegate.mimeSingles.get(0));
        assertSame(mimeBatch, delegate.mimeBatches.get(0));
        assertEquals(0, delegate.standardPreparators);
        assertTrue(seen.stream().allMatch(email -> email.sender().equals("Example Sender")));
    }

    @Test
    void blockingThresholdAndIndependentFailurePolicyWork() throws Exception {
        FakeSender delegate = new FakeSender();
        SendReputeJavaMailSender blocking = adapter(delegate, settings(true,
                SendReputeConfiguration.Mode.BLOCKING,
                SendReputeConfiguration.FailurePolicy.ALLOW, .7), email -> result(.7));
        assertThrows(SendReputeBlockedException.class,
                () -> blocking.sendWithSendRepute(mime(delegate, "blocked")));
        assertTrue(delegate.mimeSingles.isEmpty());

        SendReputeJavaMailSender failOpen = adapter(delegate, settings(true,
                SendReputeConfiguration.Mode.ADVISORY,
                SendReputeConfiguration.FailurePolicy.ALLOW, .5),
                email -> { throw new SendReputeException("transport", "offline"); });
        MimeMessage allowed = mime(delegate, "allowed");
        failOpen.sendWithSendRepute(allowed);
        assertSame(allowed, delegate.mimeSingles.get(0));

        SendReputeJavaMailSender failClosed = adapter(delegate, settings(true,
                SendReputeConfiguration.Mode.ADVISORY,
                SendReputeConfiguration.FailurePolicy.BLOCK, .5),
                email -> { throw new SendReputeException("transport", "offline"); });
        assertThrows(SendReputeBlockedException.class,
                () -> failClosed.sendWithSendRepute(mime(delegate, "closed")));
    }

    @Test
    void disabledAndMissingConsentMakeNoPaidCall() throws Exception {
        FakeSender delegate = new FakeSender();
        AtomicInteger calls = new AtomicInteger();
        Classifier classifier = email -> { calls.incrementAndGet(); return result(0); };
        adapter(delegate, settings(false, SendReputeConfiguration.Mode.ADVISORY,
                SendReputeConfiguration.FailurePolicy.BLOCK, .8), classifier)
                .sendWithSendRepute(mime(delegate, "disabled"));
        SendReputeConfiguration noConsent = new SendReputeConfiguration(null, true, false,
                SendReputeConfiguration.Mode.ADVISORY, SendReputeConfiguration.FailurePolicy.ALLOW,
                .8, Duration.ofSeconds(1), Duration.ofSeconds(1));
        adapter(delegate, noConsent, classifier).sendWithSendRepute(mime(delegate, "no consent"));
        assertEquals(0, calls.get());
    }

    @Test
    void collectsEveryAlternativeAndFramesNormalizerMetacharacters() throws Exception {
        FakeSender delegate = new FakeSender();
        MimeMessage message = delegate.createMimeMessage();
        message.setFrom(new InternetAddress("sender@example.com", "Example Sender"));
        message.setRecipients(jakarta.mail.Message.RecipientType.TO, "recipient@example.com");
        message.setSubject("alternatives");
        MimeMultipart alternatives = new MimeMultipart("alternative");
        MimeBodyPart plain = new MimeBodyPart();
        plain.setText("benign <style> =3Cscript braces{x} Content-Transfer-Encoding: base64", "UTF-8");
        alternatives.addBodyPart(plain);
        MimeBodyPart html = new MimeBodyPart();
        html.setContent("<html><body>visible malicious offer<script>hidden</script></body></html>",
                "text/html; charset=UTF-8");
        alternatives.addBodyPart(html);
        message.setContent(alternatives);

        ExtractedEmail email = new MimeEmailExtractor().extract(message);
        assertTrue(email.body().contains("alternative 1"));
        assertTrue(email.body().contains("alternative 2"));
        assertTrue(email.body().contains("visible malicious offer"));
        assertFalse(email.body().contains("<style>"));
        assertTrue(email.body().contains("&#60;style&#62;"));
        assertTrue(email.body().contains("&#61;3Cscript"));
        assertTrue(email.body().contains("braces&#123;x&#125;"));
        assertTrue(email.body().contains("Encoding&#58; base64"));
    }

    @Test
    void validatesEntireBatchBeforeFirstPaidCall() throws Exception {
        FakeSender delegate = new FakeSender();
        AtomicInteger calls = new AtomicInteger();
        SendReputeJavaMailSender sender = adapter(delegate, settings(true,
                SendReputeConfiguration.Mode.ADVISORY,
                SendReputeConfiguration.FailurePolicy.BLOCK, .8),
                email -> { calls.incrementAndGet(); return result(0); });

        MimeMessage valid = mime(delegate, "valid");
        MimeMessage invalid = delegate.createMimeMessage();
        invalid.setFrom("sender@example.com"); // Deliberately no display name.
        invalid.setSubject("invalid");
        invalid.setText("body");
        assertThrows(SendReputeBlockedException.class,
                () -> sender.sendWithSendRepute(valid, invalid));
        assertEquals(0, calls.get());
        assertTrue(delegate.mimeBatches.isEmpty());

        AtomicInteger prepared = new AtomicInteger();
        MimeMessagePreparator first = message -> {
            prepared.incrementAndGet();
            populate(message, "first");
        };
        MimeMessagePreparator second = message -> {
            prepared.incrementAndGet();
            throw new IllegalStateException("fixture failure");
        };
        assertThrows(SendReputeException.class,
                () -> sender.sendWithSendRepute(first, second));
        assertEquals(2, prepared.get());
        assertEquals(0, calls.get());

        SimpleMailMessage badSimple = simple();
        badSimple.setFrom("sender@example.com");
        assertThrows(SendReputeBlockedException.class,
                () -> sender.sendWithSendRepute(simple(), badSimple));
        assertEquals(0, calls.get());
    }

    @Test
    void rejectedPreflightDoesNotMutateCallerOwnedMime() throws Exception {
        FakeSender delegate = new FakeSender();
        MimeMessage original = mime(delegate, "unchanged body");
        List<String> before = headerLines(original);
        assertFalse(original.isSet(jakarta.mail.Flags.Flag.DRAFT));
        SendReputeJavaMailSender sender = adapter(delegate, settings(true,
                SendReputeConfiguration.Mode.BLOCKING,
                SendReputeConfiguration.FailurePolicy.BLOCK, .5), email -> result(.9));

        assertThrows(SendReputeBlockedException.class,
                () -> sender.sendWithSendRepute(original));
        assertEquals(before, headerLines(original));
        assertEquals("unchanged body", original.getContent().toString().trim());
        assertTrue(delegate.mimeSingles.isEmpty());
    }

    @Test
    void rejectsTraversalLimitsAndLazyBinaryBeforeMaterializationOrPayment() throws Exception {
        FakeSender delegate = new FakeSender();
        AtomicInteger classifierCalls = new AtomicInteger();
        SendReputeJavaMailSender sender = adapter(delegate, settings(true,
                SendReputeConfiguration.Mode.ADVISORY,
                SendReputeConfiguration.FailurePolicy.BLOCK, .8),
                email -> { classifierCalls.incrementAndGet(); return result(0); });

        MimeMessage tooMany = envelope(delegate, "too many");
        MimeMultipart many = new MimeMultipart("mixed");
        for (int index = 0; index < 257; index++) {
            MimeBodyPart part = new MimeBodyPart();
            part.setText("part " + index);
            many.addBodyPart(part);
        }
        tooMany.setContent(many);

        MimeMessage tooDeep = envelope(delegate, "too deep");
        MimeBodyPart nested = new MimeBodyPart();
        nested.setText("leaf");
        for (int depth = 0; depth < 18; depth++) {
            MimeMultipart level = new MimeMultipart("mixed");
            level.addBodyPart(nested);
            MimeBodyPart wrapper = new MimeBodyPart();
            wrapper.setContent(level);
            nested = wrapper;
        }
        MimeMultipart root = new MimeMultipart("mixed");
        root.addBodyPart(nested);
        tooDeep.setContent(root);

        MimeMessage tooLarge = envelope(delegate, "too large");
        tooLarge.setText("x".repeat(524_289), "UTF-8");

        for (MimeMessage invalid : List.of(tooMany, tooDeep, tooLarge)) {
            assertThrows(SendReputeBlockedException.class,
                    () -> sender.sendWithSendRepute(invalid));
        }

        CountingDataSource lazyBinary = new CountingDataSource();
        MimeMessage binary = envelope(delegate, "lazy binary");
        binary.setDataHandler(new DataHandler(lazyBinary));
        assertThrows(SendReputeBlockedException.class,
                () -> sender.sendWithSendRepute(binary));

        CountingDataSource lazyText = new CountingDataSource("text/plain; charset=UTF-8");
        MimeMessage unknownText = envelope(delegate, "lazy text");
        unknownText.setDataHandler(new DataHandler(lazyText));
        assertThrows(SendReputeBlockedException.class,
                () -> sender.sendWithSendRepute(unknownText));

        assertEquals(0, lazyBinary.openCount.get());
        assertEquals(0, lazyText.openCount.get());
        assertEquals(0, classifierCalls.get());
        assertTrue(delegate.mimeSingles.isEmpty());
    }

    private static SendReputeJavaMailSender adapter(
            FakeSender delegate, SendReputeConfiguration configuration, Classifier classifier) {
        return new SendReputeJavaMailSender(delegate, configuration, classifier);
    }

    private static SendReputeConfiguration settings(
            boolean enabled, SendReputeConfiguration.Mode mode,
            SendReputeConfiguration.FailurePolicy failurePolicy, double threshold) {
        return new SendReputeConfiguration("server-secret-test-value", enabled, true, mode,
                failurePolicy, threshold, Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    private static ClassificationResult result(double probability) {
        return new ClassificationResult("req_test", "thor",
                probability >= .5 ? "spam" : "inbox", probability, 10, false);
    }

    private static SimpleMailMessage simple() {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom("Example Sender <sender@example.com>");
        message.setTo("recipient@example.com");
        message.setSubject("subject");
        message.setText("body");
        return message;
    }

    private static MimeMessage mime(FakeSender sender, String body) throws Exception {
        MimeMessage message = envelope(sender, "subject");
        message.setText(body, "UTF-8");
        return message;
    }

    private static MimeMessage envelope(FakeSender sender, String subject) throws Exception {
        MimeMessage message = sender.createMimeMessage();
        message.setFrom(new InternetAddress("sender@example.com", "Example Sender"));
        message.setRecipients(jakarta.mail.Message.RecipientType.TO, "recipient@example.com");
        message.setSubject(subject);
        return message;
    }

    private static void populate(MimeMessage message, String body) throws Exception {
        message.setFrom(new InternetAddress("sender@example.com", "Example Sender"));
        message.setRecipients(jakarta.mail.Message.RecipientType.TO, "recipient@example.com");
        message.setSubject("subject");
        message.setText(body, "UTF-8");
    }

    private static List<String> headerLines(MimeMessage message) throws Exception {
        return Collections.list((Enumeration<String>) message.getAllHeaderLines());
    }

    private static final class FakeSender implements JavaMailSender {
        final List<SimpleMailMessage> simpleSingles = new ArrayList<>();
        final List<MimeMessage> mimeSingles = new ArrayList<>();
        final List<MimeMessage[]> mimeBatches = new ArrayList<>();
        SimpleMailMessage[] lastSimpleBatch;
        MimeMessage[] lastMimeBatch;
        int standardPreparators;

        @Override public MimeMessage createMimeMessage() {
            return new MimeMessage(Session.getInstance(new Properties()));
        }
        @Override public MimeMessage createMimeMessage(InputStream stream) throws MailException {
            try { return new MimeMessage(Session.getInstance(new Properties()), stream); }
            catch (Exception exception) { throw new IllegalStateException(exception); }
        }
        @Override public void send(MimeMessage message) { mimeSingles.add(message); }
        @Override public void send(MimeMessage... messages) {
            lastMimeBatch = messages;
            mimeBatches.add(messages);
        }
        @Override public void send(MimeMessagePreparator preparator) { standardPreparators++; }
        @Override public void send(MimeMessagePreparator... preparators) { standardPreparators++; }
        @Override public void send(SimpleMailMessage message) { simpleSingles.add(message); }
        @Override public void send(SimpleMailMessage... messages) { lastSimpleBatch = messages; }
    }

    private static final class CountingDataSource implements DataSource {
        final AtomicInteger openCount = new AtomicInteger();
        private final String contentType;

        private CountingDataSource() {
            this("application/octet-stream");
        }

        private CountingDataSource(String contentType) {
            this.contentType = contentType;
        }

        @Override public InputStream getInputStream() {
            openCount.incrementAndGet();
            return new ByteArrayInputStream(new byte[1_000_000]);
        }
        @Override public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }
        @Override public String getContentType() {
            return contentType;
        }
        @Override public String getName() {
            return "lazy-binary-fixture";
        }
    }
}
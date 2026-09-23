package com.sendrepute.mail;

import jakarta.activation.DataHandler;
import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

final class MimeEmailExtractor {
    private static final int MAX_BODY_CHARACTERS = 524_288;
    private static final int MAX_DISPLAYED_BYTES = 524_288;
    private static final int MAX_PARTS = 256;
    private static final int MAX_DEPTH = 16;
    private static final String OBJECT_DATA_SOURCE = "jakarta.activation.DataHandlerDataSource";
    private static final String MIME_PART_DATA_SOURCE = "jakarta.mail.internet.MimePartDataSource";
    ExtractedEmail extract(MimeMessage message) {
        try {
            // Do not call saveChanges(), writeTo(), or the MimeMessage copy
            // constructor: real Jakarta Mail may generate headers on the
            // caller-owned source as a side effect.
            String sender = senderDisplayName(message);
            String subject = message.getSubject();
            if (subject == null || subject.isBlank() || subject.length() > 998) {
                throw unsupported("subject must contain 1..998 characters");
            }
            List<String> displayed = new ArrayList<>();
            Traversal traversal = new Traversal();
            collect(message, displayed, traversal, 0);
            if (displayed.isEmpty()) {
                throw unsupported("message has no supported displayed text");
            }
            String body = frame(displayed);
            if (body.length() > MAX_BODY_CHARACTERS) {
                throw unsupported("normalized displayed text exceeds 524288 characters");
            }
            return new ExtractedEmail(sender, subject, body);
        } catch (MessagingException | IOException exception) {
            throw new SendReputeException("unsupported_message", "MIME message could not be normalized safely", exception);
        }
    }

    private static void collect(Part part, List<String> displayed, Traversal traversal, int depth)
            throws MessagingException, IOException {
        if (depth > MAX_DEPTH) throw unsupported("MIME nesting depth exceeds " + MAX_DEPTH);
        if (++traversal.parts > MAX_PARTS) throw unsupported("MIME part count exceeds " + MAX_PARTS);
        String disposition = part.getDisposition();
        if (Part.ATTACHMENT.equalsIgnoreCase(disposition)) return;
        DataHandler handler = part.getDataHandler();
        String contentType = handler.getContentType();
        String mediaType = mediaType(contentType);
        if (mediaType.equals("multipart/encrypted") || mediaType.equals("multipart/signed")
                || mediaType.startsWith("message/")) {
            throw unsupported("encrypted, signed, and nested messages require an explicit unsupported-message decision");
        }
        if (mediaType.startsWith("multipart/")) {
            Object content = boundedObjectContent(part, handler, MAX_DISPLAYED_BYTES);
            if (!(content instanceof Multipart multipart)) throw unsupported("unsupported multipart content");
            int childCount = multipart.getCount();
            if (childCount > MAX_PARTS - traversal.parts) {
                throw unsupported("MIME part count exceeds " + MAX_PARTS);
            }
            for (int index = 0; index < childCount; index++) {
                BodyPart child = multipart.getBodyPart(index);
                collect(child, displayed, traversal, depth + 1);
            }
            return;
        }
        if (mediaType.equals("text/plain")) {
            displayed.add(readText(part, handler, contentType, traversal));
            return;
        }
        if (mediaType.equals("text/html")) {
            displayed.add(visibleHtml(readText(part, handler, contentType, traversal)));
            return;
        }
        if (disposition == null && part.getFileName() == null
                && !mediaType.startsWith("image/") && !mediaType.startsWith("application/")) {
            throw unsupported("unsupported displayed MIME type: " + contentType);
        }
    }

    private static String readText(Part part, DataHandler handler, String contentType, Traversal traversal)
            throws MessagingException, IOException {
        String source = handler.getDataSource().getClass().getName();
        String text;
        if (source.equals(OBJECT_DATA_SOURCE)) {
            Object content = handler.getContent();
            if (!(content instanceof String value)) {
                throw unsupported("object-backed text is not a string");
            }
            text = value;
        } else if (source.equals(MIME_PART_DATA_SOURCE)) {
            int declaredSize = part.getSize();
            int remaining = MAX_DISPLAYED_BYTES - traversal.displayedBytes;
            if (declaredSize < 0 || declaredSize > remaining) {
                throw unsupported("lazy text size is unknown or exceeds the displayed-text limit");
            }
            try (InputStream stream = handler.getInputStream()) {
                byte[] bytes = stream.readNBytes(remaining + 1);
                if (bytes.length > remaining) throw unsupported("displayed text exceeds 524288 bytes");
                text = new String(bytes, charset(contentType));
            }
        } else {
            throw unsupported("unknown lazy displayed-text source");
        }
        if (text.length() > MAX_BODY_CHARACTERS) throw unsupported("displayed part is too large");
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_DISPLAYED_BYTES - traversal.displayedBytes) {
            throw unsupported("aggregate displayed text exceeds 524288 bytes");
        }
        traversal.displayedBytes += bytes;
        return text;
    }

    private static Object boundedObjectContent(Part part, DataHandler handler, int maximum)
            throws MessagingException, IOException {
        String source = handler.getDataSource().getClass().getName();
        if (!source.equals(OBJECT_DATA_SOURCE)) {
            int size = part.getSize();
            if (!source.equals(MIME_PART_DATA_SOURCE) || size < 0 || size > maximum) {
                throw unsupported("unknown or oversized lazy multipart source");
            }
        }
        return handler.getContent();
    }

    private static String mediaType(String contentType) {
        try {
            return new ContentType(contentType).getBaseType().toLowerCase(Locale.ROOT);
        } catch (Exception exception) {
            throw unsupported("invalid MIME content type");
        }
    }

    private static Charset charset(String contentType) {
        try {
            String name = new ContentType(contentType).getParameter("charset");
            return name == null ? StandardCharsets.US_ASCII : Charset.forName(name);
        } catch (Exception exception) {
            throw unsupported("invalid or unsupported text charset");
        }
    }

    private static String visibleHtml(String html) {
        Document document = Jsoup.parse(html);
        document.select("head,script,style,template,svg,xml,[hidden],[aria-hidden=true]").remove();
        for (Element element : document.getAllElements()) {
            String style = element.attr("style").toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
            if (style.contains("display:none") || style.contains("visibility:hidden")
                    || style.contains("mso-hide:all") || style.matches(".*(?:font-size|max-height):0(?:px|pt|em|rem|%)?(?:;.*)?")) {
                element.remove();
            }
        }
        return document.text();
    }

    /*
     * Server normalization decodes QP/base64 before stripping markup/CSS. Every
     * fragment is first reduced to displayed text, then metacharacters for all
     * later server passes are numeric entities. Entity decoding is the server's
     * final structural pass, so one fragment cannot consume a later fragment.
     */
    static String frame(List<String> parts) {
        StringBuilder body = new StringBuilder("SendRepute displayed alternatives ");
        for (int index = 0; index < parts.size(); index++) {
            body.append(" alternative ").append(index + 1).append(" ");
            String text = parts.get(index);
            for (int offset = 0; offset < text.length(); offset++) {
                char character = text.charAt(offset);
                switch (character) {
                    case '&' -> body.append("&#38;");
                    case '<' -> body.append("&#60;");
                    case '>' -> body.append("&#62;");
                    case '=' -> body.append("&#61;");
                    case ':' -> body.append("&#58;");
                    case '{' -> body.append("&#123;");
                    case '}' -> body.append("&#125;");
                    default -> body.append(character);
                }
            }
            body.append(' ');
        }
        return body.toString();
    }

    private static String senderDisplayName(MimeMessage message) throws MessagingException {
        Address[] from = message.getFrom();
        if (from == null || from.length != 1 || !(from[0] instanceof InternetAddress address)) {
            throw unsupported("exactly one InternetAddress sender is required");
        }
        String personal = address.getPersonal();
        if (personal == null || personal.isBlank() || personal.length() > 320) {
            throw unsupported("sender display name is required and must not be an email address");
        }
        return personal;
    }

    private static SendReputeException unsupported(String detail) {
        return new SendReputeException("unsupported_message", detail);
    }

    private static final class Traversal {
        private int parts;
        private int displayedBytes;
    }
}
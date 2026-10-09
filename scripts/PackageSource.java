import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class PackageSource {
    private static final String PREFIX = "sendrepute-spring-mail-0.1.2/";
    private static final FileTime EPOCH = FileTime.fromMillis(0);
    private static final List<String> FILES = List.of(
            "LICENSE", "README.md", "SECURITY.md", "pom.xml", "sendrepute-manifest.json",
            "scripts/PackageSource.java",
            "src/main/java/com/sendrepute/mail/ClassificationResult.java",
            "src/main/java/com/sendrepute/mail/Classifier.java",
            "src/main/java/com/sendrepute/mail/ExtractedEmail.java",
            "src/main/java/com/sendrepute/mail/MimeEmailExtractor.java",
            "src/main/java/com/sendrepute/mail/SendReputeBlockedException.java",
            "src/main/java/com/sendrepute/mail/SendReputeClassifier.java",
            "src/main/java/com/sendrepute/mail/CustomerServices.java",
            "src/main/java/com/sendrepute/mail/CustomerServiceClient.java",
            "src/main/java/com/sendrepute/mail/SendReputeConfiguration.java",
            "src/main/java/com/sendrepute/mail/SendReputeException.java",
            "src/main/java/com/sendrepute/mail/SendReputeJavaMailSender.java");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|"
                    + "(?:api[_-]?token|authorization)\\s*[:=]\\s*[\"'][A-Za-z0-9_./+\\-=]{20,}");

    public static void main(String[] args) throws Exception {
        Path root = Files.exists(Path.of("pom.xml")) ? Path.of(".") : Path.of("integrations/java");
        Path output = root.resolve("dist/sendrepute-spring-mail-0.1.2-source.zip");
        Files.createDirectories(output.getParent());
        Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
            zip.setLevel(9);
            for (String name : FILES) add(zip, root, name);
        }
        Files.move(temporary, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        System.out.println(output.toAbsolutePath().normalize());
    }

    private static void add(ZipOutputStream zip, Path root, String name) throws IOException {
        Path path = root.resolve(name);
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) {
            throw new IOException("allowlisted entry is not a regular file: " + name);
        }
        byte[] content = Files.readAllBytes(path);
        String text = new String(content, StandardCharsets.UTF_8);
        if (SECRET.matcher(text).find()) throw new IOException("possible credential in " + name);
        ZipEntry entry = new ZipEntry(PREFIX + name);
        entry.setLastModifiedTime(EPOCH);
        entry.setLastAccessTime(EPOCH);
        entry.setCreationTime(EPOCH);
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }
}
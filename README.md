# SendRepute Spring JavaMail adapter

## Customer services

`CustomerServiceClient` exposes all 49 customer API operations independently of
the mail-send decorator. Its fixed destination cannot be replaced with an
arbitrary URL.

```java
var services = new CustomerServiceClient(apiToken, Duration.ofSeconds(180));
var account = services.request("customerGetAccount", null, Map.of(), Map.of(), false);
```

The remaining arguments are a JSON body, path parameters, query parameters and
per-request mutation authorization. Obtain and review server quotes and submit
expected-price fields before authorizing paid operations. No operation retries
automatically; preserve recovery/result IDs if the response is interrupted.

Version 0.1.2 is a Java 17 / Spring Framework 6 library that decorates a
`JavaMailSender`. It has no auto-configuration and never captures ordinary mail.
Only an explicit `sendWithSendRepute(...)` call can make the paid pre-send
request. Classification is an advisory content signal, not an inbox-placement
or deliverability guarantee.

## Install locally

This artifact is not published to Maven Central or another registry. Build or
install the reviewed source ZIP locally:

```sh
unzip sendrepute-spring-mail-0.1.1-source.zip
cd sendrepute-spring-mail-0.1.1
mvn verify
mvn install
```

The supported and tested baseline is Java 17 with Spring Framework 6.2.19. No
Spring Boot 4 compatibility claim is made. The consuming application must
provide a configured `JavaMailSender`; `angus-mail` is a runtime dependency.

## Explicit bean and per-message opt-in

Both `enabled` and `paidAnalysisConsent` default OFF. Keep the bearer token in
server secret configuration; never expose it to a browser, message, checked-in
property file, or log.

```java
@Bean
SendReputeJavaMailSender sendReputeSender(
        JavaMailSender transport,
        @Value("${SENDREPUTE_API_TOKEN:}") String token,
        @Value("${SENDREPUTE_ENABLED:false}") boolean enabled,
        @Value("${SENDREPUTE_PAID_CONSENT:false}") boolean consent) {
    var settings = new SendReputeConfiguration(
        token, enabled, consent,
        SendReputeConfiguration.Mode.ADVISORY,
        SendReputeConfiguration.FailurePolicy.ALLOW,
        0.80, Duration.ofSeconds(3), Duration.ofSeconds(10));
    return new SendReputeJavaMailSender(transport, settings);
}
```

Call `sendWithSendRepute(message)` only where the paid analysis is intended.
The explicit route has single and varargs overloads for `SimpleMailMessage`,
`MimeMessage`, and `MimeMessagePreparator`. Password-reset, MFA, security-alert,
account-recovery, and other critical mail should use ordinary `send(...)` unless
its owner explicitly opts that individual send into analysis. Every ordinary
`JavaMailSender.send(...)` overload delegates unchanged and makes no API call.

Preparators are rendered exactly once. The same rendered `MimeMessage` is
classified and delegated. Existing `MimeMessage` objects and varargs arrays are
delegated by identity; `SimpleMailMessage` is rendered only for inspection and
the original is delegated. Recipients, envelope, headers, attachments, body,
and transport configuration are not rewritten. Inspection never calls
`saveChanges()`, `writeTo()`, or the Jakarta Mail copy constructor on a
caller-owned message, because those operations can generate headers.

For every varargs route, all messages are prepared and safely normalized before
the first paid call. A later malformed message or throwing preparator therefore
cannot charge an earlier item. Once the entire batch is known to be sendable,
classification requests remain per-message because the public API classifies
one message per request.

`ADVISORY` never blocks on a score. `BLOCKING` rejects a finite
`spamProbability` at or above the configured 0..1 threshold.
`FailurePolicy.ALLOW` and `FailurePolicy.BLOCK` independently choose what to do
with configuration, malformed MIME, transport, API, and response failures.
There are no retries.

## MIME and API security boundary

The endpoint is fixed to
`https://www.sendrepute.com/api/v1/classify`; callers cannot configure a host.
The JDK client uses verified HTTPS, refuses redirects, has bounded connect and
whole-request deadlines, and writes/reads at most 1 MiB. It sends only sender display
name, subject, and normalized displayed body. It does not send addresses,
recipients, attachments, envelope metadata, or raw MIME.

All displayed `text/plain` and `text/html` MIME parts are collected. Attachments
are excluded. Signed, encrypted, nested-message, unknown/unbounded lazy,
oversized, or otherwise ambiguous displayed MIME is rejected before a paid
request (then the independent failure policy applies). HTML hidden/raw elements
are removed before all alternatives are combined. Each fragment's `<`, `>`,
`&`, `=`, MIME-header colon, and CSS braces is encoded as a numeric entity.
This is deliberate:
the production `visibleEmailText` normalizer performs base64/QP, markup and CSS
passes before entity decoding, so an earlier fragment cannot consume a later
one.

Traversal is bounded to 16 nested multipart levels, 256 total parts, and
524,288 aggregate displayed UTF-8 bytes. The adapter reads disposition and
`DataHandler` content type before content. Inline binary/application parts are
never materialized. Unknown lazy text/multipart sources and lazy parts without
a trustworthy bounded size are rejected before opening their stream; supported
Jakarta `MimePartDataSource` text is read through a bounded stream. These checks
also happen for an entire varargs batch before its first paid request.

The request is exactly `{sender, subject, body}`. Response validation follows
the OpenAPI schema recursively: exact object keys, model/label/confidence/audit
enums, request-ID bounds, all required result fields, typed reason and string
arrays, optional flagged count, fully typed optional content audit, and exact
integer/boolean billing fields. Unknown or mistyped data is fail-policy input,
never a classification decision.

## Verification and packaging

```sh
mvn test
java scripts/PackageSource.java
```

The recorded verification environment was GraalVM CE JDK 19.0.2 compiling with
`javac --release 17`, Maven 3.8.6, Spring Framework 6.2.19, Jakarta Mail API
2.1.3 / Angus Mail 2.0.4, Jackson 2.18.11, and jsoup 1.23.2. This validates the
Java 17 bytecode/API floor against actual dependencies; it is not a broad
certification of untested framework versions.

Tests use the real Spring 6 `JavaMailSender`/Jakarta Mail types and offline HTTP
response fixtures. They cover transparent standard sends, all six opted-in
single/varargs families, one-time preparator rendering, preserved object
identity, mixed text/HTML alternatives, malformed API data, thresholds,
failure policy, disabled/consent gates, traversal depth/count/aggregate limits,
unknown lazy text, zero-materialization inline lazy binary, and the public
OpenAPI path/schema.
`ContractAndNormalizerTest` invokes the repository's actual exported
`visibleEmailText` function from
`artifacts/api-server/src/lib/classifier-features.ts`. Payload regressions cover
CSS-brace removal, whole-body base64-looking text longer than 300 characters,
MIME-style base64 headers, QP markers, markup, and preservation of every later
fragment. Separate fixtures lock exact response rejection. No test sends email,
performs a paid call, or needs network access.

The deterministic source ZIP uses a fixed timestamp and an exact allowlist. It
excludes tests, build output, credentials, caches, IDE files, and vendored
dependencies. Packaging scans included text for private keys and token-like
secrets. The ZIP is local and unpublished.

See `SECURITY.md` for private reporting.
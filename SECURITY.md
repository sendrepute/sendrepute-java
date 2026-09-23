# Security policy

Report vulnerabilities privately to support@sendrepute.com. Do not include API
tokens, customer email content, recipient data, or unredacted logs.

Version 0.1.x receives security fixes while maintained. The adapter intentionally
has a fixed HTTPS endpoint, no redirects or automatic retries, bounded requests,
explicit paid consent, per-send opt-in, and a fail-open/fail-closed policy that
is independent from advisory/blocking score mode. Treat any relaxation of those
boundaries or incomplete MIME-alternative inspection as security-sensitive.
MIME traversal is capped at 16 levels, 256 parts, and 524,288 displayed UTF-8
bytes. Unknown lazy displayed sources are rejected, and inline binary content
is type-checked without materialization, before any paid call.

Rotate the API token immediately if it may have entered source, a mail message,
logs, a source ZIP, or client-side configuration.
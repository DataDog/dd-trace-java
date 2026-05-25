# Akka HTTP AppSec Instrumentation

Extended reference: [docs/appsec/ig-events.md](../../../../docs/appsec/ig-events.md),
[docs/appsec/blocking-patterns.md](../../../../docs/appsec/blocking-patterns.md)

## Two multipart routes must both be instrumented

Akka HTTP has two independent entry points for multipart form data. Any new WAF address that
captures multipart body data must instrument both:

| Route | Entry point | Notes |
|---|---|---|
| Route 1 | `handleMultipartStrictFormData(Multipart$FormData$Strict)` | Has `reqCtx` as local variable; iterates via `getStrictParts()` |
| Route 2 | `handleStrictFormData(StrictForm)` | No `reqCtx` in scope; must obtain via `activeSpan()` |

If only Route 1 is instrumented, multipart requests processed via `formFieldMultiMap` (Route 2)
silently miss the WAF event.

## Do not extract filenames callback dispatch into `UnmarshallerHelpers`

Extracting the `requestFilesFilenames` callback dispatch into a shared helper method in
`UnmarshallerHelpers` is known to cause problems. Keep dispatch inline in each advice class.

## No `effectivelyBlocked()` in Akka advice

Akka HTTP uses the Netty-style blocking model. Do not call `effectivelyBlocked()` in advice.
See [docs/appsec/blocking-patterns.md](../../../../docs/appsec/blocking-patterns.md#netty-never-effectivelyblocked).

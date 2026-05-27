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

Keep the `requestFilesFilenames` callback dispatch inline in each advice class. The two routes
obtain `reqCtx` differently and handle the `pendingBlock` state separately. When the dispatch was
previously extracted into a shared helper, the filenames callback was silently skipped on Route 2
when the body had already triggered blocking, because the helper did not propagate the pending
`BlockingException` correctly across the two call sites.

## No `effectivelyBlocked()` in Akka advice

Akka HTTP uses the Netty-style blocking model. Do not call `effectivelyBlocked()` in advice.
See [docs/appsec/blocking-patterns.md](../../../../docs/appsec/blocking-patterns.md#netty-never-effectivelyblocked).

# Undertow AppSec Instrumentation

Extended reference: [docs/appsec/blocking-patterns.md](../../../../docs/appsec/blocking-patterns.md),
[docs/appsec/ig-events.md](../../../../docs/appsec/ig-events.md)

## `tryCommitBlockingResponse` is not idempotent in Undertow

Unlike Tomcat/Jersey, Undertow's `tryCommitBlockingResponse` commits the response immediately.
Calling it twice sends a duplicate response and can throw an `IllegalStateException`.

When an advice has two blocking paths (body + filenames), the `t == null` guard must be checked
BEFORE each `tryCommitBlockingResponse` call, not after:

```java
// Wrong -- commits response even if already blocked
if (bodyCallback != null && !map.isEmpty()) {
    Flow<Void> flow = bodyCallback.apply(ctx, map);
    t = tryBlock(flow, ctx);
}
if (filenamesCallback != null && !filenames.isEmpty()) {
    Flow<Void> flow = filenamesCallback.apply(ctx, filenames);
    t = tryBlock(flow, ctx);  // tryBlock calls tryCommitBlockingResponse again
}

// Correct -- guard prevents second commit
if (bodyCallback != null && !map.isEmpty()) {
    Flow<Void> flow = bodyCallback.apply(ctx, map);
    t = tryBlock(flow, ctx);
}
if (t == null && filenamesCallback != null && !filenames.isEmpty()) {
    Flow<Void> flow = filenamesCallback.apply(ctx, filenames);
    t = tryBlock(flow, ctx);
}
```

See [docs/appsec/ig-events.md](../../../docs/appsec/ig-events.md#undertow-trycommitblockingresponse-is-not-idempotent)
for the full explanation of why this differs from other Servlet containers.

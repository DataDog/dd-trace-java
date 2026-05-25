# Netty 4.1 AppSec Instrumentation

Extended reference: [docs/appsec/blocking-patterns.md](../../../../docs/appsec/blocking-patterns.md)

## NEVER call `effectivelyBlocked()` in Netty advice

`effectivelyBlocked()` must not appear in any Netty advice class or helper. `BlockingResponseHandler` calls it internally when the blocking response is committed. Calling it in advice causes a double-invocation and closes the span prematurely.

This applies even to the urlencoded body-processed path in `HttpPostRequestDecoderInstrumentation` -- the span is already closed synchronously by `tryCommitBlockingResponse()` before advice can reach the mark.

Correct pattern:
```java
// Netty advice — blocking path
if (brf != null) {
    brf.tryCommitBlockingResponse(ctx.getTraceSegment(), rba);
    thr = new BlockingException("...");
    // do NOT call ctx.getTraceSegment().effectivelyBlocked()
}
```

## `thr` creation must be inside `if (brf != null)`

If `thr = new BlockingException(...)` is placed outside the `brf != null` guard, it is created even when no blocking action exists. The exception is then thrown unconditionally in `@Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)`, producing spurious 500 responses.

## FileUpload content: two-branch read

`HttpData` stores uploads either in memory or on disk depending on size. Always branch on `isInMemory()`:

```java
if (fileUpload.isInMemory()) {
    ByteBuf buf = fileUpload.getByteBuf();
    // read from buf
} else {
    File f = fileUpload.getFile();
    try (InputStream is = new FileInputStream(f)) {
        // read from stream
    }
}
```

Never call `getFile()` on an in-memory upload -- the file does not exist on disk and the call throws.

## `@RequiresRequestContext` + `Config.get()` causes muzzle failure

Do not declare `static final` fields initialized from `Config.get()` in any `@RequiresRequestContext`-annotated advice inner class. Muzzle validates those classes against the instrumented library's classpath (Netty), where `Config` is absent, causing `MuzzleValidationException` in CI.

Move such constants to the helper class declared in `helperClassNames()`:

```java
// Wrong -- in @RequiresRequestContext advice inner class
private static final int MAX_FILES = Config.get().getAppSecMaxFileContentCount();

// Correct -- in NettyMultipartHelper or similar helper
public class NettyMultipartHelper {
    static final int MAX_FILES_TO_INSPECT = Config.get().getAppSecMaxFileContentCount();
}
```

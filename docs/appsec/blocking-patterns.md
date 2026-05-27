# AppSec Blocking Patterns

How to correctly implement request blocking in advice classes. The pattern differs by server — getting it wrong causes silent failures where the block action is lost.

## Netty 4.1

### `effectivelyBlocked()` — never call it in Netty advice

`BlockingResponseHandler` calls `effectivelyBlocked()` internally as part of the blocking pipeline. When advice calls `tryCommitBlockingResponse()`, that triggers `BlockingResponseHandler`, which calls `effectivelyBlocked()` and finishes the span (`span.finish()`). A second call from advice on an already-finished span throws an exception.

The advice has `@Advice.OnMethodExit(suppress = Throwable.class)`. With `suppress = Throwable.class`, the exception from `effectivelyBlocked()` is silently swallowed — the advice does not visibly fail. But if code follows `effectivelyBlocked()` (typically `thr = new BlockingException(...)`), that code **never executes** because the exception interrupted the flow. Result: the request is not aborted.

**Correct pattern in Netty:**
```java
BlockResponseFunction brf = reqCtx.getBlockResponseFunction();
if (brf != null) {
    brf.tryCommitBlockingResponse(reqCtx.getTraceSegment(), rba);
    thr = new BlockingException("Blocked request");
    // DO NOT call effectivelyBlocked() here — BlockingResponseHandler already did it
}
```

### Exception: urlencoded body-processed path

The body-processed (urlencoded) path in `HttpPostRequestDecoderInstrumentation` must **not** call `effectivelyBlocked()` even in the pattern above. In this path, `tryCommitBlockingResponse()` closes the span synchronously (in the Netty event loop). Calling `effectivelyBlocked()` afterwards raises `AssertionError: Interaction with TraceSegment after root span has already finished`, which is swallowed by `suppress = Throwable.class` and registered as an instrumentation error.

The multipart paths (filenames, content) in the same advice **do** call `effectivelyBlocked()` within `if (brf != null)`. The asymmetry is intentional.

| Path in `HttpPostRequestDecoderInstrumentation` | `effectivelyBlocked()` |
|---|---|
| body-processed (urlencoded) | no |
| filenames (multipart) | yes, inside `if (brf != null)` |
| content (multipart) | yes, inside `if (brf != null)` |

### `thr` must be assigned inside `if (brf != null)`

`thr = new BlockingException(...)` must be inside the `if (brf != null)` block. If assigned outside, it is set even when no blocking response function is available, causing the decoder to abort without having sent a blocking response.

### Netty FileUpload content reading

When reading bytes from `HttpData`/`FileUpload` for `files_content`, there are exactly two correct paths:

```java
if (fileUpload.isInMemory()) {
    ByteBuf buf = fileUpload.getByteBuf();
    int length = (int) Math.min((long) MAX_CONTENT_BYTES, (long) buf.readableBytes());
    byte[] bytes = new byte[length];
    buf.getBytes(buf.readerIndex(), bytes);  // absolute read — does NOT advance readerIndex
    contentStr = MultipartContentDecoder.decodeBytes(bytes, length, fileUpload.getContentType());
} else {
    byte[] bytes = new byte[MAX_CONTENT_BYTES];
    try (FileInputStream fis = new FileInputStream(fileUpload.getFile())) {
        int read = fis.read(bytes);
        contentStr = MultipartContentDecoder.decodeBytes(bytes, read < 0 ? 0 : read, fileUpload.getContentType());
    }
}
```

**Why not the alternatives:**
- `getInputStream()` — not declared in the `HttpData` interface; only available on `AbstractDiskHttpData` (internal class). Compilation fails on `FileUpload` type.
- `getByteBuf()` on disk-backed uploads — `AbstractDiskHttpData.getByteBuf()` loads the entire file into RAM. Catastrophic for large uploads.
- `get()` — same as above, loads everything.
- `getChunk(int)` — maintains internal state (`fileChannel`, `chunkPosition`). Side effects can interfere with Netty's upload lifecycle.

---

## Tomcat / Servlet containers (Tomcat, GlassFish, Payara)

### `effectivelyBlocked()` — always call it, but after assigning `t`

Unlike Netty, the blocking response function in Tomcat does **not** call `effectivelyBlocked()` internally. It must be called explicitly in the advice.

**Mandatory ordering:** assign `t = new BlockingException(...)` **before** calling `effectivelyBlocked()`.

The advice has `@Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)`. If `effectivelyBlocked()` throws and `suppress = Throwable.class` swallows the exception, and `effectivelyBlocked()` was called before assigning `t`, then `t` is never assigned and the request is not aborted.

**Correct pattern — all three blocking paths:**
```java
BlockResponseFunction brf = reqCtx.getBlockResponseFunction();
if (brf != null) {
    brf.tryCommitBlockingResponse(reqCtx.getTraceSegment(), rba);
    t = new BlockingException("Blocked request (...)");   // 1. assign t
    reqCtx.getTraceSegment().effectivelyBlocked();         // 2. then effectivelyBlocked
}
```

The three blocking paths in a typical Servlet advice (body-processed, filenames, content) must all follow this pattern consistently.

### `t` must be assigned inside `if (brf != null)`

Same rule as Netty: `t = new BlockingException(...)` must be inside `if (brf != null)`. Assigning it outside aborts the request even when no blocking response could be sent.

### Filenames → content: sequential ordering

When an advice handles both `requestFilesFilenames` and `requestFilesContent`:
- Fire filenames first.
- Fire content **only if** filenames did not block (`if (t == null)`).

Filenames and content represent the same file upload. If the request is already blocked by filename, sending content to the WAF adds no value.

```java
// filenames path
if (filenamesCallback != null && !filenames.isEmpty()) {
    Flow<Void> flow = filenamesCallback.apply(reqCtx, filenames);
    if (action instanceof Flow.Action.RequestBlockingAction) {
        if (brf != null) {
            brf.tryCommitBlockingResponse(...);
            t = new BlockingException("Blocked (filenames)");
            reqCtx.getTraceSegment().effectivelyBlocked();
        }
    }
}

// content path — only if filenames did not block
if (t == null && contentCallback != null) {
    List<String> contents = collector.getContents();
    if (!contents.isEmpty()) {
        Flow<Void> flow = contentCallback.apply(reqCtx, contents);
        // ... same blocking pattern
    }
}
```

---

## Body + filenames: independent callbacks — both must always fire

When an advice handles both `requestBodyProcessed` and `requestFilesFilenames` (or `requestFilesContent`) for the same request body, both callbacks must fire even if the first one triggers blocking. These are independent WAF addresses — a WAF config with only filenames rules will have `bodyCallback == null`. Silencing one because the other blocked deprives the WAF of information.

**Wrong — throw on body blocks before filenames fires:**
```java
executeCallback(reqCtx, bodyCallback, conv);  // throws BlockingException if blocked
filenamesCallback.apply(reqCtx, filenames);   // never reached
```

**Correct — `pendingBlock` pattern:**
```java
BlockingException pendingBlock = null;

if (bodyCallback != null) {
    Flow<Void> flow = bodyCallback.apply(reqCtx, conv);
    if (action instanceof Flow.Action.RequestBlockingAction) {
        BlockResponseFunction brf = reqCtx.getBlockResponseFunction();
        if (brf != null) {
            boolean success = brf.tryCommitBlockingResponse(reqCtx.getTraceSegment(), rba);
            if (success) {
                pendingBlock = new BlockingException("Blocked (body)");
            }
        }
    }
}

if (filenamesCallback != null && filenames != null && !filenames.isEmpty()) {
    Flow<Void> flow = filenamesCallback.apply(reqCtx, filenames);
    if (pendingBlock == null && action instanceof Flow.Action.RequestBlockingAction) {
        // apply blocking only if body did not block first
        ...
        pendingBlock = new BlockingException("Blocked (filenames)");
    }
}

if (pendingBlock != null) throw pendingBlock;
```

The blocking logic (`tryCommitBlockingResponse`, `new BlockingException`) is specific to each server's `BlockResponseFunction`. It can be extracted to a helper **within the same module** but must **never be shared across different server modules**.

---

## Undertow

`UndertowBlockResponseFunction.tryCommitBlockingResponse` is **not idempotent**. Each call:
- Overwrites `exchange.putAttachment(TRACE_SEGMENT, segment)`
- Overwrites `exchange.putAttachment(REQUEST_BLOCKING_DATA, rab)`
- Dispatches to `UndertowBlockingHandler`

If two blocking paths exist (body + filenames), the second call to `tryCommitBlockingResponse` generates a second dispatch on the IO thread.

**The guard `t == null` must be placed before the call to `tryCommitBlockingResponse`**, not after:

```java
// Correct — guard before
if (brf != null && t == null) {
    boolean success = brf.tryCommitBlockingResponse(...);
    if (success) {
        t = new BlockingException("...");
    }
}

// Wrong — guard after: tryCommitBlockingResponse already ran
if (brf != null) {
    boolean success = brf.tryCommitBlockingResponse(...);
    if (success && t == null) {  // too late
        t = new BlockingException("...");
    }
}
```

---

## GlassFish / Payara (module `tomcat-appsec-7.0`)

`TomcatServerInstrumentation` is not active on Payara because `PECoyoteResponse` is not `org.apache.catalina.connector.Response`. As a result, `BlockResponseFunction` is never registered and `reqCtx.getBlockResponseFunction()` returns `null`.

**Fallback:** obtain `HttpServletRequest` and `HttpServletResponse` from the `Multipart.request` field via `@Advice.FieldValue`, typed as `org.apache.catalina.Request` (the Catalina interface). See `GlassFishBlockingHelper` for the full implementation.

**Why the interface, not the concrete class:** `connector.Request.getResponse()` has different JVM descriptors in Tomcat vs. GlassFish. `org.apache.catalina.Request.getResponse()` has the same descriptor in both — safe for muzzle.

`GlassFishBlockingHelper.tryBlock()` uses a two-phase try/catch to handle the case where `effectivelyBlocked()` throws because the span was already finished after a successful commit:

```java
try {
    // commit the blocking response (brf or fallback)
} catch (Exception ignored) {
    return false;  // commit failed — cannot block this request
}
try {
    reqCtx.getTraceSegment().effectivelyBlocked();
} catch (Exception ignored) {
    // span already finished — response was sent, blocking succeeded
}
return true;
```

**`Collections.emptyList()` instead of `BlockingException`** in the GlassFish advice return: propagating `BlockingException` through the Grizzly/Payara pipeline causes an uncontrolled shutdown. Returning an empty list is equivalent — the controller receives zero parts to process.


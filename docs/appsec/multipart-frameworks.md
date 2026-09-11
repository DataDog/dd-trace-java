# Multipart AppSec — Cross-Cutting Patterns

Invariants for implementing `server.request.body.filenames` and `server.request.body.files_content` across multipart frameworks.

## Reflection cache pattern for version-variant return types

When a method's return type differs between library versions (e.g., `getHeaders()` returns `javax.ws.rs.core.MultivaluedMap` in RESTEasy 3.x and `jakarta.ws.rs.core.MultivaluedMap` in RESTEasy 6.x), a direct bytecode reference fails muzzle for the other version.

**Canonical solution:** cache the `Method` as a `static final` field, resolved once in a `static {}` block:

```java
private static final Method GET_HEADERS;

static {
    Method m = null;
    try {
        m = InputPart.class.getMethod("getHeaders");
    } catch (NoSuchMethodException ignored) {
    }
    GET_HEADERS = m;
}
```

**Why `static final` and not `volatile`:** class initialization is atomic by JVM spec — the `static {}` block runs exactly once when the classloader loads the helper class. No race condition. `volatile` would be needed only for lazy initialization in an instance or a non-static field.

**Runtime cost:** zero per request. The reflection lookup happens once at class load time in the app classloader, where the library is present.

**Null guard required:** always check `if (GET_HEADERS == null) return ...` before invoking. The static block may set the field to `null` if the class is not present in the classpath (incompatible version).

This pattern applies to any method whose return type or parameters differ between major library versions.

## Per-part try/catch in for-loops

`suppress = Throwable.class` on the advice protects the **advice boundary**, not the interior of loops inside helpers. If an exception is thrown while processing part N, it short-circuits the remaining parts — the WAF receives only partial data with no visible error.

```java
for (FormDataBodyPart part : parts) {
    try {
        FormDataContentDisposition cd = part.getFormDataContentDisposition();
        collectBodyPart(part, cd, map, filenames);
    } catch (Exception ignored) {
        // malformed part — continue processing remaining parts
    }
}
```

This applies to any helper that iterates over parts, items, or framework elements where each element can fail independently.

## Servlet containers: blocking ordering in `tryBlock()`

When implementing a `tryBlock()` helper for Servlet-based frameworks (Jersey, RESTEasy, Tomcat), the ordering inside the helper must be:

```java
brf.tryCommitBlockingResponse(ctx.getTraceSegment(), rba);
BlockingException be = new BlockingException(message);  // 1. create exception
ctx.getTraceSegment().effectivelyBlocked();              // 2. mark blocked
return be;
```

`new BlockingException` before `effectivelyBlocked()`: if `effectivelyBlocked()` throws (possible if the span is already finished) and the advice has `suppress = Throwable.class`, the exception object must already exist. Creating it beforehand guarantees the caller receives a non-null value regardless of what happens next.

See [blocking-patterns.md](blocking-patterns.md) for Netty's different behaviour.

## Three-callback ordering: body + filenames + content

When all three callbacks coexist in a single advice:

1. `requestBodyProcessed` (form fields) — always fires when map is non-empty.
2. `requestFilesFilenames` — always fires even if body blocked (`pendingBlock` pattern).
3. `requestFilesContent` — fires **only if** `t == null` (sequential gate).

```java
// Guard — obtain all three upfront
BiFunction<...> bodyCallback     = cbp.getCallback(EVENTS.requestBodyProcessed());
BiFunction<...> filenamesCallback = cbp.getCallback(EVENTS.requestFilesFilenames());
BiFunction<...> contentCallback  = cbp.getCallback(EVENTS.requestFilesContent());
if (bodyCallback == null && filenamesCallback == null && contentCallback == null) return;

// body — fires always
if (bodyCallback != null && !map.isEmpty()) { bodyCallback.apply(...); tryBlock(...); }

// filenames — fires always (independent WAF address)
if (filenamesCallback != null && !filenames.isEmpty()) { filenamesCallback.apply(...); tryBlock(...); }

// content — fires only if not already blocked
if (t == null && contentCallback != null) {
    List<String> contents = readContents(...);
    if (!contents.isEmpty()) { contentCallback.apply(...); tryBlock(...); }
}
```

Body and filenames are independent WAF addresses — a WAF config with only filenames rules will have `bodyCallback == null`. Silencing filenames because body already blocked deprives the WAF of information.


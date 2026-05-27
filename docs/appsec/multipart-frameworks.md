# Multipart AppSec — Framework-Specific Patterns

Framework-specific invariants for implementing `server.request.body.filenames` and `server.request.body.files_content` in Jersey, RESTEasy, Jetty, and GlassFish/Payara.

## Jersey: dual javax / jakarta modules

Jersey 2.x uses `javax.ws.rs.*`; Jersey 3.x uses `jakarta.ws.rs.*`. The types are bytecode-incompatible. Any helper that imports JAX-RS types must be duplicated:

- `jersey-appsec-2.0` → package `jersey2`, imports `javax.*`
- `jersey-appsec-3.0` → package `jersey3`, imports `jakarta.*`

There is no mechanism to enforce synchronization between the two modules. The convention is a `// keep in sync with jersey3` comment in the 2.0 module. When adding logic to either module's `MultiPartHelper`, replicate the change in the other.

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

## RESTEasy: case-insensitive `Content-Disposition` lookup

`MultivaluedMapImpl` (used internally by RESTEasy) extends `HashMap` — `get(key)` is case-sensitive. RFC 2045 defines MIME headers as case-insensitive. If a client sends `content-disposition` in lowercase, `headers.get("Content-Disposition")` returns `null` and all filenames for that part are lost.

```java
String cd = null;
for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
    if ("content-disposition".equalsIgnoreCase(entry.getKey())) {
        List<String> values = entry.getValue();
        if (values != null && !values.isEmpty()) {
            cd = values.get(0);
        }
        break;
    }
}
```

This also applies to `Content-Type` lookups in any framework map that does not guarantee case-insensitive access.

## RESTEasy: `Content-Disposition` parser edge cases

`MultipartHelper.filenameFromContentDisposition()` handles these non-obvious cases:

| Case | Behaviour |
|---|---|
| Semicolon inside quoted filename: `filename="shell;evil.php"` | Extracts `shell;evil.php` (does not split on `;`) |
| MIME linear whitespace after `;`: `; \tfilename=` | Skips SP and HT before the parameter name |
| Optional whitespace around `=`: `filename = "f.php"` | Skips SP/HT before and after `=` |
| `filename*` (RFC 5987) must not match: `filename*=UTF-8''f.php` | Lookahead index `j` distinguishes `filename` from `filename*` |
| Escape inside quoted string: `filename="file\"name.php"` | Unescaped: returns `file"name.php` |
| Empty value: `filename=""` or `filename=` | Returns `null` — do not add to the WAF list |

Jersey does not need a custom parser — it uses `FormDataContentDisposition.getFileName()` from the Jersey public API.

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

## RESTEasy: try-with-resources in `readContent()`

`InputPart.getBody(InputStream.class, null)` may return a `FileInputStream` when the part exceeds RESTEasy's in-memory threshold (configurable via `resteasy.multipart.input.max.child.bytes`, default ~10 MB). Without try-with-resources, the file descriptor leaks until GC.

```java
try (InputStream is = inputPart.getBody(InputStream.class, null)) {
    if (is == null) return "";
    return MultipartContentDecoder.readInputStream(is, MAX_CONTENT_BYTES, contentType);
} catch (IOException ignored) {
    return "";
}
```

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

## GlassFish / Payara: no reflection via `ParameterCollector`

The Java 11+ module system blocks reflective access from unnamed modules (agent helpers) to named modules (GlassFish's `PartItem`). `Method.invoke()` and `setAccessible(true)` fail silently — `IllegalAccessException` is swallowed by `catch (Exception)` in the helper, and data never reaches the WAF.

**Correct approach:** the advice inlines access to `PartItem` via a direct cast to `javax.servlet.http.Part` (the standard Servlet interface). Method dispatch goes through the interface — no reflection needed.

Only helpers that reference exclusively bootstrap or Servlet API types can be injected via `helperClassNames()` without module access issues. `GlassFishBlockingHelper` is a correct example: it only uses `HttpServletRequest`, `HttpServletResponse` (Servlet API), and `BlockingActionHelper` (bootstrap).

`compileOnly javax.servlet:javax.servlet-api:3.1.0` is required — `Part.getSubmittedFileName()` is Servlet 3.1 (GlassFish 4+); the default Tomcat 7.x dependency only includes Servlet 3.0.

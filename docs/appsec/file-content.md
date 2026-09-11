# AppSec File Content — Reading and Encoding

How to correctly read multipart file upload content for the `server.request.body.files_content` WAF address.

## Canonical helper: `FileItemContentReader`

The reference implementation for commons-fileupload. Key constants:

```java
public static final int MAX_CONTENT_BYTES    = 4096;   // per-file limit
public static final int MAX_FILES_TO_INSPECT = 25;     // per-request limit
```

Both are `public static final` so that tests can reference them rather than hardcoding the values.

```java
public static List<String> readContents(List<FileItem> fileItems) {
    List<String> result = new ArrayList<>();
    for (FileItem fileItem : fileItems) {
        if (result.size() >= MAX_FILES_TO_INSPECT) break;
        if (fileItem.isFormField()) continue;   // form fields have no file content
        result.add(readContent(fileItem));
    }
    return result;
}

public static String readContent(FileItem fileItem) {
    try (InputStream is = fileItem.getInputStream()) {   // try-with-resources required
        byte[] buf = new byte[MAX_CONTENT_BYTES];
        int total = 0, n;
        while (total < MAX_CONTENT_BYTES && (n = is.read(buf, total, MAX_CONTENT_BYTES - total)) != -1)
            total += n;
        return MultipartContentDecoder.decodeBytes(buf, total, fileItem.getContentType());
    } catch (IOException ignored) {
        return "";  // IO failure — report empty rather than crash the advice
    }
}
```

---

## Form field vs. file upload: `isFormField()`, not filename null

**Use `isFormField()` to skip non-file parts**, not `fileItem.getName() != null`. Files without a filename (e.g., `curl` upload without explicit `-F "file=@..."`) still have content and must be inspected. Form fields are text input, never file uploads.

The filename null check is correct for `files_filenames` (no name → nothing to report) but **incorrect** for `files_content`.

---

## `getInputStream()` must be closed with try-with-resources

`FileItem.getInputStream()` may return a stream backed by a temp file on disk (managed by `FileCleaningTracker`). Without try-with-resources, the file descriptor stays open until GC, which can exhaust the process's available file descriptors on requests with many uploads.

---

## Charset decoding: `MultipartContentDecoder`

All file content for `files_content` must be decoded using `MultipartContentDecoder` in `internal-api`, not with a hardcoded charset. The decoder applies the following fallback chain:

1. Charset declared in the part's `Content-Type` header (e.g., `text/xml; charset=UTF-8`)
2. `Charset.defaultCharset()` if no charset is declared
3. ISO-8859-1 if the declared charset produces decoding errors

```java
// Correct
return MultipartContentDecoder.decodeBytes(buf, total, fileItem.getContentType());

// Wrong — hardcoded charset loses non-ASCII characters
return new String(buf, 0, total, StandardCharsets.ISO_8859_1);
```

**Why `new String(bytes, charset)` does not work for the fallback chain:** `new String(bytes, charset)` uses `CodingErrorAction.REPLACE` internally — it never throws, silently substituting `U+FFFD` for invalid bytes. The catch block for the ISO-8859-1 fallback would be dead code. `MultipartContentDecoder` uses `CharsetDecoder` with `CodingErrorAction.REPLACE` as well (after considering the truncation-at-boundary case), so the decoder applies the correct charset while being robust to partial multi-byte sequences at the `MAX_CONTENT_BYTES` boundary.

---

## Integration-specific notes

### Tomcat / GlassFish (`ParameterCollector`)

The `Part` interface is accessed via reflection to avoid bytecode references to types that differ between javax and jakarta. The `CachedMethods` inner class caches `getSubmittedFileName()`, `getInputStream()`, and `getContentType()` using a volatile immutable holder — see [multipart-frameworks.md](multipart-frameworks.md#reflection-cache-pattern) for the pattern.

The `inspectContent` flag must be evaluated **before** iterating over parts:

```java
boolean inspectContent = cbp.getCallback(EVENTS.requestFilesContent()) != null;
ParameterCollector collector = new ParameterCollector.ParameterCollectorImpl(inspectContent);
for (Object part : parts) {
    collector.addPart(part);
}
```

Without the flag, `getInputStream()` is called for every file even when no rule uses `files_content`, adding unnecessary I/O overhead.

### Netty 4.1 (`NettyMultipartHelper`)

Netty stores uploads either in memory (`ByteBuf`) or on disk (`File`). See [blocking-patterns.md](blocking-patterns.md#netty-fileupload-content-reading) for the two-branch read pattern using `isInMemory()`.

### RESTEasy

`InputPart.getBody(InputStream.class, null)` may return a `FileInputStream` for uploads above the in-memory threshold. Use try-with-resources:

```java
try (InputStream is = inputPart.getBody(InputStream.class, null)) {
    if (is == null) return "";
    return MultipartContentDecoder.readInputStream(is, MAX_CONTENT_BYTES, contentType);
}
```

`MultipartContentDecoder.readInputStream()` does **not** close the caller's stream — the caller is responsible.

---

## Ordering: filenames before content, content only if not blocked

When an advice fires both `requestFilesFilenames` and `requestFilesContent`:
- Fire filenames first.
- Fire content only if filenames did not produce a blocking action.

See [blocking-patterns.md](blocking-patterns.md#filenames--content-sequential-ordering) for the full pattern.

---

## Required tests for new implementations

- Truncation: content larger than `MAX_CONTENT_BYTES` → result is exactly `MAX_CONTENT_BYTES` bytes
- `IOException` from `getInputStream()` → returns `""`
- Form fields: skipped in `readContents()`
- Files with null or empty filename: included (not filtered by name)
- More than `MAX_FILES_TO_INSPECT` files: only the first `MAX_FILES_TO_INSPECT` inspected
- Empty file list → empty content list
- `contentCallback.apply()` not called when content list is empty

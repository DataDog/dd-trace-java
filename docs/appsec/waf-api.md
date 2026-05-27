# WAF API Reference — libddwaf-java

Reference for the [`libddwaf-java`](https://github.com/DataDog/libddwaf-java) binding used by the AppSec module. Based on libddwaf-java v1.30.0 / libddwaf v1.30.0.

## Lifecycle

```
Waf.initialize(false)             // loads native .so (singleton, irreversible)
   ↓
WafBuilder builder = new WafBuilder(config)
builder.addOrUpdateConfig("path", rulesMap)   // → WafDiagnostics
   ↓
WafHandle handle = builder.buildWafHandleInstance()
builder.close()
   ↓
WafContext ctx = new WafContext(handle)   // one per HTTP request
   ↓
Waf.ResultWithData r = ctx.run(persistentData, limits, metrics)
ctx.runEphemeral(ephemeralData, limits, metrics)
   ↓
ctx.close()     // at end of request
handle.close()  // when ruleset is reloaded
```

**Lifecycle rules:**
- `WafContext` is `Closeable` — use try-with-resources or close in `finally`
- `WafHandle` is thread-safe for reads (ReadWriteLock); write (close) is exclusive
- `WafBuilder.close()` may be called after `buildWafHandleInstance()` without affecting the handle
- Existing contexts continue working after `handle.close()` (reference counting)
- `Waf.deinitialize()` releases JNI — **not thread-safe**; crashes if threads are using the WAF

---

## Java types accepted as address values

The `Map<String, Object>` passed to `ctx.run()` maps address names to values:

| Java type | WAF type (`ddwaf_object`) | Notes |
|---|---|---|
| `null` | `DDWAF_OBJ_NULL` | Serialized as explicit null |
| `String` | `DDWAF_OBJ_STRING` | UTF-16 → UTF-8. Truncated to `maxStringSize` UTF-16 chars |
| `CharSequence` (any) | `DDWAF_OBJ_STRING` | Same as String |
| `CharBuffer` (heap or direct) | `DDWAF_OBJ_STRING` | Zero-copy for direct `ByteOrder.nativeOrder()` |
| `Integer`, `Long`, `Short`, `Byte`, `AtomicInteger`, `BigInteger` | `DDWAF_OBJ_SIGNED` (int64_t) | Any non-float Number |
| `Double`, `Float`, `BigDecimal` | `DDWAF_OBJ_FLOAT` (double IEEE 754) | |
| `Boolean` | `DDWAF_OBJ_BOOL` | |
| `Map<?, ?>` | `DDWAF_OBJ_MAP` | Keys converted to String via `toString()`. Recursive |
| `Collection` (List, Set, etc.) | `DDWAF_OBJ_ARRAY` | Recursive |
| Native arrays (`String[]`, `int[]`, etc.) | `DDWAF_OBJ_ARRAY` | Recursive |
| Any `Iterable` | `DDWAF_OBJ_ARRAY` | Recursive |
| Any other object | `DDWAF_OBJ_NULL` | **Silent null — no exception thrown** |

**Edge cases:**
- Empty strings `""`: valid, serialized as `DDWAF_OBJ_STRING` of length 0
- `null` keys in a Map: converted to `""` (empty string)
- Incomplete UTF-16 surrogates: replaced with `U+FFFD` (no exception)
- Unknown objects (`new Object()`): silently become `DDWAF_OBJ_NULL`
- `ConcurrentModificationException` during iteration: propagates as Java exception

---

## Limits (`Waf.Limits`)

```java
Waf.Limits limits = new Waf.Limits(
    int  maxDepth,          // max nesting depth
    int  maxElements,       // max total nodes (maps + arrays, cumulative)
    int  maxStringSize,     // max UTF-16 chars per string (keys AND values)
    long generalBudgetInUs, // total microseconds (serialization + execution)
    long runBudgetInUs      // microseconds for ddwaf_run() (0 = no separate limit)
);
```

**Values used in dd-trace-java:** see `WafInitialization.java` and `AppSecSystem.java` for current values.

**Truncation behavior** (tracked in `WafMetrics`):

| Limit exceeded | Effect | Metric |
|---|---|---|
| `maxDepth` | Object replaced with empty map | `truncatedObjectTooDeepCount` |
| `maxElements` | Map/array replaced with empty map | `truncatedListMapTooLargeCount` |
| `maxStringSize` | String truncated | `truncatedStringTooLongCount` |
| `generalBudgetInUs` | `TimeoutWafException` | — |

`maxElements` is a **global counter per `run()` call**, decremented for each node (maps, arrays, and their elements). A map with 2 keys costs 3 (the map itself + 2 elements).

`maxStringSize` is in **UTF-16 chars, not bytes**. An emoji (e.g. `👍`) is 2 `char` units; if `maxStringSize=1` the emoji is truncated to empty.

---

## Persistent vs. ephemeral data

`WafContext` accepts two data slots per `run()`:

| | Persistent | Ephemeral |
|---|---|---|
| Storage | Cached in context across calls | Only for the current call |
| Exclusion caching | Yes | No (always re-evaluated) |
| Typical use | HTTP headers, IP, URI, body | gRPC messages, streaming payloads |
| Released | On `ctx.close()` | At end of `ctx.run()` |

Persistent data **accumulates** — if run 1 passes `server.request.headers` and run 2 passes `server.request.body`, the context has both in run 2. Rules that depend on both addresses are only evaluated once all their data is available.

gRPC messages must be passed as **ephemeral** data (`runEphemeral()`). Passing them as persistent means only the first message is evaluated; subsequent messages show no new data.

---

## WAF addresses — full catalogue

Declared in `KnownAddresses.java`.

### HTTP request

| Address | Expected type | Notes |
|---|---|---|
| `server.request.method` | `String` | "GET", "POST", etc. |
| `server.request.uri.raw` | `String` | Full URI |
| `server.request.uri` | `Map<String,String>` | Generated by `uri_parse` preprocessor. Keys: scheme, userinfo, host, port, path, query, fragment |
| `server.request.headers.no_cookies` | `Map<String, List<String>>` | Headers without cookies. Keys lowercase |
| `server.request.cookies` | `Map<String, String>` | |
| `server.request.query` | `Map<String, List<String>>` | |
| `server.request.path_params` | `Map<String, String>` | |
| `server.request.body` | `Map<String, Object>` or `String` | Parsed or raw body |
| `server.request.body.raw` | `String` | Unparsed body (input for processors) |
| `server.request.transport` | `String` | "http", "https", "grpc" |
| `server.request.jwt` | `Map` | Decoded JWT (generated by `jwt_decode` processor) |

### HTTP response

| Address | Expected type | Notes |
|---|---|---|
| `server.response.status` | `Integer` (unsigned) | Numeric HTTP code — **not** a String |
| `server.response.body` | `String` | |
| `server.response.headers.no_cookies` | `Map<String, List<String>>` | |

### Client and user

| Address | Expected type |
|---|---|
| `http.client_ip` | `String` |
| `usr.id` | `String` |
| `usr.session_id` | `String` |

### gRPC

| Address | Expected type | Persistence |
|---|---|---|
| `grpc.server.request.message` | `Map` | **Ephemeral** |
| `grpc.server.request.metadata` | `Map<String,List<String>>` | Persistent |

### RASP

| Address | Expected type | When |
|---|---|---|
| `server.db.statement` | `String` | Before executing SQL |
| `server.db.system` | `String` | "mysql", "postgresql", etc. |
| `server.io.fs.file` | `String` | File path being accessed |
| `server.io.net.url` | `String` | Outbound request URL |
| `server.sys.shell.cmd` | `String` | Shell command |

### Business logic

| Address | Expected type |
|---|---|
| `server.business_logic.users.login.success` | `Map` |
| `server.business_logic.users.login.failure` | `Map` |

### Processor activator

| Address | Value |
|---|---|
| `waf.context.processor` | `Map{"fingerprint": true}` — activates fingerprint generation |

### Processor outputs (returned in `ResultWithData.attributes`, not passed as input)

- `_dd.appsec.s.req.body` — body schema (gzip+base64)
- `_dd.appsec.fp.http.endpoint` — endpoint fingerprint
- `_dd.appsec.fp.http.header` — header fingerprint
- `_dd.appsec.fp.http.network` — network fingerprint
- `_dd.appsec.fp.session` — session fingerprint

**Never pass these as input addresses** — they are outputs from `ResultWithData.attributes`.

`server.request.headers.no_cookies` is **without cookies**. Passing all headers (including cookies) to this address is a bug; cookies go in `server.request.cookies`.

`server.response.status` must be an `Integer`, not a String `"200"`. Changed from String to Integer in v1.28.0.

---

## Result: `Waf.ResultWithData`

```java
result.result      // Waf.Result.OK or Waf.Result.MATCH
result.data        // String JSON with events (null if OK)
result.actions     // Map<String, Map<String, Object>>: actions to execute
result.attributes  // Map<String, Object>: WAF-derived data (schemas, fingerprints)
result.keep        // boolean: whether to keep the trace
result.duration    // long: nanoseconds of native execution
result.events      // boolean: whether events were generated
```

**Known actions:**
- `block_request`: `{status_code: int, type: "json"|"html"|"auto", grpc_status_code: int}`
- `redirect_request`: `{status_code: int, location: String}`
- `generate_stack`: generate stack trace
- `generate_schema`: activate schema extraction

From v1.29.0, each blocking action includes a `block_id` correlating with the event in `data`.

---

## `WafDiagnostics` — result of `addOrUpdateConfig()`

```java
WafDiagnostics d = builder.addOrUpdateConfig("path", map);

d.getNumConfigOK()       // int
d.getNumConfigError()    // int
d.getAllErrors()          // Map<String, List<String>>
d.getRulesetVersion()    // String
d.isWellStructured()     // boolean
```

Available sections: `rules`, `customRules`, `rulesData`, `rulesOverride`, `exclusions`, `exclusionData`, `actions`, `processors`, `scanners`.

---

## `WafMetrics` — execution metrics

```java
WafMetrics m = new WafMetrics();
ctx.run(data, limits, m);

m.getTotalRunTimeNs()                  // total: serialization + native WAF
m.getTotalDdwafRunTimeNs()             // native WAF only
m.getTruncatedStringTooLongCount()
m.getTruncatedListMapTooLargeCount()
m.getTruncatedObjectTooDeepCount()
```

Serialization time = `totalRunTimeNs - totalDdwafRunTimeNs`.

---

## Obfuscation — `WafConfig`

```java
WafConfig config = new WafConfig();
config.obfuscatorKeyRegex   = "(?i)pass|pwd|secret|api.key|token|...";
config.obfuscatorValueRegex = "...";
// To disable obfuscation:
config.obfuscatorKeyRegex   = "";
```

Obfuscated data appears as `<Redacted>` in `result.data`.

---

## Schema extraction

Activated by the `extract_schema` processor in the ruleset. Results are returned in `ResultWithData.attributes` under keys like `_dd.appsec.s.req.body`, already gzip-compressed and base64-encoded. Write them to the span as-is — do not decompress.

**Type codes in schema:**

| Code | Type |
|---|---|
| 1 | Null |
| 2 | Boolean |
| 4 | Integer |
| 8 | String |
| 16 | Float/Double |

---

## Version history

| Version | Changes |
|---|---|
| v1.30.0 | No breaking Java API changes |
| v1.29.0 | `block_id` in block/redirect actions; `processor_overrides` are now incremental |
| v1.28.0 | New address `server.request.uri` (from `uri_parse`); `server.response.status` changed from String to Integer |
| v1.28.1 | Float support in status codes for ruleset compatibility |

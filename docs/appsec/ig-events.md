# AppSec Instrumentation Gateway Events

How to add a new WAF address exposed through the Instrumentation Gateway (IG), and the invariants that apply to all IG event callbacks in advice classes.

## Adding a new IG event — 4 required changes

### 1. `Events.java` — declare the event type

```java
static final int REQUEST_FILES_CONTENT_ID = 31;  // unique, increment from last used

@SuppressWarnings("rawtypes")
private static final EventType REQUEST_FILES_CONTENT =
    new ET<>("request.body.files.content", REQUEST_FILES_CONTENT_ID);

@SuppressWarnings("unchecked")
public EventType<BiFunction<RequestContext, List<String>, Flow<Void>>> requestFilesContent() {
    return (EventType<BiFunction<RequestContext, List<String>, Flow<Void>>>) REQUEST_FILES_CONTENT;
}
```

The ID must be unique and sequential. Check the last used ID before assigning.

### 2. `InstrumentationGateway.java` — register the callback type

Add the new event ID to the appropriate case in `getCallback()` for the matching `BiFunction` signature.

### 3. `KnownAddresses.java` — declare the WAF address

```java
Address<List<String>> REQUEST_FILES_CONTENT =
    new Address<>("server.request.body.files_content");
```

Also add it to the `fromString()` switch.

### 4. `GatewayBridge.java` — wire the callback

Four changes needed:
1. Subscriber field: `private volatile DataSubscriberInfo requestFilesContentSubInfo;`
2. Registration in `init()` (conditional on `additionalIGEvents`):
   ```java
   if (additionalIGEvents.contains(EVENTS.requestFilesContent())) {
       subscriptionService.registerCallback(EVENTS.requestFilesContent(), this::onRequestFilesContent);
   }
   ```
3. Reset in `reset()`: `requestFilesContentSubInfo = null;`
4. Handler method (see pattern below)
5. Entry in `DATA_DEPENDENCIES`:
   ```java
   DATA_DEPENDENCIES.put(KnownAddresses.REQUEST_FILES_CONTENT, l(EVENTS.requestFilesContent()));
   ```

### Handler pattern in `GatewayBridge`

All handlers follow the same retry-on-expiry pattern:

```java
private Flow<Void> onRequestFilesContent(RequestContext ctx_, List<String> filesContent) {
    AppSecRequestContext ctx = ctx_.getData(RequestContextSlot.APPSEC);
    if (ctx == null || filesContent == null || filesContent.isEmpty()) {
        return NoopFlow.INSTANCE;
    }
    while (true) {
        DataSubscriberInfo subInfo = requestFilesContentSubInfo;
        if (subInfo == null) {
            subInfo = producerService.getDataSubscribers(KnownAddresses.REQUEST_FILES_CONTENT);
            requestFilesContentSubInfo = subInfo;
        }
        if (subInfo == null || subInfo.isEmpty()) {
            return NoopFlow.INSTANCE;
        }
        DataBundle bundle =
            new SingletonDataBundle<>(KnownAddresses.REQUEST_FILES_CONTENT, filesContent);
        try {
            GatewayContext gwCtx = new GatewayContext(false);  // transient=false for request data; RASP uses true
            return producerService.publishDataEvent(subInfo, ctx, bundle, gwCtx);
        } catch (ExpiredSubscriberInfoException e) {
            requestFilesContentSubInfo = null;
        }
    }
}
```

---

## `KnownAddressesSpecificationForkedTest` — update instance count

Every new `Address<?>` declared in `KnownAddresses.java` must be accompanied by incrementing the expected count in:

```groovy
void 'number of known addresses is expected number'() {
    expect:
    Address.instanceCount() == 47  // increment by 1 for each new Address
}
```

The test will fail if the count is not updated.

---

## Guard ordering in advice — obtain all callbacks before early return

When an advice handles multiple IG callbacks, all callbacks must be fetched **before** any early-return guard. If a callback is fetched after a guard, it is silently lost when only that callback is registered.

```java
// Wrong — contentCb is never fetched if bodyCallback is null
BiFunction<...> bodyCallback = cbp.getCallback(EVENTS.requestBodyProcessed());
if (bodyCallback == null) return;   // early return skips contentCb entirely
BiFunction<...> contentCb = cbp.getCallback(EVENTS.requestFilesContent());

// Correct — fetch all first, guard is AND of all
BiFunction<...> bodyCallback = cbp.getCallback(EVENTS.requestBodyProcessed());
BiFunction<...> contentCb    = cbp.getCallback(EVENTS.requestFilesContent());
if (bodyCallback == null && contentCb == null) return;
```

**Subtle variant:** a callback fetched inside a conditional block after the loop is equally invisible when only that callback is registered:

```java
// Also wrong — filenamesCb is never fetched when filenames list happens to be empty
// and bodyCallback already returned early
for (Part part : parts) { collector.addPart(part); }
if (!filenames.isEmpty()) {
    BiFunction<...> filenamesCb = cbp.getCallback(EVENTS.requestFilesFilenames());  // too late
    ...
}
```

The correct pattern: fetch all callbacks at the top of the method, then reference them by variable in conditional blocks.

---

## `@RequiresRequestContext` + `Config.get()` — muzzle failure

Do **not** place `static final` constants initialized from `Config.get()` in advice inner classes annotated with `@RequiresRequestContext`.

Muzzle treats `@RequiresRequestContext`-annotated classes as user classes and tries to validate `Config` against the instrumented library's classpath (e.g., Netty, commons-fileupload) — where `Config` does not exist. This causes a `MuzzleValidationException` in CI even though the code compiles.

```java
// Wrong — Config.get() in @RequiresRequestContext advice inner class
@RequiresRequestContext(RequestContextSlot.APPSEC)
static class ParseBodyAdvice {
    private static final int MAX_FILES = Config.get().getAppSecMaxFileContentCount();  // breaks muzzle
}

// Correct — constant in helper class declared in helperClassNames()
public class NettyMultipartHelper {
    static final int MAX_FILES_TO_INSPECT = Config.get().getAppSecMaxFileContentCount();
}
```

---

## Undertow: `tryCommitBlockingResponse` is not idempotent

See [blocking-patterns.md](blocking-patterns.md#undertow) for the full explanation. Summary: when an advice has two blocking paths (body + filenames), the guard `t == null` must appear **before** the call to `tryCommitBlockingResponse`, not after.

---

## Akka HTTP: two multipart routes

Any new WAF address that captures multipart body data must instrument **both** routes in Akka HTTP:

| Route | Entry point | Notes |
|---|---|---|
| Route 1 | `handleMultipartStrictFormData(Multipart$FormData$Strict)` | Has `reqCtx` as local variable; iterates via Java API `getStrictParts()` |
| Route 2 | `handleStrictFormData(StrictForm)` | No `reqCtx` in scope; must obtain via `activeSpan()` |

If only one route is instrumented, multipart requests processed via `formFieldMultiMap` (Route 2) silently miss the WAF event.

Do **not** extract the filenames callback dispatch into a separate helper method in `UnmarshallerHelpers`. This is known to cause problems.

---

## Vert.x: advice classes vs. helper classes

`RoutingContextImplInstrumentation` has no `helperClassNames()` override — it uses the empty default. Advice classes (`RoutingContextJsonAdvice`, `RoutingContextFilenamesAdvice`, etc.) are inlined by ByteBuddy, not injected. Only runtime-instantiated handlers go in `helperClassNames()` (e.g. `WafPublishingBodyHandler`).

New `@RequiresRequestContext` classes for `RoutingContextImplInstrumentation` must **not** be added to `helperClassNames()`.

`RoutingContext.fileUploads()` returns an empty set unless `BodyHandler` has previously parsed the body. `setExpectMultipart(true)` + `endHandler` populates `formAttributes()` but **not** file uploads.

Use a distinct `CallDepthThreadLocalMap` key per advice class to avoid interference between the filenames advice and the existing JSON advice.

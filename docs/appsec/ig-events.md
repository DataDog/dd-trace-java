# AppSec Instrumentation Gateway Events

How to add a new WAF address exposed through the Instrumentation Gateway (IG), and the invariants that apply to all IG event callbacks in advice classes.

## Adding a new IG event — 4 required changes

### 1. `Events.java` — declare the event type

Add the event type, unique integer ID, and accessor method in
`dd-java-agent/src/main/java/datadog/trace/api/gateway/Events.java`.
The ID must be unique and sequential — check the last used ID before assigning.

### 2. `InstrumentationGateway.java` — register the callback type

Add the new event ID to the appropriate case in `getCallback()` for the matching `BiFunction` signature.

### 3. `KnownAddresses.java` — declare the WAF address

Declare the `Address<T>` constant and add it to the `fromString()` switch in
`dd-java-agent/src/main/java/datadog/trace/api/gateway/KnownAddresses.java`.

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

All handlers follow the same retry-on-expiry pattern. See existing handlers in `GatewayBridge.java`
for the full template (`onRequestBodyProcessed`, `onRequestFilesFilenames`, etc.).

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

Muzzle treats `@RequiresRequestContext`-annotated classes as user classes and validates all referenced types against the instrumented library's classpath (e.g., Netty, commons-fileupload), where `Config` does not exist. This causes a `MuzzleValidationException` in CI even though the code compiles.

Put any `Config.get()` constants in a helper class declared in `helperClassNames()` instead.

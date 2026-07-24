# AppSec Module

Extended reference: [docs/appsec/](../../docs/appsec/)

## Adding a new WAF address: 4-file checklist

Every new WAF address requires changes in exactly 4 files. Missing any one causes a silent no-op:

1. `Events.java` -- declare the event type with a unique sequential ID
2. `InstrumentationGateway.java` -- register the callback type in `getCallback()`
3. `KnownAddresses.java` -- declare the `Address<T>` constant and add to `fromString()` switch
4. `GatewayBridge.java` -- subscriber field, `init()` registration, `reset()` null, handler method, `DATA_DEPENDENCIES` entry

`GatewayBridgeIGRegistrationSpecification` verifies that every `DATA_DEPENDENCIES` entry registers
its events in `init()`. If you add a `DATA_DEPENDENCIES` entry but forget the `registerCallback()`
call, that test fails with an explanation.

`KnownAddressesSpecificationForkedTest` verifies that every address is in `fromString()` and that
the total count is up to date.

See [docs/appsec/ig-events.md](../../docs/appsec/ig-events.md) for the full handler pattern including the retry-on-expiry loop.

## `KnownAddressesSpecificationForkedTest` instance count

Every new `Address<?>` declared in `KnownAddresses.java` must be accompanied by incrementing the
expected count in `KnownAddressesSpecificationForkedTest`:

```groovy
Address.instanceCount() == 47  // increment by 1 per new Address
```

The test fails if this is not updated.

## WAF API quick reference

- `server.response.status` must be `Integer`, not `String` (changed in libddwaf v1.28.0)
- gRPC messages must use `runEphemeral()`, not `run()` -- persistent data caches only the first message
- Processor outputs (`_dd.appsec.s.req.body`, fingerprints) come from `ResultWithData.attributes` -- never pass them as input addresses
- `server.request.headers.no_cookies` must not include cookies -- cookies go in `server.request.cookies`

Full type mapping and limits: [docs/appsec/waf-api.md](../../docs/appsec/waf-api.md)

## Callback guard ordering in advice

Fetch ALL callbacks before any early-return guard. A callback fetched after a conditional return
is silently skipped when only that callback is registered:

```java
// Correct
BiFunction<...> bodyCallback     = cbp.getCallback(EVENTS.requestBodyProcessed());
BiFunction<...> filenamesCallback = cbp.getCallback(EVENTS.requestFilesFilenames());
BiFunction<...> contentCallback  = cbp.getCallback(EVENTS.requestFilesContent());
if (bodyCallback == null && filenamesCallback == null && contentCallback == null) return;
```

## Blocking model per framework

| Framework | Call `effectivelyBlocked()`? | Notes |
|---|---|---|
| Netty | NEVER | `BlockingResponseHandler` does it internally |
| Tomcat / Jersey / Jetty | YES, after `t = new BlockingException(...)` | Inside `if (brf != null)` |
| Undertow | YES, but `tryCommitBlockingResponse` is not idempotent | Guard `t == null` before each call |
| Vert.x | NO | Handler-based model |

Full details: [docs/appsec/blocking-patterns.md](../../docs/appsec/blocking-patterns.md)

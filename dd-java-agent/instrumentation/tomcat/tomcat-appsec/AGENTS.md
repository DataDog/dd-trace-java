# Tomcat AppSec Instrumentation

Extended reference: [docs/appsec/blocking-patterns.md](../../../../docs/appsec/blocking-patterns.md),
[docs/appsec/file-content.md](../../../../docs/appsec/file-content.md),
[docs/appsec/multipart-frameworks.md](../../../../docs/appsec/multipart-frameworks.md)

## MUST call `effectivelyBlocked()` in Tomcat blocking path

Unlike Netty, Tomcat advice must call `effectivelyBlocked()` explicitly. The call must come AFTER
`t = new BlockingException(...)` and both must be inside `if (brf != null)`:

```java
if (brf != null) {
    brf.tryCommitBlockingResponse(ctx.getTraceSegment(), rba);
    t = new BlockingException("...");           // 1. create first
    ctx.getTraceSegment().effectivelyBlocked(); // 2. mark after
}
```

If `effectivelyBlocked()` throws (span already finished), the exception object must already exist
so the advice can still return a non-null value and let the container propagate it.

## `Part` access via reflection: GlassFish restriction does NOT apply here

Tomcat's `ParameterCollector` uses reflection to avoid bytecode references to `javax` vs `jakarta`
Part types. This is safe for Tomcat because the agent classloader is not subject to Java 9+ module
access restrictions for Tomcat's classloader.

For GlassFish, direct cast via `javax.servlet.http.Part` interface is required instead.
See [docs/appsec/multipart-frameworks.md](../../../../docs/appsec/multipart-frameworks.md#glassfishpayara-no-reflection-via-parametercollector).

## `inspectContent` flag must be evaluated before iterating parts

Check whether the `requestFilesContent` callback is registered BEFORE iterating parts, not inside
the loop. This avoids calling `getInputStream()` on every file when no rule uses `files_content`:

```java
boolean inspectContent = cbp.getCallback(EVENTS.requestFilesContent()) != null;
ParameterCollector collector = new ParameterCollector.ParameterCollectorImpl(inspectContent);
for (Object part : parts) {
    collector.addPart(part);
}
```

## `helperClassNames()` must list all nested inner classes

ByteBuddy does not auto-discover nested classes. Every `$Inner` class used at runtime must be
explicitly listed in `helperClassNames()` or a `NoClassDefFoundError` will occur silently.

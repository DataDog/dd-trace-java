# Bytecode Advice Patterns — dd-trace-java

Non-obvious rules for writing `@Advice` instrumentation in dd-trace-java. Based on review
feedback from smola and manuel-alvarez-alvarez across 210+ PRs.

## `helperClassNames()` must list all nested inner classes

ByteBuddy injects exactly the classes listed. It does NOT auto-discover nested inner classes of
listed parent classes. Every `$Inner` class used at runtime must be listed explicitly:

```java
@Override
public String[] helperClassNames() {
    return new String[] {
        "com.example.MyHelper",
        "com.example.MyHelper$InnerClass",    // must be explicit
        "com.example.MyHelper$Inner$Nested",  // and nested-inner too
    };
}
```

If a class is omitted, `NoClassDefFoundError` is silently swallowed by
`suppress = Throwable.class` in the advice -- data is lost with no visible error.

## `muzzleDirective()` must not return null in modules with `assertInverse = true`

`MuzzleVersionScanPlugin` treats `null` from `muzzleDirective()` as "include in every test":

```java
// MuzzleVersionScanPlugin.java
if (null == directiveToTest || directiveToTest.equals(muzzleDirective)) {
    toBeTested.add(module);
}
```

If a module has any muzzle block with `assertInverse = true`, every new instrumentation in that
module MUST override `muzzleDirective()` with a non-null value. Without it, the new
instrumentation is included in the inverse-mode tests where failure is expected -- muzzle passes
instead, CI fails with "MUZZLE PASSED BUT FAILURE WAS EXPECTED".

## `@Advice.FieldValue` -- type at the most specific stable descriptor

Type the parameter to the exact declared type of the field, or to an interface/supertype whose
method descriptors are stable across all muzzle-tested versions. Never use `Object`:

```java
// Wrong -- requires reflection to call methods, no muzzle validation
@Advice.FieldValue("request") Object requestField

// Wrong -- concrete type with version-varying descriptors causes muzzle failure
@Advice.FieldValue("request") org.apache.catalina.connector.Request requestField

// Correct -- interface with stable descriptor, muzzle validates the relationship
@Advice.FieldValue("request") org.apache.catalina.Request catRequest
org.apache.catalina.Response cr = catRequest.getResponse();
```

Using an interface type gives muzzle validation for free: if the field stops implementing that
interface in any version, muzzle detects it at build time.

## `suppress = Throwable.class` does NOT protect loops inside helpers

`suppress = Throwable.class` on `@Advice.OnMethodExit` protects the advice boundary. An
exception thrown inside a `for` loop in a helper method short-circuits the remaining iterations
silently -- the WAF receives partial data with no visible error.

Wrap each per-item operation in its own `try/catch`:

```java
for (Part part : parts) {
    try {
        processPartSafely(part);
    } catch (Exception ignored) {
        // malformed part -- continue with remaining parts
    }
}
```

## Business logic must be extracted to testable helper classes

For traditional `@Advice` instrumentations (not callsite), advice methods should contain only:
- Null/context guards (`if (span == null) return`)
- A single call to a module or helper

Any logic beyond this -- data transformations, conditional branches, object construction,
parsing -- must move to a `static final` method in a `*Helper` class and be covered by unit
tests directly on that helper. Integration tests through the instrumentation are not sufficient.

**Exception**: AppSec blocking logic (`tryCommitBlockingResponse`, `RequestBlockingAction` flows)
is exempt from this rule. It has a dedicated testing pattern and historically causes problems
when extracted.

## Reflection cache for version-variant return types

When a method's return type differs between library versions (e.g., `javax.*` vs `jakarta.*`),
a direct bytecode reference fails muzzle for the other version.

Canonical solution -- cache the `Method` as a `static final` field resolved once in a `static {}`
block. This runs once when the helper class loads in the app classloader, where the library is
present. Zero per-request cost:

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

Always add a null guard before invoking: `if (GET_HEADERS == null) return ...`.

**Why `static final` and not `volatile`:** class initialization is atomic per JVM spec -- the
`static {}` block runs exactly once. `volatile` would be needed only for lazy initialization of
instance or non-static fields.

## Constants from `Config.get()` must NOT be in `@RequiresRequestContext` advice inner classes

Muzzle validates `@RequiresRequestContext`-annotated classes against the instrumented library's
classpath -- where `Config` does not exist. This causes `MuzzleValidationException` in CI even
though the code compiles correctly.

Move such constants to the helper class declared in `helperClassNames()`:

```java
// Wrong -- in @RequiresRequestContext advice inner class
@RequiresRequestContext(RequestContextSlot.APPSEC)
static class ParseBodyAdvice {
    private static final int MAX_FILES = Config.get().getAppSecMaxFileContentCount(); // CI failure
}

// Correct -- in the helper class
public class NettyMultipartHelper {
    static final int MAX_FILES_TO_INSPECT = Config.get().getAppSecMaxFileContentCount();
}
```

## One-shot methods must not be extracted to constants

`triggerClasses()`, `contextStore()`, `classLoaderMatcher()`, and `methodAdvice()` are called
once by the ByteBuddy framework. Extracting their return values to `static final` constants adds
constant-pool bloat with no benefit.

## Thread safety in shared fields

Any field accessed from multiple threads must use a thread-safe type. Common mistakes:

- `ArrayList` is not thread-safe. Use `CopyOnWriteArrayList` or `Collections.synchronizedList()`.
- Read-then-write on `AtomicXxx` is not atomic. Use `updateAndGet` or `accumulateAndGet` with a
  lambda instead of `get()` + `set()`.
- For concurrent maximums: `accumulateAndGet(value, Math.max)`.

## Logging level

Internal agent messages that are not actionable by customers must use `DEBUG`, not `WARN` or
`INFO`. Do not log inside loops -- if a limit is exceeded, log once at the boundary.

## `catch (Exception/Throwable ignored)` requires an explanatory comment

Every silent catch block must answer two questions inline:
1. What scenario causes this exception?
2. What is the fallback behavior?

```java
} catch (Exception ignored) {
    // malformed Content-Disposition header -- skip part, continue loop
}
```

Reviewers (smola, manuel) reject silent catches without explanation.

# Maintainability

Duplication, dead code, comments, naming, and test value.

The [techdebt skill](../../techdebt/SKILL.md) is a separate, refactor-only review of duplication, unnecessary complexity, and dead code. This lens does not replace it or reuse its output. Keep the two distinct:

- This lens reports findings against the frozen diff and does not offer or apply fixes. `techdebt` offers behavior-preserving fixes.
- Findings here use this skill's severity scale and rule IDs. `techdebt` has its own report format.
- This lens also covers comments, naming, and test value, which `techdebt` does not.
- Do not run `techdebt` from this lens. Where both would flag the same code, that overlap is expected.

**When planning:** take the rules below into account when designing the change.

**When reviewing:** report a finding when the diff matches a rule's `Flag:` line and no `Not when:` applies. Cite the rule ID and point to the existing helper or simpler shape.

## 1. Duplication and reuse

### MAINT-1.1: Call the existing helper (`datadog.json.JsonWriter`, `LongStringUtils.toHexStringPadded`, `OkHttpUtils`) instead of reimplementing it
- Flag: new hand-rolled JSON serialization/escaping, a `StringBuilder` loop that zero-pads hex, or another copy of an HTTP header block that a module it already depends on could share.
- ❌ `String hex = value.toString(16); while (padded.length() < HEX_LENGTH - hex.length()) padded.append('0');`
- ✅ `LongStringUtils.toHexStringPadded(high, low, 32)`

### MAINT-1.2: Extract one shared helper when the diff copies the same block into sibling sites
- Flag: the same multi-line block (status extraction then `setError`, a sampling/drop guard, "add header if non-null", a jar-entry lookup) appears in two or more methods or sibling decorators after the change, especially when the copies already differ.
- ❌ `if (value != null && !value.isEmpty()) { builder.addHeader(NAME, value); }` repeated per header
- ✅ `addHeaderIfPresent(builder, NAME, value);`

### MAINT-1.3: Replace a repeated or unexplained literal with one named constant
- Flag: the same literal repeated across call sites (`depth < 5`, `Context.class.getName()` in every `contextStore()` entry, a `5000` timeout), or a magic value (`0x9e3775cd`) with no name or comment.
- ❌ `for (int depth = 0; current != null && depth < 5; depth++)` in six files
- ✅ `depth < MAX_CAUSE_CHAIN_DEPTH`

### MAINT-1.4: Keep one source of truth for lists and policies that must stay in sync
- Flag: the diff adds a list, resource file, matcher or check that must be kept in step with another by hand, with nothing deriving one from the other. Signals: a comment or test that talks about keeping two things "in sync" or catching "drift"; two matchers that each start from the same base expression; the same condition recomputed at several call sites.
- Not when: the entries are different types that genuinely need separate checks, each covered by a regression test.

## 2. Dead and redundant code

### MAINT-2.1: Don't null-check the current or root `Context`; it is never null
- Flag: `== null`/`!= null` on a value returned by `currentContext()`, `rootContext()`, `Context.current()`, `Context.root()` (or the older `getCurrentContext()`/`getRootContext()`). Check every advice class in the diff, since sibling instrumentations usually repeat it. This includes an unchanged null check on a variable whose assignment the diff changes.
- ❌ `if (parentContext != null && parentContext != rootContext()) {`
- ✅ `if (parentContext != rootContext()) {`

### MAINT-2.2: Drop redundant state and intermediate values
- Flag: a boolean flag that duplicates an existing field's state (`seeded` beside `context == null`), a per-item size map kept only to compute a delta, `.collect(toList())` on a stream iterated once, or a normalization step whose result is never needed.
- ❌ `private boolean seeded;`
- ✅ `if (context == null) { ... }`

### MAINT-2.3: Delete no-op methods, obsolete workarounds and leftover deprecated overloads
- Flag: a non-override method with an empty body kept only for its signature (`onResult(span, result) {}`), a workaround that upstream fixes already made unnecessary (check that CI passes without it), or a deprecated overload left after the PR removed its siblings.
- ❌ `public void onResult(final AgentSpan span, Object result) {}`
- ✅ `// delete the method and its call sites`

### MAINT-2.4: Remove configuration that another setting already implies
- Flag: a `@WithConfig`, system property or build setting that duplicates one already enabled by another setting on the same test or task.
- Not when: the harness turns off the implicit wiring (for example a `noAgent()` smoke test), so the explicit setting is required.
- ❌ `@WithConfig(key = "integration.opentelemetry-annotations-1.20.enabled", value = "true")` next to `trace.otel.enabled`

### MAINT-2.5: Don't add defensive code for failures the code already rules out
- Flag: a `try`/`catch` around decorator calls (decorators throw only `BlockingException`), or an early-return guard that the following loop or check already handles. See [decorators must not throw](../../../../docs/how_instrumentations_work.md#decorators-must-not-throw).

## 3. Comments and Javadoc

### MAINT-3.1: Delete LLM reasoning, change narration and internal notes from comments
- Flag: a new comment of three or more lines on a single statement, field, branch or class placement; a comment starting `NOTE:`; a comment that explains how the code differs from a previous version ("isValid(), not == ZERO: ..."); or a test class Javadoc longer than two lines. Check every new comment in the diff, including tests.
- Not when: the comment explains a decision, contract or encoding that the code can't show (why a check is kept, what a numeric range means).
- ❌ `// isValid(), not == ZERO: a parsed zero Root must still count as unset.`
- ✅ `if (interpreter.traceId == null || !interpreter.traceId.isValid()) {`

### MAINT-3.2: Fix Javadoc and docs that are stale, copy-pasted or overclaim
- Flag: Javadoc copied from another method (a "given" context on an instance method), an `@return` case that cannot happen, an invariant kept only by caller convention stated as a guarantee, or docs (including skill docs) that describe an option, file or step that the change removed, that doesn't exist in the repo, or that isn't supported yet.
- ❌ `@return the captured context; {@link Context#root()} if there is none.`
- ✅ `@return the captured context.`

### MAINT-3.3: Comment code that relies on a hidden contract
- Flag: any of these without a comment saying why: a `catch (InterruptedException ...)` that neither rethrows nor calls `Thread.currentThread().interrupt()`; an `==` comparison of arrays, buffers or other non-enum objects used to decide ownership; reflection (`getDeclaredConstructor()`, `newInstance()`) that requires something of user-supplied classes, such as a no-arg constructor; or a test-double method with the same signature as a JDK method it shadows.
- ❌ `} catch (InterruptedException ignore) {`
- ✅ `} catch (InterruptedException ignore) { // mid-shutdown: no caller to propagate the interrupt to`

### MAINT-3.4: Carry comments over when migrating or rewriting code
- Flag: a deleted `.groovy` test with a new `.java` counterpart where `//` comments, or Spock block descriptions (`expect: "..."`, `when: "..."`), in the deleted file have no equivalent in the new one; or a rewrite that deletes Javadoc naming which code uses a class. Compare the removed and added versions of each migrated test.

## 4. Naming and code shape

### MAINT-4.1: Rename variables when the method or type they hold is renamed
- Flag: the diff renames an accessor or type (`context()` to `spanContext()`), and locals, fields or parameters assigned from it keep a name based on the old one. That includes generic and role-prefixed names, such as `context` or `parentContext` holding `spanContext()`. Check every changed call site, including tests.
- ❌ `AgentSpanContext ctx = activeSpan.spanContext();`
- ✅ `AgentSpanContext spanContext = activeSpan.spanContext();`

### MAINT-4.2: Keep mechanical refactors mechanical
- Flag: a signature or rename refactor that also reshapes untouched code, such as unrolling a fluent `span.setX().setY()` chain into separate statements.

### MAINT-4.3: Prefer the most direct control-flow shape
- Flag: a flag defaulted to `true` and reset in an `else`, a `continue` that could be a stream `filter`, a deeply nested ByteBuddy matcher chain, or a new delegation layer (`doWrite`) over methods that are already internal delegates.
- ❌ `boolean limitReached = true; if (a) {...} else if (b) {...} else { limitReached = false; }`
- ✅ `boolean limitReached = false; if (a) { limitReached = true; ... }`

### MAINT-4.4: Name new types and test variables for what they are
- Flag: a new no-op or empty implementation named unlike its siblings (`EmptyContextContinuation` beside `NoopContextScope`), a count named like a collection (`baggageItems`), test variables named parent/child when the test creates no such relationship, or test locals that don't say which value is expected and which is actual.

## 5. Tests

### MAINT-5.1: Reuse shared test utilities (`ThreadUtils.ThrowingRunnable`, `PollingConditions`, `SpanMatcher.childOfIndex()`, JSONAssert)
- Flag: a test redeclaring a functional interface or polling loop that `utils/test-utils` provides, manual parent-span-id capture where `childOfIndex()` works, field-by-field assertions on a JSON payload, or a container class copied between modules of the same library instead of a shared test fixture.
- Not when: the new helper asserts something different from the existing one (for example the serialized form rather than lookup behaviour).
- ❌ `@FunctionalInterface private interface ThrowingRunnable { void run() throws Exception; }`
- ✅ `import datadog.trace.test.util.ThreadUtils.ThrowingRunnable;`

### MAINT-5.2: Factor repeated test setup into helpers, fields or one parameterized test
- Flag: the same setup of three or more lines (mocks, an expected-values map, an anonymous listener) repeated in three or more test methods of one class; test methods that are copies differing only in input values; or an inline lambda or anonymous class longer than about 15 lines inside a test.
- Not when: the setup is central to each test's lifecycle and reads better spelled out.
- ❌ `ConfigListener listener = mock(ConfigListener.class);` at the top of every test
- ✅ `private final ConfigListener listener = mock(ConfigListener.class);`

### MAINT-5.3: Scope test state and cleanup to the tests that need it
- Flag: a new `@BeforeEach`/`@AfterEach` (or Spock `setup()`/`cleanup()`) whose effect only one test method needs. Check which tests use the state it resets, stops or overrides (for example `Locale.setDefault`, or stopping a component only one test starts). Also flag `static final` mutable collections that a per-test fixture fills and each test has to clear.
- ❌ `@BeforeEach void forceTurkishLocale() { Locale.setDefault(TURKISH); }`
- ✅ `// set and restore the locale inside the one test that checks it`

### MAINT-5.4: Use named constants and expressions in test data, not resolved literals
- Flag: `2147483647` instead of `Integer.MAX_VALUE`, resolved ordinals instead of the constant references, `20480` instead of `20 << 10`, a bare timeout repeated across `eventually` calls, or different timeouts with no reason.
- ❌ `eventually(() -> assertFalse(REQUESTS.isEmpty()), 5000);`
- ✅ `eventually(() -> assertFalse(REQUESTS.isEmpty()), POLL_TIMEOUT_MS);`

### MAINT-5.5: Write assertions that state what the test checks
- Flag: incidental `assertNotNull` when the point is that the call doesn't throw, array-reference workarounds to get values out of a lambda, compound boolean assertions (`assertTrue(a == 1 || !b)`), or nested stream pipelines in assertions.
- ❌ `ContextContinuation[] ref = {null}; assertDoesNotThrow(() -> ref[0] = context.capture());`
- ✅ `assertDoesNotThrow(() -> { ContextContinuation c = context.capture(); c.release(); }, "capture should not throw");`

### MAINT-5.6: Put tests with the code and test class that own the behaviour
- Flag: contract tests for a type in a new class beside its existing `<Type>Test`, cross-module `testFixtures` added only so another module's instrumentation can be tested, or unrelated coverage (protocol decoding) mixed into a simple smoke test.

## Don't flag
- Grouping all "has thrown" handling (decorate, close scope, finish span) into one branch in advice, when the existing `try`/`finally` shape keeps the scope from leaking if a later call throws.
- A hardcoded floor in a build check (class count, jar size) that is meant to catch regressions, when deriving it from sources would hide them.
- A hand-written `compareTo` kept for measured performance, or a missing `toString()` on DTOs whose siblings don't have one.
- An `xInBytes`-style accessor name that matches existing codebase naming (`Payload.sizeInBytes`).

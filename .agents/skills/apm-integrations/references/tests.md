# Writing Tests

> Referenced from `SKILL.md` Step 9.1 (Instrumentation test). For muzzle directives (Step 9.2), see `muzzle.md` in this directory. For situational rules that don't apply to every module (version-mismatch comments, mutual-exclusion deps, `latestDepTest` routing, style conventions), see [Test Style & Situational Rules](tests-style.md).

## 1. Instrumentation test (mandatory)

**Write Groovy/Spock tests for instrumentation tests** (per `AGENTS.md`: "Only use Groovy / Spock tests for instrumentation and smoke tests"). This is unconditional — including for modules whose existing siblings happen to use Java/JUnit — an existing Java-DSL sibling is NOT license to add more Java tests; do not migrate a Groovy family to Java either. Confirm what the family is on with `ls src/test/` on the module's master version and its version-siblings (e.g. `jedis-1.4/`, `jedis-4.0/` for `jedis-3.0`) before writing tests. Java examples in [tests-style.md](tests-style.md) are style-only illustrations for modules ALREADY on the Java/JUnit DSL — NOT a license to introduce Java into a Groovy family. Adding new `.groovy` files to a PR will trigger the `Enforce Groovy Migration` bot — add the `tag: override groovy enforcement` label to bypass it.

- Groovy/Spock test class in `src/test/groovy/datadog/trace/instrumentation/<framework>/`
- Verify: spans created, tags set, errors propagated, resource names correct
- Use `assertTraces(N) { trace(N) { span { ... } } }` for span assertions (Spock DSL from `InstrumentationSpecification`)
- Use `TEST_WRITER.waitForTraces(N)` for setup/teardown flushing (not for assertions)
- Use `runUnderTrace("root") { ... }` from `TraceUtils` for synchronous code (trailing Groovy closure)

**Tests must cover error/exception scenarios, not just the happy path.** At minimum, add a test that exercises an exception or error condition and asserts the span's error tags (`error.type`, `error.message`, `error.stack`) are set correctly. See [tests-style.md](tests-style.md#error-test-example) for an example.

For tests that need a separate JVM, suffix the test class with `ForkedTest` and run via the `forkedTest` task — see [tests-style.md](tests-style.md#forkedtest-variants-must-have-a-concrete-isolation-reason) for when this is actually warranted.

### Register new integration names in `metadata/supported-configurations.json`

See [Supported Configurations](supported-configurations.md) for the key shapes, CI checks, and JSON format.

## Test hygiene (always applies)

- **No `Thread.sleep()`** — use `TEST_WRITER.waitForTraces(N)`, a `CountDownLatch`/`CompletableFuture.get(timeout, ...)`, or Spock's `PollingConditions`. If you catch yourself writing `Thread.sleep(...)`, name the specific signal you're waiting for and wait on that signal directly.
- **Do not add default `jvmArgs`** — `dd.trace.enabled=true` is already the default; only add jvmArgs that meaningfully diverge from defaults.

For the rest — version-mismatch comments, mutual-exclusion `testImplementation` deps, embedded-server reuse, shared base classes, `ForkedTest` isolation criteria, `latestDepTest` source-set routing, and no-banner-comment style — see [tests-style.md](tests-style.md); those are situational and don't come up on every module.

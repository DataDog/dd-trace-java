---
name: migrate-groovy-to-java
description: >
  Converts Spock/Groovy test files in a Gradle module to equivalent JUnit 5 Java tests.
  Use when asked to "migrate groovy", "convert groovy to java", "g2j", or when a module
  has .groovy test files that need to be replaced with .java equivalents.
---

Migrate test Groovy files to Java using JUnit 5

1. List all Groovy files of the current Gradle module
2. Convert Groovy files to Java using JUnit 5
3. Make sure the tests are still passing after migration and that the test count has not changed
4. Remove Groovy files

When converting Groovy code to Java code, make sure that:
- The Java code generated is compatible with JDK 8
- When translating Spock tests, prefer `@TableTest` for data rows that are naturally tabular. See detailed guidance in the "TableTest usage" section.
- `@TableTest` and `@MethodSource` may be combined on the same `@ParameterizedTest` when most cases are tabular but a few cases require programmatic setup.
- In combined mode, keep table-friendly cases in `@TableTest`, and put only non-tabular/complex cases in `@MethodSource`.
- If `@TableTest` is not viable for the test at all, use `@MethodSource` only.
- If `@TableTest` was successfully used and if the `@ParameterizedTest` is not used to specify the test name, `@ParameterizedTest` can then be removed as `@TableTest` replace it fully.
- For `@MethodSource`, name the arguments method `<testMethodName>Arguments` (camelCase, e.g. `testMethodArguments`) and return `Stream<Arguments>` using `Stream.of(...)` and `arguments(...)` with static import.
- Ensure parameterized test names are human-readable (i.e. no hashcodes); instead add a description string as the first `Arguments.arguments(...)` value or index the test case
- When converting tuples, create a light dedicated structure instead to keep the typing system
- Instead of checking a state and throwing an exception, use JUnit asserts
- Instead of using `assertTrue(a.equals(b))` or `assertFalse(a.equals(b))`, use `assertEquals(expected, actual)` and `assertNotEquals(unexpected, actual)`
- Import frequently used types rather than using fully-qualified names inline, to improve readability
- Do not wrap checked exceptions and throw a Runtime exception; prefer adding a throws clause at method declaration
- Do not mark local variables `final`
- Ensure variables are human-readable; avoid single-letter names and pre-define variables that are referenced multiple times
- When translating Spock `Mock(...)` usage, use `libs.bundles.mockito` instead of writing manual recording/stub implementations
- Replace `injectSysConfig(key, value)` calls with `@WithConfig` when the key and value are static literals. Put it on the test method for per-test config, or on the class when every test needs it. The `dd.` prefix is added automatically — use the bare key (e.g. `"trace.scope.strict.mode"`, not `"dd.trace.scope.strict.mode"`). For dynamic or parameterized values, keep the imperative `WithConfigExtension.injectSysConfig(key, value)` call.
- Keep inline comments
- Migrate the named Spock clauses if they exist as inline comments in the Java unit test
- When Groovy tests navigate a JSON request body through helpers like `asMap()` / `asLong()` / `asList()`, check whether `json-unit-assertj` (`libs.json.unit.assertj`) is already in the module's build file. If it is, add a method that returns the raw JSON string and use `assertThatJson(json).node("some.nested.field").isEqualTo(value)` directly instead of the map traversal.
- Groovy's `[key: val]` map literals use a `LinkedHashMap`. When the test doesn't care about insertion order, use `singletonMap` for a single entry or `HashMap` for two or more. If a helper method builds these maps, add a two-arg overload rather than scattering `new LinkedHashMap<>()` constructions through test bodies.

TableTest usage
  Import: `import org.tabletest.junit.TableTest;`

  JDK 8 rules:
    - No text blocks.
    - @TableTest must use String[] annotation array syntax:
    ```
    @TableTest({
      "a | b",
      "1 | 2"
    })
    ```

  Spock `where:` → @TableTest:
    - First row = header (column names = method parameters).
    - Add `scenario` column as first column (display name, not a method parameter).
    - Use `|` delimiter; align columns so pipes line up vertically.
    - Prefer single quotes for strings with special chars (e.g., `'a|b'`, `'[]'`).
    - Blank cell = null (object types); `''` = empty string.
    - Collections: `[a, b]` = List/array, `{a, b}` = Set, `[k: v]` = Map.

  Mixed eligibility:
    - Prefer combining `@TableTest` + `@MethodSource` on one `@ParameterizedTest` when only some cases are complex.
    - Use `@MethodSource` only when tabular representation is not practical for the test.

  Do NOT use @TableTest when:
    - Majority of rows require complex objects or custom converters.

## Quality rules (apply during generation)

Before writing any Java output, read `.claude/skills/migrate-groovy-to-java/QUALITY_RULES.md` in full.
Apply all BLOCKER rules unconditionally.
Apply WARNING rules unless they require creating utility classes that do not yet exist.
Apply STYLE rules where they improve clarity without added complexity.

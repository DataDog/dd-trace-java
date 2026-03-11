---
name: use-tabletest
description: Convert JUnit 5 @MethodSource/@CsvSource/@ValueSource parameterized tests to @TableTest (JDK8)
---
Goal: Migrate JUnit 5 parameterized tests using @MethodSource/@CsvSource/@ValueSource to @TableTest with minimal churn and passing tests.

Process (do in this order):
1) Locate targets via Grep (no agent subprocess). Search for: "@ParameterizedTest", "@MethodSource", "@CsvSource", "@ValueSource".
2) Read all matching files up front (parallel is OK).
3) Convert eligible tests to @TableTest.
4) Write each modified file once in full using Write (no incremental per-test edits).
5) Run module tests once and verify "BUILD SUCCESSFUL". If failed, inspect JUnit XML report.

Dependency:
- If missing, add:
  - Groovy: testImplementation libs.tabletest
  - Kotlin: testImplementation(libs.tabletest)

Import:
- Ensure: import org.tabletest.junit.TableTest;

JDK 8 rules:
- No text blocks.
- @TableTest must use String[] annotation array syntax:
  @TableTest({ "a | b", "1 | 2" })

Table formatting rules (mandatory):
- Always include a header row (parameter names).
- Always add a "scenario" column; using common sense for naming; scenario is NOT a method parameter.
- Use '|' as delimiter.
- Align columns with spaces so pipes line up vertically.
- Prefer single quotes for strings requiring quotes (e.g., 'a|b', '[]', '{}', ' ').

Conversions:
A) @CsvSource
- Remove @ParameterizedTest and @CsvSource.
- If delimiter is '|': rows map directly to @TableTest.
- If delimiter is ',' (default): replace ',' with '|' in rows.

B) @ValueSource
- Convert to @TableTest with header from parameter name.
- Each value becomes one row.
- Add "scenario" column using common sense for name.

C) @MethodSource (convert only if values are representable as strings)
- Convert when argument values are primitives, strings, enums, booleans, nulls, and simple collection literals supported by TableTest:
  - Array: [a, b, ...]
  - List: [a, b, ...]
  - Set: {a, b, ...}
  - Map: [k: v, ...]
- Blank cell = null (non-primitive).
- '' = empty string.
- For String params that start with '[' or '{', quote to avoid collection parsing (prefer '[]'/'{}').

Scenario handling:
- If MethodSource includes a leading description string OR @ParameterizedTest(name=...) uses {0}, convert that to a scenario column and remove that parameter from method signature.

Cleanup:
- Delete now-unused @MethodSource provider methods and unused imports.

Mixed eligibility:
- If only a few cases need complex construction, split:
  - Simple cases ⇒ @TableTest
  - Complex cases ⇒ separate @Test(s) (descriptive names) OR keep a small @MethodSource.

Do NOT convert when:
- Most rows require complex builders/mocks.
- Parameters are arrays (String[], int[]) — keep @MethodSource (or refactor to List to convert).

Test command (exact):
./gradlew :path:to:module:test --rerun-tasks 2>&1 | tail -20
- If BUILD FAILED: cat path/to/module/build/test-results/test/TEST-*.xml

Never:
- --info
- extra gradle runs just to “confirm”

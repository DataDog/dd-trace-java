---
name: migrate-junit-source-to-tabletest
description: >-
  Convert eligible JUnit 5 @MethodSource, @CsvSource, and @ValueSource parameterized
  tests to @TableTest with minimal churn and JDK 8 compatibility. Also the shared
  argument-source guidance for Spock where: blocks in Groovy migrations.
---

Migrate eligible tests while preserving assertions, setup, and case coverage. This is the canonical TableTest guidance; `migrate-groovy-to-java` delegates here.

## Choosing argument sources

- Retain `@ValueSource` for single-parameter literal tests by default. Convert to `@TableTest` only when every row has a precise, natural scenario name that explains why the case matters and clearly improves readability. Names that merely repeat the input, add generic labels, or invent unsupported meaning do not justify conversion; when in doubt, keep `@ValueSource`.
- Retain `@NullSource`, `@EmptySource`, and `@NullAndEmptySource` when they clearly mark the tested inputs. Do not absorb them into a table merely for consistency. Include null or empty cases in a table only when the same scenario-name criterion improves readability, preserving their distinct meanings and original coverage.
- Prefer `@TableTest` for scenarios that can be described clearly as text, including multi-column data from `@CsvSource`, `@MethodSource`, or Spock `where:` blocks.
- For object arguments, use [built-in conversion](https://tabletest.org/reference/type-conversion/) or a simple `@TypeConverter` when table values map naturally to the target type. Check the test class, module helpers, and `utils/test-junit-converter-utils` for an existing converter before adding one. Reuse shared converters through `@TypeConverterSources`; JUnit's parameter-level `@ConvertWith` is also supported.
- Before choosing `@MethodSource` for an object argument, attempt to express its building blocks as table columns and construct the object at the start of the test. Keep construction short, deterministic, and faithful to the original behavior. Avoid elaborate parsers, branching factories, or mocks introduced solely to force a table.
- Combine `@TableTest` and `@MethodSource` on the same `@ParameterizedTest` when they exercise the same behavior with compatible argument signatures. Keep text-friendly cases in the table and only cases requiring programmatic construction in the method source. Do not split a test merely because its sources differ or duplicate cases across sources.
- Fall back to `@MethodSource` when literals, simple converters, and readable building blocks cannot express a case easily. The proportion of complex rows alone does not determine eligibility.
- Keep `@ParameterizedTest` for combined sources or custom JUnit names. A table-only test can use `@TableTest` alone.

For example, represent a socket address as host, port, and resolvable, then construct it at the start of the test:

```java
@TableTest({
  "scenario   | host      | port | resolvable",
  "resolved   | 127.0.0.1 | 8126 | true",
  "unresolved | localhost | 8126 | false"
})
void handlesAddress(String host, int port, boolean resolvable) {
  InetSocketAddress address =
      resolvable
          ? new InetSocketAddress(host, port)
          : InetSocketAddress.createUnresolved(host, port);
  // Exercise the behavior using address and retain the original assertions.
}
```

A single conditional choosing between two constructors is acceptable; anything beyond that is the elaborate construction to avoid. Use numeric loopback for the resolved case to avoid external DNS. Preserve resolution-specific behavior if the original test exercises it.

## Table representation

Import `org.tabletest.junit.TableTest`. For JDK 8, use string-array annotation syntax as above, never text blocks.

- Include a header row with argument column names and a first `scenario` column for readable names. The scenario column is not a method parameter.
- Use `|` delimiters. Do not hand-align columns: run `./gradlew spotlessApply`, whose TableTest formatter aligns every column, including padding the final cell. Accept its output as-is.
- Preserve CSV quoting, null, empty-string, and delimiter semantics when rewriting rows; do not blindly replace commas inside values.
- Blank cells mean null for non-primitives; `''` means an empty string. Quote strings with delimiters, significant whitespace, or collection-looking values: `'a|b'`, `' '`, `'[]'`, `'{}'`.
- Supported values include primitives, strings, enums, booleans, nulls, and simple collection literals: arrays/lists `[a, b]`, sets `{a, b}`, and maps `[k: v]`. Array parameters such as `String[]` accept list literals (see `JsonMapperTest.testMappingArrayToJsonArray` in `components/json`).
- Use value sets (`{a, b, c}`) to collapse rows that differ in a single column. Value sets in several columns of one row expand to their Cartesian product, which can silently change the invocation count; only do that when the original covered every combination. Distinguish a value set (multiple cases) from a collection passed as one argument.
- Prefer a simple `@TypeConverter` for symbolic constants such as `Long.MAX_VALUE` or `DDSpanId.MAX`. Reuse existing shared converters; introduce shared utilities only with concrete reuse.

Combined method sources must supply the actual test parameters, without the table's display-only scenario column. Remove a former description parameter only after adapting all retained sources and preserving readable names. Name providers `<testMethodName>Arguments`, returning `Stream<Arguments>` with `Stream.of(...)` and statically imported `arguments(...)`.

## Spock `where:` blocks

- Data table header names become method parameters; prepend a `scenario` column, deriving names from the Spock feature name's `#placeholders` when present.
- Spock's `||` input/output separator becomes a plain `|`.
- Data pipes (`x << [a, b]`) become one column per variable; multiple pipes in one block zip by index, not as a Cartesian product, so expand them row by row.
- Derived data variables (`y = x * 2`) become local variables computed at the start of the test, not columns.

## Migration and validation

1. Locate candidates with `rg` for `@ParameterizedTest`, `@MethodSource`, `@CsvSource`, and `@ValueSource`; read relevant files before editing. For Groovy migrations, apply the same selection and representation rules to `where:` data.
2. Read the repository's JUnit testing guide (`docs/how_to_test_with_junit.md`). The `dd-trace-java.conventions.java` plugin already adds `libs.tabletest` to `testImplementation`; add the dependency and regenerate lockfiles only for a module that does not apply it.
3. Plan each file's conversion as a coherent edit. Remove replaced annotations, unused imports, and providers only when no remaining tests use them. Run `./gradlew spotlessApply` afterwards.
4. Run affected module tests with `./gradlew :path:to:module:test --rerun-tasks`. Inspect JUnit XML on failure and compare case coverage and invocation counts, including arrays and combined sources. Repeat only after changes or unresolved failures warrant it.
5. Report tested scope and cases retained on their original source. Formatting success alone is not behavioral validation.

package com.datadog.iast.sensitive;

import static datadog.trace.api.iast.sink.SqlInjectionModule.DATABASE_PARAMETER;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.sensitive.SensitiveHandler.Tokenizer;
import com.datadog.iast.util.Ranged;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SqlRegexpTokenizerTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("tokenizesSqlLiteralsArguments")
  void tokenizesSqlLiterals(
      final String description,
      @Nullable final String dialect,
      final String sql,
      final List<String> expected) {
    assertEquals(expected, tokenize(dialect, sql));
  }

  static Stream<Arguments> tokenizesSqlLiteralsArguments() {
    return Stream.of(
        // ANSI (default dialect when no database is provided)
        arguments(
            "ansi single-quoted string",
            null,
            "SELECT name FROM u WHERE name = 'john'",
            singletonList("john")),
        arguments(
            "ansi escaped single quote", null, "SELECT 'O''Brien'", singletonList("O''Brien")),
        arguments("ansi integer literal", null, "SELECT 12345", singletonList("12345")),
        arguments("ansi decimal literal", null, "SELECT 3.14", singletonList("3.14")),
        arguments("ansi hex literal", null, "SELECT 0x1aF", singletonList("0x1aF")),
        arguments("ansi line comment", null, "SELECT a -- bye", singletonList(" bye")),
        arguments("ansi block comment", null, "SELECT /* hidden */ a", singletonList(" hidden ")),
        arguments("ansi ignores double quotes", null, "SELECT \"x\" FROM t", emptyList()),
        // MySQL family treats double-quoted strings as literals
        arguments(
            "mysql double-quoted string",
            "mysql",
            "SELECT \"secret\" FROM t",
            singletonList("secret")),
        // Oracle q'...' escaped literals (bracket pairs + enumerated single-char delimiters)
        arguments(
            "oracle q bracket literal",
            "oracle",
            "SELECT q'[hello]' FROM dual",
            singletonList("hello")),
        arguments(
            "oracle q paren literal",
            "oracle",
            "SELECT q'(hello)' FROM dual",
            singletonList("hello")),
        arguments(
            "oracle q custom delimiter",
            "oracle",
            "SELECT q'#hello#' FROM dual",
            singletonList("hello")),
        // Oracle forbids whitespace as a q' delimiter, so a space-delimited q' is intentionally
        // NOT recognized as a q-literal; its content is still captured by the generic
        // single-quoted-string branch (so the secret is still redacted, just not q-unwrapped).
        arguments(
            "oracle q whitespace delimiter is not a q-literal",
            "oracle",
            "SELECT q' secret ' FROM dual",
            singletonList(" secret ")),
        // PostgreSQL dollar-quoting
        arguments(
            "postgres dollar quote",
            "postgresql",
            "SELECT $tag$secret$tag$",
            singletonList("secret")),
        arguments(
            "postgres empty-tag dollar quote",
            "postgresql",
            "SELECT $$secret$$",
            singletonList("secret")),
        arguments("postgres overlapping tags", "postgresql", "SELECT $a$x$a$", singletonList("x")),
        arguments(
            "postgres unterminated tag is skipped", "postgresql", "SELECT $tag$value", emptyList()),
        // Boundary cases around buildDollarTagPositions: the scan must not read past end-of-string.
        arguments("postgres lone dollar at end of string", "postgresql", "SELECT a$", emptyList()),
        arguments(
            "postgres identifier tag without closing dollar at end of string",
            "postgresql",
            "SELECT $tag",
            emptyList()),
        arguments(
            "postgres empty-tag opener without close at end of string",
            "postgresql",
            "SELECT $$secret",
            emptyList()),
        // Parameter placeholders ($1, $2) must NOT be treated as dollar-quote openers; only their
        // digits are tokenized as numeric literals.
        arguments(
            "postgres placeholders are not dollar quotes",
            "postgresql",
            "SELECT * FROM t WHERE a = $1 AND b = $2",
            asList("1", "2")));
  }

  @Test
  void manyUnterminatedDollarTagsRunInLinearTime() {
    // Each "$tN$" is a distinct, valid but unterminated dollar-quote opener. The previous
    // indexOf-per-opener implementation scanned to end-of-string once per opener (O(n^2)) and would
    // not finish anywhere near this budget; the precomputed tag index keeps it near-linear.
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < 60_000; i++) {
      builder.append('$').append('t').append(i).append('$');
    }
    String sql = builder.toString();

    assertTimeoutPreemptively(
        Duration.ofSeconds(10),
        () -> {
          Tokenizer tokenizer = new SqlRegexpTokenizer(postgresEvidence(sql));
          // None of the openers has a matching close, so tokenization yields nothing and must
          // simply terminate quickly.
          assertFalse(tokenizer.next());
        });
  }

  private static Evidence postgresEvidence(String sql) {
    return evidence("postgresql", sql);
  }

  private static Evidence evidence(@Nullable String dialect, String sql) {
    Evidence evidence = new Evidence(sql);
    if (dialect != null) {
      evidence.getContext().put(DATABASE_PARAMETER, dialect);
    }
    return evidence;
  }

  private static List<String> tokenize(@Nullable String dialect, String sql) {
    Tokenizer tokenizer = new SqlRegexpTokenizer(evidence(dialect, sql));
    List<String> tokens = new ArrayList<>();
    while (tokenizer.next()) {
      Ranged range = tokenizer.current();
      tokens.add(sql.substring(range.getStart(), range.getStart() + range.getLength()));
    }
    return tokens;
  }
}

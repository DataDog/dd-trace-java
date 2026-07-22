package com.datadog.iast.sensitive;

import static datadog.trace.api.iast.sink.SqlInjectionModule.DATABASE_PARAMETER;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.sensitive.SensitiveHandler.Tokenizer;
import java.util.Arrays;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** Tracks the cost of the IAST evidence-redaction "sensitive analyzer" tokenizers. */
@Warmup(iterations = 2, time = 250, timeUnit = MILLISECONDS)
@Measurement(iterations = 3, time = 250, timeUnit = MILLISECONDS)
@Fork(1)
@OutputTimeUnit(MICROSECONDS)
@BenchmarkMode(Mode.AverageTime)
@State(Scope.Benchmark)
public class SensitiveTokenizerBenchmark {

  /** Each scenario pairs a malformed payload shape with the tokenizer that processes it. */
  public enum Scenario {
    /** LDAP filter opened, never closed, packed with operators — quadratic: {@code "(" + "="*n}. */
    LDAP_UNCLOSED_FILTER {
      @Override
      String payload(final int n) {
        return "(" + repeat('=', n - 1);
      }

      @Override
      Tokenizer tokenizer(final String payload) {
        return new LdapRegexTokenizer(new Evidence(payload));
      }
    },
    /** Repeated open-group + operator — CUBIC, the worst found: {@code "(="*n}. */
    LDAP_NESTED_OPEN_EQ {
      @Override
      String payload(final int n) {
        return repeatUnit("(=", n);
      }

      @Override
      Tokenizer tokenizer(final String payload) {
        return new LdapRegexTokenizer(new Evidence(payload));
      }
    },
    /** ANSI SQL string literal opened but never closed — stack overflow: {@code "'" + "a"*n}. */
    SQL_ANSI_UNTERMINATED_STRING {
      @Override
      String payload(final int n) {
        return "'" + repeat('a', n - 1);
      }

      @Override
      Tokenizer tokenizer(final String payload) {
        return sql(payload, null);
      }
    },
    /** Oracle {@code q'<delim> ...} escaped literal with no matching close — stack overflow. */
    SQL_ORACLE_ESCAPED_LITERAL {
      @Override
      String payload(final int n) {
        return "q'~" + repeat('a', n - 3);
      }

      @Override
      Tokenizer tokenizer(final String payload) {
        return sql(payload, "oracle");
      }
    },
    /** MySQL double-quoted string literal opened but never closed — stack overflow. */
    SQL_MYSQL_UNTERMINATED_STRING {
      @Override
      String payload(final int n) {
        return "\"" + repeat('a', n - 1);
      }

      @Override
      Tokenizer tokenizer(final String payload) {
        return sql(payload, "mysql");
      }
    },
    /** URL query separator + long key, no {@code =} value — linear baseline. */
    URL_QUERY {
      @Override
      String payload(final int n) {
        return "http://h/p?" + repeat('a', n - 11);
      }

      @Override
      Tokenizer tokenizer(final String payload) {
        return new UrlRegexpTokenizer(new Evidence(payload));
      }
    },
    /** Run of {@code ?} (also matched by {@code [^=&;]}) — quadratic: {@code "?"*n}. */
    URL_QUESTION_RUN {
      @Override
      String payload(final int n) {
        return repeat('?', n);
      }

      @Override
      Tokenizer tokenizer(final String payload) {
        return new UrlRegexpTokenizer(new Evidence(payload));
      }
    },
    /** URL authority started with {@code //}, no {@code @} terminator — linear baseline. */
    URL_AUTHORITY {
      @Override
      String payload(final int n) {
        return "//" + repeat('a', n - 2);
      }

      @Override
      Tokenizer tokenizer(final String payload) {
        return new UrlRegexpTokenizer(new Evidence(payload));
      }
    },
    /** Single command + long argument — linear baseline. */
    COMMAND_SINGLE_TOKEN {
      @Override
      String payload(final int n) {
        return "cmd " + repeat('a', n - 4);
      }

      @Override
      Tokenizer tokenizer(final String payload) {
        return new CommandRegexpTokenizer(new Evidence(payload));
      }
    },
    /**
     * Blank lines exploit MULTILINE {@code ^} + {@code \s*} backtracking — quadratic: {@code
     * "\n"*n}.
     */
    COMMAND_BLANK_LINES {
      @Override
      String payload(final int n) {
        return repeat('\n', n);
      }

      @Override
      Tokenizer tokenizer(final String payload) {
        return new CommandRegexpTokenizer(new Evidence(payload));
      }
    };

    abstract String payload(int sizeBytes);

    abstract Tokenizer tokenizer(String payload);

    static Tokenizer sql(final String payload, final String dialect) {
      final Evidence evidence = new Evidence(payload);
      if (dialect != null) {
        evidence.getContext().put(DATABASE_PARAMETER, dialect);
      }
      return new SqlRegexpTokenizer(evidence);
    }

    static String repeat(final char c, final int count) {
      final int n = Math.max(count, 0);
      final char[] chars = new char[n];
      Arrays.fill(chars, c);
      return new String(chars);
    }

    static String repeatUnit(final String unit, final int totalLen) {
      final int n = Math.max(totalLen, 0);
      final StringBuilder sb = new StringBuilder(n);
      while (sb.length() < n) {
        sb.append(unit);
      }
      sb.setLength(n);
      return sb.toString();
    }
  }

  @Param({
    "LDAP_UNCLOSED_FILTER",
    "LDAP_NESTED_OPEN_EQ",
    "SQL_ANSI_UNTERMINATED_STRING",
    "SQL_ORACLE_ESCAPED_LITERAL",
    "SQL_MYSQL_UNTERMINATED_STRING",
    "URL_QUERY",
    "URL_QUESTION_RUN",
    "URL_AUTHORITY",
    "COMMAND_SINGLE_TOKEN",
    "COMMAND_BLANK_LINES"
  })
  Scenario scenario;

  @Param({"512", "1024", "2048"})
  int sizeBytes;

  private String payload;

  @Setup(Level.Trial)
  public void setup() {
    payload = scenario.payload(sizeBytes);
  }

  /**
   * Builds the tokenizer and fully drains it, exactly as evidence redaction does. Returns the
   * number of tokens (consumed by JMH). A pathological pattern may overflow the stack; we catch it
   * so the run stays stable and report {@code -1} — see the class javadoc.
   */
  @Benchmark
  public long tokenize() {
    try {
      final Tokenizer tokenizer = scenario.tokenizer(payload);
      long count = 0;
      while (tokenizer.next()) {
        tokenizer.current();
        count++;
      }
      return count;
    } catch (final Throwable pathological) {
      return -1;
    }
  }
}

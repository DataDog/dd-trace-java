package com.datadog.iast.sensitive;

import static datadog.trace.api.iast.sink.SqlInjectionModule.DATABASE_PARAMETER;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.util.Ranged;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public class SqlRegexpTokenizer extends AbstractRegexTokenizer {

  private static final String STRING_LITERAL = "'(?:''|[^'])*'";
  private static final String ORACLE_ESCAPED_LITERAL =
      "q'<.*?>'|q'\\(.*?\\)'|q'\\{.*?\\}'|q'\\[.*?\\]'|q'(?<ESCAPE>.).*?\\k<ESCAPE>'";
  private static final String POSTGRESQL_ESCAPED_LITERAL =
      "\\$(?<ESCAPE>[^$]*?)\\$.*?\\$\\k<ESCAPE>\\$";
  private static final String MYSQL_STRING_LITERAL = "\"(?:\\\"|[^\"])*\"|'(?:\\'|[^'])*'";
  private static final String LINE_COMMENT = "--.*$";
  private static final String BLOCK_COMMENT = "/\\*[\\s\\S]*\\*/";
  private static final String EXPONENT = "(?:E[-+]?\\d+[fd]?)?";
  private static final String INTEGER_NUMBER = "(?<!\\w)\\d+";
  private static final String DECIMAL_NUMBER = "\\d*\\.\\d+";
  private static final String HEX_NUMBER = "x'[0-9a-f]+'|0x[0-9a-f]+";
  private static final String BIN_NUMBER = "b'[0-9a-f]+'|0b[0-9a-f]+";
  private static final String NUMERIC_LITERAL =
      String.format(
          "[-+]?(?:%s)",
          String.join(
              "|", HEX_NUMBER, BIN_NUMBER, DECIMAL_NUMBER + EXPONENT, INTEGER_NUMBER + EXPONENT));

  private static final Map<Dialect, Pattern> PATTERNS = new EnumMap<>(Dialect.class);

  private final String sql;

  public SqlRegexpTokenizer(final Evidence evidence) {
    super(
        PATTERNS.computeIfAbsent(Dialect.fromEvidence(evidence), Dialect::buildPattern),
        evidence.getValue());
    this.sql = evidence.getValue();
  }

  @Override
  protected Ranged buildNext() {
    int start = matcher.start();
    int end = matcher.end();
    final char startChar = sql.charAt(start);
    if (startChar == '\'' || startChar == '"') {
      start++;
      end--;
    } else if (end > start + 1) {
      final char nextChar = sql.charAt(start + 1);
      if (startChar == '/' && nextChar == '*') {
        start += 2;
        end -= 2;
      } else if (startChar == '-' && startChar == nextChar) {
        start += 2;
      } else if (Character.toLowerCase(startChar) == 'q' && nextChar == '\'') {
        start += 3;
        end -= 2;
      } else if (startChar == '$') {
        final String match = matcher.group();
        final int size = match.indexOf('$', 1) + 1;
        if (size > 1) {
          start += size;
          end -= size;
        }
      }
    }
    return Ranged.build(start, end - start);
  }

  private enum Dialect {
    ORACLE(
        "oracle"::equalsIgnoreCase,
        () ->
            buildPattern(
                NUMERIC_LITERAL,
                ORACLE_ESCAPED_LITERAL,
                STRING_LITERAL,
                LINE_COMMENT,
                BLOCK_COMMENT)),
    POSTGRESQL(
        "postgresql"::equalsIgnoreCase,
        () ->
            buildPattern(
                NUMERIC_LITERAL,
                POSTGRESQL_ESCAPED_LITERAL,
                STRING_LITERAL,
                LINE_COMMENT,
                BLOCK_COMMENT)),

    MYSQL(
        "mysql"::equalsIgnoreCase,
        () -> buildPattern(NUMERIC_LITERAL, MYSQL_STRING_LITERAL, LINE_COMMENT, BLOCK_COMMENT)),
    MARIADB("mariadb"::equalsIgnoreCase, MYSQL::buildPattern),
    SQLITE("sqlite"::equalsIgnoreCase, MYSQL::buildPattern),
    ANSI(
        dialect -> true,
        () -> buildPattern(NUMERIC_LITERAL, STRING_LITERAL, LINE_COMMENT, BLOCK_COMMENT));

    private final Predicate<String> dialect;
    private final Supplier<Pattern> pattern;

    Dialect(final Predicate<String> dialect, final Supplier<Pattern> pattern) {
      this.dialect = dialect;
      this.pattern = pattern;
    }

    public static Dialect fromEvidence(final Evidence evidence) {
      if (evidence.getContext() != null) {
        final String database = evidence.getContext().get(DATABASE_PARAMETER);
        for (Dialect item : Dialect.values()) {
          if (item.dialect.test(database)) {
            return item;
          }
        }
      }
      return ANSI;
    }

    public Pattern buildPattern() {
      return pattern.get();
    }

    private static Pattern buildPattern(final String... patterns) {
      return Pattern.compile(
          String.join("|", patterns), Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    }
  }
}

package com.datadog.iast.sensitive;

import static datadog.trace.api.iast.sink.SqlInjectionModule.DATABASE_PARAMETER;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.util.Ranged;
import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.annotation.Nullable;

public class SqlRegexpTokenizer implements SensitiveHandler.Tokenizer {

  private static final String STRING_LITERAL = "'(?:''|[^'])*'";
  private static final String ORACLE_ESCAPED_LITERAL = buildOracleEscapedLiteral();
  // $$ or $tag$ where tag is a SQL identifier
  private static final String POSTGRESQL_ESCAPED_LITERAL = "\\$(?:[a-zA-Z_]\\w*)?\\$";
  private static final String MYSQL_STRING_LITERAL = "\"(?:\\\"|[^\"])*\"|'(?:\\'|[^'])*'";
  private static final String LINE_COMMENT = "--.*$";
  private static final String BLOCK_COMMENT = "/\\*[\\s\\S]*\\*/";
  private static final String EXPONENT = "(?:E[-+]?\\d+[fd]?)?";
  private static final String INTEGER_NUMBER = "\\b\\d+";
  private static final String DECIMAL_NUMBER = "\\d*\\.\\d+";
  private static final String HEX_NUMBER = "x'[0-9a-f]+'|0x[0-9a-f]+";
  private static final String BIN_NUMBER = "b'[0-9a-f]+'|0b[0-9a-f]+";
  private static final String NUMERIC_LITERAL =
      String.format(
          "[-+]?(?:%s)",
          String.join(
              "|", HEX_NUMBER, BIN_NUMBER, DECIMAL_NUMBER + EXPONENT, INTEGER_NUMBER + EXPONENT));

  private static final Map<Dialect, Pattern> PATTERNS = new ConcurrentHashMap<>();

  private final String sql;
  private final Matcher matcher;
  private int searchFrom;
  @Nullable private Ranged current;
  // Lazily built (Postgres only): every "$tag$" occurrence indexed by tag, so the matching close
  // can be located with a binary search instead of an O(n) scan per opener.
  @Nullable private Map<String, int[]> dollarTagPositions;

  public SqlRegexpTokenizer(final Evidence evidence) {
    this.sql = evidence.getValue();
    this.matcher =
        PATTERNS
            .computeIfAbsent(Dialect.fromEvidence(evidence), Dialect::buildPattern)
            .matcher(sql);
  }

  @Override
  public boolean next() {
    while (matcher.find(searchFrom)) {
      final int start = matcher.start();
      int end = matcher.end();
      int rangeStart = start;
      int rangeEnd = end;
      final char startChar = sql.charAt(start);
      if (startChar == '$') {
        // Postgres dollar-quoting: the regex matched the opening "$tag$"; find the matching close.
        final String tag = sql.substring(start, end);
        final int close = nextDollarTag(tag, end);
        if (close < 0) {
          // No matching close tag: not a dollar-quoted literal. Skip past the whole opener we
          // already matched (not just one char) so find() does not re-scan it.
          searchFrom = end;
          continue;
        }
        end = close + tag.length();
        rangeStart = start + tag.length();
        rangeEnd = close;
      } else if (startChar == '\'' || startChar == '"') {
        rangeStart++;
        rangeEnd--;
      } else if (end > start + 1) {
        final char nextChar = sql.charAt(start + 1);
        if (startChar == '/' && nextChar == '*') {
          rangeStart += 2;
          rangeEnd -= 2;
        } else if (startChar == '-' && startChar == nextChar) {
          rangeStart += 2;
        } else if (Character.toLowerCase(startChar) == 'q' && nextChar == '\'') {
          rangeStart += 3;
          rangeEnd -= 2;
        }
      }
      searchFrom = end;
      current = Ranged.build(rangeStart, rangeEnd - rangeStart);
      return true;
    }
    current = null;
    return false;
  }

  @Override
  public Ranged current() {
    if (current == null) {
      throw new NoSuchElementException();
    }
    return current;
  }

  /**
   * Returns the start offset of the first {@code "$tag$"} occurrence at or after {@code from}, or
   * {@code -1} if there is none. Equivalent to {@code sql.indexOf(tag, from)} for dollar-quote tags
   * but backed by a precomputed index so the whole tokenization stays near-linear instead of
   * scanning to end-of-string once per opener.
   */
  private int nextDollarTag(final String tag, final int from) {
    final int[] positions = dollarTagPositions().get(tag);
    if (positions == null) {
      return -1;
    }
    // first position >= from
    int lo = 0;
    int hi = positions.length;
    while (lo < hi) {
      final int mid = (lo + hi) >>> 1;
      if (positions[mid] < from) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo < positions.length ? positions[lo] : -1;
  }

  private Map<String, int[]> dollarTagPositions() {
    if (dollarTagPositions == null) {
      dollarTagPositions = buildDollarTagPositions(sql);
    }
    return dollarTagPositions;
  }

  /**
   * Single left-to-right pass collecting the start offset of every {@code "$tag$"} token (empty tag
   * or a SQL identifier), keyed by the token text. Each character is visited once: the optional
   * identifier run after a {@code '$'} contains no {@code '$'}, so runs from distinct openers never
   * overlap, making the scan O(n).
   */
  private static Map<String, int[]> buildDollarTagPositions(final String sql) {
    final Map<String, List<Integer>> positions = new HashMap<>();
    final int length = sql.length();
    for (int i = 0; i < length; i++) {
      if (sql.charAt(i) != '$') {
        continue;
      }
      int end = i + 1;
      if (end < length && isIdentifierStart(sql.charAt(end))) {
        end++;
        while (end < length && isIdentifierPart(sql.charAt(end))) {
          end++;
        }
      }
      if (end < length && sql.charAt(end) == '$') {
        final String tag = sql.substring(i, end + 1);
        positions.computeIfAbsent(tag, k -> new ArrayList<>()).add(i);
      }
    }
    final Map<String, int[]> result = new HashMap<>(positions.size() * 2);
    for (final Map.Entry<String, List<Integer>> entry : positions.entrySet()) {
      final List<Integer> list = entry.getValue();
      final int[] array = new int[list.size()];
      for (int i = 0; i < array.length; i++) {
        array[i] = list.get(i);
      }
      result.put(entry.getKey(), array);
    }
    return result;
  }

  private static boolean isIdentifierStart(final char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
  }

  private static boolean isIdentifierPart(final char c) {
    return isIdentifierStart(c) || (c >= '0' && c <= '9');
  }

  /**
   * Builds the Oracle {@code q'…'} alternation: the four bracket-paired delimiters plus one branch
   * per other printable single-character delimiter (RE2J has no back-reference to require the same
   * closing char, so the finite delimiter alphabet is enumerated).
   *
   * <p>The enumeration is intentionally restricted to the printable ASCII range {@code 0x21..0x7e}.
   * Oracle forbids whitespace (space, tab, carriage return) as a {@code q'} delimiter, so excluding
   * those characters matches Oracle's own rules rather than dropping valid literals. Multi-byte
   * (non-ASCII) delimiters, which Oracle does allow, are not enumerated; such literals fall back to
   * being tokenized by the generic {@code STRING_LITERAL} branch.
   */
  private static String buildOracleEscapedLiteral() {
    final List<String> alternatives = new ArrayList<>();
    alternatives.add("q'<.*?>'");
    alternatives.add("q'\\(.*?\\)'");
    alternatives.add("q'\\{.*?\\}'");
    alternatives.add("q'\\[.*?\\]'");
    for (char delim = 0x21; delim <= 0x7e; delim++) {
      // brackets handled above; ' is ambiguous with the surrounding quotes.
      if ("<>(){}[]'".indexOf(delim) >= 0) {
        continue;
      }
      final String escaped = escapeDelimiter(delim);
      alternatives.add("q'" + escaped + ".*?" + escaped + "'");
    }
    return String.join("|", alternatives);
  }

  private static String escapeDelimiter(final char delim) {
    return "\\.+*?^$|".indexOf(delim) >= 0 ? "\\" + delim : String.valueOf(delim);
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

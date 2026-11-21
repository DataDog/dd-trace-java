package datadog.trace.util;

import java.util.regex.Pattern;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Glob matching as trace sampling rules use it, on service, operation and resource names: {@link
 * GlobMatcher#compileIgnoringCase} against the case-insensitive {@code java.util.regex} pattern the
 * glob translates to, which is what {@code Matchers.compileGlob} matched with before it moved to
 * {@code GlobMatcher}.
 *
 * <p>Inputs: a name that matches, a short one that doesn't, and a 500-character resource name that
 * doesn't, as SQL or URL resources can be. Report time and allocation ({@code -prof gc}).
 */
@Fork(3)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Threads(4)
@State(Scope.Benchmark)
public class GlobMatcherBenchmark {
  @Param({"prefix", "suffix", "contains", "path", "question"})
  public String shape;

  @Param({"hit", "miss", "long-miss"})
  public String input;

  private Pattern regex;
  private GlobMatcher glob;
  private String text;

  @Setup
  public void setUp() {
    final String pattern;
    final String hit;
    final String miss;
    switch (shape) {
      case "prefix":
        pattern = "web-*";
        hit = "web-checkout";
        miss = "api-checkout";
        break;
      case "suffix":
        pattern = "*-prod";
        hit = "checkout-service-prod";
        miss = "checkout-service-staging";
        break;
      case "contains":
        pattern = "*.checkout.*";
        hit = "servlet.checkout.request";
        miss = "servlet.cart.request";
        break;
      case "path":
        pattern = "GET /api/*/orders/*";
        hit = "GET /api/v2/orders/12345";
        miss = "GET /api/v2/carts/12345";
        break;
      case "question":
        pattern = "shard-??-*";
        hit = "shard-07-eu";
        miss = "shard-7-eu";
        break;
      default:
        throw new IllegalArgumentException(shape);
    }
    regex = toRegex(pattern);
    glob = GlobMatcher.compileIgnoringCase(pattern);
    switch (input) {
      case "hit":
        text = hit;
        break;
      case "miss":
        text = miss;
        break;
      default:
        final StringBuilder s = new StringBuilder(500 + miss.length());
        while (s.length() < 500) {
          s.append("SELECT x FROM t WHERE a = ? AND ");
        }
        text = s.append(miss).toString();
        break;
    }
    if (regex.matcher(text).matches() != glob.matches(text)) {
      throw new IllegalStateException("disagree on " + shape + "/" + input);
    }
  }

  /**
   * The glob as a case-insensitive regex, as the removed {@code GlobPattern.globToRegexPattern}
   * built it.
   */
  private static Pattern toRegex(final String glob) {
    final StringBuilder regex = new StringBuilder("^");
    for (final char c : glob.toCharArray()) {
      if (c == '*') {
        regex.append(".*");
      } else if (c == '?') {
        regex.append('.');
      } else if ("^$|.\\()[]{}".indexOf(c) >= 0) {
        regex.append('\\').append(c);
      } else {
        regex.append(c);
      }
    }
    return Pattern.compile(regex.append('$').toString(), Pattern.CASE_INSENSITIVE);
  }

  @Benchmark
  public boolean regex() {
    return regex.matcher(text).matches();
  }

  @Benchmark
  public boolean globMatcher() {
    return glob.matches(text);
  }
}

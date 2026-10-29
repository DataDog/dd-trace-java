package datadog.trace.bootstrap.regex;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Fork(2)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@SuppressForbidden
public class RegexOpsBenchmark {

  static final String SPLIT_REGEX = ",\\s*";
  static final String SPLIT_FASTPATH_REGEX = ",";
  static final String REPLACE_ALL_REGEX = "[^A-Za-z0-9]";
  static final String REPLACE_FIRST_REGEX = "(\\w+)@(\\w+)";
  static final String REPLACE_FIRST_REPLACEMENT = "$1@redacted";
  static final String COMPILE_REGEX = "\\b(\\w+)=(\\w+)\\b";
  static final String MATCHES_REGEX = "[a-z0-9 ,=@.:-]*";

  private static final String SHORT_INPUT = "alpha, beta=1, gamma@example, delta: x";

  @Param({"short", "long"})
  String input;

  String subject;

  @Setup
  public void setup() {
    if ("short".equals(input)) {
      subject = SHORT_INPUT;
    } else {
      StringBuilder sb = new StringBuilder(2100);
      while (sb.length() < 2000) {
        sb.append(SHORT_INPUT).append(", ");
      }
      subject = sb.toString();
    }
  }

  private static String fresh(String regex) {
    return new String(regex);
  }

  @Benchmark
  public Pattern compile_jdk_literal() {
    return Pattern.compile(COMPILE_REGEX);
  }

  @Benchmark
  public Pattern compile_ops_literal() {
    return RegexOps.compile(COMPILE_REGEX);
  }

  @Benchmark
  public Pattern compile_jdk_dynamic() {
    return Pattern.compile(fresh(COMPILE_REGEX));
  }

  @Benchmark
  public Pattern compile_ops_dynamic() {
    return RegexOps.compile(fresh(COMPILE_REGEX));
  }

  @Benchmark
  public int compileFlagsFind_jdk_literal() {
    return countFinds(Pattern.compile(COMPILE_REGEX, Pattern.CASE_INSENSITIVE));
  }

  @Benchmark
  public int compileFlagsFind_ops_literal() {
    return countFinds(RegexOps.compile(COMPILE_REGEX, Pattern.CASE_INSENSITIVE));
  }

  @Benchmark
  public int compileFlagsFind_jdk_dynamic() {
    return countFinds(Pattern.compile(fresh(COMPILE_REGEX), Pattern.CASE_INSENSITIVE));
  }

  @Benchmark
  public int compileFlagsFind_ops_dynamic() {
    return countFinds(RegexOps.compile(fresh(COMPILE_REGEX), Pattern.CASE_INSENSITIVE));
  }

  private int countFinds(Pattern p) {
    Matcher m = p.matcher(subject);
    int n = 0;
    while (m.find()) {
      n++;
    }
    return n;
  }

  @Benchmark
  public boolean patternMatches_jdk_literal() {
    return Pattern.matches(MATCHES_REGEX, subject);
  }

  @Benchmark
  public boolean patternMatches_ops_literal() {
    return RegexOps.patternMatches(MATCHES_REGEX, subject);
  }

  @Benchmark
  public boolean patternMatches_jdk_dynamic() {
    return Pattern.matches(fresh(MATCHES_REGEX), subject);
  }

  @Benchmark
  public boolean patternMatches_ops_dynamic() {
    return RegexOps.patternMatches(fresh(MATCHES_REGEX), subject);
  }

  @Benchmark
  public boolean matches_jdk_literal() {
    return subject.matches(MATCHES_REGEX);
  }

  @Benchmark
  public boolean matches_ops_literal() {
    return RegexOps.matches(subject, MATCHES_REGEX);
  }

  @Benchmark
  public boolean matches_jdk_dynamic() {
    return subject.matches(fresh(MATCHES_REGEX));
  }

  @Benchmark
  public boolean matches_ops_dynamic() {
    return RegexOps.matches(subject, fresh(MATCHES_REGEX));
  }

  @Benchmark
  public String replaceAll_jdk_literal() {
    return subject.replaceAll(REPLACE_ALL_REGEX, "");
  }

  @Benchmark
  public String replaceAll_ops_literal() {
    return RegexOps.replaceAll(subject, REPLACE_ALL_REGEX, "");
  }

  @Benchmark
  public String replaceAll_jdk_dynamic() {
    return subject.replaceAll(fresh(REPLACE_ALL_REGEX), "");
  }

  @Benchmark
  public String replaceAll_ops_dynamic() {
    return RegexOps.replaceAll(subject, fresh(REPLACE_ALL_REGEX), "");
  }

  @Benchmark
  public String replaceFirst_jdk_literal() {
    return subject.replaceFirst(REPLACE_FIRST_REGEX, REPLACE_FIRST_REPLACEMENT);
  }

  @Benchmark
  public String replaceFirst_ops_literal() {
    return RegexOps.replaceFirst(subject, REPLACE_FIRST_REGEX, REPLACE_FIRST_REPLACEMENT);
  }

  @Benchmark
  public String replaceFirst_jdk_dynamic() {
    return subject.replaceFirst(fresh(REPLACE_FIRST_REGEX), REPLACE_FIRST_REPLACEMENT);
  }

  @Benchmark
  public String replaceFirst_ops_dynamic() {
    return RegexOps.replaceFirst(subject, fresh(REPLACE_FIRST_REGEX), REPLACE_FIRST_REPLACEMENT);
  }

  @Benchmark
  public String[] split_jdk_literal() {
    return subject.split(SPLIT_REGEX);
  }

  @Benchmark
  public String[] split_ops_literal() {
    return RegexOps.split(subject, SPLIT_REGEX);
  }

  @Benchmark
  public String[] split_jdk_dynamic() {
    return subject.split(fresh(SPLIT_REGEX));
  }

  @Benchmark
  public String[] split_ops_dynamic() {
    return RegexOps.split(subject, fresh(SPLIT_REGEX));
  }

  @Benchmark
  public String[] splitLimit_jdk_literal() {
    return subject.split(SPLIT_REGEX, 4);
  }

  @Benchmark
  public String[] splitLimit_ops_literal() {
    return RegexOps.split(subject, SPLIT_REGEX, 4);
  }

  @Benchmark
  public String[] splitLimit_jdk_dynamic() {
    return subject.split(fresh(SPLIT_REGEX), 4);
  }

  @Benchmark
  public String[] splitLimit_ops_dynamic() {
    return RegexOps.split(subject, fresh(SPLIT_REGEX), 4);
  }

  @Benchmark
  public String[] splitFastpath_jdk_literal() {
    return subject.split(SPLIT_FASTPATH_REGEX);
  }

  @Benchmark
  public String[] splitFastpath_ops_literal() {
    return RegexOps.split(subject, SPLIT_FASTPATH_REGEX);
  }
}

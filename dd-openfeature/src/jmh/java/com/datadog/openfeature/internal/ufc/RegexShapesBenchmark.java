package com.datadog.openfeature.internal.ufc;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import de.thetaphi.forbiddenapis.SuppressForbidden;
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

/**
 * PROTOTYPE: {@code java.util.regex} {@code find()} (master), {@link RegexMatcher} (#13487) and
 * {@link PrototypeRegex}, on common MATCHES pattern shapes: the literal tiers #13487 already has,
 * multi-segment globs, and literal + class runs.
 */
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Fork(1)
public class RegexShapesBenchmark {
  @Param({
    "suffix",
    "contains",
    "unescaped-domain",
    "prefix-suffix",
    "two-inner",
    "case-insensitive",
    "email-class",
    "id-class",
    "digits",
    "version"
  })
  public String shape;

  /** A matching input, a short input that does not match, or a 2,000-character miss. */
  @Param({"hit", "miss", "long-miss"})
  public String input;

  private Pattern jdk;
  private RegexMatcher regexMatcher;
  private PrototypeRegex prototype;
  private String value;

  @Setup
  @SuppressForbidden
  public void setup() {
    final String pattern;
    final String hit;
    final String miss;
    switch (shape) {
      case "suffix":
        pattern = "@datadoghq\\.com$";
        hit = "jane.doe@datadoghq.com";
        miss = "jane.doe@example.com";
        break;
      case "contains":
        pattern = "my-great-app";
        hit = "svc-my-great-app-01";
        miss = "svc-my-other-app-01";
        break;
      case "unescaped-domain":
        pattern = "@mycompany.com";
        hit = "bob.smith@mycompany.com";
        miss = "bob.smith@othercompany.com";
        break;
      case "prefix-suffix":
        pattern = "^api-.*-prod$";
        hit = "api-checkout-prod";
        miss = "api-checkout-staging";
        break;
      case "two-inner":
        pattern = "checkout.*v2";
        hit = "svc-checkout-service-v2";
        miss = "svc-checkout-service-v3";
        break;
      case "case-insensitive":
        pattern = "(?i)^admin.*panel";
        hit = "ADMIN-control-Panel";
        miss = "user-admin-panel";
        break;
      case "email-class":
        pattern = "^[a-zA-Z0-9._%+-]+@site\\.test$";
        hit = "jane.doe+x@site.test";
        miss = "jane.doe+x@other.test";
        break;
      case "id-class":
        pattern = "^org-0000-[a-z]+-[0-9]{2,6}$";
        hit = "org-0000-acme-12345";
        miss = "org-0000-acme-x12345";
        break;
      case "digits":
        pattern = "^\\d+$";
        hit = "1234567890";
        miss = "12345a7890";
        break;
      case "version":
        pattern = "^v[0-9]+\\.[0-9]+\\.[0-9]+$";
        hit = "v1.20.3";
        miss = "v1.20";
        break;
      default:
        throw new IllegalArgumentException(shape);
    }
    jdk = Pattern.compile(pattern);
    regexMatcher = RegexMatcher.compile(pattern);
    prototype = PrototypeRegex.compile(pattern);
    switch (input) {
      case "hit":
        value = hit;
        break;
      case "miss":
        value = miss;
        break;
      default:
        final StringBuilder s = new StringBuilder(2_000 + miss.length());
        for (int i = 0; i < 2_000; i++) {
          s.append('a');
        }
        value = s.append(miss).toString();
        break;
    }
    if (jdk.matcher(value).find() != regexMatcher.matches(value)
        || regexMatcher.matches(value) != prototype.matches(value)) {
      throw new IllegalStateException("engines disagree on " + shape + "/" + input);
    }
    System.out.println(
        "["
            + shape
            + "] "
            + pattern
            + ": RegexMatcher "
            + regexMatcher.kind()
            + ", prototype "
            + prototype.tier());
  }

  @Benchmark
  public boolean javaUtilRegex() {
    return jdk.matcher(value).find();
  }

  @Benchmark
  public boolean regexMatcher() {
    return regexMatcher.matches(value);
  }

  @Benchmark
  public boolean prototype() {
    return prototype.matches(value);
  }
}

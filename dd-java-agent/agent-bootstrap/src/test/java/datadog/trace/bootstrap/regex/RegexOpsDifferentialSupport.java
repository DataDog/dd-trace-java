package datadog.trace.bootstrap.regex;

import static org.junit.jupiter.api.Assertions.fail;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressForbidden
final class RegexOpsDifferentialSupport {

  private RegexOpsDifferentialSupport() {}

  static final String[] PATTERN_PROBES = {
    "",
    "a",
    "A",
    "ab",
    "aB",
    "abc",
    "a,b,,c,",
    "foo bar\nbaz",
    "line1\r\nline2\n",
    "x.y|z",
    "AaBb123",
    "\u00e9\u00c9",
    "\uD83D\uDE00x\uD83D\uDE00",
    "  # comment",
    "\u0130i\u0131I",
    "a\u030a\u00e5",
    "aaa",
    "abab",
    "$1\\",
  };

  private static final boolean[] SCHEDULE = {true, true, false, true, true, false, false, true};

  @FunctionalInterface
  interface Op {
    Object apply(String regex) throws Throwable;
  }

  static final class Outcome {
    final Object value;
    final Class<?> errorClass;
    final String errorMessage;

    private Outcome(Object value, Class<?> errorClass, String errorMessage) {
      this.value = value;
      this.errorClass = errorClass;
      this.errorMessage = errorMessage;
    }

    static Outcome of(Op op, String regex) {
      try {
        return new Outcome(normalize(op.apply(regex)), null, null);
      } catch (Throwable t) {
        return new Outcome(null, t.getClass(), t.getMessage());
      }
    }

    boolean sameIgnoringMessage(Outcome o) {
      return Objects.equals(value, o.value) && Objects.equals(errorClass, o.errorClass);
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof Outcome)) {
        return false;
      }
      Outcome o = (Outcome) obj;
      return sameIgnoringMessage(o)
          && (errorClass == NullPointerException.class
              || Objects.equals(errorMessage, o.errorMessage));
    }

    @Override
    public int hashCode() {
      return Objects.hash(value, errorClass, errorMessage);
    }

    @Override
    public String toString() {
      if (errorClass != null) {
        return "threw " + errorClass.getName() + ": " + errorMessage;
      }
      return "returned " + value;
    }
  }

  static Object normalize(Object result) {
    if (result instanceof String[]) {
      return Arrays.asList((String[]) result);
    }
    if (result instanceof Pattern) {
      return describe((Pattern) result);
    }
    return result;
  }

  static String describe(Pattern p) {
    StringBuilder sb = new StringBuilder();
    sb.append("Pattern[").append(p.pattern()).append("|flags=").append(p.flags());
    sb.append("|toString=").append(p.toString());
    for (String probe : PATTERN_PROBES) {
      sb.append("|<").append(probe).append(">:");
      Matcher m = p.matcher(probe);
      sb.append(m.matches() ? 'M' : 'm');
      m.reset();
      sb.append(m.lookingAt() ? 'L' : 'l');
      m.reset();
      int found = 0;
      while (m.find() && found++ < 50) {
        sb.append('[').append(m.start()).append(',').append(m.end());
        for (int g = 1; g <= m.groupCount(); g++) {
          sb.append(',').append(m.start(g)).append('-').append(m.end(g));
        }
        sb.append(']');
      }
    }
    return sb.append(']').toString();
  }

  @SuppressWarnings("StringOperationCanBeSimplified")
  static String copy(String regex) {
    return regex == null ? null : new String(regex);
  }

  static final class Divergences {
    private final List<String> failures = new ArrayList<>();
    private int comparisons;

    void check(String label, String regex, Op jdk, Op ops) {
      check(label, regex, jdk, ops, true);
    }

    void check(String label, String regex, Op jdk, Op ops, boolean compareMessage) {
      Outcome expected = Outcome.of(jdk, regex);
      for (int i = 0; i < SCHEDULE.length; i++) {
        String arg = SCHEDULE[i] ? regex : copy(regex);
        Outcome actual = Outcome.of(ops, arg);
        comparisons++;
        boolean same =
            compareMessage ? expected.equals(actual) : expected.sameIgnoringMessage(actual);
        if (!same) {
          record(label, regex, i, SCHEDULE[i] ? "same-ref" : "copy", expected, actual);
        }
      }
    }

    void compare(String label, String regex, Outcome expected, Outcome actual, String mode) {
      comparisons++;
      if (!expected.equals(actual)) {
        record(label, regex, -1, mode, expected, actual);
      }
    }

    private void record(
        String label, String regex, int call, String mode, Outcome expected, Outcome actual) {
      if (failures.size() < 200) {
        failures.add(
            label
                + " regex="
                + quote(regex)
                + " call#"
                + call
                + " ("
                + mode
                + "): JDK "
                + expected
                + " but RegexOps "
                + actual);
      } else if (failures.size() == 200) {
        failures.add("... (more divergences truncated)");
      }
    }

    int comparisons() {
      return comparisons;
    }

    void assertNone() {
      if (!failures.isEmpty()) {
        fail(
            failures.size()
                + " divergence(s) out of "
                + comparisons
                + " comparisons:\n  "
                + String.join("\n  ", failures));
      }
    }
  }

  static String quote(String s) {
    if (s == null) {
      return "null";
    }
    StringBuilder sb = new StringBuilder("\"");
    int max = Math.min(s.length(), 80);
    for (int i = 0; i < max; i++) {
      char c = s.charAt(i);
      if (c < 0x20 || c > 0x7e) {
        sb.append(String.format("\\u%04x", (int) c));
      } else {
        sb.append(c);
      }
    }
    if (s.length() > max) {
      sb.append("...(len=").append(s.length()).append(')');
    }
    return sb.append('"').toString();
  }

  static void checkAllOps(
      Divergences d, String regex, String input, String[] replacements, int[] limits) {
    String in = quote(input);
    d.check("compile in=" + in, regex, Pattern::compile, RegexOps::compile);
    d.check(
        "patternMatches in=" + in,
        regex,
        r -> Pattern.matches(r, input),
        r -> RegexOps.patternMatches(r, input));
    d.check(
        "patternMatches(StringBuilder) in=" + in,
        regex,
        r -> Pattern.matches(r, input == null ? null : new StringBuilder(input)),
        r -> RegexOps.patternMatches(r, input == null ? null : new StringBuilder(input)));
    boolean msg = input != null;
    d.check("matches in=" + in, regex, r -> input.matches(r), r -> RegexOps.matches(input, r), msg);
    d.check("split in=" + in, regex, r -> input.split(r), r -> RegexOps.split(input, r), msg);
    for (int limit : limits) {
      d.check(
          "split limit=" + limit + " in=" + in,
          regex,
          r -> input.split(r, limit),
          r -> RegexOps.split(input, r, limit),
          msg);
    }
    for (String rep : replacements) {
      String rq = " rep=" + quote(rep) + " in=" + in;
      d.check(
          "replaceAll" + rq,
          regex,
          r -> input.replaceAll(r, rep),
          r -> RegexOps.replaceAll(input, r, rep),
          msg);
      d.check(
          "replaceFirst" + rq,
          regex,
          r -> input.replaceFirst(r, rep),
          r -> RegexOps.replaceFirst(input, r, rep),
          msg);
    }
  }

  static void checkCompileWithFlags(Divergences d, String regex, int flags) {
    d.check(
        "compile flags=0x" + Integer.toHexString(flags),
        regex,
        r -> Pattern.compile(r, flags),
        r -> RegexOps.compile(r, flags));
  }
}

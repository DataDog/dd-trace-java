package datadog.trace.core.util;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class StackTraces {
  private StackTraces() {}

  /**
   * Safely retrieves the message from a throwable.
   *
   * <p>Third-party exception classes occasionally use formatting utilities (e.g. {@code
   * java.text.MessageFormat}) inside {@code getMessage()}, which can throw when the pattern
   * contains non-integer placeholders.
   *
   * @param t the throwable to retrieve the message from
   * @return {@code null} if {@code t} is {@code null}; the result of {@link Throwable#getMessage()}
   *     on success; or a diagnostic string of the form {@code "(Exception message unavailable for
   *     ClassName: getMessage() threw ExceptionType)"} if {@code getMessage()} throws
   */
  public static String safeGetMessage(Throwable t) {
    if (t == null) {
      return null;
    }
    try {
      return t.getMessage();
    } catch (Exception e) {
      return "(Exception message unavailable for "
          + t.getClass().getSimpleName()
          + ": getMessage() threw "
          + e.getClass().getSimpleName()
          + ")";
    }
  }

  /**
   * Returns the stack trace of {@code t} as a string, truncated to {@code maxChars} characters.
   *
   * <p>Uses {@link Throwable#printStackTrace(java.io.PrintWriter)} to produce the full trace
   * including {@code Caused by} and {@code Suppressed} chains. If {@code printStackTrace} itself
   * throws (e.g. because {@link Throwable#getMessage()} throws inside {@code toString()}), falls
   * back to reconstructing the trace from {@link Throwable#getStackTrace()} so the call site
   * remains locatable.
   *
   * @param t the throwable to format
   * @param maxChars maximum length of the returned string
   * @return the stack trace string, truncated if necessary
   */
  public static String getStackTrace(Throwable t, int maxChars) {
    String trace;
    try {
      StringWriter sw = new StringWriter();
      t.printStackTrace(new PrintWriter(sw));
      trace = sw.toString();
    } catch (Exception ignored) {
      // printStackTrace() failed (e.g. getMessage() throws inside toString()).
      // Reconstruct from getStackTrace() so the call site is still locatable.
      try {
        trace =
            t.getClass().getName()
                + System.lineSeparator()
                + Arrays.stream(t.getStackTrace())
                    .map(f -> "\tat " + f)
                    .collect(Collectors.joining(System.lineSeparator()));
      } catch (Exception ignored2) {
        trace = t.getClass().getName();
      }
    }
    try {
      return truncate(trace, maxChars);
    } catch (Exception e) {
      // If something goes wrong, return the original trace
      return trace;
    }
  }

  static String truncate(String trace, int maxChars) {
    if (trace.length() <= maxChars) {
      return trace;
    }

    trace = abbreviatePackageNames(trace);
    if (trace.length() <= maxChars) {
      return trace;
    }

    trace = removeStackTraceMiddleForEachException(trace);
    if (trace.length() <= maxChars) {
      return trace;
    }

    /* last-ditch centre cut to guarantee the limit */
    String cutMessage = "\t... trace centre-cut to " + maxChars + " chars ...";
    int retainedLength = maxChars - cutMessage.length() - 2; // 2 for the newlines
    if (retainedLength <= 0) {
      return cutMessage + System.lineSeparator();
    }
    int half = retainedLength / 2;
    return trace.substring(0, half)
        + System.lineSeparator()
        + cutMessage
        + System.lineSeparator()
        + trace.substring(trace.length() - (retainedLength - half));
  }

  private static final Pattern FRAME = Pattern.compile("^\\s*at ([^(]+)(\\(.*)$");

  private static String abbreviatePackageNames(String trace) {
    StringBuilder sb = new StringBuilder(trace.length());
    new BufferedReader(new StringReader(trace))
        .lines()
        .forEach(
            line -> {
              Matcher m = FRAME.matcher(line);
              if (m.matches()) {
                sb.append("\tat ").append(abbreviatePackageName(m.group(1))).append(m.group(2));
              } else {
                sb.append(line);
              }
              sb.append(System.lineSeparator());
            });
    return sb.toString();
  }

  /**
   * Abbreviates only the package part of a fully qualified class name with member. For example,
   * "com.myorg.MyClass.myMethod" to "c.m.MyClass.myMethod". If there is no package (e.g.
   * "MyClass.myMethod"), returns the input unchanged.
   */
  private static String abbreviatePackageName(String fqcnWithMember) {
    int lastDot = fqcnWithMember.lastIndexOf('.');
    if (lastDot < 0) {
      return fqcnWithMember;
    }
    int preClassDot = fqcnWithMember.lastIndexOf('.', lastDot - 1);
    if (preClassDot < 0) {
      return fqcnWithMember;
    }
    String packagePart = fqcnWithMember.substring(0, preClassDot);
    String classAndAfter = fqcnWithMember.substring(preClassDot + 1);

    StringBuilder sb = new StringBuilder(fqcnWithMember.length());
    int segmentStart = 0;
    for (int i = 0; i <= packagePart.length(); i++) {
      if (i == packagePart.length() || packagePart.charAt(i) == '.') {
        sb.append(packagePart.charAt(segmentStart)).append('.');
        segmentStart = i + 1;
      }
    }
    sb.append(classAndAfter);
    return sb.toString();
  }

  private static final int HEAD_LINES = 8, TAIL_LINES = 4;

  /**
   * Removes lines from the middle of each exception stack trace, leaving {@link
   * StackTraces#HEAD_LINES} lines at the beginning and {@link StackTraces#TAIL_LINES} lines at the
   * end
   */
  private static String removeStackTraceMiddleForEachException(String trace) {
    List<String> lines =
        new BufferedReader(new StringReader(trace)).lines().collect(Collectors.toList());
    List<String> out = new ArrayList<>(lines.size());
    int i = 0;
    while (i < lines.size()) {
      out.add(lines.get(i++)); // "Exception ..." / "Caused by: ..."
      int start = i;
      while (i < lines.size() && lines.get(i).startsWith("\tat")) {
        i++;
      }

      int total = i - start;

      int keepHead = Math.min(HEAD_LINES, total);
      for (int j = 0; j < keepHead; j++) {
        out.add(lines.get(start + j));
      }

      int keepTail = Math.min(TAIL_LINES, total - keepHead);
      int skipped = total - keepHead - keepTail;
      if (skipped > 0) {
        out.add("\t... " + skipped + " trimmed ...");
      }

      for (int j = total - keepTail; j < total; j++) {
        out.add(lines.get(start + j));
      }

      // "... n more" continuation markers
      if (i < lines.size() && lines.get(i).startsWith("\t...")) {
        out.add(lines.get(i++));
      }
    }
    return String.join(System.lineSeparator(), out) + System.lineSeparator();
  }
}

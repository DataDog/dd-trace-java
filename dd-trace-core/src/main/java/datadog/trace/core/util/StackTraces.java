package datadog.trace.core.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class StackTraces {
  private StackTraces() {}

  public static String getStackTrace(Throwable t, int maxChars) {
    StringWriter sw = new StringWriter();
    t.printStackTrace(new PrintWriter(sw));
    return truncate(sw.toString(), maxChars);
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
    try (BufferedReader reader = new BufferedReader(new StringReader(trace))) {
      String line;
      while ((line = reader.readLine()) != null) {
        Matcher m = FRAME.matcher(line);
        if (m.matches()) {
          sb.append("\tat ").append(abbreviatePackageName(m.group(1))).append(m.group(2));
        } else {
          sb.append(line);
        }
        sb.append(System.lineSeparator());
      }
      trace = sb.toString();
      return trace;

    } catch (IOException ignored) {
      // This should never happen since we are reading from a StringReader
      return trace;
    }
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

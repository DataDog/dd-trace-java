package datadog.crashtracking.parsers;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utilities for redacting potentially sensitive data from JVM crash log register-to-memory mapping
 * entries.
 */
public final class RedactUtils {

  static final String REDACTED = "redacted";
  private static final String REDACTED_STRING = "REDACTED";

  private static final String[] KNOWN_PACKAGES_PREFIXES = {
    // Java SE / JDK internals
    "java/",
    "jdk/",
    "sun/",
    "javax/",
    // Jakarta EE (successor to javax)
    "jakarta/",
    // Oracle/Sun vendor packages
    "com/sun/",
    "com/oracle/",
  };

  // " - string: "value"" in String oop dumps
  private static final Pattern STRING_CONTENT = Pattern.compile("(\\s*- string: )\"[^\"]*\"");

  // Type descriptors like Lcom/company/Type;
  private static final Pattern TYPE_DESCRIPTOR = Pattern.compile("L([A-Za-z$_][A-Za-z0-9$_/]*);");

  // klass/interface references: - klass: 'com/company/Class'
  private static final Pattern KLASS_REF = Pattern.compile("((?:klass|interface): ')([^']+)(')");

  // 'in 'class'' clause in {method} descriptor entries
  private static final Pattern METHOD_IN_CLASS = Pattern.compile("( in ')([^']+)(')");

  // Library path in two formats produced by os::print_location():
  //   <offset 0x...> in /path/to/lib.so at 0x...       (no dladdr symbol)
  //   symbol+offset in /path/to/lib.so at 0x...         (dladdr resolved a symbol name)
  private static final Pattern LIBRARY_PATH =
      Pattern.compile("((?:<[^>]+>|\\S+\\+\\S+)\\s+in\\s+)(/\\S+)");

  // Dotted class name followed by an OOP reference: "com.company.Type"{0x...}
  // This specifically identifies the inline string value of a java.lang.Class 'name' field
  private static final Pattern DOTTED_CLASS_OOP_REF =
      Pattern.compile(
          "\"([A-Za-z][A-Za-z0-9$]*(?:\\.[A-Za-z][A-Za-z0-9$]*)*)\"(\\{0x[0-9a-fA-F]+\\})");

  // is an oop: com.company.Class
  private static final Pattern IS_AN_OOP =
      Pattern.compile("(is an oop: )([A-Za-z][A-Za-z0-9$]*(?:\\.[A-Za-z][A-Za-z0-9$]*)*)");

  // Hex-dump bytes in "points into unknown readable memory:" lines.
  // Two formats produced by os::print_location():
  //   "memory: 0x<addr> | ff ff ff ff ..."  (Linux/macOS amd64 — address + pipe + bytes)
  //   "memory: ff ff ff ff ..."              (Linux aarch64    — bytes only)
  // The address (when present) is kept; only the raw bytes are redacted.
  private static final Pattern READABLE_MEMORY_HEX_DUMP =
      Pattern.compile(
          "(points into unknown readable memory: (?:0x[0-9a-fA-F]+ \\| )?)([0-9a-fA-F]{2}(?: [0-9a-fA-F]{2})*)");

  private RedactUtils() {}

  /**
   * Main entry point: redact sensitive data from a register-to-memory mapping value (possibly
   * multiline).
   */
  public static String redactRegisterToMemoryMapping(String value) {
    if (value == null || value.isEmpty()) return value;
    String[] lines = value.split("\n", -1);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < lines.length; i++) {
      if (i > 0) sb.append('\n');
      sb.append(redactLine(lines[i]));
    }
    return sb.toString();
  }

  private static String redactLine(String line) {
    line = redactStringTypeValue(line);
    line = redactTypeDescriptors(line);
    line = redactKlassReference(line);
    line = redactMethodClass(line);
    line = redactLibraryPath(line);
    line = redactDottedClassOopRef(line);
    line = redactOopClassName(line);
    line = redactReadableMemoryHexDump(line);
    return line;
  }

  /**
   * Redacts string content in String oop dump lines: <code> - string: "Some string"</code> to
   * <code> - string: "REDACTED"</code>
   */
  static String redactStringTypeValue(String line) {
    return STRING_CONTENT.matcher(line).replaceAll("$1\"" + REDACTED_STRING + "\"");
  }

  /**
   * Redacts the package of type descriptors in a line: <code>Lcom/company/Type;</code> to <code>
   * Lredacted/redacted/Type;</code>
   */
  static String redactTypeDescriptors(String line) {
    return replaceAll(TYPE_DESCRIPTOR, line, m -> "L" + redactJvmClassName(m.group(1)) + ";");
  }

  /**
   * Redacts klass/interface references in a line: <code>klass: 'com/company/Class'</code> to <code>
   * klass: 'redacted/redacted/Class'</code>
   */
  static String redactKlassReference(String line) {
    return replaceAll(
        KLASS_REF, line, m -> m.group(1) + redactJvmClassName(m.group(2)) + m.group(3));
  }

  /**
   * Redacts the class in a method descriptor's {@code in 'class'} clause: <code>
   * in 'com/company/Class'</code> to <code>in 'redacted/redacted/Class'</code>
   */
  static String redactMethodClass(String line) {
    return replaceAll(
        METHOD_IN_CLASS, line, m -> m.group(1) + redactJvmClassName(m.group(2)) + m.group(3));
  }

  /**
   * Redacts all but the parent directory and filename from a library path. Handles both
   * <code>&lt;offset 0x...&gt; in /path/to/dir/lib.so</code> and <code>symbol+0 in
   * /path/to/dir/lib.so</code> to <code>... in /redacted/redacted/dir/lib.so</code>
   */
  static String redactLibraryPath(String line) {
    return replaceAll(LIBRARY_PATH, line, m -> m.group(1) + redactPath(m.group(2)));
  }

  /**
   * Redacts dotted class names that appear as inline field values followed by an OOP reference:
   * <code>"com.company.SomeType"{0x...}</code> to <code>"redacted.redacted.SomeType"{0x...}</code>
   */
  static String redactDottedClassOopRef(String line) {
    return replaceAll(
        DOTTED_CLASS_OOP_REF,
        line,
        m -> "\"" + redactDottedClassName(m.group(1)) + "\"" + m.group(2));
  }

  /**
   * Redacts the class name in {@code is an oop: ClassName}: <code>is an oop: com.company.Class
   * </code> to <code>is an oop: redacted.redacted.Class</code>
   */
  static String redactOopClassName(String line) {
    return replaceAll(IS_AN_OOP, line, m -> m.group(1) + redactDottedClassName(m.group(2)));
  }

  /**
   * Redacts hex-dump bytes in <code>points into unknown readable memory:</code> lines, keeping the
   * optional leading address. Handles two formats:
   *
   * <ul>
   *   <li><code>memory: 0x&lt;addr&gt; | ff ff ff ff</code> to <code>memory: 0x&lt;addr&gt; |
   *       REDACTED</code>
   *   <li><code>memory: ff ff ff ff</code> to <code>memory: REDACTED</code>
   * </ul>
   */
  static String redactReadableMemoryHexDump(String line) {
    return replaceAll(READABLE_MEMORY_HEX_DUMP, line, m -> m.group(1) + REDACTED_STRING);
  }

  /**
   * Redacts the package of a slash-separated JVM class name, unless it belongs to a known package.
   * <code>com/company/SomeType</code> to <code>redacted/redacted/SomeType</code>; <code>
   * java/lang/String</code> unchanged.
   */
  static String redactJvmClassName(String className) {
    if (isKnownJvmPackage(className)) {
      return className;
    }
    return redactClassName('/', className);
  }

  /**
   * Redacts the package of a dot-separated class name, unless it belongs to a known package. <code>
   * com.company.SomeType</code> to <code>redacted.redacted.SomeType</code>; <code>java.lang.String
   * </code> unchanged.
   */
  static String redactDottedClassName(String className) {
    if (isKnownJvmPackage(className.replace('.', '/'))) {
      return className;
    }
    return redactClassName('.', className);
  }

  private static String redactClassName(char sep, String className) {
    int lastSep = className.lastIndexOf(sep);
    if (lastSep < 0) return className;
    StringBuilder sb = new StringBuilder();
    int pos = 0;
    while (pos <= lastSep) {
      int next = className.indexOf(sep, pos);
      if (sb.length() > 0) sb.append(sep);
      sb.append(REDACTED);
      pos = next + 1;
    }
    return sb.append(sep).append(className, lastSep + 1, className.length()).toString();
  }

  /**
   * Redacts all path segments except the parent directory and filename. <code>/path/to/dir/lib.so
   * </code> to <code>/redacted/redacted/dir/lib.so</code>
   */
  static String redactPath(String path) {
    String[] parts = path.split("/", -1);
    // parts[0] is always "" (before the leading slash)
    if (parts.length <= 3) {
      return path; // /dir/file or shorter: nothing to redact
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 1; i < parts.length - 2; i++) {
      sb.append('/').append(REDACTED);
    }
    return sb.append('/')
        .append(parts[parts.length - 2])
        .append('/')
        .append(parts[parts.length - 1])
        .toString();
  }

  private static boolean isKnownJvmPackage(String slashClassName) {
    for (String prefix : KNOWN_PACKAGES_PREFIXES) {
      if (slashClassName.startsWith(prefix)) {
        return true;
      }
    }
    return slashClassName.contains("datadog") || slashClassName.startsWith("com/dd/");
  }

  private static String replaceAll(
      Pattern pattern, String input, Function<Matcher, String> replacement) {
    Matcher m = pattern.matcher(input);
    if (!m.find()) {
      return input;
    }
    StringBuilder sb = new StringBuilder();
    int lastEnd = 0;
    do {
      sb.append(input, lastEnd, m.start());
      sb.append(replacement.apply(m));
      lastEnd = m.end();
    } while (m.find());
    return sb.append(input, lastEnd, input.length()).toString();
  }
}

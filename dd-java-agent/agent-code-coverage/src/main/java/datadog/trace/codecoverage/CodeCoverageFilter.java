package datadog.trace.codecoverage;

import java.util.function.Predicate;

/**
 * Determines whether a class should be instrumented for production code coverage based on
 * include/exclude patterns.
 */
public final class CodeCoverageFilter implements Predicate<String> {

  private final String[] includePrefixes;
  private final String[] excludePrefixes;
  private final boolean includeAll;

  /**
   * @param includes include patterns (e.g. {@code ["com.example.*", "*"]}). A single {@code "*"}
   *     means include everything.
   * @param excludes exclude patterns (e.g. {@code ["com.example.internal.*"]})
   */
  public CodeCoverageFilter(String[] includes, String[] excludes) {
    this.includeAll = includes.length == 1 && "*".equals(includes[0]);
    this.includePrefixes = toVmPrefixes(includes);
    this.excludePrefixes = toVmPrefixes(excludes);
  }

  /**
   * @param className class name in VM format (e.g. {@code "com/example/MyClass"})
   * @return {@code true} if the class should be instrumented
   */
  @Override
  public boolean test(String className) {
    // Always reject agent internals
    if (className.startsWith("datadog/")) {
      return false;
    }

    // Check excludes first
    for (String excludePrefix : excludePrefixes) {
      if (className.startsWith(excludePrefix)) {
        return false;
      }
    }

    if (includeAll) {
      return true;
    }

    // Check includes
    for (String includePrefix : includePrefixes) {
      if (className.startsWith(includePrefix)) {
        return true;
      }
    }

    return false;
  }

  /**
   * Converts dot-separated patterns like {@code "com.example.*"} to VM-format prefixes like {@code
   * "com/example/"}.
   */
  private static String[] toVmPrefixes(String[] patterns) {
    if (patterns == null || patterns.length == 0) {
      return new String[0];
    }
    String[] prefixes = new String[patterns.length];
    for (int i = 0; i < patterns.length; i++) {
      String pattern = patterns[i].trim();
      if ("*".equals(pattern)) {
        prefixes[i] = "";
        continue;
      }
      // Strip trailing wildcard
      if (pattern.endsWith(".*") || pattern.endsWith("/*")) {
        pattern = pattern.substring(0, pattern.length() - 1);
      } else if (pattern.endsWith("*")) {
        pattern = pattern.substring(0, pattern.length() - 1);
      }
      // Convert dots to slashes
      prefixes[i] = pattern.replace('.', '/');
    }
    return prefixes;
  }
}

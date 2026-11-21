package datadog.trace.api.appsec.route;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** RFC-1125 parameter names: sanitization and de-duplication. */
final class RouteNames {

  /** Name prefix of unnamed parameters: {@code param1}, {@code param2}, ... */
  static final String UNNAMED_PREFIX = "param";

  /** Separator of the {@code _N} suffix given to colliding sanitized names. */
  static final String DEDUP_SEPARATOR = "_";

  private RouteNames() {}

  private static boolean isNameChar(int c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
  }

  /** Replaces each code point outside {@code [A-Za-z0-9_]} with {@code _}. */
  static String sanitizeName(String name) {
    int len = name.length();
    int i = 0;
    while (i < len && isNameChar(name.charAt(i))) {
      i++;
    }
    if (i == len) {
      return name;
    }
    StringBuilder sb = new StringBuilder(len);
    sb.append(name, 0, i);
    while (i < len) {
      int cp = name.codePointAt(i);
      sb.append(isNameChar(cp) ? (char) cp : '_');
      i += Character.charCount(cp);
    }
    return sb.toString();
  }

  /**
   * Resolves names in path order: valid names are kept; sanitized names that collide get the
   * smallest unused {@code _N} (N >= 2); unnamed params get {@code param<N>} skipping used names.
   */
  static String[] resolveNames(List<RoutePart.Param> params) {
    int count = params.size();
    String[] names = new String[count];
    Set<String> used = new HashSet<>();
    for (int i = 0; i < count; i++) {
      String name = params.get(i).frameworkName();
      if (name != null && sanitizeName(name).equals(name)) {
        names[i] = name;
        used.add(name);
      }
    }
    for (int i = 0; i < count; i++) {
      String name = params.get(i).frameworkName();
      if (name != null && names[i] == null) {
        String base = sanitizeName(name);
        String prefix = base + DEDUP_SEPARATOR;
        names[i] = used.contains(base) ? prefix + firstUnused(prefix, 2, used) : base;
        used.add(names[i]);
      }
    }
    int next = 1;
    for (int i = 0; i < count; i++) {
      if (names[i] == null) {
        next = firstUnused(UNNAMED_PREFIX, next, used);
        names[i] = UNNAMED_PREFIX + next++;
        used.add(names[i]);
      }
    }
    return names;
  }

  /** Smallest {@code n >= from} such that {@code prefix + n} is not used. */
  private static int firstUnused(String prefix, int from, Set<String> used) {
    int n = from;
    while (used.contains(prefix + n)) {
      n++;
    }
    return n;
  }
}

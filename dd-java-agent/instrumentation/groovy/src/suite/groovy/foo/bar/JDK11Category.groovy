package foo.bar

import de.thetaphi.forbiddenapis.SuppressForbidden

@SuppressForbidden
class JDK11Category {

  static String repeat(String s, int count) {
    final StringBuilder builder = new StringBuilder()
    for (int i = 0; i < count; i++) {
      builder.append(s)
    }
    return builder.toString()
  }
}

package foo.bar

import de.thetaphi.forbiddenapis.SuppressForbidden

@SuppressForbidden
class GStringSuite {

  String format(final String left, final Object right) {
    return "$left $right"
  }
}

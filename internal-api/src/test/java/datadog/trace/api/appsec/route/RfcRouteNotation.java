package datadog.trace.api.appsec.route;

import static datadog.trace.api.appsec.route.RoutePart.catchAll;
import static datadog.trace.api.appsec.route.RoutePart.group;
import static datadog.trace.api.appsec.route.RoutePart.literal;
import static datadog.trace.api.appsec.route.RoutePart.optionalParam;
import static datadog.trace.api.appsec.route.RoutePart.param;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Test-only builder of {@link RouteTemplate}s from the RFC-1125 test-case notation: {@code {name}}
 * required, {@code {name?}} optional (takes the preceding separator with it), {@code {*name}}
 * catch-all, {@code **} unnamed catch-all, {@code *} unnamed parameter, {@code [...]} optional
 * static text, {@code (...)} optional group. Not a framework parser.
 */
final class RfcRouteNotation {

  /** Key used by {@link #presence(Set)} for unnamed parameters. */
  static final String UNNAMED = "anon";

  /** Suffix for a catch-all key that matched only {@code /}, e.g. {@code path=/}. */
  static final String ONLY_SLASH = "=/";

  private RfcRouteNotation() {}

  static RouteTemplate parse(String route) {
    RouteTemplate.Builder builder = RouteTemplate.builder();
    String body = route;
    if (body.endsWith("/")) {
      builder.trailingSlash(true);
      body = body.substring(0, body.length() - 1);
    }
    for (String segment : splitTopLevel(body)) {
      if (segment.isEmpty()) {
        continue;
      }
      if (segment.startsWith("{")
          && segment.endsWith("?}")
          && segment.indexOf('}') == segment.length() - 1) {
        String name = segment.substring(1, segment.length() - 2);
        builder.optionalSegment(name.startsWith("*") ? catchAll(name.substring(1)) : param(name));
        continue;
      }
      int split = separatorGroupStart(segment);
      String head = split < 0 ? segment : segment.substring(0, split);
      if (!head.isEmpty()) {
        builder.segment(parts(head).toArray(new RoutePart[0]));
      }
      int i = split;
      while (i >= 0 && i < segment.length()) {
        int end = matching(segment, i);
        builder.optionalSegment(parts(segment.substring(i + 2, end)).toArray(new RoutePart[0]));
        i = end + 1;
      }
    }
    return builder.build();
  }

  /**
   * Resolver from a set of present keys (param names, {@link #UNNAMED}, or group literal text). A
   * catch-all key followed by {@link #ONLY_SLASH} is {@link RoutePresence#ABSENT_TRAILING_SLASH}.
   */
  static PresenceResolver presence(final Set<String> present) {
    if (present == null) {
      return null;
    }
    return new PresenceResolver() {
      @Override
      public RoutePresence presenceOf(RoutePart.Param param) {
        String key = param.frameworkName() == null ? UNNAMED : param.frameworkName();
        if (present.contains(key)) {
          return RoutePresence.PRESENT;
        }
        return param.isCatchAll() && present.contains(key + ONLY_SLASH)
            ? RoutePresence.ABSENT_TRAILING_SLASH
            : RoutePresence.ABSENT;
      }

      @Override
      public RoutePresence presenceOf(RoutePart.Group group) {
        StringBuilder text = new StringBuilder();
        boolean hasParams = false;
        for (RoutePart part : group.parts()) {
          if (part instanceof RoutePart.Param) {
            hasParams = true;
            if (presenceOf((RoutePart.Param) part) != RoutePresence.PRESENT) {
              return RoutePresence.ABSENT;
            }
          } else if (part instanceof RoutePart.Literal) {
            text.append(((RoutePart.Literal) part).text());
          }
        }
        return hasParams || present.contains(text.toString())
            ? RoutePresence.PRESENT
            : RoutePresence.ABSENT;
      }
    };
  }

  private static List<String> splitTopLevel(String body) {
    List<String> out = new ArrayList<>();
    int depth = 0;
    int start = 0;
    for (int i = 0; i < body.length(); i++) {
      char c = body.charAt(i);
      if (c == '(' || c == '[' || c == '{') {
        depth++;
      } else if (c == ')' || c == ']' || c == '}') {
        depth--;
      } else if (c == '/' && depth == 0) {
        out.add(body.substring(start, i));
        start = i + 1;
      }
    }
    out.add(body.substring(start));
    return out;
  }

  private static int separatorGroupStart(String segment) {
    for (int i = 0; i + 1 < segment.length(); i++) {
      char c = segment.charAt(i);
      if ((c == '(' || c == '[') && segment.charAt(i + 1) == '/') {
        return i;
      }
    }
    return -1;
  }

  private static int matching(String text, int open) {
    int depth = 0;
    for (int i = open; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '(' || c == '[') {
        depth++;
      } else if ((c == ')' || c == ']') && --depth == 0) {
        return i;
      }
    }
    throw new IllegalArgumentException("unbalanced: " + text);
  }

  private static List<RoutePart> parts(String text) {
    List<RoutePart> parts = new ArrayList<>();
    StringBuilder lit = new StringBuilder();
    int i = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == '{') {
        int end = text.indexOf('}', i);
        String inner = text.substring(i + 1, end);
        boolean optional = inner.endsWith("?");
        if (optional) {
          inner = inner.substring(0, inner.length() - 1);
        }
        if (inner.startsWith("*")) {
          flush(lit, parts);
          parts.add(catchAll(inner.substring(1)));
        } else if (optional
            && lit.length() > 0
            && !Character.isLetterOrDigit(lit.charAt(lit.length() - 1))) {
          String sep = lit.substring(lit.length() - 1);
          lit.setLength(lit.length() - 1);
          flush(lit, parts);
          parts.add(group(literal(sep), param(inner)));
        } else {
          flush(lit, parts);
          parts.add(optional ? optionalParam(inner) : param(inner));
        }
        i = end + 1;
      } else if (c == '*') {
        flush(lit, parts);
        if (i + 1 < text.length() && text.charAt(i + 1) == '*') {
          parts.add(catchAll(null));
          i += 2;
        } else {
          parts.add(param(null));
          i++;
        }
      } else if (c == '(' || c == '[') {
        int end = matching(text, i);
        flush(lit, parts);
        parts.add(group(parts(text.substring(i + 1, end)).toArray(new RoutePart[0])));
        i = end + 1;
      } else {
        lit.append(c);
        i++;
      }
    }
    flush(lit, parts);
    return parts;
  }

  private static void flush(StringBuilder lit, List<RoutePart> parts) {
    if (lit.length() > 0) {
      parts.add(literal(lit.toString()));
      lit.setLength(0);
    }
  }
}

package datadog.trace.api.appsec.route;

import static datadog.trace.api.appsec.route.RoutePart.catchAll;
import static datadog.trace.api.appsec.route.RoutePart.literal;
import static datadog.trace.api.appsec.route.RoutePart.param;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.internal.VisibleForTesting;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a Vert.x web route path, exactly as declared by the app ({@code router.route("/a/:id")}),
 * into a {@link RouteTemplate}. Vert.x syntax:
 *
 * <ul>
 *   <li>{@code :name}: parameter; the name ends at the first char outside the {@link Grammar} name
 *       class, so a segment may hold several ({@code /:id.:format}, {@code /:a-:b}). A {@code :}
 *       not followed by a valid first char is static text;
 *   <li>a trailing {@code *}: unnamed catch-all, a whole segment after {@code /} ({@code /a/*}) or
 *       the end of the last segment ({@code /img*});
 *   <li>everything else: static text, percent-decoded (Vert.x matches the encoded request path).
 * </ul>
 *
 * A path with a {@code :} is compiled by Vert.x into a regex that only escapes {@code ( ) $ + .}:
 * there, any of {@code ? [ ] { } | ^ \} or a non-trailing {@code *} gives {@link
 * RouteTemplate#NOT_EXPRESSIBLE}. Without {@code :}, every char but the trailing {@code *} is
 * static. Empty segments are ignored; a trailing {@code /} is kept.
 */
public final class VertxRouteParser {

  private static final Logger log = LoggerFactory.getLogger(VertxRouteParser.class);

  /** Route paths are declared in code, so their cardinality is low; 256 bounds odd apps. */
  private static final int CACHE_CAPACITY = 256;

  /**
   * Parameter name grammar of the Vert.x version. Vert.x escapes {@code $} before it looks for
   * names, so {@code $} never ends up in a name, even in {@link #EXTENDED}.
   */
  public enum Grammar {
    /** Vert.x 3.x to 4.0.2: {@code [A-Za-z][A-Za-z0-9_]*}. */
    LEGACY,
    /** Vert.x 4.0.3+ and 5.x: {@code [A-Za-z0-9_]+}. */
    DEFAULT,
    /**
     * Vert.x 4.0.3+ with {@code -Dio.vertx.web.route.param.extended-pattern=true}: {@code
     * [A-Za-z_$][A-Za-z0-9_$-]*}.
     */
    EXTENDED;

    private final DDCache<String, RouteTemplate> cache = DDCaches.newFixedSizeCache(CACHE_CAPACITY);

    private final Function<String, RouteTemplate> safeParse =
        safely(path -> VertxRouteParser.parse(path, this));

    boolean isNameStart(char c) {
      if (isAsciiLetter(c)) {
        return true;
      }
      switch (this) {
        case DEFAULT:
          return isDigit(c) || c == '_';
        case EXTENDED:
          return c == '_';
        default:
          return false;
      }
    }

    boolean isNamePart(char c) {
      return isAsciiLetter(c) || isDigit(c) || c == '_' || (this == EXTENDED && c == '-');
    }

    private static boolean isAsciiLetter(char c) {
      return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static boolean isDigit(char c) {
      return c >= '0' && c <= '9';
    }
  }

  private VertxRouteParser() {}

  /**
   * Same as {@link #parse(String, Grammar)} but backed by a bounded cache per grammar, for the
   * request hot path. Never returns null or throws.
   */
  public static RouteTemplate cached(String declaredPath, Grammar grammar) {
    if (grammar == null) {
      return RouteTemplate.NOT_EXPRESSIBLE;
    }
    return cached(grammar.cache, declaredPath, grammar.safeParse);
  }

  @VisibleForTesting
  static RouteTemplate cached(
      DDCache<String, RouteTemplate> cache, String declaredPath, Grammar grammar) {
    return cached(cache, declaredPath, grammar.safeParse);
  }

  @VisibleForTesting
  static RouteTemplate cached(
      DDCache<String, RouteTemplate> cache,
      String declaredPath,
      Function<String, RouteTemplate> producer) {
    // null cannot be a cache key, and empty is not worth an entry
    if (declaredPath == null || declaredPath.isEmpty()) {
      return RouteTemplate.NOT_EXPRESSIBLE;
    }
    try {
      return cache.computeIfAbsent(declaredPath, producer);
    } catch (Throwable t) {
      // an Error may be transient (e.g. OutOfMemoryError): the cache stores nothing when the
      // producer throws, so the next request retries
      log.debug("Failed to parse Vert.x route path {}", declaredPath, t);
      return RouteTemplate.NOT_EXPRESSIBLE;
    }
  }

  /**
   * Wraps {@code parser} so that a {@link RuntimeException} becomes a cached {@link
   * RouteTemplate#NOT_EXPRESSIBLE}: parsing is deterministic, so it would fail again for the same
   * path. Errors propagate so that {@link #cached} does not cache them.
   */
  @VisibleForTesting
  static Function<String, RouteTemplate> safely(final Function<String, RouteTemplate> parser) {
    return path -> {
      try {
        return parser.apply(path);
      } catch (RuntimeException e) {
        log.debug("Failed to parse Vert.x route path {}", path, e);
        return RouteTemplate.NOT_EXPRESSIBLE;
      }
    };
  }

  /**
   * Parses a declared Vert.x route path in a single pass. Malformed input is not an error: it gives
   * {@link RouteTemplate#NOT_EXPRESSIBLE}.
   *
   * @return the template, or {@link RouteTemplate#NOT_EXPRESSIBLE} for null, empty, relative or
   *     regex-like paths
   */
  public static RouteTemplate parse(String declaredPath, Grammar grammar) {
    if (declaredPath == null
        || declaredPath.isEmpty()
        || declaredPath.charAt(0) != '/'
        || grammar == null) {
      return RouteTemplate.NOT_EXPRESSIBLE;
    }
    int len = declaredPath.length();
    boolean trailingStar = declaredPath.charAt(len - 1) == '*';
    int end = trailingStar ? len - 1 : len;
    // Vert.x compiles the path to a regex as soon as it holds a ':', valid name or not
    boolean regex = declaredPath.indexOf(':') >= 0;
    RouteTemplate.Builder builder = RouteTemplate.builder();
    List<RoutePart> parts = new ArrayList<>();
    List<String> names = null;
    int literalStart = -1;
    int i = 0;
    while (i < end) {
      char c = declaredPath.charAt(i);
      if (c == '/') {
        literalStart = flushLiteral(declaredPath, literalStart, i, parts);
        endSegment(builder, parts);
        i++;
      } else if (regex && isRegexOperator(c)) {
        return RouteTemplate.NOT_EXPRESSIBLE;
      } else if (c == ':' && i + 1 < end && grammar.isNameStart(declaredPath.charAt(i + 1))) {
        literalStart = flushLiteral(declaredPath, literalStart, i, parts);
        int nameEnd = i + 2;
        while (nameEnd < end && grammar.isNamePart(declaredPath.charAt(nameEnd))) {
          nameEnd++;
        }
        String name = declaredPath.substring(i + 1, nameEnd);
        if (names == null) {
          names = new ArrayList<>();
        } else if (names.contains(name)) {
          // Vert.x rejects a name used twice
          return RouteTemplate.NOT_EXPRESSIBLE;
        }
        names.add(name);
        parts.add(param(name));
        i = nameEnd;
      } else {
        if (literalStart < 0) {
          literalStart = i;
        }
        i++;
      }
    }
    flushLiteral(declaredPath, literalStart, end, parts);
    if (trailingStar && declaredPath.charAt(end - 1) == '/') {
      // "/a/*": the '/' before it already closed the previous segment
      builder.segment(catchAll(null));
    } else {
      if (trailingStar) {
        parts.add(catchAll(null));
      }
      endSegment(builder, parts);
    }
    return builder
        .trailingSlash(!trailingStar && len > 1 && declaredPath.charAt(len - 1) == '/')
        .build();
  }

  /**
   * Whether {@code declaredPath} declares a {@code :name} parameter called {@code name} under
   * {@code grammar}. Never allocates.
   */
  static boolean declaresParam(String declaredPath, String name, Grammar grammar) {
    if (declaredPath == null || name == null || grammar == null) {
      return false;
    }
    int len = declaredPath.length();
    for (int i = declaredPath.indexOf(':'); i >= 0; i = declaredPath.indexOf(':', i + 1)) {
      int nameEnd = nameEnd(declaredPath, i, len, grammar);
      if (nameEnd - i - 1 == name.length() && declaredPath.startsWith(name, i + 1)) {
        return true;
      }
    }
    return false;
  }

  /**
   * End (exclusive) of the parameter name that starts after the {@code :} at {@code colon}, within
   * {@code end}; -1 when that {@code :} does not start a name (static text).
   */
  static int nameEnd(String path, int colon, int end, Grammar grammar) {
    if (colon + 1 >= end || !grammar.isNameStart(path.charAt(colon + 1))) {
      return -1;
    }
    int nameEnd = colon + 2;
    while (nameEnd < end && grammar.isNamePart(path.charAt(nameEnd))) {
      nameEnd++;
    }
    return nameEnd;
  }

  /** Regex syntax that Vert.x does not escape in a parameter path (besides a non-trailing '*'). */
  private static boolean isRegexOperator(char c) {
    switch (c) {
      case '?':
      case '[':
      case ']':
      case '{':
      case '}':
      case '|':
      case '^':
      case '\\':
      case '*':
        return true;
      default:
        return false;
    }
  }

  private static int flushLiteral(String path, int start, int end, List<RoutePart> parts) {
    if (start >= 0 && end > start) {
      parts.add(literal(RouteEncoding.decodePath(path.substring(start, end))));
    }
    return -1;
  }

  private static void endSegment(RouteTemplate.Builder builder, List<RoutePart> parts) {
    if (!parts.isEmpty()) {
      builder.segment(parts.toArray(new RoutePart[0]));
      parts.clear();
    }
  }
}

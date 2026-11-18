package datadog.trace.api.appsec.route;

import static datadog.trace.api.appsec.route.RoutePart.catchAll;
import static datadog.trace.api.appsec.route.RoutePart.constrainedParam;
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
 * Turns a Spring route pattern ({@code HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE} in MVC, or
 * {@code PathPattern.getPatternString()} in WebFlux) into a {@link RouteTemplate}. The parser
 * accepts the union of the {@code PathPattern} and {@code AntPathMatcher} syntax, since the mode
 * cannot be told from the string:
 *
 * <ul>
 *   <li>{@code {name}} and {@code {name:regex}}: parameter {@code name}; the regex is skipped by
 *       counting braces and backslash escapes, never compiled (the parameter records that it had
 *       one);
 *   <li>{@code {*name}}: named catch-all; {@code **} as a whole segment, anywhere: unnamed
 *       catch-all;
 *   <li>{@code *} (or a run of {@code *} inside a segment) and {@code ?}: unnamed parameter;
 *   <li>everything else: static text.
 * </ul>
 *
 * Unbalanced braces, a {@code /} inside braces or an empty name give {@link
 * RouteTemplate#NOT_EXPRESSIBLE}. Empty segments are ignored; a trailing {@code /} is kept.
 */
public final class SpringRouteParser {

  private static final Logger log = LoggerFactory.getLogger(SpringRouteParser.class);

  /** Route patterns are declared in code, so their cardinality is low; 256 bounds odd apps. */
  private static final int CACHE_CAPACITY = 256;

  private static final DDCache<String, RouteTemplate> CACHE =
      DDCaches.newFixedSizeCache(CACHE_CAPACITY);

  private static final Function<String, RouteTemplate> SAFE_PARSE =
      safely(SpringRouteParser::parse);

  private SpringRouteParser() {}

  /**
   * Same as {@link #parse(String)} but backed by a bounded cache keyed by the pattern, for the
   * request hot path. Never returns null or throws.
   */
  public static RouteTemplate cached(String pattern) {
    return cached(CACHE, pattern, SAFE_PARSE);
  }

  @VisibleForTesting
  static RouteTemplate cached(DDCache<String, RouteTemplate> cache, String pattern) {
    return cached(cache, pattern, SAFE_PARSE);
  }

  @VisibleForTesting
  static RouteTemplate cached(
      DDCache<String, RouteTemplate> cache,
      String pattern,
      Function<String, RouteTemplate> producer) {
    // null cannot be a cache key, and empty is not worth an entry
    if (pattern == null || pattern.isEmpty()) {
      return RouteTemplate.NOT_EXPRESSIBLE;
    }
    try {
      return cache.computeIfAbsent(pattern, producer);
    } catch (Throwable t) {
      // an Error may be transient (e.g. OutOfMemoryError): the cache stores nothing when the
      // producer throws, so the next request retries
      log.debug("Failed to parse route pattern {}", pattern, t);
      return RouteTemplate.NOT_EXPRESSIBLE;
    }
  }

  /**
   * Wraps {@code parser} so that a {@link RuntimeException} becomes a cached {@link
   * RouteTemplate#NOT_EXPRESSIBLE}: parsing is deterministic, so it would fail again for the same
   * pattern. Errors propagate so that {@link #cached} does not cache them.
   */
  @VisibleForTesting
  static Function<String, RouteTemplate> safely(final Function<String, RouteTemplate> parser) {
    return pattern -> {
      try {
        return parser.apply(pattern);
      } catch (RuntimeException e) {
        log.debug("Failed to parse route pattern {}", pattern, e);
        return RouteTemplate.NOT_EXPRESSIBLE;
      }
    };
  }

  /**
   * Parses a Spring route pattern in a single pass. Malformed input is not an error: it gives
   * {@link RouteTemplate#NOT_EXPRESSIBLE}.
   *
   * @return the template, or {@link RouteTemplate#NOT_EXPRESSIBLE} for null, empty or malformed
   *     patterns
   */
  public static RouteTemplate parse(String pattern) {
    if (pattern == null || pattern.isEmpty()) {
      return RouteTemplate.NOT_EXPRESSIBLE;
    }
    RouteTemplate.Builder builder = RouteTemplate.builder();
    List<RoutePart> parts = new ArrayList<>();
    int len = pattern.length();
    int segmentStart = 0;
    int literalStart = -1;
    int i = 0;
    while (i < len) {
      char c = pattern.charAt(i);
      if (c == '/') {
        literalStart = flushLiteral(pattern, literalStart, i, parts);
        endSegment(builder, parts);
        segmentStart = ++i;
      } else if (c == '{') {
        literalStart = flushLiteral(pattern, literalStart, i, parts);
        i = variable(pattern, i, parts);
        if (i < 0) {
          return RouteTemplate.NOT_EXPRESSIBLE;
        }
      } else if (c == '}') {
        return RouteTemplate.NOT_EXPRESSIBLE;
      } else if (c == '*') {
        literalStart = flushLiteral(pattern, literalStart, i, parts);
        i = wildcard(pattern, i, i == segmentStart, parts);
      } else if (c == '?') {
        literalStart = flushLiteral(pattern, literalStart, i, parts);
        parts.add(param(null));
        i++;
      } else {
        if (literalStart < 0) {
          literalStart = i;
        }
        i++;
      }
    }
    flushLiteral(pattern, literalStart, len, parts);
    endSegment(builder, parts);
    return builder.trailingSlash(len > 1 && pattern.charAt(len - 1) == '/').build();
  }

  /**
   * Adds the variable opened at {@code open}: {@code *name}, {@code name} or {@code name:regex}.
   * Returns the index after its closing brace, or -1 when it cannot be expressed.
   */
  private static int variable(String pattern, int open, List<RoutePart> parts) {
    int close = closingBrace(pattern, open);
    if (close < 0) {
      return -1;
    }
    int from = open + 1;
    if (from < close && pattern.charAt(from) == '*') {
      if (from + 1 == close) {
        return -1;
      }
      parts.add(catchAll(pattern.substring(from + 1, close)));
      return close + 1;
    }
    int colon = pattern.indexOf(':', from);
    int end = colon < 0 || colon > close ? close : colon;
    if (end == from) {
      return -1;
    }
    String name = pattern.substring(from, end);
    parts.add(end < close ? constrainedParam(name) : param(name));
    return close + 1;
  }

  /**
   * Adds the wildcard at {@code start}: {@code **} as a whole segment is an unnamed catch-all; a
   * {@code *} or a run of them next to other text (Ant treats it as {@code *}) is one unnamed
   * single-segment param. Returns the index after the wildcard.
   */
  private static int wildcard(
      String pattern, int start, boolean atSegmentStart, List<RoutePart> parts) {
    int len = pattern.length();
    if (atSegmentStart
        && start + 1 < len
        && pattern.charAt(start + 1) == '*'
        && (start + 2 == len || pattern.charAt(start + 2) == '/')) {
      parts.add(catchAll(null));
      return start + 2;
    }
    parts.add(param(null));
    int i = start;
    while (i < len && pattern.charAt(i) == '*') {
      i++;
    }
    return i;
  }

  /**
   * Index of the closing brace of the variable opened at {@code open}, tracking nested braces and
   * backslash escapes of the regex; -1 when unbalanced or when a {@code /} appears inside.
   */
  private static int closingBrace(String pattern, int open) {
    int depth = 0;
    for (int i = open; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (c == '\\') {
        i++;
      } else if (c == '{') {
        depth++;
      } else if (c == '}') {
        if (--depth == 0) {
          return i;
        }
      } else if (c == '/') {
        return -1;
      }
    }
    return -1;
  }

  private static int flushLiteral(String pattern, int start, int end, List<RoutePart> parts) {
    if (start >= 0 && end > start) {
      parts.add(literal(pattern.substring(start, end)));
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

package datadog.trace.api.appsec.route;

import static datadog.trace.api.appsec.route.RoutePart.catchAll;
import static datadog.trace.api.appsec.route.RoutePart.literal;
import static datadog.trace.api.appsec.route.RoutePart.optionalParam;
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
 * Turns a Play route path ({@code HandlerDef.path}, as given to {@code withRoute}) into a {@link
 * RouteTemplate}. The grammar is the one the Play routes compiler emits:
 *
 * <ul>
 *   <li>{@code $name<regex>}: dynamic part {@code name} (a Java identifier, as the routes compiler
 *       accepts, running until the {@code <}); the regex runs until the first {@code >} and is
 *       classified by a character scan, never compiled. A regex that provably matches within one
 *       segment ({@code [^/]+}, {@code \d+}, {@code [a-z0-9_.-]+}, ...) gives a parameter (optional
 *       when it can match the empty string, e.g. {@code [^/]*}, so an empty value is absent); any
 *       other regex gives a catch-all when it is the tail of the route (only {@code /} may follow
 *       it), and {@link RouteTemplate#NOT_EXPRESSIBLE} otherwise;
 *   <li>everything else: static text, percent-encoded as written in the routes file, so {@code %XX}
 *       escapes are decoded (UTF-8) and the template re-encodes them; a malformed escape stays
 *       literal. Braces are plain text.
 * </ul>
 *
 * Several dynamic parts and static text may share a segment. Play static text never contains {@code
 * $}, so a {@code $} that does not open a complete {@code $name<regex>} gives {@link
 * RouteTemplate#NOT_EXPRESSIBLE}. Empty segments are ignored; a trailing {@code /} is kept.
 */
public final class PlayRouteParser {

  private static final Logger log = LoggerFactory.getLogger(PlayRouteParser.class);

  /** Routes are declared in the routes file, so their cardinality is low; 256 bounds odd apps. */
  private static final int CACHE_CAPACITY = 256;

  private static final DDCache<String, RouteTemplate> CACHE =
      DDCaches.newFixedSizeCache(CACHE_CAPACITY);

  private static final Function<String, RouteTemplate> SAFE_PARSE = safely(PlayRouteParser::parse);

  private PlayRouteParser() {}

  /**
   * Same as {@link #parse(String)} but backed by a bounded cache keyed by the route path, for the
   * request hot path. Never returns null or throws.
   */
  public static RouteTemplate cached(String path) {
    return cached(CACHE, path, SAFE_PARSE);
  }

  @VisibleForTesting
  static RouteTemplate cached(
      DDCache<String, RouteTemplate> cache, String path, Function<String, RouteTemplate> producer) {
    // null cannot be a cache key, and empty is not worth an entry
    if (path == null || path.isEmpty()) {
      return RouteTemplate.NOT_EXPRESSIBLE;
    }
    try {
      return cache.computeIfAbsent(path, producer);
    } catch (Throwable t) {
      // an Error may be transient (e.g. OutOfMemoryError): the cache stores nothing when the
      // producer throws, so the next request retries
      log.debug("Failed to parse Play route {}", path, t);
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
        log.debug("Failed to parse Play route {}", path, e);
        return RouteTemplate.NOT_EXPRESSIBLE;
      }
    };
  }

  /**
   * Parses a Play route path in a single pass. Malformed input is not an error: it gives {@link
   * RouteTemplate#NOT_EXPRESSIBLE}.
   *
   * @return the template, or {@link RouteTemplate#NOT_EXPRESSIBLE} for null, empty, malformed or
   *     ambiguous paths
   */
  public static RouteTemplate parse(String path) {
    if (path == null || path.isEmpty()) {
      return RouteTemplate.NOT_EXPRESSIBLE;
    }
    RouteTemplate.Builder builder = RouteTemplate.builder();
    List<RoutePart> parts = new ArrayList<>();
    int len = path.length();
    int literalStart = -1;
    // set once a catch-all was added: only "/" may follow it
    boolean tail = false;
    int i = 0;
    while (i < len) {
      char c = path.charAt(i);
      if (c == '/') {
        literalStart = flushLiteral(path, literalStart, i, parts);
        endSegment(builder, parts);
        i++;
        continue;
      }
      if (tail) {
        return RouteTemplate.NOT_EXPRESSIBLE;
      }
      if (c == '$') {
        int nameEnd = identifierEnd(path, i + 1);
        // static text never holds "$": one that does not open "$name<regex>" is not Play grammar
        if (nameEnd == i + 1 || nameEnd >= len || path.charAt(nameEnd) != '<') {
          return RouteTemplate.NOT_EXPRESSIBLE;
        }
        int close = path.indexOf('>', nameEnd + 1);
        if (close < 0) {
          return RouteTemplate.NOT_EXPRESSIBLE;
        }
        literalStart = flushLiteral(path, literalStart, i, parts);
        String name = path.substring(i + 1, nameEnd);
        switch (classifyRegex(path, nameEnd + 1, close)) {
          case PARAM:
            parts.add(param(name));
            break;
          case OPTIONAL_PARAM:
            // a regex that can match "" binds an empty value, which renders as absent
            parts.add(optionalParam(name));
            break;
          default:
            parts.add(catchAll(name));
            tail = true;
        }
        i = close + 1;
        continue;
      }
      if (literalStart < 0) {
        literalStart = i;
      }
      i++;
    }
    flushLiteral(path, literalStart, len, parts);
    endSegment(builder, parts);
    return builder.trailingSlash(len > 1 && path.charAt(len - 1) == '/').build();
  }

  /**
   * Index after the Java identifier starting at {@code from} ({@code $} included, as in Play's
   * routes compiler); {@code from} when there is none.
   */
  private static int identifierEnd(String path, int from) {
    int len = path.length();
    if (from >= len || !Character.isJavaIdentifierStart(path.codePointAt(from))) {
      return from;
    }
    int i = from + Character.charCount(path.codePointAt(from));
    while (i < len) {
      int cp = path.codePointAt(i);
      if (!Character.isJavaIdentifierPart(cp)) {
        break;
      }
      i += Character.charCount(cp);
    }
    return i;
  }

  /** How a dynamic part's regex renders. */
  @VisibleForTesting
  enum RegexKind {
    /** Provably matches within one segment and never the empty string. */
    PARAM,
    /** Provably matches within one segment, possibly the empty string. */
    OPTIONAL_PARAM,
    /** Not provably single-segment: a catch-all at the tail, not expressible elsewhere. */
    MULTI_SEGMENT
  }

  /**
   * Classifies the regex {@code path[from, to)}: it is provably single-segment when it is one atom
   * ({@code \d}, {@code \w}, a bracket class excluding {@code /}) and an optional greedy quantifier
   * ({@code +}, {@code *}, {@code {n}}, {@code {n,}}, {@code {n,m}}). Anything else is not
   * provable, which is the safe answer.
   */
  @VisibleForTesting
  static RegexKind classifyRegex(String path, int from, int to) {
    int atomEnd = singleSegmentAtomEnd(path, from, to);
    if (atomEnd < 0 || !isQuantifier(path, atomEnd, to)) {
      return RegexKind.MULTI_SEGMENT;
    }
    return allowsZero(path, atomEnd, to) ? RegexKind.OPTIONAL_PARAM : RegexKind.PARAM;
  }

  /**
   * Index after the leading atom of {@code path[from, to)} when it provably never matches a {@code
   * /} ({@code \d}, {@code \w}, a bracket class excluding {@code /}), else -1.
   */
  private static int singleSegmentAtomEnd(String path, int from, int to) {
    if (from >= to) {
      return -1;
    }
    int atomEnd;
    char c = path.charAt(from);
    if (c == '\\') {
      if (from + 1 >= to) {
        return -1;
      }
      char escaped = path.charAt(from + 1);
      if (escaped != 'd' && escaped != 'w') {
        return -1;
      }
      atomEnd = from + 2;
    } else if (c == '[') {
      atomEnd = bracketClassEnd(path, from, to);
    } else {
      atomEnd = -1;
    }
    return atomEnd;
  }

  /**
   * True when the quantifier {@code path[from, to)} (already validated by {@link #isQuantifier})
   * allows zero repetitions: {@code *}, {@code {0}}, {@code {0,}} or {@code {0,n}}.
   */
  private static boolean allowsZero(String path, int from, int to) {
    if (from == to) {
      return false;
    }
    char c = path.charAt(from);
    if (c == '*') {
      return true;
    }
    if (c != '{') {
      return false;
    }
    // the minimum is the digits before the comma (or the closing brace)
    for (int i = from + 1; i < to - 1; i++) {
      char q = path.charAt(i);
      if (q == ',') {
        break;
      }
      if (q != '0') {
        return false;
      }
    }
    return true;
  }

  /**
   * Index after the bracket class opened at {@code open} when it provably excludes {@code /}, else
   * -1. A negated class must list {@code /}; a positive one must not hold {@code /} (also through a
   * range, or escaped) nor {@code \S}, {@code \W}, {@code \D}. A {@code .} inside a class is
   * literal, so it is allowed. Nested classes, intersections and escapes other than {@code \d},
   * {@code \w}, {@code \s} (and their negations in a negated class) or an escaped symbol are
   * rejected.
   */
  private static int bracketClassEnd(String path, int open, int to) {
    int i = open + 1;
    boolean negated = i < to && path.charAt(i) == '^';
    if (negated) {
      i++;
    }
    int first = i;
    boolean excludesSlash = false;
    // last single literal char, a candidate range start; -1 when none
    int prev = -1;
    while (i < to) {
      char c = path.charAt(i);
      if (c == ']' && i > first) {
        return negated && !excludesSlash ? -1 : i + 1;
      }
      if (c == '[' || c == ']' || (c == '&' && i + 1 < to && path.charAt(i + 1) == '&')) {
        return -1;
      }
      if (c == '\\') {
        if (i + 1 >= to) {
          return -1;
        }
        char escaped = path.charAt(i + 1);
        if (isAsciiAlphanumeric(escaped)) {
          if (!isAllowedClassEscape(escaped, negated)) {
            return -1;
          }
          prev = -1;
        } else {
          if (escaped == '/') {
            if (!negated) {
              return -1;
            }
            excludesSlash = true;
          }
          prev = escaped;
        }
        i += 2;
        continue;
      }
      if (c == '-' && prev >= 0 && i + 1 < to && path.charAt(i + 1) != ']') {
        char end = path.charAt(i + 1);
        if (end == '\\' || end == '[' || end < prev) {
          return -1;
        }
        boolean coversSlash = prev <= '/' && '/' <= end;
        if (coversSlash) {
          if (!negated) {
            return -1;
          }
          excludesSlash = true;
        }
        prev = -1;
        i += 2;
        continue;
      }
      if (c == '/') {
        if (!negated) {
          return -1;
        }
        excludesSlash = true;
      }
      prev = c;
      i++;
    }
    return -1;
  }

  private static boolean isAllowedClassEscape(char escaped, boolean negated) {
    switch (escaped) {
      case 'd':
      case 'w':
      case 's':
        return true;
      case 'D':
      case 'W':
      case 'S':
        // their complement includes "/": fine only when it is excluded
        return negated;
      default:
        return false;
    }
  }

  private static boolean isAsciiAlphanumeric(char c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
  }

  /** True when {@code path[from, to)} is empty or one greedy quantifier. */
  private static boolean isQuantifier(String path, int from, int to) {
    if (from == to) {
      return true;
    }
    char c = path.charAt(from);
    if (c == '+' || c == '*') {
      return from + 1 == to;
    }
    if (c != '{' || path.charAt(to - 1) != '}') {
      return false;
    }
    int digits = 0;
    int commas = 0;
    for (int i = from + 1; i < to - 1; i++) {
      char q = path.charAt(i);
      if (q >= '0' && q <= '9') {
        digits++;
      } else if (q == ',' && digits > 0 && commas == 0) {
        commas++;
      } else {
        return false;
      }
    }
    return digits > 0;
  }

  private static int flushLiteral(String path, int start, int end, List<RoutePart> parts) {
    if (start >= 0 && end > start) {
      // static text is percent-encoded in the routes file: decode so the template re-encodes it
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

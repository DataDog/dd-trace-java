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
 * Turns a JAX-RS route (the class and method {@code @Path} values joined, as the JAX-RS decorators
 * pass it to {@code withRoute}) into a {@link RouteTemplate}:
 *
 * <ul>
 *   <li>{@code {name}} and {@code {name: regex}}, with optional whitespace around the name, the
 *       colon and the regex: parameter {@code name}. The regex is skipped by counting braces and
 *       backslash escapes, never compiled, and may contain {@code /}; segments are only split on a
 *       {@code /} outside braces;
 *   <li>a regex that can match {@code /} (see {@link #acceptsSlash(String)}) makes the parameter a
 *       catch-all, e.g. {@code {path: .*}};
 *   <li>everything else: static text. JAX-RS treats {@code %XX} in {@code @Path} as an escape, so a
 *       valid escape is decoded (as UTF-8) before the literal is encoded: {@code %c3%a9} and {@code
 *       é} both give {@code %C3%A9}, {@code %20} stays {@code %20}. The core always re-encodes
 *       literal text, so an escape of an unreserved character comes out decoded ({@code %7E} gives
 *       {@code ~}) and invalid UTF-8 gives U+FFFD. A {@code %} that does not start an escape is
 *       literal text ({@code %25}).
 * </ul>
 *
 * Unbalanced braces, a closing brace outside a parameter, or an empty or invalid name give {@link
 * RouteTemplate#NOT_EXPRESSIBLE}. Empty segments are ignored; a trailing {@code /} is kept.
 */
public final class JaxRsRouteParser {

  private static final Logger log = LoggerFactory.getLogger(JaxRsRouteParser.class);

  /** Routes are declared in code, so their cardinality is low; 256 bounds odd apps. */
  private static final int CACHE_CAPACITY = 256;

  private static final DDCache<String, RouteTemplate> CACHE =
      DDCaches.newFixedSizeCache(CACHE_CAPACITY);

  /** {@code literalStart} when no static text is pending. */
  private static final int NO_LITERAL = -1;

  private static final Function<String, RouteTemplate> SAFE_PARSE = safely(JaxRsRouteParser::parse);

  private JaxRsRouteParser() {}

  /**
   * Same as {@link #parse(String)} but backed by a bounded cache keyed by the route, for the
   * request hot path. Never returns null or throws.
   */
  public static RouteTemplate cached(String route) {
    return cached(CACHE, route, SAFE_PARSE);
  }

  @VisibleForTesting
  static RouteTemplate cached(DDCache<String, RouteTemplate> cache, String route) {
    return cached(cache, route, SAFE_PARSE);
  }

  @VisibleForTesting
  static RouteTemplate cached(
      DDCache<String, RouteTemplate> cache,
      String route,
      Function<String, RouteTemplate> producer) {
    // null cannot be a cache key, and empty is not worth an entry
    if (route == null || route.isEmpty()) {
      return RouteTemplate.NOT_EXPRESSIBLE;
    }
    try {
      return cache.computeIfAbsent(route, producer);
    } catch (Throwable t) {
      // an Error may be transient (e.g. OutOfMemoryError): the cache stores nothing when the
      // producer throws, so the next request retries
      log.debug("Failed to parse JAX-RS route {}", route, t);
      return RouteTemplate.NOT_EXPRESSIBLE;
    }
  }

  /**
   * Wraps {@code parser} so that a {@link RuntimeException} becomes a cached {@link
   * RouteTemplate#NOT_EXPRESSIBLE}: parsing is deterministic, so it would fail again for the same
   * route. Errors propagate so that {@link #cached} does not cache them.
   */
  @VisibleForTesting
  static Function<String, RouteTemplate> safely(final Function<String, RouteTemplate> parser) {
    return route -> {
      try {
        return parser.apply(route);
      } catch (RuntimeException e) {
        log.debug("Failed to parse JAX-RS route {}", route, e);
        return RouteTemplate.NOT_EXPRESSIBLE;
      }
    };
  }

  /**
   * Parses a JAX-RS route in a single pass. Malformed input is not an error: it gives {@link
   * RouteTemplate#NOT_EXPRESSIBLE}.
   *
   * @return the template, or {@link RouteTemplate#NOT_EXPRESSIBLE} for null, empty or malformed
   *     routes
   */
  public static RouteTemplate parse(String route) {
    if (route == null || route.isEmpty()) {
      return RouteTemplate.NOT_EXPRESSIBLE;
    }
    RouteTemplate.Builder builder = RouteTemplate.builder();
    List<RoutePart> parts = new ArrayList<>();
    int len = route.length();
    int literalStart = NO_LITERAL;
    int i = 0;
    while (i < len) {
      char c = route.charAt(i);
      if (c == '/') {
        flushLiteral(route, literalStart, i, parts);
        literalStart = NO_LITERAL;
        endSegment(builder, parts);
        i++;
      } else if (c == '{') {
        flushLiteral(route, literalStart, i, parts);
        literalStart = NO_LITERAL;
        i = parseParam(route, i, parts);
        if (i < 0) {
          return RouteTemplate.NOT_EXPRESSIBLE;
        }
      } else if (c == '}') {
        return RouteTemplate.NOT_EXPRESSIBLE;
      } else {
        if (literalStart == NO_LITERAL) {
          literalStart = i;
        }
        i++;
      }
    }
    flushLiteral(route, literalStart, len, parts);
    endSegment(builder, parts);
    return builder.trailingSlash(len > 1 && route.charAt(len - 1) == '/').build();
  }

  /**
   * Adds the parameter opened at {@code open}: {@code name} or {@code name: regex}, with optional
   * whitespace. Returns the index after its closing brace, or -1 when it cannot be expressed.
   */
  private static int parseParam(String route, int open, List<RoutePart> parts) {
    int close = closingBrace(route, open);
    if (close < 0) {
      return -1;
    }
    int colon = route.indexOf(':', open + 1);
    int nameEnd = colon < 0 || colon > close ? close : colon;
    String name = route.substring(open + 1, nameEnd).trim();
    if (!isValidName(name)) {
      return -1;
    }
    String regex = nameEnd < close ? route.substring(nameEnd + 1, close).trim() : "";
    if (regex.isEmpty()) {
      parts.add(param(name));
    } else if (acceptsSlash(regex)) {
      parts.add(catchAll(name));
    } else {
      parts.add(constrainedParam(name));
    }
    return close + 1;
  }

  /** JAX-RS names are letters, digits, {@code _}, {@code -} and {@code .}; never empty. */
  private static boolean isValidName(String name) {
    if (name.isEmpty()) {
      return false;
    }
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (!Character.isLetterOrDigit(c) && c != '_' && c != '-' && c != '.') {
        return false;
      }
    }
    return true;
  }

  /**
   * Index of the closing brace of the parameter opened at {@code open}, tracking nested braces and
   * backslash escapes of the regex; -1 when unbalanced.
   */
  private static int closingBrace(String route, int open) {
    int depth = 0;
    for (int i = open; i < route.length(); i++) {
      char c = route.charAt(i);
      if (c == '\\') {
        i++;
      } else if (c == '{') {
        depth++;
      } else if (c == '}' && --depth == 0) {
        return i;
      }
    }
    return -1;
  }

  /**
   * True when {@code regex} may match a {@code /}, found by a static scan (the regex is never
   * compiled). Outside a character class: an unescaped {@code .} or {@code /}, or an escape that
   * may match {@code /} (see {@link #escape}). Inside a class: a member or a range that holds
   * {@code /} (e.g. {@code !-~}), or a negated class without it. A class with a nested class or an
   * intersection counts as accepting {@code /}, since nesting can negate or narrow its members.
   * Invalid constructs (unterminated class, unknown letter escape, malformed {@code \x}, <code>
   * &#92;u</code> or octal escape) count as accepting {@code /}.
   */
  @VisibleForTesting
  static boolean acceptsSlash(String regex) {
    int len = regex.length();
    int i = 0;
    while (i < len) {
      char c = regex.charAt(i);
      if (c == '\\') {
        Atom escape = escape(regex, i);
        if (escape.mayMatchSlash()) {
          return true;
        }
        i = escape.end;
      } else if (c == '[') {
        int end = classEnd(regex, i);
        if (end < 0 || classAcceptsSlash(regex, i, end)) {
          return true;
        }
        i = end + 1;
      } else if (c == '.' || c == '/') {
        return true;
      } else {
        i++;
      }
    }
    return false;
  }

  /** Index of the {@code ]} closing the class opened at {@code open}, or -1. */
  private static int classEnd(String regex, int open) {
    int depth = 0;
    for (int i = open; i < regex.length(); i++) {
      char c = regex.charAt(i);
      if (c == '\\') {
        i++;
      } else if (c == '[') {
        depth++;
      } else if (c == ']' && --depth == 0) {
        return i;
      }
    }
    return -1;
  }

  /**
   * True when the class between {@code open} and {@code end} holds an unescaped nested class
   * ({@code [}) or an intersection ({@code &&}).
   */
  private static boolean hasNestedClassOrIntersection(String regex, int open, int end) {
    for (int i = open + 1; i < end; i++) {
      char c = regex.charAt(i);
      if (c == '\\') {
        i++;
      } else if (c == '[' || (c == '&' && i + 1 < end && regex.charAt(i + 1) == '&')) {
        return true;
      }
    }
    return false;
  }

  /** Whether the class between {@code open} ({@code [}) and {@code end} ({@code ]}) admits '/'. */
  private static boolean classAcceptsSlash(String regex, int open, int end) {
    if (hasNestedClassOrIntersection(regex, open, end)) {
      // a nested class or an intersection can negate or narrow its members: be conservative
      return true;
    }
    int i = open + 1;
    boolean negated = i < end && regex.charAt(i) == '^';
    if (negated) {
      i++;
    }
    boolean hasSlash = false;
    int previous = Atom.NONE; // the last member, the possible start of a range
    while (i < end && !hasSlash) {
      Atom member;
      if (isRangeDash(regex, i, end, previous)) {
        member = classMember(regex, i + 1);
        hasSlash = rangeHasSlash(previous, member.code);
        previous = Atom.NONE;
      } else {
        member = classMember(regex, i);
        hasSlash = member.mayMatchSlash();
        previous = member.code;
      }
      i = member.end;
    }
    return negated != hasSlash;
  }

  /** Whether the {@code -} at {@code i} joins {@code previous} and the next member into a range. */
  private static boolean isRangeDash(String regex, int i, int end, int previous) {
    return regex.charAt(i) == '-'
        && (previous >= 0 || previous == Atom.UNKNOWN_CHAR)
        && i + 1 < end
        && regex.charAt(i + 1) != '[';
  }

  /** A range admits '/' when it spans it; a bound that is not a known character counts as yes. */
  private static boolean rangeHasSlash(int from, int to) {
    return from < 0 || to < 0 || (from <= '/' && '/' <= to);
  }

  /** The class member at {@code at}: an escape, a syntax character or a plain character. */
  private static Atom classMember(String regex, int at) {
    char c = regex.charAt(at);
    if (c == '\\') {
      return escape(regex, at);
    }
    boolean syntax = c == '[' || c == ']' || c == '^' || c == '&';
    return new Atom(at + 1, syntax ? Atom.NONE : c);
  }

  /**
   * The escape starting with the backslash at {@code at}, shared by the scans inside and outside
   * classes. The character escapes {@code \/}, {@code \x2F}, {@code \x{2F}}, <code>&#92;u002F
   * </code>, octal {@code \057} / {@code \0057}, {@code \co} and {@code \N{SOLIDUS}} are a {@code
   * /}; {@code \S}, {@code \W}, {@code \D}, {@code \H}, {@code \V} and {@code \X} accept it; {@code
   * \p{..}} accepts it for a property that holds it (see {@link #propertyHasSlash}) and {@code
   * \P{..}} for one that does not; a {@code \Q..\E} quote accepts it when the quoted text holds it.
   */
  private static Atom escape(String regex, int at) {
    int len = regex.length();
    if (at + 1 >= len) {
      return new Atom(len, Atom.NONE); // trailing backslash
    }
    char e = regex.charAt(at + 1);
    int from = at + 2;
    switch (e) {
      case 'Q':
        return quote(regex, from);
      case 'p':
      case 'P':
        return property(regex, from, e == 'P');
      case 'x':
        return from < len && regex.charAt(from) == '{'
            ? bracedHex(regex, from)
            : hex(regex, from, from + 2);
      case 'u':
        return hex(regex, from, from + 4);
      case '0':
        return octal(regex, from);
      case 'c':
        return from < len ? new Atom(from + 1, regex.charAt(from) ^ 0x40) : Atom.invalid(len);
      case 'N':
        return named(regex, from);
      case 'k':
        int close = regex.indexOf('>', from);
        return new Atom(close < 0 ? len : close + 1, Atom.NONE); // back reference
      default:
        return new Atom(from, simpleEscape(e));
    }
  }

  /** The code of a one-character escape {@code \e}: a character, {@link Atom#ANY} or NONE. */
  private static int simpleEscape(char e) {
    switch (e) {
      case 'S':
      case 'W':
      case 'D':
      case 'H':
      case 'V':
      case 'X':
        return Atom.ANY;
      case 's':
      case 'w':
      case 'd':
      case 'h':
      case 'v':
      case 'R':
      case 'b':
      case 'B':
      case 'A':
      case 'z':
      case 'Z':
      case 'G':
      case 'E':
        return Atom.NONE; // classes without '/' and zero-width assertions
      case 't':
        return '\t';
      case 'n':
        return '\n';
      case 'r':
        return '\r';
      case 'f':
        return '\f';
      case 'a':
        return 0x07;
      case 'e':
        return 0x1B;
      default:
        if (e >= '1' && e <= '9') {
          return Atom.NONE; // back reference: its group is scanned where it is declared
        }
        // an unknown letter escape is invalid in Java: count it as accepting anything
        return (e >= 'a' && e <= 'z') || (e >= 'A' && e <= 'Z') ? Atom.ANY : e;
    }
  }

  /** {@code \Q..\E} quote whose text starts at {@code from}; unterminated runs to the end. */
  private static Atom quote(String regex, int from) {
    int end = regex.indexOf("\\E", from);
    int stop = end < 0 ? regex.length() : end;
    int slash = regex.indexOf('/', from);
    return new Atom(end < 0 ? stop : end + 2, slash >= 0 && slash < stop ? Atom.ANY : Atom.NONE);
  }

  /** {@code \x{h..h}} with its brace at {@code open}. */
  private static Atom bracedHex(String regex, int open) {
    int close = regex.indexOf('}', open);
    if (close < 0 || close == open + 1 || close - open > 7) {
      return Atom.invalid(close < 0 ? regex.length() : close + 1);
    }
    Atom atom = hex(regex, open + 1, close);
    return new Atom(close + 1, atom.code);
  }

  /**
   * The character whose hex digits are {@code [from, to)} ({@code \xhh}, <code>&#92;uhhhh</code>).
   */
  private static Atom hex(String regex, int from, int to) {
    if (to > regex.length()) {
      return Atom.invalid(regex.length());
    }
    int code = 0;
    for (int i = from; i < to; i++) {
      int digit = Character.digit(regex.charAt(i), 16);
      if (digit < 0) {
        return Atom.invalid(to);
      }
      code = code * 16 + digit;
    }
    return code > Character.MAX_CODE_POINT ? Atom.invalid(to) : new Atom(to, code);
  }

  /**
   * Octal escape {@code \0n}, {@code \0nn} or {@code \0mnn} (m at most 3), digits at {@code from}.
   */
  private static Atom octal(String regex, int from) {
    int len = regex.length();
    int max = from < len && regex.charAt(from) <= '3' ? 3 : 2;
    int code = 0;
    int i = from;
    while (i < len && i - from < max && regex.charAt(i) >= '0' && regex.charAt(i) <= '7') {
      code = code * 8 + regex.charAt(i) - '0';
      i++;
    }
    return i == from ? Atom.invalid(i) : new Atom(i, code);
  }

  /**
   * {@code \N{name}}: only {@code SOLIDUS} is resolved, any other name is a character but not '/'.
   */
  private static Atom named(String regex, int from) {
    int close = from < regex.length() && regex.charAt(from) == '{' ? regex.indexOf('}', from) : -1;
    if (close < 0) {
      return Atom.invalid(regex.length());
    }
    String name = regex.substring(from + 1, close).trim();
    return new Atom(close + 1, "SOLIDUS".equalsIgnoreCase(name) ? '/' : Atom.UNKNOWN_CHAR);
  }

  /** {@code \p{..}} or, when {@code negated}, {@code \P{..}}, with the name at {@code from}. */
  private static Atom property(String regex, int from, boolean negated) {
    int end = propertyEnd(regex, from);
    boolean hasSlash = propertyHasSlash(regex, from, end);
    return new Atom(end, hasSlash != negated ? Atom.ANY : Atom.NONE);
  }

  /**
   * Index after the {@code \p} property name starting at {@code from} ({@code X} or {@code {..}}).
   */
  private static int propertyEnd(String regex, int from) {
    if (from < regex.length() && regex.charAt(from) == '{') {
      int close = regex.indexOf('}', from);
      return close < 0 ? regex.length() : close + 1;
    }
    return Math.min(from + 1, regex.length());
  }

  /**
   * Whether the {@code \p} property in {@code [from, end)} includes '/' (category Po, script
   * Common, block Basic Latin). Other names are taken as without it, so {@code \P} of an unlisted
   * name counts as accepting '/'.
   */
  private static boolean propertyHasSlash(String regex, int from, int end) {
    String name = regex.substring(from, end);
    if (name.startsWith("{")) {
      name = name.substring(1, name.length() - (name.endsWith("}") ? 1 : 0));
    }
    switch (name) {
      case "P":
      case "Po":
      case "IsP":
      case "IsPo":
      case "gc=P":
      case "gc=Po":
      case "general_category=P":
      case "general_category=Po":
      case "Punct":
      case "IsPunctuation":
      case "Graph":
      case "Print":
      case "ASCII":
      case "InBasicLatin":
      case "InBasic_Latin":
      case "IsCommon":
      case "sc=Common":
      case "script=Common":
      case "IsAssigned":
      case "javaDefined":
      case "all":
        return true;
      default:
        return false;
    }
  }

  /**
   * One element of a regex: where it ends and what it matches, a single character (its code) or one
   * of the negative codes.
   */
  private static final class Atom {
    /** Matches no '/' and is not a character (a class without '/' or an assertion). */
    static final int NONE = -1;

    /** May match '/' (a class or quote that holds it, or an invalid construct). */
    static final int ANY = -2;

    /** A single character that is not '/' but whose code is unknown (a named character). */
    static final int UNKNOWN_CHAR = -3;

    final int end;
    final int code;

    Atom(int end, int code) {
      this.end = end;
      this.code = code;
    }

    static Atom invalid(int end) {
      return new Atom(end, ANY);
    }

    boolean mayMatchSlash() {
      return code == '/' || code == ANY;
    }
  }

  /** Adds the literal in {@code [start, end)} with its {@code %XX} escapes decoded. */
  private static void flushLiteral(String route, int start, int end, List<RoutePart> parts) {
    if (start != NO_LITERAL && end > start) {
      parts.add(literal(RouteEncoding.decodePath(route.substring(start, end))));
    }
  }

  private static void endSegment(RouteTemplate.Builder builder, List<RoutePart> parts) {
    if (!parts.isEmpty()) {
      builder.segment(parts.toArray(new RoutePart[0]));
      parts.clear();
    }
  }
}

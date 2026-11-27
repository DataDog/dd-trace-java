package datadog.trace.api.appsec.route;

import static datadog.trace.api.appsec.route.RoutePart.catchAll;
import static datadog.trace.api.appsec.route.RoutePart.constrainedParam;
import static datadog.trace.api.appsec.route.RoutePart.literal;
import static datadog.trace.api.appsec.route.RoutePart.param;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.internal.VisibleForTesting;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
   * compiled). Each element is classified as {@link Slash#YES}, {@link Slash#NO} or {@link
   * Slash#UNKNOWN}, and anything but NO counts as accepting {@code /}: a wrong yes only keeps the
   * catch-all, a wrong no would make the parameter mandatory. Outside a character class: an
   * unescaped {@code .} or {@code /}, or an escape that may match {@code /} (see {@link #escape}).
   * Inside a class: a member or a range that holds {@code /} (e.g. {@code !-~}), or a negated class
   * without it (negation swaps YES and NO but keeps UNKNOWN). A class with a nested class or an
   * intersection, a class starting with {@code ]}, an unterminated class, invalid escapes and the
   * comments flag {@code (?x)} are UNKNOWN.
   */
  @VisibleForTesting
  static boolean acceptsSlash(String regex) {
    int len = regex.length();
    int i = 0;
    while (i < len) {
      char c = regex.charAt(i);
      if (c == '\\') {
        Atom escape = escape(regex, i, false);
        if (escape.slash != Slash.NO) {
          return true;
        }
        i = escape.end;
      } else if (c == '[') {
        int end = classEnd(regex, i);
        if (end < 0 || classSlash(regex, i, end) != Slash.NO) {
          return true;
        }
        i = end + 1;
      } else if (c == '.' || c == '/' || (c == '(' && setsCommentsFlag(regex, i))) {
        return true;
      } else {
        i++;
      }
    }
    return false;
  }

  /**
   * Whether the group opened at {@code open} sets inline flags with {@code x}: comments mode
   * ignores whitespace inside classes too ({@code [! - ~]} is the range {@code !-~}), which the
   * scan does not model.
   */
  private static boolean setsCommentsFlag(String regex, int open) {
    int i = open + 1;
    if (i >= regex.length() || regex.charAt(i) != '?') {
      return false;
    }
    for (i++; i < regex.length(); i++) {
      char c = regex.charAt(i);
      if (c == 'x') {
        return true;
      }
      if (!Character.isLetter(c) && c != '-') {
        return false;
      }
    }
    return false;
  }

  /**
   * Index of the {@code ]} closing the class opened at {@code open}, or -1 when unterminated or
   * when the class starts with {@code ]} (Java takes it as a member, so the end is not known).
   */
  private static int classEnd(String regex, int open) {
    int len = regex.length();
    int first = open + 1 < len && regex.charAt(open + 1) == '^' ? open + 2 : open + 1;
    if (first < len && regex.charAt(first) == ']') {
      return -1;
    }
    int depth = 0;
    for (int i = open; i < len; i++) {
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
   * ({@code [}) or an intersection ({@code &&}). A lone {@code &} is a plain member.
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
  private static Slash classSlash(String regex, int open, int end) {
    if (hasNestedClassOrIntersection(regex, open, end)) {
      // a nested class or an intersection can negate or narrow its members
      return Slash.UNKNOWN;
    }
    int i = open + 1;
    boolean negated = i < end && regex.charAt(i) == '^';
    if (negated) {
      i++;
    }
    Slash members = Slash.NO;
    Atom previous = null; // the last member, the possible start of a range
    while (i < end && members != Slash.YES) {
      Atom member;
      if (isRangeDash(regex, i, end, previous)) {
        member = classMember(regex, i + 1);
        members = members.or(rangeSlash(previous, member));
        previous = null;
      } else {
        member = classMember(regex, i);
        members = members.or(member.slash);
        previous = member;
      }
      i = member.end;
    }
    return negated ? members.negate() : members;
  }

  /** Whether the {@code -} at {@code i} joins {@code previous} and the next member into a range. */
  private static boolean isRangeDash(String regex, int i, int end, Atom previous) {
    return regex.charAt(i) == '-' && previous != null && previous.mayBoundRange() && i + 1 < end;
  }

  /** A range admits '/' when it spans it; UNKNOWN when a bound is not a known character. */
  private static Slash rangeSlash(Atom from, Atom to) {
    if (from.code < 0 || to.code < 0) {
      return Slash.UNKNOWN;
    }
    return from.code <= '/' && '/' <= to.code ? Slash.YES : Slash.NO;
  }

  /**
   * The class member at {@code at}: an escape or a plain character ({@code &} and {@code ^} too).
   */
  private static Atom classMember(String regex, int at) {
    char c = regex.charAt(at);
    return c == '\\' ? escape(regex, at, true) : Atom.character(at + 1, c);
  }

  /**
   * The escape starting with the backslash at {@code at}, shared by the scans inside ({@code
   * inClass}) and outside classes. The character escapes {@code \/}, {@code \x2F}, {@code \x{2F}},
   * <code>&#92;u002F</code>, octal {@code \057} / {@code \0057}, {@code \co} and {@code
   * \N{SOLIDUS}} are a {@code /}; {@code \S}, {@code \W}, {@code \D}, {@code \H}, {@code \V} and
   * {@code \X} accept it; {@code \p{..}} and {@code \P{..}} follow {@link #propertySlash}. A {@code
   * \Q..\E} quote accepts it when the quoted text holds it. Inside a class, a quote and a named
   * character other than {@code SOLIDUS} are UNKNOWN, since they may bound a range.
   */
  private static Atom escape(String regex, int at, boolean inClass) {
    int len = regex.length();
    if (at + 1 >= len) {
      return Atom.of(len, Slash.NO); // trailing backslash
    }
    char e = regex.charAt(at + 1);
    int from = at + 2;
    switch (e) {
      case 'Q':
        return quote(regex, from, inClass);
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
        return from < len ? Atom.character(from + 1, regex.charAt(from) ^ 0x40) : Atom.invalid(len);
      case 'N':
        return named(regex, from, inClass);
      case 'k':
        int close = regex.indexOf('>', from);
        return Atom.of(close < 0 ? len : close + 1, Slash.NO); // back reference
      default:
        return simpleEscape(e, from);
    }
  }

  /** Escapes of a class that may match '/'. */
  private static final String SLASH_CLASS_ESCAPES = "SWDHVX";

  /** Escapes of a class without '/', zero-width assertions and the quote end. */
  private static final String SLASH_FREE_ESCAPES = "swdhvRbBAzZGE";

  /** Control character escapes and, at the same index, their characters. */
  private static final String CONTROL_ESCAPES = "tnrfae";

  private static final String CONTROL_CHARACTERS = "\t\n\r\f\u0007\u001B";

  /** The one-character escape {@code \e}, ending at {@code end}. */
  private static Atom simpleEscape(char e, int end) {
    if (SLASH_CLASS_ESCAPES.indexOf(e) >= 0) {
      return Atom.of(end, Slash.YES);
    }
    if (SLASH_FREE_ESCAPES.indexOf(e) >= 0 || (e >= '1' && e <= '9')) {
      // a back reference's group is scanned where it is declared
      return Atom.of(end, Slash.NO);
    }
    int control = CONTROL_ESCAPES.indexOf(e);
    if (control >= 0) {
      return Atom.character(end, CONTROL_CHARACTERS.charAt(control));
    }
    // an unknown letter escape is invalid in Java; any other escaped character is itself
    boolean letter = (e >= 'a' && e <= 'z') || (e >= 'A' && e <= 'Z');
    return letter ? Atom.invalid(end) : Atom.character(end, e);
  }

  /**
   * {@code \Q..\E} quote whose text starts at {@code from}; unterminated runs to the end. Inside a
   * class it is UNKNOWN, since its last character may start a range.
   */
  private static Atom quote(String regex, int from, boolean inClass) {
    int close = regex.indexOf("\\E", from);
    int stop = close < 0 ? regex.length() : close;
    int end = close < 0 ? stop : close + 2;
    if (inClass) {
      return Atom.of(end, Slash.UNKNOWN);
    }
    int slash = regex.indexOf('/', from);
    return Atom.of(end, slash >= 0 && slash < stop ? Slash.YES : Slash.NO);
  }

  /** {@code \x{h..h}} with its brace at {@code open}. */
  private static Atom bracedHex(String regex, int open) {
    int close = regex.indexOf('}', open);
    if (close < 0 || close == open + 1 || close - open > 7) {
      return Atom.invalid(close < 0 ? regex.length() : close + 1);
    }
    Atom atom = hex(regex, open + 1, close);
    return atom.code < 0 ? Atom.invalid(close + 1) : Atom.character(close + 1, atom.code);
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
    return code > Character.MAX_CODE_POINT ? Atom.invalid(to) : Atom.character(to, code);
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
    return i == from ? Atom.invalid(i) : Atom.character(i, code);
  }

  /**
   * {@code \N{name}}: only {@code SOLIDUS} (the one name Java resolves to '/') is resolved. Any
   * other name is a character other than '/', UNKNOWN inside a class where it may bound a range.
   */
  private static Atom named(String regex, int from, boolean inClass) {
    int close = from < regex.length() && regex.charAt(from) == '{' ? regex.indexOf('}', from) : -1;
    if (close < 0) {
      return Atom.invalid(regex.length());
    }
    String name = regex.substring(from + 1, close).trim();
    if ("SOLIDUS".equalsIgnoreCase(name)) {
      return Atom.character(close + 1, '/');
    }
    return Atom.of(close + 1, inClass ? Slash.UNKNOWN : Slash.NO);
  }

  /** {@code \p{..}} or, when {@code negated}, {@code \P{..}}, with the name at {@code from}. */
  private static Atom property(String regex, int from, boolean negated) {
    int end = propertyEnd(regex, from);
    Slash slash = propertySlash(regex, from, end);
    return Atom.of(end, negated ? slash.negate() : slash);
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

  /** Properties known to exclude '/' (letters, digits, marks, whitespace). */
  private static final Set<String> SLASH_FREE_PROPERTIES =
      new HashSet<>(
          Arrays.asList(
              "L",
              "Lu",
              "Ll",
              "Lt",
              "Lm",
              "Lo",
              "N",
              "Nd",
              "Nl",
              "No",
              "M",
              "Alpha",
              "Digit",
              "Alnum",
              "Upper",
              "Lower",
              "IsAlphabetic",
              "IsLetter",
              "IsDigit",
              "IsUppercase",
              "IsLowercase",
              "javaLetter",
              "javaDigit",
              "javaLetterOrDigit",
              "javaUpperCase",
              "javaLowerCase",
              "XDigit",
              "Space",
              "IsWhite_Space"));

  /** Properties known to include '/' (punctuation Po, script Common, block Basic Latin). */
  private static final Set<String> SLASH_PROPERTIES =
      new HashSet<>(
          Arrays.asList(
              "P",
              "Po",
              "IsP",
              "IsPo",
              "gc=P",
              "gc=Po",
              "general_category=P",
              "general_category=Po",
              "Punct",
              "IsPunctuation",
              "Graph",
              "Print",
              "ASCII",
              "InBasicLatin",
              "InBasic_Latin",
              "IsCommon",
              "sc=Common",
              "script=Common",
              "IsAssigned",
              "javaDefined",
              "all"));

  /**
   * Whether the {@code \p} property in {@code [from, end)} includes '/': YES or NO for the names in
   * {@link #SLASH_PROPERTIES} and {@link #SLASH_FREE_PROPERTIES}, UNKNOWN for any other name or
   * alias.
   */
  private static Slash propertySlash(String regex, int from, int end) {
    String name = regex.substring(from, end);
    if (name.startsWith("{")) {
      name = name.substring(1, name.length() - (name.endsWith("}") ? 1 : 0));
    }
    if (SLASH_PROPERTIES.contains(name)) {
      return Slash.YES;
    }
    return SLASH_FREE_PROPERTIES.contains(name) ? Slash.NO : Slash.UNKNOWN;
  }

  /** Whether a regex element may match '/'; UNKNOWN when the scan cannot tell. */
  private enum Slash {
    YES,
    NO,
    UNKNOWN;

    /** The answer for the complement: YES and NO swap, UNKNOWN stays UNKNOWN. */
    Slash negate() {
      if (this == UNKNOWN) {
        return UNKNOWN;
      }
      return this == YES ? NO : YES;
    }

    /** The answer for the union with {@code other}: YES if either is YES, then UNKNOWN, then NO. */
    Slash or(Slash other) {
      if (this == YES || other == YES) {
        return YES;
      }
      return this == UNKNOWN || other == UNKNOWN ? UNKNOWN : NO;
    }
  }

  /**
   * One element of a regex: where it ends, its character code when it is a single known character
   * ({@link #NO_CODE} otherwise) and whether it may match '/'.
   */
  private static final class Atom {
    /** Not a single known character. */
    static final int NO_CODE = -1;

    final int end;
    final int code;
    final Slash slash;

    private Atom(int end, int code, Slash slash) {
      this.end = end;
      this.code = code;
      this.slash = slash;
    }

    static Atom character(int end, int code) {
      return new Atom(end, code, code == '/' ? Slash.YES : Slash.NO);
    }

    static Atom of(int end, Slash slash) {
      return new Atom(end, NO_CODE, slash);
    }

    /** An invalid construct: UNKNOWN, so that a negated class does not flip it. */
    static Atom invalid(int end) {
      return of(end, Slash.UNKNOWN);
    }

    /** A single character, known or UNKNOWN, may start a range; a class escape may not. */
    boolean mayBoundRange() {
      return code >= 0 || slash == Slash.UNKNOWN;
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

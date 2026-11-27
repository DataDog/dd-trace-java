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
 * Turns a Ratpack {@code PathBinding.getDescription()} (the whole {@code prefix(...)} chain joined
 * with {@code /}) into a {@link RouteTemplate}. Tokens mirror Ratpack's {@code
 * DefaultPathBinderBuilder} placeholder rules, scanned by hand, and only start a segment:
 *
 * <ul>
 *   <li>{@code :name} (name made of {@code [A-Za-z0-9_]}): parameter, optionally followed by static
 *       text in the same segment ({@code :name.json});
 *   <li>{@code :name?}: optional parameter, absent together with its {@code /};
 *   <li>{@code :name:regex} and {@code :name?:regex} (regex up to the next {@code /}): the same
 *       parameters, regex removed;
 *   <li>{@code ::regex}: unnamed parameter;
 *   <li>everything else, including {@code :} that does not start a token ({@code :id:} before
 *       {@code /} is {@code :id} plus {@code :}, {@code ::} alone is static): static text,
 *       percent-decoded because Ratpack matches the raw request path.
 * </ul>
 *
 * Empty segments are ignored and a trailing {@code /} is kept. Render with {@link
 * RatpackPresenceResolver}, which takes the remainder catch-all presence straight from the past
 * binding.
 *
 * <p>Limitations:
 *
 * <ul>
 *   <li>an optional token followed by text in the same segment ({@code :fmt?.json}) gives {@link
 *       RouteTemplate#NOT_EXPRESSIBLE}, since Ratpack makes its {@code /} optional too;
 *   <li>an optional token sharing its name with another parameter of the description ({@code
 *       prefix(":id"){get(":id?")}} gives {@code :id/:id?}) gives {@link
 *       RouteTemplate#NOT_EXPRESSIBLE}: {@code getAllTokens()} merges the parent and child values,
 *       so the presence of the optional one cannot be known;
 *   <li>{@code /x/} under {@code prefix("x"){all{}}} renders {@code /x} because Ratpack then
 *       reports an empty past binding;
 *   <li>programmatic binders ({@code PathBinder.builder()}) can produce descriptions the string
 *       grammar cannot: a {@code literal(":name")} is parsed as the token {@code :name}, and a
 *       token regex or {@code literalPattern} containing {@code /} is cut into segments;
 *   <li>a user regex with capturing groups shifts Ratpack's own token group indexes, so presence
 *       follows the values Ratpack binds;
 *   <li>stacked empty bindings ({@code prefix("users"){prefix(""){get{}}}}) give {@code users//},
 *       like {@code prefix("users/"){get{}}}, and render the declared-looking trailing {@code /}.
 * </ul>
 */
public final class RatpackRouteParser {

  private static final Logger log = LoggerFactory.getLogger(RatpackRouteParser.class);

  /** One slot per (emptyInnerPath, hasRemainder) combination, see {@link #slot}. */
  private static final int SLOTS = 4;

  /**
   * Route descriptions are declared in code; 256 per slot, like the single Spring cache, since the
   * exact-route slot holds nearly every route.
   */
  private static final int CACHE_CAPACITY = 256;

  /** One cache per slot, indexed by {@link #slot}. */
  @SuppressWarnings("unchecked")
  private static final DDCache<String, RouteTemplate>[] CACHES = new DDCache[SLOTS];

  @SuppressWarnings("unchecked")
  private static final Function<String, RouteTemplate>[] PRODUCERS = new Function[SLOTS];

  static {
    for (int s = 0; s < SLOTS; s++) {
      final boolean emptyInnerPath = (s & 1) != 0;
      final boolean hasRemainder = (s & 2) != 0;
      CACHES[s] = DDCaches.newFixedSizeCache(CACHE_CAPACITY);
      PRODUCERS[s] = safely(description -> parse(description, emptyInnerPath, hasRemainder));
    }
  }

  private RatpackRouteParser() {}

  /**
   * Same as {@link #parse(String, boolean, boolean)} but backed by bounded caches keyed by the
   * description, for the request hot path. Never returns null or throws.
   */
  public static RouteTemplate cached(
      String description, boolean emptyInnerPath, boolean hasRemainder) {
    int slot = slot(emptyInnerPath, hasRemainder);
    return cached(CACHES[slot], description, PRODUCERS[slot]);
  }

  @VisibleForTesting
  static RouteTemplate cached(
      DDCache<String, RouteTemplate> cache,
      String description,
      Function<String, RouteTemplate> producer) {
    // null cannot be a cache key
    if (description == null) {
      return RouteTemplate.NOT_EXPRESSIBLE;
    }
    try {
      return cache.computeIfAbsent(description, producer);
    } catch (Throwable t) {
      // an Error may be transient (e.g. OutOfMemoryError): the cache stores nothing when the
      // producer throws, so the next request retries
      log.debug("Failed to parse Ratpack route {}", description, t);
      return RouteTemplate.NOT_EXPRESSIBLE;
    }
  }

  /**
   * Wraps {@code parser} so that a {@link RuntimeException} becomes a cached {@link
   * RouteTemplate#NOT_EXPRESSIBLE}: parsing is deterministic. Errors propagate so that {@link
   * #cached} does not cache them.
   */
  @VisibleForTesting
  static Function<String, RouteTemplate> safely(final Function<String, RouteTemplate> parser) {
    return description -> {
      try {
        return parser.apply(description);
      } catch (RuntimeException e) {
        log.debug("Failed to parse Ratpack route {}", description, e);
        return RouteTemplate.NOT_EXPRESSIBLE;
      }
    };
  }

  /**
   * Parses a Ratpack path binding description in a single pass.
   *
   * @param description {@code PathBinding.getDescription()}, with or without a leading {@code /};
   *     {@code ""} (root {@code get("")}) renders {@code /}
   * @param emptyInnerPath {@code PathBinding.getBoundTo().isEmpty()}: a final {@code /} is then the
   *     join artifact of {@code get(handler)} under a prefix and is dropped once
   * @param hasRemainder {@code !PathBinding.getPastBinding().isEmpty()} ({@code prefix(x){all{}}}
   *     with an unconsumed rest): adds a trailing unnamed catch-all, whose presence comes from the
   *     past binding (see {@link RatpackPresenceResolver}), and no declared trailing {@code /}
   * @return the template, or {@link RouteTemplate#NOT_EXPRESSIBLE} for null or unsupported input
   */
  public static RouteTemplate parse(
      String description, boolean emptyInnerPath, boolean hasRemainder) {
    if (description == null) {
      return RouteTemplate.NOT_EXPRESSIBLE;
    }
    int end = description.length();
    if (emptyInnerPath && end > 0 && description.charAt(end - 1) == '/') {
      end--;
    }
    boolean trailingSlash = !hasRemainder && end > 0 && description.charAt(end - 1) == '/';
    RouteTemplate.Builder builder = RouteTemplate.builder();
    List<RoutePart> parts = new ArrayList<>(2);
    List<String> names = new ArrayList<>(2);
    List<String> optionalNames = new ArrayList<>(1);
    int start = 0;
    while (start < end) {
      int slash = description.indexOf('/', start);
      int segmentEnd = slash < 0 || slash > end ? end : slash;
      if (segmentEnd > start) {
        if (!segment(description, start, segmentEnd, parts, names, optionalNames)) {
          return RouteTemplate.NOT_EXPRESSIBLE;
        }
        builder.segment(parts.toArray(new RoutePart[0]));
        parts.clear();
      }
      start = segmentEnd + 1;
    }
    if (hasRemainder) {
      builder.segment(catchAll(null));
    }
    return builder.trailingSlash(trailingSlash).build();
  }

  /**
   * Adds the parts of the non-empty segment {@code [start, end)}, applying Ratpack's placeholder
   * alternation in order: {@code :name[?]:regex}, then {@code ::regex}, then {@code :name[?]}.
   * Returns false when the segment cannot be expressed. {@code names} and {@code optionalNames}
   * collect the token names seen so far in the description.
   */
  private static boolean segment(
      String description,
      int start,
      int end,
      List<RoutePart> parts,
      List<String> names,
      List<String> optionalNames) {
    if (description.charAt(start) != ':' || start + 1 == end) {
      parts.add(decodedLiteral(description, start, end));
      return true;
    }
    int nameStart = start + 1;
    if (description.charAt(nameStart) == ':') {
      if (nameStart + 1 < end) {
        // ::regex
        parts.add(param(null));
      } else {
        parts.add(decodedLiteral(description, start, end));
      }
      return true;
    }
    int nameEnd = nameStart;
    while (nameEnd < end && isWordChar(description.charAt(nameEnd))) {
      nameEnd++;
    }
    if (nameEnd == nameStart) {
      parts.add(decodedLiteral(description, start, end));
      return true;
    }
    String name = description.substring(nameStart, nameEnd);
    boolean optional = nameEnd < end && description.charAt(nameEnd) == '?';
    int tokenEnd = optional ? nameEnd + 1 : nameEnd;
    if (!register(name, optional, names, optionalNames)) {
      return false;
    }
    if (tokenEnd + 1 < end && description.charAt(tokenEnd) == ':') {
      // :name:regex or :name?:regex, the regex runs to the end of the segment
      parts.add(optional ? optionalParam(name) : param(name));
      return true;
    }
    if (optional) {
      if (tokenEnd < end) {
        // Ratpack makes the "/" optional with the token: absent, the text would join the
        // previous segment, which the segment model cannot express
        return false;
      }
      parts.add(optionalParam(name));
      return true;
    }
    parts.add(param(name));
    if (tokenEnd < end) {
      parts.add(decodedLiteral(description, tokenEnd, end));
    }
    return true;
  }

  /**
   * Records a token name. Returns false when an optional token shares its name with any other
   * token: {@code getAllTokens()} merges the values of both, so the optional one's presence is
   * unknown. Repeated required names are fine.
   */
  private static boolean register(
      String name, boolean optional, List<String> names, List<String> optionalNames) {
    if (optionalNames.contains(name) || (optional && names.contains(name))) {
      return false;
    }
    names.add(name);
    if (optional) {
      optionalNames.add(name);
    }
    return true;
  }

  private static RoutePart decodedLiteral(String description, int start, int end) {
    return literal(RouteEncoding.decodePath(description.substring(start, end)));
  }

  /** Java regex {@code \w} without UNICODE_CHARACTER_CLASS. */
  private static boolean isWordChar(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
  }

  private static int slot(boolean emptyInnerPath, boolean hasRemainder) {
    return (emptyInnerPath ? 1 : 0) | (hasRemainder ? 2 : 0);
  }
}

package datadog.trace.api.appsec.route;

import datadog.trace.api.appsec.route.VertxRouteParser.Grammar;
import datadog.trace.api.internal.VisibleForTesting;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point of the Vert.x web integrations for {@code _dd.appsec.normalized_route}: they collect
 * plain values from the {@code RoutingContext} and this class builds the template, the mount prefix
 * and the catch-all presence. The catch-all remainder follows Vert.x' own matching: {@code
 * startsWith} for routes without parameters, the {@code [^/]+} segments of the compiled regex
 * otherwise, with the mount joined as {@code RouteState.pathMatches} does.
 */
public final class VertxNormalizedRoute {

  private static final Logger log = LoggerFactory.getLogger(VertxNormalizedRoute.class);

  /** System property that selects the {@link Grammar#EXTENDED} name grammar on Vert.x 4.0.3+. */
  private static final String EXTENDED_PATTERN_PROPERTY =
      "io.vertx.web.route.param.extended-pattern";

  /** Path param Vert.x binds to the catch-all match; never used for presence. */
  private static final String REST_PARAM = "*";

  /** {@link #remainderStart} result: the request path does not match the static base. */
  private static final int UNKNOWN_REMAINDER = -1;

  /**
   * {@link #remainderStart} result: the full base ends inside a request segment while the {@code *}
   * is a whole segment. Vert.x matched something the template cannot describe (a mount {@code /sub}
   * without trailing {@code /} matches {@code /subway/x} for the sub-route {@code /*}).
   */
  private static final int NOT_A_SEGMENT_BOUNDARY = -2;

  /** Catch-all resolvers by {@link RoutePresence#ordinal()}, so rendering allocates none. */
  private static final PresenceResolver[] CATCH_ALL_RESOLVERS;

  static {
    RoutePresence[] values = RoutePresence.values();
    CATCH_ALL_RESOLVERS = new PresenceResolver[values.length];
    for (RoutePresence presence : values) {
      CATCH_ALL_RESOLVERS[presence.ordinal()] = new CatchAllResolver(presence);
    }
  }

  private VertxNormalizedRoute() {}

  /**
   * Replaces the normalized route tag of {@code span}: any previous value is removed first, then
   * the route is tagged when it can be expressed. Call it after {@code http.route} is set and only
   * when {@link NormalizedRouteHelper#isEnabled()}. Never throws.
   *
   * @param declaredPath route path exactly as declared by the app (with its trailing {@code *}), or
   *     null when unknown (regex route, no path)
   * @param grammar parameter name grammar: {@link Grammar#LEGACY} on 3.x, {@link #vertx4Grammar()}
   *     on 4.x and 5.x
   * @param mountPoint {@code RoutingContext.mountPoint()}, null at the top level
   * @param normalizedPath {@code RoutingContext.normalisedPath()} (3.x) or {@code normalizedPath()}
   *     (4.x, 5.x): the full request path, mount included
   * @param pathParams {@code RoutingContext.pathParams()}
   */
  public static void tag(
      AgentSpan span,
      String declaredPath,
      Grammar grammar,
      String mountPoint,
      String normalizedPath,
      Map<String, String> pathParams) {
    if (span == null) {
      return;
    }
    try {
      // a later route that cannot be expressed must not keep the tag of an earlier one
      span.setTag(NormalizedRouteHelper.NORMALIZED_ROUTE_TAG, (String) null);
      RouteTemplate template = template(declaredPath, grammar, mountPoint, pathParams);
      if (template == null) {
        return;
      }
      PresenceResolver resolver =
          resolver(template, declaredPath, grammar, mountPoint, normalizedPath);
      if (resolver != null) {
        NormalizedRouteHelper.tag(
            span, template, NormalizedRouteHelper.decodePath(mountPoint), resolver);
      }
    } catch (Throwable t) {
      log.debug("Failed to set the Vert.x normalized route tag", t);
    }
  }

  /** Same computation as {@link #tag}; returns the rendered route, or null when there is no tag. */
  @VisibleForTesting
  static String render(
      String declaredPath,
      Grammar grammar,
      String mountPoint,
      String normalizedPath,
      Map<String, String> pathParams) {
    RouteTemplate template = template(declaredPath, grammar, mountPoint, pathParams);
    if (template == null) {
      return null;
    }
    PresenceResolver resolver =
        resolver(template, declaredPath, grammar, mountPoint, normalizedPath);
    return resolver == null
        ? null
        : template.render(NormalizedRouteHelper.decodePath(mountPoint), resolver);
  }

  /**
   * Name grammar of the running Vert.x 4.x or 5.x: {@link Grammar#EXTENDED} when {@code
   * -Dio.vertx.web.route.param.extended-pattern=true}, else {@link Grammar#DEFAULT}. The property
   * is read once, as Vert.x does.
   */
  public static Grammar vertx4Grammar() {
    return Vertx4GrammarHolder.GRAMMAR;
  }

  @VisibleForTesting
  static Grammar vertx4Grammar(boolean extendedPattern) {
    return extendedPattern ? Grammar.EXTENDED : Grammar.DEFAULT;
  }

  /**
   * Template to render, or null when there is no tag: the route cannot be expressed, or the mount
   * point holds request values. A mount with parameters returns the concrete matched prefix and
   * shares its {@code pathParams()} with the sub-router, so a bound parameter that the route does
   * not declare means a parameter mount (conservative: a middleware route may also have left it). A
   * root mount ({@code /}) never holds request values, so it skips that check.
   */
  private static RouteTemplate template(
      String declaredPath, Grammar grammar, String mountPoint, Map<String, String> pathParams) {
    RouteTemplate template = VertxRouteParser.cached(declaredPath, grammar);
    if (!template.isExpressible()) {
      return null;
    }
    if (pathParams != null && !isRootMount(mountPoint)) {
      for (String name : pathParams.keySet()) {
        if (!REST_PARAM.equals(name)
            && !VertxRouteParser.declaresParam(declaredPath, name, grammar)) {
          return null;
        }
      }
    }
    return template;
  }

  /** Whether there is no mount, or the mount is {@code /} (empty once its trailing '/' is gone). */
  private static boolean isRootMount(String mountPoint) {
    return mountPoint == null
        || mountPoint.isEmpty()
        || (mountPoint.length() == 1 && mountPoint.charAt(0) == '/');
  }

  /**
   * Presence of the trailing catch-all ({@link PresenceResolver#UNKNOWN} for templates that do not
   * depend on the request), or null when there is no tag.
   */
  private static PresenceResolver resolver(
      RouteTemplate template,
      String declaredPath,
      Grammar grammar,
      String mountPoint,
      String normalizedPath) {
    // the trailing '*' is the only optional part of a Vert.x template
    if (template.staticRoute() != null) {
      return PresenceResolver.UNKNOWN;
    }
    int start = remainderStart(declaredPath, grammar, mountPoint, normalizedPath);
    if (start == NOT_A_SEGMENT_BOUNDARY) {
      return null;
    }
    return catchAllResolver(
        start == UNKNOWN_REMAINDER ? RoutePresence.UNKNOWN : presenceFrom(normalizedPath, start));
  }

  /**
   * Same as {@link RoutePresence#ofCatchAllValue} for {@code path.substring(start)}, without the
   * copy.
   */
  private static RoutePresence presenceFrom(String path, int start) {
    int length = path.length();
    if (start == length) {
      return RoutePresence.ABSENT;
    }
    for (int i = start; i < length; i++) {
      if (path.charAt(i) != '/') {
        return RoutePresence.PRESENT;
      }
    }
    return RoutePresence.ABSENT_TRAILING_SLASH;
  }

  /** Shared resolver that answers {@code presence} for the unnamed catch-all. */
  @VisibleForTesting
  static PresenceResolver catchAllResolver(RoutePresence presence) {
    return CATCH_ALL_RESOLVERS[presence.ordinal()];
  }

  /**
   * Index of {@code normalizedPath} where the trailing {@code *} match starts, {@link
   * #UNKNOWN_REMAINDER} when the path does not match the base, or {@link #NOT_A_SEGMENT_BOUNDARY}.
   * The static base is the declared path without the {@code *} and the {@code /} right before it;
   * the full base is the mount (without its trailing {@code /}) followed by the base, which is
   * Vert.x' join: {@code mount + path.substring(1)} for a mount ending with {@code /}, the mount
   * alone for the route path {@code /}, else {@code mount + path}.
   */
  private static int remainderStart(
      String declaredPath, Grammar grammar, String mountPoint, String normalizedPath) {
    if (normalizedPath == null) {
      return UNKNOWN_REMAINDER;
    }
    int baseEnd = declaredPath.length() - 1;
    boolean wholeSegment = baseEnd > 0 && declaredPath.charAt(baseEnd - 1) == '/';
    if (wholeSegment) {
      baseEnd--;
    }
    int mountLength = mountPoint == null ? 0 : mountPoint.length();
    if (mountLength > 0 && mountPoint.charAt(mountLength - 1) == '/') {
      mountLength--;
    }
    if (mountLength > 0 && !normalizedPath.regionMatches(0, mountPoint, 0, mountLength)) {
      return UNKNOWN_REMAINDER;
    }
    int start;
    if (declaredPath.indexOf(':') < 0) {
      // no parameter: Vert.x matches the joined path with startsWith
      if (!normalizedPath.regionMatches(mountLength, declaredPath, 0, baseEnd)) {
        return UNKNOWN_REMAINDER;
      }
      start = mountLength + baseEnd;
    } else {
      start =
          skipSegments(declaredPath, baseEnd, wholeSegment, grammar, normalizedPath, mountLength);
      if (start < 0) {
        return UNKNOWN_REMAINDER;
      }
    }
    if (wholeSegment && start < normalizedPath.length() && normalizedPath.charAt(start) != '/') {
      // "/a/*" never matches "/ab" by itself; a mount without trailing '/' can make it match
      return NOT_A_SEGMENT_BOUNDARY;
    }
    return start;
  }

  /**
   * Mirrors the regex Vert.x compiles for a parameter path: each declared segment of the base is a
   * single request segment (parameters match {@code [^/]+}, literals hold no {@code /}). For an
   * in-segment {@code *} ({@code /u/:id*}), the last segment is matched as the greedy regex does.
   * Returns the index after the base, or -1 when the path does not match it.
   */
  private static int skipSegments(
      String declaredPath,
      int baseEnd,
      boolean wholeSegment,
      Grammar grammar,
      String path,
      int position) {
    int pathLength = path.length();
    int segmentStart = 0;
    while (segmentStart < baseEnd) {
      // invariant: every declared segment starts with '/' (parse rejects relative paths)
      if (position >= pathLength || path.charAt(position) != '/') {
        return -1;
      }
      int segmentEnd = declaredPath.indexOf('/', segmentStart + 1);
      if (segmentEnd < 0 || segmentEnd > baseEnd) {
        segmentEnd = baseEnd;
      }
      int pathSegmentEnd = path.indexOf('/', position + 1);
      if (pathSegmentEnd < 0) {
        pathSegmentEnd = pathLength;
      }
      if (segmentEnd == baseEnd && !wholeSegment) {
        return partialSegmentEnd(
            declaredPath, segmentStart + 1, baseEnd, grammar, path, position + 1, pathSegmentEnd);
      }
      position = pathSegmentEnd;
      segmentStart = segmentEnd;
    }
    return position;
  }

  /**
   * Where {@code .*} starts after the last declared segment {@code [from, to)} of an in-segment
   * {@code *}, matched against the request segment {@code [pathFrom, pathTo)}. Greedy {@code [^/]+}
   * parameters take as much of the segment as they can: after a final parameter the match starts at
   * the end of the segment, after a final literal at its last occurrence. A segment without
   * parameter must start with its literal.
   */
  private static int partialSegmentEnd(
      String declaredPath,
      int from,
      int to,
      Grammar grammar,
      String path,
      int pathFrom,
      int pathTo) {
    int lastParamEnd = -1;
    for (int i = declaredPath.indexOf(':', from);
        i >= 0 && i < to;
        i = declaredPath.indexOf(':', i + 1)) {
      int nameEnd = VertxRouteParser.nameEnd(declaredPath, i, to, grammar);
      if (nameEnd >= 0) {
        lastParamEnd = nameEnd;
      }
    }
    if (lastParamEnd < 0) {
      // a literal holds no '/', so it can only match inside the segment
      int length = to - from;
      return path.regionMatches(pathFrom, declaredPath, from, length) ? pathFrom + length : -1;
    }
    if (lastParamEnd == to) {
      return pathTo;
    }
    int literalLength = to - lastParamEnd;
    // the parameters before the literal match at least one char
    for (int i = pathTo - literalLength; i > pathFrom; i--) {
      if (path.regionMatches(i, declaredPath, lastParamEnd, literalLength)) {
        return i + literalLength;
      }
    }
    return -1;
  }

  /** Presence of the unnamed catch-all; every other optional part is unknown. */
  private static final class CatchAllResolver implements PresenceResolver {
    private final RoutePresence presence;

    CatchAllResolver(RoutePresence presence) {
      this.presence = presence;
    }

    @Override
    public RoutePresence presenceOf(RoutePart.Param param) {
      return param.isCatchAll() && param.frameworkName() == null ? presence : RoutePresence.UNKNOWN;
    }

    @Override
    public RoutePresence presenceOf(RoutePart.Group group) {
      return RoutePresence.UNKNOWN;
    }
  }

  /** Reads {@link #EXTENDED_PATTERN_PROPERTY} on first use only, like Vert.x' own static init. */
  private static final class Vertx4GrammarHolder {
    static final Grammar GRAMMAR = vertx4Grammar(Boolean.getBoolean(EXTENDED_PATTERN_PROPERTY));
  }
}

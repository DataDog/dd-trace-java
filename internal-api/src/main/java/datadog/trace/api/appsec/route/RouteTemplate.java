package datadog.trace.api.appsec.route;

import static java.util.Arrays.asList;

import datadog.trace.api.internal.VisibleForTesting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable, framework-agnostic route template used to compute {@code _dd.appsec.normalized_route}
 * (RFC-1125). Framework tokenizers build it once per declared route so that every RFC rule lives in
 * one renderer; names and literal encoding are resolved at build time, so a cached template only
 * needs the per-request {@link PresenceResolver} to render. A segment made of a single
 * whole-segment {@link RoutePart.Group} is optional and takes its leading {@code /} with it.
 */
public final class RouteTemplate {

  /**
   * Marker for a route that cannot be expressed as segments and parameters: renders null. Callers
   * compare by identity; its {@link #staticRoute()} is null.
   */
  public static final RouteTemplate NOT_EXPRESSIBLE =
      new RouteTemplate(new RoutePart[0][], false, Collections.<RoutePart.Param>emptyList());

  private final RoutePart[][] segments;
  private final boolean trailingSlash;
  private final List<RoutePart.Param> params;

  /** Pre-rendered body when the template has no optional part and renders non-empty. */
  private final String staticRoute;

  /** Unnamed catch-all that may take its presence from the matcher remainder; null otherwise. */
  private final RoutePart.Param trailingUnnamedCatchAll;

  private RouteTemplate(
      RoutePart[][] segments, boolean trailingSlash, List<RoutePart.Param> params) {
    this.segments = segments;
    this.trailingSlash = trailingSlash;
    this.params = params;
    String body = hasOptionalParts(segments) ? null : renderBody(PresenceResolver.UNKNOWN);
    this.staticRoute = body == null || body.isEmpty() ? null : body;
    this.trailingUnnamedCatchAll = findTrailingUnnamedCatchAll(segments, params);
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Renders the normalized route for the current request.
   *
   * @param prefix mount prefix already served before the route (e.g. {@code /ctx}), decoded; null
   *     or empty for none. It is encoded with the same literal rules, keeping {@code /} as
   *     separator.
   * @param resolver presence of optional parts; null means every optional part is unknown
   * @return the normalized route, or null when the route cannot be expressed (no tag)
   */
  public String render(String prefix, PresenceResolver resolver) {
    if (this == NOT_EXPRESSIBLE) {
      return null;
    }
    String body =
        staticRoute != null
            ? staticRoute
            : renderBody(resolver == null ? PresenceResolver.UNKNOWN : resolver);
    String normalizedPrefix = RouteEncoding.normalizePrefix(prefix);
    if (body.isEmpty()) {
      // every segment absent: "/ctx" under a prefix, "/" otherwise
      return normalizedPrefix.isEmpty() ? "/" : normalizedPrefix;
    }
    return normalizedPrefix.isEmpty() ? body : normalizedPrefix + body;
  }

  /**
   * Pre-rendered route (without prefix) when it does not depend on the request, else null. Always
   * null for {@link #NOT_EXPRESSIBLE}.
   */
  public String staticRoute() {
    return this == NOT_EXPRESSIBLE ? null : staticRoute;
  }

  public boolean isExpressible() {
    return this != NOT_EXPRESSIBLE;
  }

  /**
   * The unnamed catch-all whose presence comes from the path remainder the framework matched, or
   * null; see {@link #needsUnnamedCatchAllRemainder()}.
   */
  RoutePart.Param trailingUnnamedCatchAll() {
    return trailingUnnamedCatchAll;
  }

  /**
   * True when callers should compute the matcher remainder for a trailing {@code **} (only then is
   * it used). The {@code **} must be the last segment, optionally followed by the declared trailing
   * {@code /} (the matcher ignores empty segments, so {@code /files/**}{@code /} still ends with
   * {@code **}). The remainder must follow {@code AntPathMatcher.extractPathWithinPattern}
   * semantics, which start at the first raw pattern segment with {@code *} or {@code ?} (also
   * inside {@code {...}}), not {@code PathPattern} ones (first capture): so it equals the {@code
   * **} match only when no earlier parameter is unnamed, a catch-all, regex-constrained or has a
   * {@code *} or {@code ?} in its name, while plain named params ({@code {id}}) before it are fine.
   * Literals never hold {@code *} or {@code ?} here: the Spring parser turns them into parameters.
   */
  public boolean needsUnnamedCatchAllRemainder() {
    return trailingUnnamedCatchAll != null;
  }

  /** Resolved parameters in path order. */
  @VisibleForTesting
  List<RoutePart.Param> params() {
    return params;
  }

  @Override
  public String toString() {
    if (this == NOT_EXPRESSIBLE) {
      return "RouteTemplate[not expressible]";
    }
    StringBuilder sb = new StringBuilder();
    for (RoutePart[] segment : segments) {
      if (!isOptionalSegment(segment)) {
        sb.append('/');
      }
      for (RoutePart part : segment) {
        sb.append(part);
      }
    }
    if (trailingSlash || sb.length() == 0) {
      sb.append('/');
    }
    return sb.toString();
  }

  /**
   * Route body without prefix; "" when the template has segments but every one rendered empty and
   * nothing keeps a {@code /} ({@link #render} turns it into the prefix alone, or {@code /}).
   */
  private String renderBody(PresenceResolver resolver) {
    StringBuilder sb = new StringBuilder(32);
    boolean emptyTail = false;
    for (RoutePart[] segment : segments) {
      int mark = sb.length();
      boolean keepSlash = false;
      if (isOptionalSegment(segment)) {
        RoutePart.Group group = (RoutePart.Group) segment[0];
        if (resolver.presenceOf(group) != RoutePresence.PRESENT) {
          // a catch-all that matched "/" keeps its slash only when nothing follows it
          emptyTail = false;
          continue;
        }
        sb.append('/');
        appendParts(group.parts(), sb, resolver);
      } else {
        sb.append('/');
        for (RoutePart part : segment) {
          keepSlash |= appendPart(part, sb, resolver);
        }
      }
      // empty segment: never emit "//"; only a catch-all that matched "/" keeps a trailing "/"
      emptyTail = sb.length() == mark + 1 && keepSlash;
      if (sb.length() == mark + 1) {
        sb.setLength(mark);
      }
    }
    if (trailingSlash || emptyTail || segments.length == 0) {
      sb.append('/');
    }
    return sb.toString();
  }

  private static void appendParts(List<RoutePart> parts, StringBuilder sb, PresenceResolver r) {
    for (RoutePart part : parts) {
      appendPart(part, sb, r);
    }
  }

  /**
   * Appends one part. Returns true when it is a catch-all that matched only {@code /} ({@link
   * RoutePresence#ABSENT_TRAILING_SLASH}), so that an otherwise empty segment keeps a trailing
   * {@code /}.
   */
  private static boolean appendPart(RoutePart part, StringBuilder sb, PresenceResolver resolver) {
    if (part instanceof RoutePart.Literal) {
      sb.append(((RoutePart.Literal) part).encoded());
    } else if (part instanceof RoutePart.Param) {
      RoutePart.Param param = (RoutePart.Param) part;
      RoutePresence presence =
          param.isOptional() ? resolver.presenceOf(param) : RoutePresence.PRESENT;
      if (presence == RoutePresence.PRESENT) {
        sb.append('{').append(param.name()).append('}');
        return false;
      }
      return param.isCatchAll() && presence == RoutePresence.ABSENT_TRAILING_SLASH;
    } else {
      RoutePart.Group group = (RoutePart.Group) part;
      if (resolver.presenceOf(group) == RoutePresence.PRESENT) {
        appendParts(group.parts(), sb, resolver);
      }
    }
    return false;
  }

  /** See {@link #needsUnnamedCatchAllRemainder()} for the rule. */
  private static RoutePart.Param findTrailingUnnamedCatchAll(
      RoutePart[][] segments, List<RoutePart.Param> params) {
    // a declared trailing "/" is an empty segment for the matcher: "**" is still the last one
    if (segments.length == 0) {
      return null;
    }
    RoutePart[] last = segments[segments.length - 1];
    if (last.length != 1 || !(last[0] instanceof RoutePart.Param)) {
      return null;
    }
    RoutePart.Param candidate = (RoutePart.Param) last[0];
    if (!candidate.isCatchAll() || candidate.frameworkName() != null) {
      return null;
    }
    for (RoutePart.Param param : params) {
      // the candidate is the last param: every other one comes before it
      if (param != candidate && isMatcherWildcard(param)) {
        return null;
      }
    }
    return candidate;
  }

  /**
   * True when the raw declaration of {@code param} makes {@code AntPathMatcher} start extracting
   * the remainder at its segment: unnamed ({@code *}, {@code ?}, {@code **}), a catch-all ({@code
   * {*name}} is a single-segment variable in Ant), a regex constraint, or a name with {@code *} or
   * {@code ?} (e.g. {@code {file?}}).
   */
  private static boolean isMatcherWildcard(RoutePart.Param param) {
    String name = param.frameworkName();
    return name == null
        || param.isCatchAll()
        || param.hasRegexConstraint()
        || name.indexOf('*') >= 0
        || name.indexOf('?') >= 0;
  }

  private static boolean isOptionalSegment(RoutePart[] segment) {
    return segment.length == 1
        && segment[0] instanceof RoutePart.Group
        && ((RoutePart.Group) segment[0]).isWholeSegment();
  }

  private static boolean hasOptionalParts(RoutePart[][] segments) {
    for (RoutePart[] segment : segments) {
      for (RoutePart part : segment) {
        if (part instanceof RoutePart.Group
            || (part instanceof RoutePart.Param && ((RoutePart.Param) part).isOptional())) {
          return true;
        }
      }
    }
    return false;
  }

  /** Builds a {@link RouteTemplate}. Not thread-safe. */
  public static final class Builder {
    private final List<RoutePart[]> segments = new ArrayList<>();
    private boolean trailingSlash;

    Builder() {}

    /**
     * Adds a segment (text between two {@code /}). Literals must not contain {@code /}: it is
     * encoded as {@code %2F}. A segment without parts is ignored (no empty segments).
     */
    public Builder segment(RoutePart... parts) {
      RoutePart[] copy = nonNull(parts);
      if (copy.length > 0) {
        segments.add(copy);
      }
      return this;
    }

    /**
     * Adds an optional segment including its leading {@code /}, e.g. {@code [/preview]}. Ignored
     * without parts.
     */
    public Builder optionalSegment(RoutePart... parts) {
      RoutePart[] copy = nonNull(parts);
      if (copy.length > 0) {
        segments.add(
            new RoutePart[] {new RoutePart.Group(asList(copy), true, RoutePart.UNRESOLVED)});
      }
      return this;
    }

    /** The declared route ends with {@code /}. */
    public Builder trailingSlash(boolean trailingSlash) {
      this.trailingSlash = trailingSlash;
      return this;
    }

    public RouteTemplate build() {
      // pass 1: collect every parameter in path order to resolve names against the whole route
      List<RoutePart.Param> declared = new ArrayList<>();
      for (RoutePart[] segment : segments) {
        collectParams(asList(segment), declared);
      }
      Resolver resolver = new Resolver(RouteNames.resolveNames(declared));
      // pass 2: copy into resolved parts
      RoutePart[][] resolved = new RoutePart[segments.size()][];
      for (int s = 0; s < resolved.length; s++) {
        RoutePart[] segment = segments.get(s);
        resolved[s] = new RoutePart[segment.length];
        for (int p = 0; p < segment.length; p++) {
          resolved[s][p] = resolver.resolve(segment[p]);
        }
      }
      return new RouteTemplate(
          resolved, trailingSlash, Collections.unmodifiableList(resolver.params));
    }

    /** Copy of {@code parts} without null elements; tokenizers must not make the build throw. */
    private static RoutePart[] nonNull(RoutePart[] parts) {
      if (parts == null) {
        return new RoutePart[0];
      }
      int count = 0;
      for (RoutePart part : parts) {
        if (part != null) {
          count++;
        }
      }
      RoutePart[] copy = new RoutePart[count];
      int i = 0;
      for (RoutePart part : parts) {
        if (part != null) {
          copy[i++] = part;
        }
      }
      return copy;
    }

    private static void collectParams(List<RoutePart> parts, List<RoutePart.Param> out) {
      for (RoutePart part : parts) {
        if (part instanceof RoutePart.Param) {
          out.add((RoutePart.Param) part);
        } else if (part instanceof RoutePart.Group) {
          collectParams(((RoutePart.Group) part).parts(), out);
        }
      }
    }
  }

  /** Copies unresolved parts into resolved ones, assigning names and indexes in path order. */
  private static final class Resolver {
    private final String[] names;
    final List<RoutePart.Param> params = new ArrayList<>();
    private int groupIndex;

    Resolver(String[] names) {
      this.names = names;
    }

    RoutePart resolve(RoutePart part) {
      if (part instanceof RoutePart.Literal) {
        String text = ((RoutePart.Literal) part).text();
        return new RoutePart.Literal(text, RouteEncoding.encodeLiteral(text));
      }
      if (part instanceof RoutePart.Param) {
        RoutePart.Param p = (RoutePart.Param) part;
        int index = params.size();
        RoutePart.Param resolved =
            new RoutePart.Param(
                p.frameworkName(),
                p.isOptional(),
                p.isCatchAll(),
                p.hasRegexConstraint(),
                index,
                names[index]);
        params.add(resolved);
        return resolved;
      }
      RoutePart.Group g = (RoutePart.Group) part;
      int index = groupIndex++;
      List<RoutePart> children = new ArrayList<>(g.parts().size());
      for (RoutePart child : g.parts()) {
        children.add(resolve(child));
      }
      return new RoutePart.Group(children, g.isWholeSegment(), index);
    }
  }
}

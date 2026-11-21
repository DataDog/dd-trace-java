package datadog.trace.api.appsec.route;

import static java.util.Arrays.asList;

import datadog.trace.api.internal.VisibleForTesting;
import java.util.Collections;
import java.util.List;

/**
 * A part of a route segment: static text ({@link Literal}), a parameter ({@link Param}) or an
 * optional group ({@link Group}). Parts created through the static factories are descriptions;
 * {@link RouteTemplate.Builder#build()} copies them into resolved parts (encoded text, final
 * parameter names, path-order indexes), which are the instances handed to a {@link
 * PresenceResolver}.
 */
public abstract class RoutePart {

  /** Index of a part that is not yet resolved by {@link RouteTemplate.Builder#build()}. */
  static final int UNRESOLVED = -1;

  RoutePart() {}

  /** Static text, as the framework declares it (not yet percent-encoded). */
  public static Literal literal(String text) {
    return new Literal(text, null);
  }

  /** A required parameter; a null or empty name means unnamed ({@code param<N>}). */
  public static Param param(String frameworkName) {
    return new Param(frameworkName, false, false, UNRESOLVED, null);
  }

  /** An optional parameter, without separator (RFC notation {@code {name?}} minus separator). */
  public static Param optionalParam(String frameworkName) {
    return new Param(frameworkName, true, false, UNRESOLVED, null);
  }

  /** A catch-all parameter (always optional, rendered as one parameter even if it spans "/"). */
  public static Param catchAll(String frameworkName) {
    return new Param(frameworkName, true, true, UNRESOLVED, null);
  }

  /** An optional group inside one segment, e.g. {@code (.{format})} or {@code .{ext?}}. */
  public static Group group(RoutePart... parts) {
    return new Group(asList(parts), false, UNRESOLVED);
  }

  /** Static text. */
  public static final class Literal extends RoutePart {
    private final String text;
    private final String encoded;

    Literal(String text, String encoded) {
      this.text = text == null ? "" : text;
      this.encoded = encoded;
    }

    /** Text as declared by the framework. */
    public String text() {
      return text;
    }

    /** Percent-encoded text (null on unresolved parts). */
    public String encoded() {
      return encoded;
    }

    @Override
    public String toString() {
      return encoded != null ? encoded : text;
    }
  }

  /** A path parameter. */
  public static final class Param extends RoutePart {
    private final String frameworkName;
    private final boolean optional;
    private final boolean catchAll;
    private final int index;
    private final String name;

    Param(String frameworkName, boolean optional, boolean catchAll, int index, String name) {
      this.frameworkName = frameworkName == null || frameworkName.isEmpty() ? null : frameworkName;
      this.optional = optional || catchAll;
      this.catchAll = catchAll;
      this.index = index;
      this.name = name;
    }

    /** Name as declared by the framework, or null when unnamed. */
    public String frameworkName() {
      return frameworkName;
    }

    /** True for optional parameters and every catch-all. */
    public boolean isOptional() {
      return optional;
    }

    public boolean isCatchAll() {
      return catchAll;
    }

    /** Zero-based position among all parameters, in path order ({@link #UNRESOLVED} if not). */
    @VisibleForTesting
    int index() {
      return index;
    }

    /** Rendered name (sanitized, de-duplicated or {@code param<N>}); null on unresolved parts. */
    public String name() {
      return name;
    }

    @Override
    public String toString() {
      return "{" + (name != null ? name : frameworkName) + (optional ? "?}" : "}");
    }
  }

  /** An optional group of parts, present or absent as a whole. */
  public static final class Group extends RoutePart {
    private final List<RoutePart> parts;
    private final boolean wholeSegment;
    private final int index;

    Group(List<RoutePart> parts, boolean wholeSegment, int index) {
      this.parts = Collections.unmodifiableList(parts);
      this.wholeSegment = wholeSegment;
      this.index = index;
    }

    public List<RoutePart> parts() {
      return parts;
    }

    /**
     * True when the group includes the preceding {@code /}: it is a whole optional segment, e.g.
     * {@code [/preview]}, {@code (/page-{n})} or {@code /{x?}}.
     */
    public boolean isWholeSegment() {
      return wholeSegment;
    }

    /** Zero-based position among all groups, in path order ({@link #UNRESOLVED} if not). */
    @VisibleForTesting
    int index() {
      return index;
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder(wholeSegment ? "(/" : "(");
      for (RoutePart part : parts) {
        sb.append(part);
      }
      return sb.append(')').toString();
    }
  }
}

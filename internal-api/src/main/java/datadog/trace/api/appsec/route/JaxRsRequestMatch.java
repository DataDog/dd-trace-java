package datadog.trace.api.appsec.route;

import static java.util.Collections.singletonMap;
import static java.util.Collections.unmodifiableMap;

import datadog.trace.api.internal.VisibleForTesting;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a JAX-RS framework matched for the current request, captured by its routing advice (Jersey
 * {@code UriRoutingContext}, RESTEasy {@code ResteasyUriInfo}) and read by the JAX-RS decorators to
 * render {@code _dd.appsec.normalized_route}: the decoded mount prefix served before the route and
 * the path parameters bound from the URL. Immutable: it only holds copies, never framework objects.
 */
public final class JaxRsRequestMatch {

  /**
   * Stored instead of a match once a sub-resource locator handled the request: its route is
   * truncated, so the decorators set no normalized route and the capture advices keep this entry.
   */
  public static final JaxRsRequestMatch SUBRESOURCE = new JaxRsRequestMatch(null, null);

  private final String prefix;
  private final Map<String, String> boundValues;

  private JaxRsRequestMatch(String prefix, Map<String, String> boundValues) {
    this.prefix = prefix;
    this.boundValues = boundValues;
  }

  /**
   * @param rawBasePath raw (encoded) path of the framework's base URI, e.g. {@code /ctx/api/}, or
   *     null when there is none
   * @param boundValues path parameters bound by the framework, by name; a {@link List} value (e.g.
   *     a {@code MultivaluedMap} entry) keeps its first element. Null or empty for none.
   */
  public static JaxRsRequestMatch of(String rawBasePath, Map<String, ?> boundValues) {
    return new JaxRsRequestMatch(RouteEncoding.decodePath(rawBasePath), copyOf(boundValues));
  }

  /** Decoded mount prefix (context path, servlet mount, application path), or null for none. */
  public String prefix() {
    return prefix;
  }

  /** Bound path parameters (first value per name), or null when none were bound. */
  @VisibleForTesting
  Map<String, String> boundValues() {
    return boundValues;
  }

  /**
   * Presence of the optional parts of {@code template} from the bound values; {@link
   * PresenceResolver#UNKNOWN} when the template has none (no allocation then).
   */
  public PresenceResolver presenceFor(RouteTemplate template) {
    return BoundValuesPresenceResolver.of(template, boundValues, null);
  }

  private static Map<String, String> copyOf(Map<String, ?> values) {
    if (values == null || values.isEmpty()) {
      return null;
    }
    if (values.size() == 1) {
      Map.Entry<String, ?> entry = values.entrySet().iterator().next();
      return singletonMap(entry.getKey(), valueOf(entry.getValue()));
    }
    // initial capacity that holds every entry under the default 0.75 load factor, without a resize
    Map<String, String> copy = new HashMap<>(values.size() * 4 / 3 + 1);
    for (Map.Entry<String, ?> entry : values.entrySet()) {
      copy.put(entry.getKey(), valueOf(entry.getValue()));
    }
    return unmodifiableMap(copy);
  }

  private static String valueOf(Object value) {
    if (value instanceof List) {
      List<?> list = (List<?>) value;
      value = list.isEmpty() ? null : list.get(0);
    }
    return value == null ? null : String.valueOf(value);
  }
}

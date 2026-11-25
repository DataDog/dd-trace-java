package datadog.trace.core;

import static datadog.trace.api.KnownTags.DD_INTEGRATION;
import static datadog.trace.api.KnownTags.DD_SVC_SRC;
import static datadog.trace.api.KnownTags.HTTP_ENDPOINT;
import static datadog.trace.api.KnownTags.HTTP_METHOD;
import static datadog.trace.api.KnownTags.HTTP_ROUTE;
import static datadog.trace.api.KnownTags.HTTP_URL;
import static datadog.trace.api.KnownTags.URL_PATH;

import datadog.trace.api.endpoint.EndpointResolver;
import datadog.trace.api.naming.NamingSchema;
import datadog.trace.api.naming.PeerServiceFormulas;
import datadog.trace.bootstrap.instrumentation.api.TagDerivations;
import datadog.trace.bootstrap.instrumentation.api.TagFormula;
import datadog.trace.bootstrap.instrumentation.api.TagReader;
import datadog.trace.bootstrap.instrumentation.api.TagsFormula;
import java.util.Map;

/**
 * The formulas of the tracer's base prototype, which every span starts with and every prototype
 * implicitly extends. Each replaces a tag post-processor: the processor still runs, but leaves a
 * tag alone when the span derives it.
 */
final class BaseTagDerivations {
  private BaseTagDerivations() {}

  /**
   * Builds the base formulas.
   *
   * @param peerServiceNaming the naming schema's peer-service policy
   * @param peerServiceMapping the configured peer-service mapping
   * @param resourceRenaming whether {@code http.endpoint} inference (resource renaming) is on
   * @param alwaysSimplifiedEndpoint whether to infer {@code http.endpoint} even when a usable
   *     {@code http.route} is set
   * @return the base formulas
   */
  static TagDerivations create(
      NamingSchema.ForPeerService peerServiceNaming,
      Map<String, String> peerServiceMapping,
      boolean resourceRenaming,
      boolean alwaysSimplifiedEndpoint) {
    TagDerivations.Builder builder = TagDerivations.builder();
    // Replaces PeerServiceCalculator's defaults. Reads the component per span; ClientDecorator
    // overrides it with a formula resolved for its own component.
    TagsFormula peerService =
        peerServiceNaming.supports() ? peerServiceNaming.formula(null, peerServiceMapping) : null;
    if (peerService != null) {
      builder.add(PeerServiceFormulas.TARGETS, peerService);
    }
    // Replaces HttpEndpointPostProcessor: the URL simplification runs only when read.
    if (resourceRenaming) {
      builder.add(HTTP_ENDPOINT, httpEndpoint(alwaysSimplifiedEndpoint));
    }
    // Replace IntegrationAdder and ServiceNameSourceAdder: copies of span fields.
    builder.add(DD_INTEGRATION, in -> in.integrationName());
    builder.add(DD_SVC_SRC, in -> in.serviceNameSource());
    return builder.build();
  }

  /**
   * {@code HttpEndpointPostProcessor} with {@code EndpointResolver.resolveEndpoint}: HTTP spans
   * only; a usable route stands in for the endpoint unless the simplified endpoint is always
   * wanted; otherwise the endpoint is simplified from the URL, or the path.
   */
  private static TagFormula httpEndpoint(final boolean alwaysSimplifiedEndpoint) {
    return in -> {
      if (in.get(HTTP_METHOD) == null) {
        return null;
      }
      if (!alwaysSimplifiedEndpoint && EndpointResolver.isRouteEligible(in.getString(HTTP_ROUTE))) {
        return null;
      }
      return EndpointResolver.computeEndpoint(urlOrPath(in));
    };
  }

  /** {@code OtelHttpSemantics.urlOrPath}, as a formula reads it. */
  private static String urlOrPath(TagReader in) {
    Object url = in.get(HTTP_URL);
    if (url == null) {
      url = in.get(URL_PATH);
    }
    return url == null ? null : url.toString();
  }
}

package datadog.trace.core;

import static datadog.trace.api.KnownTags.DD_INTEGRATION;
import static datadog.trace.api.KnownTags.DD_SVC_SRC;

import datadog.trace.api.naming.NamingSchema;
import datadog.trace.api.naming.PeerServiceFormulas;
import datadog.trace.bootstrap.instrumentation.api.TagDerivations;
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
   * @return the base formulas
   */
  static TagDerivations create(
      NamingSchema.ForPeerService peerServiceNaming, Map<String, String> peerServiceMapping) {
    TagDerivations.Builder builder = TagDerivations.builder();
    // Replaces PeerServiceCalculator's defaults. Reads the component per span; ClientDecorator
    // overrides it with a formula resolved for its own component.
    TagsFormula peerService =
        peerServiceNaming.supports() ? peerServiceNaming.formula(null, peerServiceMapping) : null;
    if (peerService != null) {
      builder.add(PeerServiceFormulas.TARGETS, peerService);
    }
    // Replace IntegrationAdder and ServiceNameSourceAdder: copies of span fields.
    builder.add(DD_INTEGRATION, in -> in.integrationName());
    builder.add(DD_SVC_SRC, in -> in.serviceNameSource());
    return builder.build();
  }
}

package datadog.trace.core.datastreams;

import datadog.trace.api.WellKnownTags;
import datadog.trace.api.time.TimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.core.propagation.HttpCodec;

public class DataStreamContextExtractor implements HttpCodec.Extractor {
  private final HttpCodec.Extractor delegate;
  private final TimeSource timeSource;
  private final WellKnownTags wellKnownTags;

  public DataStreamContextExtractor(
      HttpCodec.Extractor extractor, TimeSource timeSource, WellKnownTags wellKnownTags) {
    this.delegate = extractor;
    this.timeSource = timeSource;
    this.wellKnownTags = wellKnownTags;
  }

  @Override
  public <C> TagContext extract(C carrier, AgentPropagation.ContextVisitor<C> getter) {
    // Delegate the default HTTP extraction
    TagContext extracted = this.delegate.extract(carrier, getter);
    // Extract the pathway context
    if (extracted != null) {
      DefaultPathwayContext pathwayContext =
          DefaultPathwayContext.extract(carrier, getter, this.timeSource, this.wellKnownTags);
      extracted.withPathwayContext(pathwayContext);
    }
    // Return merged extracted context
    return extracted;
  }
}

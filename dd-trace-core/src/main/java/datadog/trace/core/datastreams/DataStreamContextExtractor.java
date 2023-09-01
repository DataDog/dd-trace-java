package datadog.trace.core.datastreams;

import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.Context.Extracted.SPAN_CONTEXT;
import static java.util.Collections.singleton;

import datadog.trace.api.WellKnownTags;
import datadog.trace.api.time.TimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.ContextKey;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.core.propagation.HttpCodec;
import java.util.Set;

public class DataStreamContextExtractor implements HttpCodec.Extractor {
  private final TimeSource timeSource;
  private final WellKnownTags wellKnownTags;

  public DataStreamContextExtractor(TimeSource timeSource, WellKnownTags wellKnownTags) {
    this.timeSource = timeSource;
    this.wellKnownTags = wellKnownTags;
  }

  @Override
  public Set<ContextKey<?>> supportedContent() {
    return singleton(
        SPAN_CONTEXT); // TODO Should use its own key rather than storing DSM context in span
  }

  @Override
  public <C> void extract(
      HttpCodec.ScopeContextBuilder builder, C carrier, AgentPropagation.ContextVisitor<C> getter) {
    DefaultPathwayContext pathwayContext =
        DefaultPathwayContext.extract(carrier, getter, this.timeSource, this.wellKnownTags);
    if (pathwayContext != null) {
      TagContext newTagContext = new TagContext();
      newTagContext.withPathwayContext(pathwayContext);
      builder.append(SPAN_CONTEXT, newTagContext);
    }
  }
}

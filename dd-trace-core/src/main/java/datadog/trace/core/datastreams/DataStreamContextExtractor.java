package datadog.trace.core.datastreams;

import datadog.trace.api.TraceConfig;
import datadog.trace.api.time.TimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.core.propagation.HttpCodec;
import java.util.function.Supplier;

public class DataStreamContextExtractor implements HttpCodec.Extractor {
  private final HttpCodec.Extractor delegate;
  private final TimeSource timeSource;
  private final Supplier<TraceConfig> traceConfigSupplier;
  private final long hashOfKnownTags;
  private final String serviceNameOverride;

  public DataStreamContextExtractor(
      HttpCodec.Extractor delegate,
      TimeSource timeSource,
      Supplier<TraceConfig> traceConfigSupplier,
      long hashOfKnownTags,
      String serviceNameOverride) {
    this.delegate = delegate;
    this.timeSource = timeSource;
    this.traceConfigSupplier = traceConfigSupplier;
    this.hashOfKnownTags = hashOfKnownTags;
    this.serviceNameOverride = serviceNameOverride;
  }

  @Override
  public <C> TagContext extract(C carrier, AgentPropagation.ContextVisitor<C> getter) {
    // Delegate the default HTTP extraction
    TagContext extracted = this.delegate.extract(carrier, getter);

    if (extracted != null) {
      boolean shouldExtractPathwayContext =
          extracted.getTraceConfig() == null
              ? traceConfigSupplier.get().isDataStreamsEnabled()
              : extracted.getTraceConfig().isDataStreamsEnabled();

      if (shouldExtractPathwayContext) {
        DefaultPathwayContext pathwayContext =
            DefaultPathwayContext.extract(
                carrier, getter, this.timeSource, this.hashOfKnownTags, serviceNameOverride);

        extracted.withPathwayContext(pathwayContext);
      }

      return extracted;
    } else if (traceConfigSupplier.get().isDataStreamsEnabled()) {
      DefaultPathwayContext pathwayContext =
          DefaultPathwayContext.extract(
              carrier, getter, this.timeSource, this.hashOfKnownTags, serviceNameOverride);

      if (pathwayContext != null) {
        extracted = new TagContext();
        extracted.withPathwayContext(pathwayContext);
        return extracted;
      }
    }

    return null;
  }
}

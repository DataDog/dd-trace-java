package datadog.trace.core.propagation;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.datastreams.DataStreamContextInjector;
import java.util.LinkedHashMap;

public class CorePropagation implements AgentPropagation {
  private final DataStreamContextInjector dataStreamContextInjector;
  private final HttpCodec.Extractor extractor;

  /**
   * Constructor
   *
   * @param extractor The context extractor.
   * @param dataStreamContextInjector The DSM context injector, as a specific object until generic
   *     context injection is available.
   */
  public CorePropagation(
      HttpCodec.Extractor extractor, DataStreamContextInjector dataStreamContextInjector) {
    this.extractor = extractor;
    this.dataStreamContextInjector = dataStreamContextInjector;
  }

  @Override
  public <C> void injectPathwayContext(
      AgentSpan span, C carrier, Setter<C> setter, LinkedHashMap<String, String> sortedTags) {
    this.dataStreamContextInjector.injectPathwayContext(span, carrier, setter, sortedTags);
  }

  @Override
  public <C> void injectPathwayContext(
      AgentSpan span,
      C carrier,
      Setter<C> setter,
      LinkedHashMap<String, String> sortedTags,
      long defaultTimestamp,
      long payloadSizeBytes) {
    this.dataStreamContextInjector.injectPathwayContext(
        span, carrier, setter, sortedTags, defaultTimestamp, payloadSizeBytes);
  }

  @Override
  public <C> void injectPathwayContextWithoutSendingStats(
      AgentSpan span, C carrier, Setter<C> setter, LinkedHashMap<String, String> sortedTags) {
    this.dataStreamContextInjector.injectPathwayContextWithoutSendingStats(
        span, carrier, setter, sortedTags);
  }
}

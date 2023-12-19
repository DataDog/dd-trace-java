package datadog.trace.core.propagation;

import datadog.trace.api.TracePropagationStyle;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.datastreams.DataStreamContextInjector;
import java.util.LinkedHashMap;
import java.util.Map;

public class CorePropagation implements AgentPropagation {
  private final HttpCodec.Injector injector;
  private final Map<TracePropagationStyle, HttpCodec.Injector> injectors;
  private final DataStreamContextInjector dataStreamContextInjector;
  private final HttpCodec.Extractor extractor;

  /**
   * Constructor
   *
   * @param extractor The context extractor.
   * @param defaultInjector The default injector when no {@link TracePropagationStyle} given.
   * @param injectors All the other injectors available for context injection.
   * @param dataStreamContextInjector The DSM context injector, as a specific object until generic
   *     context injection is available.
   */
  public CorePropagation(
      HttpCodec.Extractor extractor,
      HttpCodec.Injector defaultInjector,
      Map<TracePropagationStyle, HttpCodec.Injector> injectors,
      DataStreamContextInjector dataStreamContextInjector) {
    this.extractor = extractor;
    this.injector = defaultInjector;
    this.injectors = injectors;
    this.dataStreamContextInjector = dataStreamContextInjector;
  }

  @Override
  public <C> void inject(final AgentSpan span, final C carrier, final Setter<C> setter) {
    inject(span.context(), carrier, setter, null);
  }

  @Override
  public <C> void inject(AgentSpan.Context context, C carrier, Setter<C> setter) {
    inject(context, carrier, setter, null);
  }

  @Override
  public <C> void inject(AgentSpan span, C carrier, Setter<C> setter, TracePropagationStyle style) {
    inject(span.context(), carrier, setter, style);
  }

  private <C> void inject(
      AgentSpan.Context context, C carrier, Setter<C> setter, TracePropagationStyle style) {
    if (!(context instanceof DDSpanContext)) {
      return;
    }

    final DDSpanContext ddSpanContext = (DDSpanContext) context;
    ddSpanContext.getTrace().setSamplingPriorityIfNecessary();

    if (null == style) {
      injector.inject(ddSpanContext, carrier, setter);
    } else {
      injectors.get(style).inject(ddSpanContext, carrier, setter);
    }
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

  @Override
  public <C> AgentSpan.Context.Extracted extract(final C carrier, final ContextVisitor<C> getter) {
    return extractor.extract(carrier, getter);
  }
}

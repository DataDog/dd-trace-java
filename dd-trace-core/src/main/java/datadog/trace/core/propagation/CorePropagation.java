package datadog.trace.core.propagation;

import static datadog.trace.api.TracePropagationStyle.DSM_PATHWAY_CONTEXT;
import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.Context.Extracted.SPAN_CONTEXT;
import static datadog.trace.core.datastreams.DefaultPathwayContext.TAGS_CONTEXT_KEY;

import datadog.trace.api.TracePropagationStyle;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentScopeContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan.Context.Extracted;
import datadog.trace.core.propagation.HttpCodec.ScopeContextMerger;
import datadog.trace.core.scopemanager.ScopeContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CorePropagation implements AgentPropagation {
  private static final Logger LOGGER = LoggerFactory.getLogger(CorePropagation.class);

  private final HttpCodec.Injector injector;
  private final Map<TracePropagationStyle, HttpCodec.Injector> injectors;
  private final HttpCodec.Extractor extractor;

  /**
   * Constructor
   *
   * @param extractor The context extractor.
   * @param defaultInjector The default injector when no {@link TracePropagationStyle} given.
   * @param injectors All the other injectors available for context injection.
   */
  public CorePropagation(
      HttpCodec.Extractor extractor,
      HttpCodec.Injector defaultInjector,
      Map<TracePropagationStyle, HttpCodec.Injector> injectors) {
    this.extractor = extractor;
    this.injector = defaultInjector;
    this.injectors = injectors;
  }

  @Override
  public <C> void inject(final AgentSpan span, final C carrier, final Setter<C> setter) {
    this.injector.inject(ScopeContext.fromSpan(span), carrier, setter);
  }

  @Override
  public <C> void inject(AgentSpan span, C carrier, Setter<C> setter, TracePropagationStyle style) {
    inject(ScopeContext.fromSpan(span), carrier, setter, style);
  }

  @Override
  public <C> void inject(
      AgentScopeContext context, C carrier, Setter<C> setter, TracePropagationStyle style) {
    this.injectors.get(style).inject(context, carrier, setter);
  }

  /**
   * Injects the pathway context into the carrier using the given setter.
   *
   * @param span The span containing the context to inject.
   * @param carrier The carrier to inject the context into.
   * @param setter The setter used to inject the context.
   * @param sortedTags The sorted tags associated with the context.
   * @param <C> The type of the carrier.
   * @deprecated Use {@link #inject(AgentScopeContext, Object, Setter, TracePropagationStyle)} with
   *     {@link TracePropagationStyle#DSM_PATHWAY_CONTEXT} instead.
   */
  @Override
  @Deprecated
  public <C> void injectPathwayContext(
      AgentSpan span, C carrier, Setter<C> setter, LinkedHashMap<String, String> sortedTags) {
    AgentScopeContext context = ScopeContext.fromSpan(span).with(TAGS_CONTEXT_KEY, sortedTags);
    inject(context, carrier, setter, DSM_PATHWAY_CONTEXT);
  }

  @Override
  public <C> Extracted extract(final C carrier, final ContextVisitor<C> getter) {
    ScopeContextMerger builder = new ScopeContextMerger();
    this.extractor.extract(builder, carrier, getter);
    ScopeContext context = builder.build();

    Extracted spanContext = context.get(SPAN_CONTEXT); // TODO RETURN SCOPE CONTEXT INSTEAD
    if (spanContext instanceof ExtractedContext) {
      LOGGER.debug("Extract complete span context {}", context);
    } else {
      LOGGER.debug("Extract incomplete span context {}", context);
    }
    return spanContext;
  }
}

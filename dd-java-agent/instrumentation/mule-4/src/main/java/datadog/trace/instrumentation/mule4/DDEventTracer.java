package datadog.trace.instrumentation.mule4;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.instrumentation.mule4.MuleDecorator.DECORATE;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import org.mule.runtime.api.component.Component;
import org.mule.runtime.api.event.EventContext;
import org.mule.runtime.api.message.Error;
import org.mule.runtime.api.profiling.tracing.Span;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.tracer.api.EventTracer;
import org.mule.runtime.tracer.api.context.getter.DistributedTraceContextGetter;
import org.mule.runtime.tracer.api.sniffer.SpanSnifferManager;
import org.mule.runtime.tracer.api.span.info.EnrichedInitialSpanInfo;
import org.mule.runtime.tracer.api.span.info.InitialSpanInfo;
import org.mule.runtime.tracer.api.span.validation.Assertion;
import org.mule.runtime.tracer.customization.impl.info.ExecutionInitialSpanInfo;
import org.mule.runtime.tracer.customization.impl.provider.LazyInitialSpanInfo;

/**
 * This class is responsible for translating span reported by mule internal observability into DD
 * ones.
 */
public class DDEventTracer implements EventTracer<CoreEvent> {
  /** Holds the link between mule event context <-> ddSpan */
  private final ContextStore<EventContext, SpanState> eventContextStore;

  private final ContextStore<InitialSpanInfo, Component> componentContextStore;

  private final EventTracer<CoreEvent> delegate;

  public DDEventTracer(
      ContextStore<EventContext, SpanState> eventContextStore,
      ContextStore<InitialSpanInfo, Component> componentContextStore,
      EventTracer<CoreEvent> delegate) {
    this.eventContextStore = eventContextStore;
    this.componentContextStore = componentContextStore;
    this.delegate = delegate;
  }

  private AgentSpan maybeExtractCurrentSpan(final CoreEvent coreEvent) {
    if (coreEvent == null || coreEvent.getContext() == null) {
      return null;
    }
    SpanState spanState = eventContextStore.get(coreEvent.getContext());
    return spanState != null ? spanState.getSpanContextSpan() : null;
  }

  private AgentSpan findParent(final EventContext eventContext) {
    SpanState spanState = eventContextStore.get(eventContext);
    if (spanState != null) {
      return spanState.getEventContextSpan();
    }
    return activeSpan();
  }

  private Component findComponent(final InitialSpanInfo initialSpanInfo) {
    if (initialSpanInfo instanceof ExecutionInitialSpanInfo) {
      return componentContextStore.get(initialSpanInfo);
    } else if (initialSpanInfo instanceof LazyInitialSpanInfo) {
      return findComponent(((LazyInitialSpanInfo) initialSpanInfo).getDelegate());
    } else if (initialSpanInfo instanceof EnrichedInitialSpanInfo) {
      return findComponent(((EnrichedInitialSpanInfo) initialSpanInfo).getBaseInitialSpanInfo());
    }
    return null;
  }

  private void linkToContext(@Nonnull final EventContext eventContext, final AgentSpan span) {
    final SpanState previousState = eventContextStore.get(eventContext);
    final AgentSpan spanToLink;
    if (span != null) {
      spanToLink = span;
    } else if (previousState != null) {
      spanToLink = previousState.getEventContextSpan();
    } else {
      spanToLink = null;
    }

    eventContextStore.put(
        eventContext, new SpanState(spanToLink, previousState).withSpanContextSpan(span));
  }

  private void handleNewSpan(CoreEvent event, InitialSpanInfo spanInfo) {
    if (event == null || event.getContext() == null) {
      // we cannot store properly the span in the context.
      return;
    }

    final EventContext eventContext = event.getContext();

    final AgentSpan span =
        DECORATE.onMuleSpan(findParent(eventContext), spanInfo, event, findComponent(spanInfo));
    linkToContext(eventContext, span);
  }

  private void handleEndOfSpan(CoreEvent event) {
    if (event == null || event.getContext() == null) {
      return;
    }
    final EventContext eventContext = event.getContext();
    final SpanState spanState = eventContextStore.get(eventContext);
    if (spanState == null) {
      return;
    }
    if (spanState.getSpanContextSpan() != null) {
      final AgentSpan span = spanState.getSpanContextSpan();
      DECORATE.beforeFinish(span).finish();
    }
    eventContextStore.put(eventContext, spanState.getPreviousState());
  }

  @Override
  public Optional<Span> startSpan(CoreEvent event, InitialSpanInfo spanInfo) {
    handleNewSpan(event, spanInfo);
    final Optional<Span> span = delegate.startSpan(event, spanInfo);
    if (span.isPresent()) {
      return span;
    }
    return NoopMuleSpan.INSTANCE;
  }

  @Override
  public Optional<Span> startSpan(CoreEvent event, InitialSpanInfo spanInfo, Assertion assertion) {
    handleNewSpan(event, spanInfo);
    final Optional<Span> span = delegate.startSpan(event, spanInfo, assertion);
    if (span.isPresent()) {
      return span;
    }
    return NoopMuleSpan.INSTANCE;
  }

  @Override
  public void endCurrentSpan(CoreEvent event) {
    try {
      delegate.endCurrentSpan(event);
    } finally {
      handleEndOfSpan(event);
    }
  }

  @Override
  public void endCurrentSpan(CoreEvent event, Assertion condition) {
    try {
      delegate.endCurrentSpan(event, condition);
    } finally {
      handleEndOfSpan(event);
    }
  }

  @Override
  public void injectDistributedTraceContext(
      EventContext eventContext, DistributedTraceContextGetter distributedTraceContextGetter) {
    // TODO: we do not use it today since we've our injectors. However it can be handy in case we do
    // not support some connectors
    delegate.injectDistributedTraceContext(eventContext, distributedTraceContextGetter);
  }

  @Override
  public void recordErrorAtCurrentSpan(
      CoreEvent event, Supplier<Error> errorSupplier, boolean isErrorEscapingCurrentSpan) {
    try {
      delegate.recordErrorAtCurrentSpan(event, errorSupplier, isErrorEscapingCurrentSpan);
    } finally {
      final AgentSpan span = maybeExtractCurrentSpan(event);
      if (span != null) {
        final Error error = errorSupplier.get();
        if (error != null && error.getCause() != null) {
          DECORATE.onError(span, error.getCause());
        }
      }
    }
  }

  @Override
  public void setCurrentSpanName(CoreEvent event, String name) {
    try {
      delegate.setCurrentSpanName(event, name);
    } finally {
      final AgentSpan span = maybeExtractCurrentSpan(event);
      if (span != null) {
        span.setResourceName(name);
      }
    }
  }

  @Override
  public void addCurrentSpanAttribute(CoreEvent event, String key, String value) {
    try {
      delegate.addCurrentSpanAttribute(event, key, value);
    } finally {
      final AgentSpan span = maybeExtractCurrentSpan(event);
      if (span != null) {
        span.setTag(key, value);
      }
    }
  }

  @Override
  public void addCurrentSpanAttributes(CoreEvent event, Map<String, String> attributes) {
    try {
      delegate.addCurrentSpanAttributes(event, attributes);
    } finally {
      final AgentSpan span = maybeExtractCurrentSpan(event);
      if (span != null) {
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
          span.setTag(entry.getKey(), entry.getValue());
        }
      }
    }
  }

  @Override
  public SpanSnifferManager getSpanSnifferManager() {
    return delegate.getSpanSnifferManager();
  }
}

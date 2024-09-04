package datadog.trace.instrumentation.mule4;

import datadog.trace.api.Pair;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.mule.runtime.api.event.EventContext;
import org.mule.runtime.api.message.Error;
import org.mule.runtime.api.profiling.tracing.Span;
import org.mule.runtime.api.profiling.tracing.SpanError;
import org.mule.runtime.core.api.event.CoreEvent;
import org.mule.runtime.tracer.api.EventTracer;
import org.mule.runtime.tracer.api.context.getter.DistributedTraceContextGetter;
import org.mule.runtime.tracer.api.sniffer.SpanSnifferManager;
import org.mule.runtime.tracer.api.span.info.InitialSpanInfo;
import org.mule.runtime.tracer.api.span.validation.Assertion;

public class DDEventTracer implements EventTracer<CoreEvent> {

  private final ContextStore<Span, AgentSpan> spanStore;
  private final ContextStore<EventContext, Pair> contextStore;
  private final EventTracer<CoreEvent> delegate;

  public DDEventTracer(
      ContextStore<Span, AgentSpan> spanStore,
      ContextStore<EventContext, Pair> contextStore,
      EventTracer<CoreEvent> delegate) {
    this.spanStore = spanStore;
    this.contextStore = contextStore;
    this.delegate = delegate;
  }

  private AgentSpan fromMuleSpan(Span muleSpan, InitialSpanInfo spanInfo) {
    final AgentSpan parent =
        muleSpan.getParent() != null ? spanStore.get(muleSpan.getParent()) : null;

    final AgentSpan span;
    if (parent == null) {
      span = AgentTracer.startSpan("mule", muleSpan.getName());
    } else {
      span = AgentTracer.startSpan("mule", muleSpan.getName(), parent.context());
    }
    spanInfo.forEachAttribute(span::setTag);
    return span;
  }

  private void activateOnContext(EventContext eventContext, AgentSpan span, Span muleSpan) {
    contextStore.put(eventContext, Pair.of(span, muleSpan));
    CurrentEventHelper.attachSpanToEventContext(eventContext, contextStore);
  }

  private Optional<Span> handleNewSpan(
      CoreEvent event, Optional<Span> maybeSpan, InitialSpanInfo spanInfo) {
    if (!maybeSpan.isPresent() || CallDepthThreadLocalMap.incrementCallDepth(Span.class) > 0) {
      return maybeSpan;
    }
    try {
      final Span muleSpan = maybeSpan.get();
      final AgentSpan span = fromMuleSpan(muleSpan, spanInfo);
      spanStore.put(muleSpan, span);
      activateOnContext(event.getContext(), span, muleSpan);
    } finally {
      CallDepthThreadLocalMap.reset(Span.class);
    }
    return maybeSpan;
  }

  private void handleEndOfSpan(CoreEvent event) {
    if (CallDepthThreadLocalMap.incrementCallDepth(Span.class) > 0) {
      return;
    }
    try {
      final Pair<AgentSpan, Span> pair = contextStore.get(event.getContext());
      if (pair != null && pair.hasLeft() && pair.hasRight()) {
        final AgentSpan span = pair.getLeft();
        final Span muleSpan = pair.getRight();
        spanStore.remove(muleSpan);
        if (muleSpan.hasErrors()) {
          span.setError(true);
          for (final SpanError spanError : muleSpan.getErrors()) {
            if (spanError.getError() != null && spanError.getError().getCause() != null) {
              span.addThrowable(spanError.getError().getCause());
            }
          }
        }
        span.finish();
        final Span muleParent = muleSpan.getParent();
        if (muleParent != null) {
          final AgentSpan parent = spanStore.get(muleParent);
          if (parent != null) {
            activateOnContext(event.getContext(), parent, muleParent);
          }
        }
      }
    } finally {
      CallDepthThreadLocalMap.reset(Span.class);
    }
  }

  @Override
  public Optional<Span> startSpan(CoreEvent event, InitialSpanInfo spanInfo) {
    return handleNewSpan(event, delegate.startSpan(event, spanInfo), spanInfo);
  }

  @Override
  public Optional<Span> startSpan(CoreEvent event, InitialSpanInfo spanInfo, Assertion assertion) {
    return handleNewSpan(event, delegate.startSpan(event, spanInfo, assertion), spanInfo);
  }

  @Override
  public void endCurrentSpan(CoreEvent event) {
    delegate.endCurrentSpan(event);
    handleEndOfSpan(event);
  }

  @Override
  public void endCurrentSpan(CoreEvent event, Assertion condition) {
    delegate.endCurrentSpan(event, condition);
    handleEndOfSpan(event);
  }

  @Override
  public void injectDistributedTraceContext(
      EventContext eventContext, DistributedTraceContextGetter distributedTraceContextGetter) {
    delegate.injectDistributedTraceContext(eventContext, distributedTraceContextGetter);
  }

  @Override
  public void recordErrorAtCurrentSpan(
      CoreEvent event, Supplier<Error> errorSupplier, boolean isErrorEscapingCurrentSpan) {
    delegate.recordErrorAtCurrentSpan(event, errorSupplier, isErrorEscapingCurrentSpan);
  }

  @Override
  public void setCurrentSpanName(CoreEvent event, String name) {
    delegate.setCurrentSpanName(event, name);
    Pair<AgentSpan, Span> pair = contextStore.get(event.getContext());
    if (pair != null && pair.hasLeft()) {
      pair.getLeft().setOperationName(name);
    }
  }

  @Override
  public void addCurrentSpanAttribute(CoreEvent event, String key, String value) {
    delegate.addCurrentSpanAttribute(event, key, value);
    Pair<AgentSpan, Span> pair = contextStore.get(event.getContext());
    if (pair != null && pair.hasLeft()) {
      pair.getLeft().setTag(key, value);
    }
  }

  @Override
  public void addCurrentSpanAttributes(CoreEvent event, Map<String, String> attributes) {
    delegate.addCurrentSpanAttributes(event, attributes);
    Pair<AgentSpan, Span> pair = contextStore.get(event.getContext());
    if (pair != null && pair.hasLeft()) {
      final AgentSpan span = pair.getLeft();
      for (Map.Entry<String, String> entry : attributes.entrySet()) {
        span.setTag(entry.getKey(), entry.getValue());
      }
    }
  }

  @Override
  public SpanSnifferManager getSpanSnifferManager() {
    return delegate.getSpanSnifferManager();
  }
}

package datadog.trace.instrumentation.opentelemetry;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import io.opentelemetry.common.AttributeValue;
import io.opentelemetry.context.Scope;
import io.opentelemetry.trace.Link;
import io.opentelemetry.trace.Span;
import io.opentelemetry.trace.SpanContext;
import io.opentelemetry.trace.Tracer;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class OtelTracer implements Tracer {
  private static final String INSTRUMENTATION_NAME = "otel";
  private final String tracerName;
  private final AgentTracer.TracerAPI tracer;
  private final TypeConverter converter;

  OtelTracer(
      final String tracerName, final AgentTracer.TracerAPI tracer, final TypeConverter converter) {
    this.tracerName = tracerName;
    this.tracer = tracer;
    this.converter = converter;
  }

  @Override
  public Span getCurrentSpan() {
    return converter.toSpan(tracer.activeSpan());
  }

  @Override
  public Scope withSpan(final Span span) {
    if (null == span) {
      return null;
    }

    final AgentSpan agentSpan = converter.toAgentSpan(span);
    final AgentScope agentScope = tracer.activateManualSpan(agentSpan);
    return converter.toScope(agentScope);
  }

  @Override
  public Span.Builder spanBuilder(final String spanName) {
    return new SpanBuilder(spanName);
  }

  private class SpanBuilder implements Span.Builder {
    private final AgentTracer.SpanBuilder delegate;
    private boolean parentSet = false;

    public SpanBuilder(final String spanName) {
      delegate = tracer.buildSpan(INSTRUMENTATION_NAME, tracerName).withResourceName(spanName);
    }

    @Override
    public Span.Builder setParent(final Span parent) {
      parentSet = true;
      delegate.asChildOf(converter.toAgentSpan(parent).context());
      return this;
    }

    @Override
    public Span.Builder setParent(final SpanContext remoteParent) {
      parentSet = true;
      delegate.asChildOf(converter.toContext(remoteParent));
      return this;
    }

    @Override
    public Span.Builder setNoParent() {
      parentSet = true;
      delegate.asChildOf(null);
      delegate.ignoreActiveSpan();
      return this;
    }

    @Override
    public Span.Builder addLink(final SpanContext spanContext) {
      if (!parentSet) {
        delegate.asChildOf(converter.toContext(spanContext));
      }
      return this;
    }

    @Override
    public Span.Builder addLink(
        final SpanContext spanContext, final Map<String, AttributeValue> attributes) {
      if (!parentSet) {
        delegate.asChildOf(converter.toContext(spanContext));
      }
      return this;
    }

    @Override
    public Span.Builder addLink(final Link link) {
      if (!parentSet) {
        delegate.asChildOf(converter.toContext(link.getContext()));
      }
      return this;
    }

    @Override
    public Span.Builder setAttribute(final String key, final String value) {
      delegate.withTag(key, value);
      return this;
    }

    @Override
    public Span.Builder setAttribute(final String key, final long value) {
      delegate.withTag(key, value);
      return this;
    }

    @Override
    public Span.Builder setAttribute(final String key, final double value) {
      delegate.withTag(key, value);
      return this;
    }

    @Override
    public Span.Builder setAttribute(final String key, final boolean value) {
      delegate.withTag(key, value);
      return this;
    }

    @Override
    public Span.Builder setAttribute(final String key, final AttributeValue value) {
      switch (value.getType()) {
        case LONG:
          delegate.withTag(key, value.getLongValue());
          break;
        case DOUBLE:
          delegate.withTag(key, value.getDoubleValue());
          break;
        case STRING:
          delegate.withTag(key, value.getStringValue());
          break;
        case BOOLEAN:
          delegate.withTag(key, value.getBooleanValue());
          break;
        default:
          // Unsupported.... Ignoring.
      }
      return this;
    }

    @Override
    public Span.Builder setSpanKind(final Span.Kind spanKind) {
      // TODO: update delegate.operationName
      delegate.withTag(Tags.SPAN_KIND, spanKind.name());
      return this;
    }

    @Override
    public Span.Builder setStartTimestamp(final long startTimestamp) {
      delegate.withStartTimestamp(TimeUnit.NANOSECONDS.toMicros(startTimestamp));
      return this;
    }

    @Override
    public Span startSpan() {
      final AgentSpan agentSpan = delegate.start();
      agentSpan.context().setIntegrationName("otel");
      return converter.toSpan(agentSpan);
    }
  }
}

package datadog.trace.instrumentation.opentracing31;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.instrumentation.opentracing.DefaultLogHandler;
import io.opentracing.References;
import io.opentracing.Scope;
import io.opentracing.ScopeManager;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMap;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OTTracer implements Tracer {

  private final TypeConverter converter = new TypeConverter(new DefaultLogHandler());
  private final AgentTracer.TracerAPI tracer;
  private final ScopeManager scopeManager;

  public OTTracer(final AgentTracer.TracerAPI tracer) {
    this.tracer = tracer;
    scopeManager = new OTScopeManager(tracer, converter);
  }

  @Override
  public ScopeManager scopeManager() {
    return scopeManager;
  }

  @Override
  public Span activeSpan() {
    return converter.toSpan(AgentTracer.activeSpan());
  }

  @Override
  public SpanBuilder buildSpan(final String operationName) {
    return new OTSpanBuilder(tracer.buildSpan(operationName), converter);
  }

  @Override
  public <C> void inject(final SpanContext spanContext, final Format<C> format, final C carrier) {
    if (carrier instanceof TextMap) {
      final AgentSpan.Context context = converter.toContext(spanContext);

      tracer.inject(context, (TextMap) carrier, OTPropagation.TextMapInjectSetter.INSTANCE);
    } else {
      log.debug("Unsupported format for propagation - {}", format.getClass().getName());
    }
  }

  @Override
  public <C> SpanContext extract(final Format<C> format, final C carrier) {
    if (carrier instanceof TextMap) {
      final AgentSpan.Context tagContext =
          tracer.extract(
              (TextMap) carrier, new OTPropagation.TextMapExtractGetter((TextMap) carrier));

      return converter.toSpanContext(tagContext);
    } else {
      log.debug("Unsupported format for propagation - {}", format.getClass().getName());
      return null;
    }
  }

  public class OTSpanBuilder implements Tracer.SpanBuilder {
    private final AgentTracer.SpanBuilder delegate;
    private final TypeConverter converter;

    public OTSpanBuilder(final AgentTracer.SpanBuilder delegate, final TypeConverter converter) {
      this.delegate = delegate;
      this.converter = converter;
    }

    @Override
    public Tracer.SpanBuilder asChildOf(final SpanContext parent) {
      delegate.asChildOf(converter.toContext(parent));
      return this;
    }

    @Override
    public Tracer.SpanBuilder asChildOf(final Span parent) {
      if (parent != null) {
        delegate.asChildOf(converter.toAgentSpan(parent).context());
      }
      return this;
    }

    @Override
    public Tracer.SpanBuilder addReference(
        final String referenceType, final SpanContext referencedContext) {
      if (referencedContext == null) {
        return this;
      }

      final AgentSpan.Context context = converter.toContext(referencedContext);
      if (!(context instanceof ExtractedContext) && !(context instanceof DDSpanContext)) {
        log.debug(
            "Expected to have a DDSpanContext or ExtractedContext but got "
                + context.getClass().getName());
        return this;
      }

      if (References.CHILD_OF.equals(referenceType)
          || References.FOLLOWS_FROM.equals(referenceType)) {
        delegate.asChildOf(context);
      } else {
        log.debug("Only support reference type of CHILD_OF and FOLLOWS_FROM");
      }

      return this;
    }

    @Override
    public OTSpanBuilder ignoreActiveSpan() {
      delegate.ignoreActiveSpan();
      return this;
    }

    @Override
    public OTSpanBuilder withTag(final String key, final String value) {
      delegate.withTag(key, value);
      return this;
    }

    @Override
    public OTSpanBuilder withTag(final String key, final boolean value) {
      delegate.withTag(key, value);
      return this;
    }

    @Override
    public OTSpanBuilder withTag(final String key, final Number value) {
      delegate.withTag(key, value);
      return this;
    }

    @Override
    public OTSpanBuilder withStartTimestamp(final long microseconds) {
      delegate.withStartTimestamp(microseconds);
      return this;
    }

    @Override
    public Span startManual() {
      return start();
    }

    @Override
    public Span start() {
      final AgentSpan agentSpan = delegate.start();
      return converter.toSpan(agentSpan);
    }

    @Override
    public Scope startActive(final boolean finishSpanOnClose) {
      return converter.toScope(tracer.activateSpan(delegate.start()), finishSpanOnClose);
    }
  }
}

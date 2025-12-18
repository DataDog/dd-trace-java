package datadog.trace.instrumentation.opentracing31;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromSpanContext;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors;
import datadog.trace.instrumentation.opentracing.DefaultLogHandler;
import io.opentracing.References;
import io.opentracing.Scope;
import io.opentracing.ScopeManager;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OTTracer implements Tracer {
  private static final String INSTRUMENTATION_NAME = "opentracing";
  private static final Logger log = LoggerFactory.getLogger(OTTracer.class);

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
    return new OTSpanBuilder(tracer.buildSpan(INSTRUMENTATION_NAME, operationName));
  }

  @Override
  public <C> void inject(final SpanContext spanContext, final Format<C> format, final C carrier) {
    if (carrier instanceof TextMap) {
      final AgentSpanContext context = converter.toContext(spanContext);
      AgentSpan span = fromSpanContext(context);
      defaultPropagator().inject(span, (TextMap) carrier, OTTextMapSetter.INSTANCE);
    } else {
      log.debug("Unsupported format for propagation - {}", format.getClass().getName());
    }
  }

  @Override
  public <C> SpanContext extract(final Format<C> format, final C carrier) {
    if (carrier instanceof TextMap) {
      final AgentSpanContext tagContext =
          extractContextAndGetSpanContext(
              (TextMap) carrier, ContextVisitors.stringValuesEntrySet());

      return converter.toSpanContext(tagContext);
    } else {
      log.debug("Unsupported format for propagation - {}", format.getClass().getName());
      return null;
    }
  }

  public class OTSpanBuilder implements Tracer.SpanBuilder {
    private final AgentTracer.SpanBuilder delegate;

    public OTSpanBuilder(final AgentTracer.SpanBuilder delegate) {
      this.delegate = delegate;
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

      final AgentSpanContext context = converter.toContext(referencedContext);

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
      agentSpan.context().setIntegrationName("opentracing");
      return converter.toSpan(agentSpan);
    }

    @Override
    public Scope startActive(final boolean finishSpanOnClose) {
      final AgentSpan agentSpan = delegate.start();
      agentSpan.context().setIntegrationName("opentracing");
      return converter.toScope(tracer.activateManualSpan(agentSpan), finishSpanOnClose);
    }
  }
}

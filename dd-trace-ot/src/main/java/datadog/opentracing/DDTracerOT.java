package datadog.opentracing;

import datadog.trace.core.DDSpan;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.DDTracer;
import datadog.trace.core.DDTracer.DDSpanBuilder;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.NoopAgentSpan;
import datadog.trace.common.sampling.Sampler;
import datadog.trace.common.writer.Writer;
import datadog.trace.context.TraceScope;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.core.propagation.HttpCodec;
import datadog.trace.core.propagation.TagContext;
import datadog.trace.core.scopemanager.DDScopeManager;
import io.opentracing.References;
import io.opentracing.Scope;
import io.opentracing.ScopeManager;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.noop.NoopSpan;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMapExtract;
import io.opentracing.propagation.TextMapInject;
import io.opentracing.tag.Tag;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DDTracerOT implements Tracer {
  private final Converter converter = new Converter();
  private final DDTracer coreTracer;
  private final ScopeManager scopeManager;
  private LogHandler logHandler = new DefaultLogHandler();

  public static class DDTracerOTBuilder {
    public DDTracerOTBuilder() {
      // Apply the default values from config.
      config(Config.get());
    }

    public DDTracerOTBuilder withProperties(final Properties properties) {
      return config(Config.get(properties));
    }
  }

  @Deprecated
  public DDTracerOT() {
    coreTracer = DDTracer.builder().build();
    scopeManager = new OTScopeManager();
  }

  @Deprecated
  public DDTracerOT(final String serviceName) {
    coreTracer = DDTracer.builder().serviceName(serviceName).build();
    scopeManager = new OTScopeManager();
  }

  @Deprecated
  public DDTracerOT(final Properties properties) {
    coreTracer = DDTracer.builder().withProperties(properties).build();
    scopeManager = new OTScopeManager();
  }

  @Deprecated
  public DDTracerOT(final Config config) {
    coreTracer = DDTracer.builder().config(config).build();
    scopeManager = new OTScopeManager();
  }

  // This constructor is already used in the wild, so we have to keep it inside this API for now.
  @Deprecated
  public DDTracerOT(final String serviceName, final Writer writer, final Sampler sampler) {
    coreTracer =
        DDTracer.builder().serviceName(serviceName).writer(writer).sampler(sampler).build();
    scopeManager = new OTScopeManager();
  }

  @Deprecated
  DDTracerOT(
      final String serviceName,
      final Writer writer,
      final Sampler sampler,
      final Map<String, String> runtimeTags) {
    coreTracer =
        DDTracer.builder()
            .serviceName(serviceName)
            .writer(writer)
            .sampler(sampler)
            .localRootSpanTags(runtimeTags)
            .build();
    scopeManager = new OTScopeManager();
  }

  @Deprecated
  public DDTracerOT(final Writer writer) {
    coreTracer = DDTracer.builder().writer(writer).build();
    scopeManager = new OTScopeManager();
  }

  @Deprecated
  public DDTracerOT(final Config config, final Writer writer) {
    coreTracer = DDTracer.builder().config(config).writer(writer).build();
    scopeManager = new OTScopeManager();
  }

  @Deprecated
  public DDTracerOT(
      final String serviceName,
      final Writer writer,
      final Sampler sampler,
      final String runtimeId,
      final Map<String, String> localRootSpanTags,
      final Map<String, String> defaultSpanTags,
      final Map<String, String> serviceNameMappings,
      final Map<String, String> taggedHeaders) {
    coreTracer =
        DDTracer.builder()
            .serviceName(serviceName)
            .writer(writer)
            .sampler(sampler)
            .localRootSpanTags(customRuntimeTags(runtimeId, localRootSpanTags))
            .defaultSpanTags(defaultSpanTags)
            .serviceNameMappings(serviceNameMappings)
            .taggedHeaders(taggedHeaders)
            .build();
    scopeManager = new OTScopeManager();
  }

  @Deprecated
  public DDTracerOT(
      final String serviceName,
      final Writer writer,
      final Sampler sampler,
      final Map<String, String> localRootSpanTags,
      final Map<String, String> defaultSpanTags,
      final Map<String, String> serviceNameMappings,
      final Map<String, String> taggedHeaders) {

    coreTracer =
        DDTracer.builder()
            .serviceName(serviceName)
            .writer(writer)
            .sampler(sampler)
            .localRootSpanTags(localRootSpanTags)
            .defaultSpanTags(defaultSpanTags)
            .serviceNameMappings(serviceNameMappings)
            .taggedHeaders(taggedHeaders)
            .build();
    scopeManager = new OTScopeManager();
  }

  @Deprecated
  public DDTracerOT(
      final String serviceName,
      final Writer writer,
      final Sampler sampler,
      final Map<String, String> localRootSpanTags,
      final Map<String, String> defaultSpanTags,
      final Map<String, String> serviceNameMappings,
      final Map<String, String> taggedHeaders,
      final int partialFlushMinSpans) {

    coreTracer =
        DDTracer.builder()
            .serviceName(serviceName)
            .writer(writer)
            .sampler(sampler)
            .localRootSpanTags(localRootSpanTags)
            .defaultSpanTags(defaultSpanTags)
            .serviceNameMappings(serviceNameMappings)
            .taggedHeaders(taggedHeaders)
            .partialFlushMinSpans(partialFlushMinSpans)
            .build();
    scopeManager = new OTScopeManager();
  }

  // Should only be used internally by TracerInstaller
  @Deprecated
  public DDTracerOT(final DDTracer coreTracer) {
    this.coreTracer = coreTracer;
    this.scopeManager = new OTScopeManager();
  }

  @Builder
  // These field names must be stable to ensure the builder api is stable.
  private DDTracerOT(
      final Config config,
      final String serviceName,
      final Writer writer,
      final Sampler sampler,
      final HttpCodec.Injector injector,
      final HttpCodec.Extractor extractor,
      final ScopeManager scopeManager,
      final Map<String, String> localRootSpanTags,
      final Map<String, String> defaultSpanTags,
      final Map<String, String> serviceNameMappings,
      final Map<String, String> taggedHeaders,
      final int partialFlushMinSpans,
      final LogHandler logHandler) {

    DDScopeManager ddScopeManager = null;
    if (scopeManager != null) {
      this.scopeManager = scopeManager;
      ddScopeManager = new CustomScopeManager(scopeManager);
    } else {
      this.scopeManager = new OTScopeManager();
    }

    if (logHandler != null) {
      this.logHandler = logHandler;
    }

    coreTracer =
        DDTracer.builder()
            .config(config)
            .serviceName(serviceName)
            .writer(writer)
            .sampler(sampler)
            .injector(injector)
            .extractor(extractor)
            .scopeManager(ddScopeManager)
            .localRootSpanTags(localRootSpanTags)
            .defaultSpanTags(defaultSpanTags)
            .serviceNameMappings(serviceNameMappings)
            .taggedHeaders(taggedHeaders)
            .partialFlushMinSpans(partialFlushMinSpans)
            .build();
  }

  private static Map<String, String> customRuntimeTags(
      final String runtimeId, final Map<String, String> applicationRootSpanTags) {
    final Map<String, String> runtimeTags = new HashMap<>(applicationRootSpanTags);
    runtimeTags.put(Config.RUNTIME_ID_TAG, runtimeId);
    return Collections.unmodifiableMap(runtimeTags);
  }

  @Override
  public ScopeManager scopeManager() {
    return scopeManager;
  }

  @Override
  public Span activeSpan() {
    return scopeManager.activeSpan();
  }

  @Override
  public Scope activateSpan(final Span span) {
    return scopeManager.activate(span);
  }

  @Override
  public SpanBuilder buildSpan(final String operationName) {
    return new OTSpanBuilder(operationName);
  }

  @Override
  public <C> void inject(final SpanContext spanContext, final Format<C> format, final C carrier) {
    if (carrier instanceof TextMapInject) {
      final AgentSpan.Context context = converter.toContext(spanContext);

      coreTracer.inject(context, (TextMapInject) carrier, TextMapInjectSetter.INSTANCE);
    } else {
      log.debug("Unsupported format for propagation - {}", format.getClass().getName());
    }
  }

  @Override
  public <C> SpanContext extract(final Format<C> format, final C carrier) {
    if (carrier instanceof TextMapExtract) {
      final TagContext tagContext =
          coreTracer.extract(
              (TextMapExtract) carrier, new TextMapExtractGetter((TextMapExtract) carrier));

      return converter.toSpanContext(tagContext);
    } else {
      log.debug("Unsupported format for propagation - {}", format.getClass().getName());
      return null;
    }
  }

  @Override
  public void close() {
    coreTracer.close();
  }

  private static class TextMapInjectSetter implements AgentPropagation.Setter<TextMapInject> {
    static final TextMapInjectSetter INSTANCE = new TextMapInjectSetter();

    @Override
    public void set(final TextMapInject carrier, final String key, final String value) {
      carrier.put(key, value);
    }
  }

  private static class TextMapExtractGetter implements AgentPropagation.Getter<TextMapExtract> {
    private final Map<String, String> extracted = new HashMap<>();

    private TextMapExtractGetter(final TextMapExtract carrier) {
      for (final Entry<String, String> entry : carrier) {
        extracted.put(entry.getKey(), entry.getValue());
      }
    }

    @Override
    public Iterable<String> keys(final TextMapExtract carrier) {
      return extracted.keySet();
    }

    @Override
    public String get(final TextMapExtract carrier, final String key) {
      // This is the same as the one passed into the constructor
      // So using "extracted" is valid
      return extracted.get(key);
    }
  }

  // Centralized place to do conversions
  private class Converter {
    // TODO maybe add caching to reduce new objects being created

    public AgentSpan toAgentSpan(final Span span) {
      if (span instanceof OTSpan) {
        return ((OTSpan) span).delegate;
      } else {
        // NOOP Span
        return NoopAgentSpan.INSTANCE;
      }
    }

    public Span toSpan(final AgentSpan agentSpan) {
      if (agentSpan instanceof DDSpan) {
        return new OTSpan((DDSpan) agentSpan);
      } else {
        // NOOP AgentSpans
        return NoopSpan.INSTANCE;
      }
    }

    public Scope toScope(final AgentScope agentScope) {
      if (agentScope instanceof CustomScopeManagerScope) {
        return ((CustomScopeManagerScope) agentScope).delegate;
      } else if (agentScope instanceof TraceScope) {
        return new OTTraceScope((TraceScope) agentScope);
      } else {
        return new OTScope(agentScope);
      }
    }

    public SpanContext toSpanContext(final DDSpanContext context) {
      return new OTGenericContext(context);
    }

    public SpanContext toSpanContext(final TagContext tagContext) {
      if (tagContext instanceof ExtractedContext) {
        return new OTExtractedContext((ExtractedContext) tagContext);
      } else {
        return new OTTagContext(tagContext);
      }
    }

    public AgentSpan.Context toContext(final SpanContext spanContext) {
      // FIXME: [API] DDSpanContext, ExtractedContext, TagContext, AgentSpan.Context
      // don't share a meaningful hierarchy
      if (spanContext instanceof OTGenericContext) {
        return ((OTGenericContext) spanContext).delegate;
      } else if (spanContext instanceof OTExtractedContext) {
        return ((OTExtractedContext) spanContext).extractedContext;
      } else if (spanContext instanceof OTTagContext) {
        return ((OTTagContext) spanContext).delegate;
      } else {
        return AgentTracer.NoopContext.INSTANCE;
      }
    }
  }

  private class OTSpanBuilder implements SpanBuilder {
    private final DDSpanBuilder delegate;

    public OTSpanBuilder(final String operationName) {
      delegate = coreTracer.buildSpan(operationName);
    }

    @Override
    public SpanBuilder asChildOf(final SpanContext parent) {
      delegate.asChildOf(converter.toContext(parent));
      return this;
    }

    @Override
    public SpanBuilder asChildOf(final Span parent) {
      if (parent != null) {
        delegate.asChildOf(converter.toAgentSpan(parent).context());
      }
      return this;
    }

    @Override
    public SpanBuilder addReference(
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
    public SpanBuilder ignoreActiveSpan() {
      delegate.ignoreActiveSpan();
      return this;
    }

    @Override
    public SpanBuilder withTag(final String key, final String value) {
      delegate.withTag(key, value);
      return this;
    }

    @Override
    public SpanBuilder withTag(final String key, final boolean value) {
      delegate.withTag(key, value);
      return this;
    }

    @Override
    public SpanBuilder withTag(final String key, final Number value) {
      delegate.withTag(key, value);
      return this;
    }

    @Override
    public <T> SpanBuilder withTag(final Tag<T> tag, final T value) {
      delegate.withTag(tag.getKey(), value);
      return this;
    }

    @Override
    public SpanBuilder withStartTimestamp(final long microseconds) {
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
      final AgentScope agentScope = delegate.startActive(finishSpanOnClose);
      return converter.toScope(agentScope);
    }
  }

  /** Allows custom scope managers to be passed in to constructor */
  private class CustomScopeManager implements DDScopeManager {
    private final ScopeManager delegate;

    private CustomScopeManager(final ScopeManager scopeManager) {
      this.delegate = scopeManager;
    }

    @Override
    public AgentScope activate(final AgentSpan agentSpan, final boolean finishOnClose) {
      final Span span = converter.toSpan(agentSpan);
      final Scope scope = delegate.activate(span, finishOnClose);

      return new CustomScopeManagerScope(scope);
    }

    @Override
    public AgentScope active() {
      return new CustomScopeManagerScope(delegate.active());
    }

    @Override
    public AgentSpan activeSpan() {
      return converter.toAgentSpan(delegate.activeSpan());
    }
  }

  private class CustomScopeManagerScope implements AgentScope {
    private final Scope delegate;

    private CustomScopeManagerScope(final Scope delegate) {
      this.delegate = delegate;
    }

    @Override
    public AgentSpan span() {
      return converter.toAgentSpan(delegate.span());
    }

    @Override
    public void setAsyncPropagation(final boolean value) {}

    @Override
    public void close() {
      delegate.close();
    }
  }

  private class OTScopeManager implements ScopeManager {
    @Override
    public Scope activate(final Span span) {
      return activate(span, false);
    }

    @Override
    public Scope activate(final Span span, final boolean finishSpanOnClose) {
      final AgentSpan agentSpan = converter.toAgentSpan(span);
      final AgentScope agentScope = coreTracer.activateSpan(agentSpan, finishSpanOnClose);

      return converter.toScope(agentScope);
    }

    @Override
    public Scope active() {
      return converter.toScope(coreTracer.activeAgentScope());
    }

    @Override
    public Span activeSpan() {
      return converter.toSpan(coreTracer.activeSpan());
    }
  }

  private class OTGenericContext implements SpanContext {
    private final DDSpanContext delegate;

    private OTGenericContext(final DDSpanContext delegate) {
      this.delegate = delegate;
    }

    @Override
    public String toTraceId() {
      return delegate.getTraceId().toString();
    }

    @Override
    public String toSpanId() {
      return delegate.getSpanId().toString();
    }

    @Override
    public Iterable<Entry<String, String>> baggageItems() {
      return delegate.baggageItems();
    }
  }

  private class OTTagContext implements SpanContext {
    private final TagContext delegate;

    private OTTagContext(final TagContext delegate) {
      this.delegate = delegate;
    }

    @Override
    public String toTraceId() {
      return "0";
    }

    @Override
    public String toSpanId() {
      return "0";
    }

    @Override
    public Iterable<Entry<String, String>> baggageItems() {
      return delegate.baggageItems();
    }
  }

  private class OTExtractedContext extends OTTagContext {
    private final ExtractedContext extractedContext;

    private OTExtractedContext(final ExtractedContext delegate) {
      super(delegate);
      this.extractedContext = delegate;
    }

    @Override
    public String toTraceId() {
      return extractedContext.getTraceId().toString();
    }

    @Override
    public String toSpanId() {
      return extractedContext.getSpanId().toString();
    }
  }

  private class OTScope implements Scope {
    private final AgentScope delegate;

    private OTScope(final AgentScope delegate) {
      this.delegate = delegate;
    }

    @Override
    public void close() {
      delegate.close();
    }

    @Override
    public Span span() {
      return converter.toSpan(delegate.span());
    }
  }

  private class OTTraceScope extends OTScope implements TraceScope {
    private final TraceScope delegate;

    private OTTraceScope(final TraceScope delegate) {
      // All instances of TraceScope implement agent scope (but not vice versa)
      super((AgentScope) delegate);

      this.delegate = delegate;
    }

    @Override
    public Continuation capture() {
      return delegate.capture();
    }

    @Override
    public boolean isAsyncPropagating() {
      return delegate.isAsyncPropagating();
    }

    @Override
    public void setAsyncPropagation(final boolean value) {
      delegate.setAsyncPropagation(value);
    }
  }

  private class OTSpan implements Span {
    private final DDSpan delegate;

    private OTSpan(final DDSpan delegate) {
      this.delegate = delegate;
    }

    @Override
    public SpanContext context() {
      return converter.toSpanContext(delegate.context());
    }

    @Override
    public Span setTag(final String key, final String value) {
      delegate.setTag(key, value);
      return this;
    }

    @Override
    public Span setTag(final String key, final boolean value) {
      delegate.setTag(key, value);
      return this;
    }

    @Override
    public Span setTag(final String key, final Number value) {
      delegate.setTag(key, value);
      return this;
    }

    @Override
    public <T> Span setTag(final Tag<T> tag, final T value) {
      delegate.setTag(tag.getKey(), value);
      return this;
    }

    @Override
    public Span log(final Map<String, ?> fields) {
      logHandler.log(fields, delegate);
      return this;
    }

    @Override
    public Span log(final long timestampMicroseconds, final Map<String, ?> fields) {
      logHandler.log(timestampMicroseconds, fields, delegate);
      return this;
    }

    @Override
    public Span log(final String event) {
      logHandler.log(event, delegate);
      return this;
    }

    @Override
    public Span log(final long timestampMicroseconds, final String event) {
      logHandler.log(timestampMicroseconds, event, delegate);
      return this;
    }

    @Override
    public Span setBaggageItem(final String key, final String value) {
      delegate.setBaggageItem(key, value);
      return this;
    }

    @Override
    public String getBaggageItem(final String key) {
      return delegate.getBaggageItem(key);
    }

    @Override
    public Span setOperationName(final String operationName) {
      delegate.setOperationName(operationName);
      return this;
    }

    @Override
    public void finish() {
      delegate.finish();
    }

    @Override
    public void finish(final long finishMicros) {
      delegate.finish(finishMicros);
    }
  }
}

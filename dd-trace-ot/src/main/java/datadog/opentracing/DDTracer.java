package datadog.opentracing;

import datadog.trace.api.Config;
import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.common.sampling.Sampler;
import datadog.trace.common.writer.Writer;
import datadog.trace.context.ScopeListener;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.CoreTracer.CoreSpanBuilder;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.core.propagation.HttpCodec;
import datadog.trace.core.propagation.TagContext;
import io.opentracing.References;
import io.opentracing.Scope;
import io.opentracing.ScopeManager;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
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

/**
 * DDTracer implements the <code>io.opentracing.Tracer</code> interface to make it easy to send
 * traces and spans to Datadog using the OpenTracing API.
 */
@Slf4j
public class DDTracer implements Tracer, datadog.trace.api.Tracer {
  private final TypeConverter converter;
  private final CoreTracer coreTracer;

  // FIXME [API] There's an unfortunate cycle between OTScopeManager and CoreTracer where they
  // each depend on each other so scopeManager can't be final
  // Perhaps the api can change so that CoreTracer doesn't need to implement scope methods directly
  private ScopeManager scopeManager;

  public static class DDTracerBuilder {
    public DDTracerBuilder withProperties(final Properties properties) {
      return config(Config.get(properties));
    }
  }

  @Deprecated
  public DDTracer() {
    this(CoreTracer.builder().build());
  }

  @Deprecated
  public DDTracer(final String serviceName) {
    this(CoreTracer.builder().serviceName(serviceName).build());
  }

  @Deprecated
  public DDTracer(final Properties properties) {
    this(CoreTracer.builder().withProperties(properties).build());
  }

  @Deprecated
  public DDTracer(final Config config) {
    this(CoreTracer.builder().config(config).build());
  }

  // This constructor is already used in the wild, so we have to keep it inside this API for now.
  @Deprecated
  public DDTracer(final String serviceName, final Writer writer, final Sampler sampler) {
    this(CoreTracer.builder().serviceName(serviceName).writer(writer).sampler(sampler).build());
  }

  @Deprecated
  DDTracer(
      final String serviceName,
      final Writer writer,
      final Sampler sampler,
      final Map<String, String> runtimeTags) {
    this(
        CoreTracer.builder()
            .serviceName(serviceName)
            .writer(writer)
            .sampler(sampler)
            .localRootSpanTags(runtimeTags)
            .build());
  }

  @Deprecated
  public DDTracer(final Writer writer) {
    this(CoreTracer.builder().writer(writer).build());
  }

  @Deprecated
  public DDTracer(final Config config, final Writer writer) {
    this(CoreTracer.builder().config(config).writer(writer).build());
  }

  @Deprecated
  public DDTracer(
      final String serviceName,
      final Writer writer,
      final Sampler sampler,
      final String runtimeId,
      final Map<String, String> localRootSpanTags,
      final Map<String, String> defaultSpanTags,
      final Map<String, String> serviceNameMappings,
      final Map<String, String> taggedHeaders) {
    this(
        CoreTracer.builder()
            .serviceName(serviceName)
            .writer(writer)
            .sampler(sampler)
            .localRootSpanTags(customRuntimeTags(runtimeId, localRootSpanTags))
            .defaultSpanTags(defaultSpanTags)
            .serviceNameMappings(serviceNameMappings)
            .taggedHeaders(taggedHeaders)
            .build());
  }

  @Deprecated
  public DDTracer(
      final String serviceName,
      final Writer writer,
      final Sampler sampler,
      final Map<String, String> localRootSpanTags,
      final Map<String, String> defaultSpanTags,
      final Map<String, String> serviceNameMappings,
      final Map<String, String> taggedHeaders) {

    this(
        CoreTracer.builder()
            .serviceName(serviceName)
            .writer(writer)
            .sampler(sampler)
            .localRootSpanTags(localRootSpanTags)
            .defaultSpanTags(defaultSpanTags)
            .serviceNameMappings(serviceNameMappings)
            .taggedHeaders(taggedHeaders)
            .build());
  }

  @Deprecated
  public DDTracer(
      final String serviceName,
      final Writer writer,
      final Sampler sampler,
      final Map<String, String> localRootSpanTags,
      final Map<String, String> defaultSpanTags,
      final Map<String, String> serviceNameMappings,
      final Map<String, String> taggedHeaders,
      final int partialFlushMinSpans) {

    this(
        CoreTracer.builder()
            .serviceName(serviceName)
            .writer(writer)
            .sampler(sampler)
            .localRootSpanTags(localRootSpanTags)
            .defaultSpanTags(defaultSpanTags)
            .serviceNameMappings(serviceNameMappings)
            .taggedHeaders(taggedHeaders)
            .partialFlushMinSpans(partialFlushMinSpans)
            .build());
  }

  // Should only be used internally by TracerInstaller
  @Deprecated
  public DDTracer(final CoreTracer coreTracer) {
    this.coreTracer = coreTracer;
    converter = new TypeConverter(new DefaultLogHandler());
    scopeManager = new OTScopeManager(coreTracer, converter);
  }

  @Builder
  // These field names must be stable to ensure the builder api is stable.
  private DDTracer(
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

    if (logHandler != null) {
      converter = new TypeConverter(logHandler);
    } else {
      converter = new TypeConverter(new DefaultLogHandler());
    }

    // Each of these are only overriden if set
    // Otherwise, the values retrieved from config will be overriden with null
    CoreTracer.CoreTracerBuilder builder = CoreTracer.builder();

    if (config != null) {
      builder = builder.config(config);
    }

    if (serviceName != null) {
      builder = builder.serviceName(serviceName);
    }

    if (writer != null) {
      builder = builder.writer(writer);
    }

    if (sampler != null) {
      builder = builder.sampler(sampler);
    }

    if (injector != null) {
      builder = builder.injector(injector);
    }

    if (extractor != null) {
      builder = builder.extractor(extractor);
    }

    if (scopeManager != null) {
      this.scopeManager = scopeManager;
      builder = builder.scopeManager(new CustomScopeManagerWrapper(scopeManager, converter));
    }

    if (localRootSpanTags != null) {
      builder = builder.localRootSpanTags(localRootSpanTags);
    }

    if (defaultSpanTags != null) {
      builder = builder.defaultSpanTags(defaultSpanTags);
    }

    if (serviceNameMappings != null) {
      builder = builder.serviceNameMappings(serviceNameMappings);
    }

    if (taggedHeaders != null) {
      builder = builder.taggedHeaders(taggedHeaders);
    }

    if (partialFlushMinSpans != 0) {
      builder = builder.partialFlushMinSpans(partialFlushMinSpans);
    }

    coreTracer = builder.build();

    // FIXME [API] There's an unfortunate cycle between OTScopeManager and CoreTracer where they
    // depend on each other so CoreTracer
    // Perhaps api can change so that CoreTracer doesn't need to implement scope methods directly
    if (scopeManager == null) {
      this.scopeManager = new OTScopeManager(coreTracer, converter);
    }
  }

  private static Map<String, String> customRuntimeTags(
      final String runtimeId, final Map<String, String> applicationRootSpanTags) {
    final Map<String, String> runtimeTags = new HashMap<>(applicationRootSpanTags);
    runtimeTags.put(Config.RUNTIME_ID_TAG, runtimeId);
    return Collections.unmodifiableMap(runtimeTags);
  }

  @Override
  public String getTraceId() {
    return coreTracer.getTraceId();
  }

  @Override
  public String getSpanId() {
    return coreTracer.getSpanId();
  }

  @Override
  public boolean addTraceInterceptor(final TraceInterceptor traceInterceptor) {
    return coreTracer.addTraceInterceptor(traceInterceptor);
  }

  @Override
  public void addScopeListener(final ScopeListener listener) {
    coreTracer.addScopeListener(listener);
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
  public DDSpanBuilder buildSpan(final String operationName) {
    return new DDSpanBuilder(operationName);
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

  public class DDSpanBuilder implements SpanBuilder {
    private final CoreSpanBuilder delegate;

    public DDSpanBuilder(final String operationName) {
      delegate = coreTracer.buildSpan(operationName);
    }

    @Override
    public DDSpanBuilder asChildOf(final SpanContext parent) {
      delegate.asChildOf(converter.toContext(parent));
      return this;
    }

    @Override
    public DDSpanBuilder asChildOf(final Span parent) {
      if (parent != null) {
        delegate.asChildOf(converter.toAgentSpan(parent).context());
      }
      return this;
    }

    @Override
    public DDSpanBuilder addReference(
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
    public DDSpanBuilder ignoreActiveSpan() {
      delegate.ignoreActiveSpan();
      return this;
    }

    @Override
    public DDSpanBuilder withTag(final String key, final String value) {
      delegate.withTag(key, value);
      return this;
    }

    @Override
    public DDSpanBuilder withTag(final String key, final boolean value) {
      delegate.withTag(key, value);
      return this;
    }

    @Override
    public DDSpanBuilder withTag(final String key, final Number value) {
      delegate.withTag(key, value);
      return this;
    }

    @Override
    public <T> DDSpanBuilder withTag(final Tag<T> tag, final T value) {
      delegate.withTag(tag.getKey(), value);
      return this;
    }

    @Override
    public DDSpanBuilder withStartTimestamp(final long microseconds) {
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

    /** @deprecated use {@link #start()} instead. */
    @Deprecated
    @Override
    public Scope startActive(final boolean finishSpanOnClose) {
      return scopeManager.activate(start(), finishSpanOnClose);
    }

    public DDSpanBuilder withServiceName(final String serviceName) {
      delegate.withServiceName(serviceName);
      return this;
    }

    public DDSpanBuilder withResourceName(final String resourceName) {
      delegate.withResourceName(resourceName);
      return this;
    }

    public DDSpanBuilder withErrorFlag() {
      delegate.withErrorFlag();
      return this;
    }

    public DDSpanBuilder withSpanType(final String spanType) {
      delegate.withSpanType(spanType);
      return this;
    }
  }
}

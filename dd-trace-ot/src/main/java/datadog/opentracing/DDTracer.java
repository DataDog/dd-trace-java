package datadog.opentracing;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromSpanContext;

import datadog.context.propagation.CarrierSetter;
import datadog.metrics.api.statsd.StatsDClient;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.experimental.DataStreamsCheckpointer;
import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.api.internal.InternalTracer;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.api.profiling.Profiling;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.common.sampling.Sampler;
import datadog.trace.common.writer.Writer;
import datadog.trace.context.TraceScope;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.core.propagation.HttpCodec;
import datadog.trace.correlation.CorrelationIdInjectors;
import io.opentracing.References;
import io.opentracing.Scope;
import io.opentracing.ScopeManager;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMap;
import io.opentracing.tag.Tag;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DDTracer implements the <code>io.opentracing.Tracer</code> interface to make it easy to send
 * traces and spans to Datadog using the OpenTracing API.
 */
public class DDTracer implements Tracer, datadog.trace.api.Tracer, InternalTracer {
  private static final String INSTRUMENTATION_NAME = "opentracing";
  private static final Logger log = LoggerFactory.getLogger(DDTracer.class);

  static {
    ClassLoader classLoader = DDTracer.class.getClassLoader();
    if (classLoader == null) {
      log.error("dd-trace-ot should not be on the bootstrap classpath.");
    } else {
      try {
        Class<?> bootstrapClass =
            Class.forName("datadog.trace.bootstrap.AgentBootstrap", false, classLoader);
        if (bootstrapClass.getClassLoader() == null) {
          log.error("dd-trace-ot should not be used with dd-java-agent.");
        } else {
          log.error("dd-java-agent should not be on the classpath.");
        }
      } catch (ClassNotFoundException expected) {
        // ignore
      }
    }
  }

  public static DDTracerBuilder builder() {
    return new DDTracerBuilder();
  }

  private final TypeConverter converter;
  private final AgentTracer.TracerAPI tracer;

  // FIXME [API] There's an unfortunate cycle between OTScopeManager and CoreTracer where they
  // each depend on each other so scopeManager can't be final
  // Perhaps the api can change so that CoreTracer doesn't need to implement scope methods directly
  private ScopeManager scopeManager;

  public static class DDTracerBuilder {

    private Config config;
    private String serviceName;
    private Writer writer;
    private Sampler sampler;
    private HttpCodec.Injector injector;
    private HttpCodec.Extractor extractor;
    private Map<String, String> localRootSpanTags;
    private Map<String, String> defaultSpanTags;
    private Map<String, String> serviceNameMappings;
    private Map<String, String> taggedHeaders;
    private int partialFlushMinSpans;
    private LogHandler logHandler;
    private StatsDClient statsDClient;

    public DDTracerBuilder config(Config config) {
      this.config = config;
      return this;
    }

    public DDTracerBuilder serviceName(String serviceName) {
      this.serviceName = serviceName;
      return this;
    }

    public DDTracerBuilder writer(Writer writer) {
      this.writer = writer;
      return this;
    }

    public DDTracerBuilder sampler(Sampler sampler) {
      this.sampler = sampler;
      return this;
    }

    public DDTracerBuilder injector(HttpCodec.Injector injector) {
      this.injector = injector;
      return this;
    }

    public DDTracerBuilder extractor(HttpCodec.Extractor extractor) {
      this.extractor = extractor;
      return this;
    }

    @Deprecated
    public DDTracerBuilder scopeManager(ScopeManager scopeManager) {
      log.warn("Custom ScopeManagers are not supported");
      return this;
    }

    public DDTracerBuilder localRootSpanTags(Map<String, String> localRootSpanTags) {
      this.localRootSpanTags = localRootSpanTags;
      return this;
    }

    public DDTracerBuilder defaultSpanTags(Map<String, String> defaultSpanTags) {
      this.defaultSpanTags = defaultSpanTags;
      return this;
    }

    public DDTracerBuilder serviceNameMappings(Map<String, String> serviceNameMappings) {
      this.serviceNameMappings = serviceNameMappings;
      return this;
    }

    public DDTracerBuilder taggedHeaders(Map<String, String> taggedHeaders) {
      this.taggedHeaders = taggedHeaders;
      return this;
    }

    public DDTracerBuilder partialFlushMinSpans(int partialFlushMinSpans) {
      this.partialFlushMinSpans = partialFlushMinSpans;
      return this;
    }

    public DDTracerBuilder logHandler(LogHandler logHandler) {
      this.logHandler = logHandler;
      return this;
    }

    public DDTracerBuilder statsDClient(StatsDClient statsDClient) {
      this.statsDClient = statsDClient;
      return this;
    }

    public DDTracerBuilder withProperties(final Properties properties) {
      return config(Config.get(properties));
    }

    public DDTracer build() {
      return new DDTracer(
          config,
          serviceName,
          writer,
          sampler,
          injector,
          extractor,
          localRootSpanTags,
          defaultSpanTags,
          serviceNameMappings,
          taggedHeaders,
          partialFlushMinSpans,
          logHandler,
          statsDClient);
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
  public DDTracer(final AgentTracer.TracerAPI tracer) {
    this.tracer = tracer;
    converter = new TypeConverter(new DefaultLogHandler());
    scopeManager = new OTScopeManager(tracer, converter);
  }

  private DDTracer(
      @Deprecated final Config config,
      final String serviceName,
      final Writer writer,
      final Sampler sampler,
      final HttpCodec.Injector injector,
      final HttpCodec.Extractor extractor,
      final Map<String, String> localRootSpanTags,
      final Map<String, String> defaultSpanTags,
      final Map<String, String> serviceNameMappings,
      final Map<String, String> taggedHeaders,
      final int partialFlushMinSpans,
      final LogHandler logHandler,
      final StatsDClient statsDClient) {

    // Check if the tracer is already installed by the agent
    // Unable to use "instanceof" because of class renaming
    String expectedName =
        "avoid_rewrite.datadog.trace.agent.core.CoreTracer".substring("avoid_rewrite.".length());
    if (GlobalTracer.get().getClass().getName().equals(expectedName)) {
      log.error(
          "Datadog Tracer already installed by `dd-java-agent`. NOTE: Manually creating the tracer while using `dd-java-agent` is not supported");
      throw new IllegalStateException("Datadog Tracer already installed");
    }

    if (logHandler != null) {
      converter = new TypeConverter(logHandler);
    } else {
      converter = new TypeConverter(new DefaultLogHandler());
    }

    // Each of these are only overridden if set
    // Otherwise, the values retrieved from config will be overridden with null
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

    if (statsDClient != null) {
      builder = builder.statsDClient(statsDClient);
    }

    tracer = builder.build();

    // FIXME [API] There's an unfortunate cycle between OTScopeManager and CoreTracer where they
    // depend on each other so CoreTracer
    // Perhaps api can change so that CoreTracer doesn't need to implement scope methods directly
    this.scopeManager = new OTScopeManager(tracer, converter);

    if ((config != null && config.isLogsInjectionEnabled())
        || (config == null && Config.get().isLogsInjectionEnabled())) {
      CorrelationIdInjectors.register(this);
    }
  }

  private static Map<String, String> customRuntimeTags(
      final String runtimeId, final Map<String, String> applicationRootSpanTags) {
    final Map<String, String> runtimeTags = new HashMap<>(applicationRootSpanTags);
    runtimeTags.put(DDTags.RUNTIME_ID_TAG, runtimeId);
    return Collections.unmodifiableMap(runtimeTags);
  }

  @Override
  public String getTraceId() {
    return tracer.getTraceId();
  }

  @Override
  public String getSpanId() {
    return tracer.getSpanId();
  }

  @Override
  public boolean addTraceInterceptor(final TraceInterceptor traceInterceptor) {
    return tracer.addTraceInterceptor(traceInterceptor);
  }

  @Override
  public TraceScope muteTracing() {
    return tracer.muteTracing();
  }

  @Override
  public TraceScope.Continuation captureActiveSpan() {
    return tracer.captureActiveSpan();
  }

  @Override
  public boolean isAsyncPropagationEnabled() {
    return tracer.isAsyncPropagationEnabled();
  }

  @Override
  public void setAsyncPropagationEnabled(boolean asyncPropagationEnabled) {
    tracer.setAsyncPropagationEnabled(asyncPropagationEnabled);
  }

  @Override
  public DataStreamsCheckpointer getDataStreamsCheckpointer() {
    return tracer.getDataStreamsCheckpointer();
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
    if (carrier instanceof TextMap) {
      final AgentSpanContext context = converter.toContext(spanContext);
      AgentSpan span = fromSpanContext(context);
      defaultPropagator().inject(span, (TextMap) carrier, TextMapSetter.INSTANCE);
    } else {
      log.debug("Unsupported format for propagation - {}", format.getClass().getName());
    }
  }

  @Override
  public <C> SpanContext extract(final Format<C> format, final C carrier) {
    if (carrier instanceof TextMap) {
      final AgentSpanContext tagContext =
          extractContextAndGetSpanContext((TextMap) carrier, new TextMapGetter((TextMap) carrier));

      return converter.toSpanContext(tagContext);
    } else {
      log.debug("Unsupported format for propagation - {}", format.getClass().getName());
      return null;
    }
  }

  @Override
  public void addScopeListener(
      Runnable afterScopeActivatedCallback, Runnable afterScopeClosedCallback) {
    tracer.addScopeListener(afterScopeActivatedCallback, afterScopeClosedCallback);
  }

  @Override
  public void flush() {
    tracer.flush();
  }

  @Override
  public void flushMetrics() {
    tracer.flushMetrics();
  }

  @Override
  public Profiling getProfilingContext() {
    return tracer != null ? tracer.getProfilingContext() : Profiling.NoOp.INSTANCE;
  }

  @Override
  public TraceSegment getTraceSegment() {
    AgentSpanContext ctx = tracer.activeSpan().context();
    if (ctx instanceof DDSpanContext) {
      return ((DDSpanContext) ctx).getTraceSegment();
    }
    return null;
  }

  @Override
  public void close() {
    tracer.close();
  }

  private static class TextMapSetter implements CarrierSetter<TextMap> {
    static final TextMapSetter INSTANCE = new TextMapSetter();

    @Override
    public void set(final TextMap carrier, final String key, final String value) {
      carrier.put(key, value);
    }
  }

  private static class TextMapGetter implements AgentPropagation.ContextVisitor<TextMap> {
    private final TextMap carrier;

    private TextMapGetter(final TextMap carrier) {
      this.carrier = carrier;
    }

    @Override
    public void forEachKey(final TextMap ignored, final AgentPropagation.KeyClassifier classifier) {
      for (final Entry<String, String> entry : carrier) {
        if (!classifier.accept(entry.getKey(), entry.getValue())) {
          return;
        }
      }
    }
  }

  public class DDSpanBuilder implements SpanBuilder {
    private final AgentTracer.SpanBuilder delegate;

    public DDSpanBuilder(final String operationName) {
      delegate = tracer.buildSpan(INSTRUMENTATION_NAME, operationName);
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

      final AgentSpanContext context = converter.toContext(referencedContext);
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
      agentSpan.context().setIntegrationName("opentracing");
      return converter.toSpan(agentSpan);
    }

    /**
     * @deprecated use {@link #start()} instead.
     */
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

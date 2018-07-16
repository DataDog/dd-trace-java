package stackstate.opentracing;

import stackstate.opentracing.decorators.AbstractDecorator;
import stackstate.opentracing.decorators.STSDecoratorsFactory;
import stackstate.opentracing.propagation.Codec;
import stackstate.opentracing.propagation.ExtractedContext;
import stackstate.opentracing.propagation.HTTPCodec;
import stackstate.opentracing.scopemanager.ContextualScopeManager;
import stackstate.opentracing.scopemanager.ScopeContext;
import stackstate.trace.api.CorrelationIdentifier;
import stackstate.trace.api.interceptor.MutableSpan;
import stackstate.trace.api.interceptor.TraceInterceptor;
import stackstate.trace.api.sampling.PrioritySampling;
import stackstate.trace.common.STSTraceConfig;
import stackstate.trace.common.sampling.AllSampler;
import stackstate.trace.common.sampling.RateByServiceSampler;
import stackstate.trace.common.sampling.Sampler;
import stackstate.trace.common.writer.STSAgentWriter;
import stackstate.trace.common.writer.STSApi;
import stackstate.trace.common.writer.Writer;
import io.opentracing.Scope;
import io.opentracing.ScopeManager;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.propagation.Format;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/** STSTracer makes it easy to send traces and span to STS using the OpenTracing API. */
@Slf4j
public class STSTracer implements io.opentracing.Tracer {

  public static final String UNASSIGNED_DEFAULT_SERVICE_NAME = "unnamed-java-app";

  /** Default service name if none provided on the trace or span */
  final String serviceName;
  /** Writer is an charge of reporting traces and spans to the desired endpoint */
  final Writer writer;
  /** Sampler defines the sampling policy in order to reduce the number of traces for instance */
  final Sampler sampler;
  /** Scope manager is in charge of managing the scopes from which spans are created */
  final ContextualScopeManager scopeManager = new ContextualScopeManager();

  /** A set of tags that are added to every span */
  private final Map<String, String> spanTags;

  /** Span context decorators */
  private final Map<String, List<AbstractDecorator>> spanContextDecorators =
      new ConcurrentHashMap<>();

  private final SortedSet<TraceInterceptor> interceptors =
      new ConcurrentSkipListSet<>(
          new Comparator<TraceInterceptor>() {
            @Override
            public int compare(final TraceInterceptor o1, final TraceInterceptor o2) {
              return Integer.compare(o1.priority(), o2.priority());
            }
          });
  private final CodecRegistry registry;

  private final AtomicInteger traceCount = new AtomicInteger(0);

  /** By default, report to local agent and collect all traces. */
  public STSTracer() {
    this(new STSTraceConfig());
  }

  public STSTracer(final String serviceName) {
    this(new STSTraceConfig(serviceName));
  }

  public STSTracer(final Properties config) {
    this(
        config.getProperty(STSTraceConfig.SERVICE_NAME),
        Writer.Builder.forConfig(config),
        Sampler.Builder.forConfig(config),
        STSTraceConfig.parseMap(config.getProperty(STSTraceConfig.SPAN_TAGS)),
        STSTraceConfig.parseMap(config.getProperty(STSTraceConfig.SERVICE_MAPPING)),
        STSTraceConfig.parseMap(config.getProperty(STSTraceConfig.HEADER_TAGS)));
    log.debug("Using config: {}", config);
  }

  public STSTracer(final String serviceName, final Writer writer, final Sampler sampler) {
    this(
        serviceName,
        writer,
        sampler,
        Collections.<String, String>emptyMap(),
        Collections.<String, String>emptyMap(),
        Collections.<String, String>emptyMap());
  }

  public STSTracer(
      final String serviceName,
      final Writer writer,
      final Sampler sampler,
      final Map<String, String> defaultSpanTags,
      final Map<String, String> serviceNameMappings,
      final Map<String, String> taggedHeaders) {
    this.serviceName = serviceName;
    this.writer = writer;
    this.writer.start();
    this.sampler = sampler;
    this.spanTags = defaultSpanTags;

    try {
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread() {
                @Override
                public void run() {
                  DDTracer.this.close();
                }
              });
    } catch (final IllegalStateException ex) {
      // The JVM is already shutting down.
    }

    registry = new CodecRegistry();
    registry.register(Format.Builtin.HTTP_HEADERS, new HTTPCodec(taggedHeaders));
    registry.register(Format.Builtin.TEXT_MAP, new HTTPCodec(taggedHeaders));
    if (this.writer instanceof STSAgentWriter && sampler instanceof STSApi.ResponseListener) {
      final STSApi api = ((STSAgentWriter) this.writer).getApi();
      api.addResponseListener((STSApi.ResponseListener) this.sampler);
      api.addTraceCounter(traceCount);
    }

    registerClassLoader(ClassLoader.getSystemClassLoader());

    final List<AbstractDecorator> decorators =
        STSDecoratorsFactory.createBuiltinDecorators(serviceNameMappings);
    for (final AbstractDecorator decorator : decorators) {
      log.debug("Loading decorator: {}", decorator.getClass().getSimpleName());
      addDecorator(decorator);
    }

    CorrelationIdentifier.registerIfAbsent(OTTraceCorrelation.INSTANCE);

    log.info("New instance: {}", this);
  }

  public STSTracer(final Writer writer) {
    this(
        UNASSIGNED_DEFAULT_SERVICE_NAME,
        writer,
        new AllSampler(),
        STSTraceConfig.parseMap(new STSTraceConfig().getProperty(STSTraceConfig.SPAN_TAGS)),
        STSTraceConfig.parseMap(new STSTraceConfig().getProperty(STSTraceConfig.SERVICE_MAPPING)),
        STSTraceConfig.parseMap(new STSTraceConfig().getProperty(STSTraceConfig.HEADER_TAGS)));
  }

  /**
   * Returns the list of span context decorators
   *
   * @return the list of span context decorators
   */
  public List<AbstractDecorator> getSpanContextDecorators(final String tag) {
    return spanContextDecorators.get(tag);
  }

  /**
   * Add a new decorator in the list ({@link AbstractDecorator})
   *
   * @param decorator The decorator in the list
   */
  public void addDecorator(final AbstractDecorator decorator) {

    List<AbstractDecorator> list = spanContextDecorators.get(decorator.getMatchingTag());
    if (list == null) {
      list = new ArrayList<>();
    }
    list.add(decorator);

    spanContextDecorators.put(decorator.getMatchingTag(), list);
  }

  /**
   * Add a new interceptor to the tracer. Interceptors with duplicate priority to existing ones are
   * ignored.
   *
   * @param interceptor
   * @return false if an interceptor with same priority exists.
   */
  public boolean addInterceptor(final TraceInterceptor interceptor) {
    return interceptors.add(interceptor);
  }

  public void addScopeContext(final ScopeContext context) {
    scopeManager.addScopeContext(context);
  }

  /**
   * If an application is using a non-system classloader, that classloader should be registered
   * here. Due to the way Spring Boot structures its' executable jar, this might log some warnings.
   *
   * @param classLoader to register.
   */
  public void registerClassLoader(final ClassLoader classLoader) {
    try {
      for (final TraceInterceptor interceptor :
          ServiceLoader.load(TraceInterceptor.class, classLoader)) {
        addInterceptor(interceptor);
      }
    } catch (final ServiceConfigurationError e) {
      log.warn("Problem loading TraceInterceptor for classLoader: " + classLoader, e);
    }
  }

  @Override
  public ContextualScopeManager scopeManager() {
    return scopeManager;
  }

  @Override
  public Span activeSpan() {
    final Scope active = scopeManager.active();
    return active == null ? null : active.span();
  }

  @Override
  public STSSpanBuilder buildSpan(final String operationName) {
    return new STSSpanBuilder(operationName, scopeManager);
  }

  @Override
  public <T> void inject(final SpanContext spanContext, final Format<T> format, final T carrier) {

    final Codec<T> codec = registry.get(format);
    if (codec == null) {
      log.debug("Unsupported format for propagation - {}", format.getClass().getName());
    } else {
      codec.inject((STSSpanContext) spanContext, carrier);
    }
  }

  @Override
  public <T> SpanContext extract(final Format<T> format, final T carrier) {
    final Codec<T> codec = registry.get(format);
    if (codec == null) {
      log.debug("Unsupported format for propagation - {}", format.getClass().getName());
    } else {
      return codec.extract(carrier);
    }
    return null;
  }

  /**
   * We use the sampler to know if the trace has to be reported/written. The sampler is called on
   * the first span (root span) of the trace. If the trace is marked as a sample, we report it.
   *
   * @param trace a list of the spans related to the same trace
   */
  void write(final PendingTrace trace) {
    if (trace.isEmpty()) {
      return;
    }
    final ArrayList<STSSpan> writtenTrace;
    if (interceptors.isEmpty()) {
      writtenTrace = new ArrayList<>(trace);
    } else {
      Collection<? extends MutableSpan> interceptedTrace = new ArrayList<>(trace);
      for (final TraceInterceptor interceptor : interceptors) {
        interceptedTrace = interceptor.onTraceComplete(interceptedTrace);
      }
      writtenTrace = new ArrayList<>(interceptedTrace.size());
      for (final MutableSpan span : interceptedTrace) {
        if (span instanceof STSSpan) {
          writtenTrace.add((STSSpan) span);
        }
      }
    }
    traceCount.incrementAndGet();
    if (!writtenTrace.isEmpty() && this.sampler.sample(writtenTrace.get(0))) {
      this.writer.write(writtenTrace);
    }
  }

  public void close() {
    PendingTrace.close();
    writer.close();
  }

  @Override
  public String toString() {
    return "STSTracer-"
        + Integer.toHexString(hashCode())
        + "{ service-name="
        + serviceName
        + ", writer="
        + writer
        + ", sampler="
        + sampler
        + ", tags="
        + spanTags
        + '}';
  }

  private static class CodecRegistry {

    private final Map<Format<?>, Codec<?>> codecs = new HashMap<>();

    <T> Codec<T> get(final Format<T> format) {
      return (Codec<T>) codecs.get(format);
    }

    public <T> void register(final Format<T> format, final Codec<T> codec) {
      codecs.put(format, codec);
    }
  }

  /** Spans are built using this builder */
  public class STSSpanBuilder implements SpanBuilder {
    private final ScopeManager scopeManager;

    /** Each span must have an operationName according to the opentracing specification */
    private final String operationName;

    // Builder attributes
    private Map<String, Object> tags =
        spanTags.isEmpty()
            ? Collections.<String, Object>emptyMap()
            : new HashMap<String, Object>(spanTags);
    private long timestampMicro;
    private SpanContext parent;
    private String serviceName;
    private String resourceName;
    private boolean errorFlag;
    private String spanType;
    private boolean ignoreScope = false;

    public STSSpanBuilder(final String operationName, final ScopeManager scopeManager) {
      this.operationName = operationName;
      this.scopeManager = scopeManager;
    }

    @Override
    public SpanBuilder ignoreActiveSpan() {
      this.ignoreScope = true;
      return this;
    }

    private STSSpan startSpan() {
      final STSSpan span = new STSSpan(this.timestampMicro, buildSpanContext());
      if (STSTracer.this.sampler instanceof RateByServiceSampler) {
        ((RateByServiceSampler) STSTracer.this.sampler).initializeSamplingPriority(span);
      }
      return span;
    }

    @Override
    public Scope startActive(final boolean finishSpanOnClose) {
      final STSSpan span = startSpan();
      final Scope scope = scopeManager.activate(span, finishSpanOnClose);
      log.debug("Starting a new active span: {}", span);
      return scope;
    }

    @Override
    @Deprecated
    public STSSpan startManual() {
      return start();
    }

    @Override
    public STSSpan start() {
      final STSSpan span = startSpan();
      log.debug("Starting a new span: {}", span);
      return span;
    }

    @Override
    public STSSpanBuilder withTag(final String tag, final Number number) {
      return withTag(tag, (Object) number);
    }

    @Override
    public STSSpanBuilder withTag(final String tag, final String string) {
      return withTag(tag, (Object) string);
    }

    @Override
    public STSSpanBuilder withTag(final String tag, final boolean bool) {
      return withTag(tag, (Object) bool);
    }

    @Override
    public STSSpanBuilder withStartTimestamp(final long timestampMicroseconds) {
      this.timestampMicro = timestampMicroseconds;
      return this;
    }

    public STSSpanBuilder withServiceName(final String serviceName) {
      this.serviceName = serviceName;
      return this;
    }

    public STSSpanBuilder withResourceName(final String resourceName) {
      this.resourceName = resourceName;
      return this;
    }

    public STSSpanBuilder withErrorFlag() {
      this.errorFlag = true;
      return this;
    }

    public STSSpanBuilder withSpanType(final String spanType) {
      this.spanType = spanType;
      return this;
    }

    public Iterable<Map.Entry<String, String>> baggageItems() {
      if (parent == null) {
        return Collections.emptyList();
      }
      return parent.baggageItems();
    }

    @Override
    public STSSpanBuilder asChildOf(final Span span) {
      return asChildOf(span == null ? null : span.context());
    }

    @Override
    public STSSpanBuilder asChildOf(final SpanContext spanContext) {
      this.parent = spanContext;
      return this;
    }

    @Override
    public STSSpanBuilder addReference(final String referenceType, final SpanContext spanContext) {
      log.debug("`addReference` method is not implemented. Doing nothing");
      return this;
    }

    // Private methods
    private STSSpanBuilder withTag(final String tag, final Object value) {
      if (this.tags.isEmpty()) {
        this.tags = new HashMap<>();
      }
      this.tags.put(tag, value);
      return this;
    }

    private long generateNewId() {
      // Ensure the generated ID is in a valid range:
      return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    }

    /**
     * Build the SpanContext, if the actual span has a parent, the following attributes must be
     * propagated: - ServiceName - Baggage - Trace (a list of all spans related) - SpanType
     *
     * @return the context
     */
    private STSSpanContext buildSpanContext() {
      final long traceId;
      final long spanId = generateNewId();
      final long parentSpanId;
      final Map<String, String> baggage;
      final PendingTrace parentTrace;
      final int samplingPriority;

      final STSSpanContext context;
      SpanContext parentContext = this.parent;
      if (parentContext == null && !ignoreScope) {
        // use the Scope as parent unless overridden or ignored.
        final Scope scope = scopeManager.active();
        if (scope != null) parentContext = scope.span().context();
      }

      // Propagate internal trace
      if (parentContext instanceof STSSpanContext) {
        final STSSpanContext stssc = (STSSpanContext) parentContext;
        traceId = stssc.getTraceId();
        parentSpanId = stssc.getSpanId();
        baggage = stssc.getBaggageItems();
        parentTrace = stssc.getTrace();
        samplingPriority = PrioritySampling.UNSET;
        if (this.serviceName == null) this.serviceName = stssc.getServiceName();
        if (this.spanType == null) this.spanType = stssc.getSpanType();

        // Propagate external trace
      } else if (parentContext instanceof ExtractedContext) {
        final ExtractedContext stssc = (ExtractedContext) parentContext;
        traceId = stssc.getTraceId();
        parentSpanId = stssc.getSpanId();
        baggage = stssc.getBaggage();
        if (this.tags.isEmpty() && !stssc.getTags().isEmpty()) {
          this.tags = new HashMap<>();
        }
        if (!stssc.getTags().isEmpty()) {
          tags.putAll(ddsc.getTags());
        }
        parentTrace = new PendingTrace(STSTracer.this, traceId);
        samplingPriority = stssc.getSamplingPriority();

        // Start a new trace
      } else {
        traceId = generateNewId();
        parentSpanId = 0L;
        baggage = null;
        parentTrace = new PendingTrace(STSTracer.this, traceId);
        samplingPriority = PrioritySampling.UNSET;
      }

      if (serviceName == null) {
        serviceName = STSTracer.this.serviceName;
      }

      final String operationName =
          this.operationName != null ? this.operationName : this.resourceName;

      // some attributes are inherited from the parent
      context =
          new STSSpanContext(
              traceId,
              spanId,
              parentSpanId,
              serviceName,
              operationName,
              this.resourceName,
              samplingPriority,
              baggage,
              errorFlag,
              spanType,
              this.tags,
              parentTrace,
              STSTracer.this);

      // Apply Decorators to handle any tags that may have been set via the builder.
      for (final Map.Entry<String, Object> tag : this.tags.entrySet()) {
        if (tag.getValue() == null) {
          context.setTag(tag.getKey(), null);
          continue;
        }

        boolean addTag = true;

        // Call decorators
        final List<AbstractDecorator> decorators =
            STSTracer.this.getSpanContextDecorators(tag.getKey());
        if (decorators != null) {
          for (final AbstractDecorator decorator : decorators) {
            try {
              addTag &= decorator.shouldSetTag(context, tag.getKey(), tag.getValue());
            } catch (final Throwable ex) {
              log.debug(
                  "Could not decorate the span decorator={}: {}",
                  decorator.getClass().getSimpleName(),
                  ex.getMessage());
            }
          }
        }

        if (!addTag) {
          context.setTag(tag.getKey(), null);
        }
      }

      return context;
    }
  }
}

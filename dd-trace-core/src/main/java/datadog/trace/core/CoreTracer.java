package datadog.trace.core;

import static datadog.trace.bootstrap.instrumentation.api.PrioritizationConstants.ENSURE_TRACE_TYPE;
import static datadog.trace.bootstrap.instrumentation.api.WriterConstants.DD_AGENT_WRITER_TYPE;
import static datadog.trace.bootstrap.instrumentation.api.WriterConstants.LOGGING_WRITER_TYPE;
import static datadog.trace.bootstrap.instrumentation.api.WriterConstants.PRINTING_WRITER_TYPE;
import static datadog.trace.bootstrap.instrumentation.api.WriterConstants.TRACE_STRUCTURE_WRITER_TYPE;

import com.timgroup.statsd.NoOpStatsDClient;
import com.timgroup.statsd.NonBlockingStatsDClient;
import com.timgroup.statsd.StatsDClient;
import datadog.common.container.ServerlessInfo;
import datadog.trace.api.Config;
import datadog.trace.api.ConfigDefaults;
import datadog.trace.api.DDId;
import datadog.trace.api.IdGenerationStrategy;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentScopeManager;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ScopeSource;
import datadog.trace.common.sampling.PrioritySampler;
import datadog.trace.common.sampling.Sampler;
import datadog.trace.common.writer.DDAgentWriter;
import datadog.trace.common.writer.LoggingWriter;
import datadog.trace.common.writer.PrintingWriter;
import datadog.trace.common.writer.TraceStructureWriter;
import datadog.trace.common.writer.Writer;
import datadog.trace.common.writer.ddagent.DDAgentApi;
import datadog.trace.common.writer.ddagent.DDAgentResponseListener;
import datadog.trace.common.writer.ddagent.Prioritization;
import datadog.trace.context.ScopeListener;
import datadog.trace.context.TraceScope;
import datadog.trace.core.jfr.DDNoopScopeEventFactory;
import datadog.trace.core.jfr.DDScopeEventFactory;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.monitor.Monitoring;
import datadog.trace.core.monitor.Recording;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.core.propagation.HttpCodec;
import datadog.trace.core.propagation.TagContext;
import datadog.trace.core.scopemanager.ContinuableScopeManager;
import datadog.trace.core.taginterceptor.AbstractTagInterceptor;
import datadog.trace.core.taginterceptor.TagInterceptorsFactory;
import java.lang.ref.WeakReference;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.TimeUnit;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

/**
 * Main entrypoint into the tracer implementation. In addition to implementing
 * datadog.trace.api.Tracer and TracerAPI, it coordinates many functions necessary creating,
 * reporting, and propagating traces
 */
@Slf4j
public class CoreTracer implements AgentTracer.TracerAPI {
  // UINT64 max value
  public static final BigInteger TRACE_ID_MAX =
      BigInteger.valueOf(2).pow(64).subtract(BigInteger.ONE);

  private static final String LANG_STATSD_TAG = "lang";
  private static final String LANG_VERSION_STATSD_TAG = "lang_version";
  private static final String LANG_INTERPRETER_STATSD_TAG = "lang_interpreter";
  private static final String LANG_INTERPRETER_VENDOR_STATSD_TAG = "lang_interpreter_vendor";
  private static final String TRACER_VERSION_STATSD_TAG = "tracer_version";

  /** Default service name if none provided on the trace or span */
  final String serviceName;
  /** Writer is an charge of reporting traces and spans to the desired endpoint */
  final Writer writer;
  /** Sampler defines the sampling policy in order to reduce the number of traces for instance */
  final Sampler sampler;
  /** Scope manager is in charge of managing the scopes from which spans are created */
  final AgentScopeManager scopeManager;

  /** A set of tags that are added only to the application's root span */
  private final Map<String, String> localRootSpanTags;
  /** A set of tags that are added to every span */
  private final Map<String, String> defaultSpanTags;
  /** A configured mapping of service names to update with new values */
  private final Map<String, String> serviceNameMappings;

  /** number of spans in a pending trace before they get flushed */
  @lombok.Getter private final int partialFlushMinSpans;

  private final StatsDClient statsDClient;
  private final Monitoring monitoring;
  private final Monitoring performanceMonitoring;
  private final Recording traceWriteTimer;
  private final IdGenerationStrategy idGenerationStrategy;

  /**
   * JVM shutdown callback, keeping a reference to it to remove this if DDTracer gets destroyed
   * earlier
   */
  private final Thread shutdownCallback;

  /**
   * Span tag interceptors. This Map is only ever added to during initialization, so it doesn't need
   * to be concurrent.
   */
  private final Map<String, List<AbstractTagInterceptor>> spanTagInterceptors = new HashMap<>();

  private final SortedSet<TraceInterceptor> interceptors =
      new ConcurrentSkipListSet<>(
          new Comparator<TraceInterceptor>() {
            @Override
            public int compare(final TraceInterceptor o1, final TraceInterceptor o2) {
              return Integer.compare(o1.priority(), o2.priority());
            }
          });

  private final HttpCodec.Injector injector;
  private final HttpCodec.Extractor extractor;

  @Override
  public TraceScope.Continuation capture() {
    final TraceScope activeScope = activeScope();

    return activeScope == null ? null : activeScope.capture();
  }

  public static class CoreTracerBuilder {

    public CoreTracerBuilder() {
      // Apply the default values from config.
      config(Config.get());
    }

    public CoreTracerBuilder withProperties(final Properties properties) {
      return config(Config.get(properties));
    }

    public CoreTracerBuilder config(final Config config) {
      this.config = config;
      serviceName(config.getServiceName());
      // Explicitly skip setting writer to avoid allocating resources prematurely.
      sampler(Sampler.Builder.forConfig(config));
      injector(HttpCodec.createInjector(config));
      extractor(HttpCodec.createExtractor(config, config.getHeaderTags()));
      // Explicitly skip setting scope manager because it depends on statsDClient
      localRootSpanTags(config.getLocalRootSpanTags());
      defaultSpanTags(config.getMergedSpanTags());
      serviceNameMappings(config.getServiceMapping());
      taggedHeaders(config.getHeaderTags());
      partialFlushMinSpans(config.getPartialFlushMinSpans());

      return this;
    }
  }

  @Builder
  // These field names must be stable to ensure the builder api is stable.
  private CoreTracer(
      final Config config,
      final String serviceName,
      final Writer writer,
      final IdGenerationStrategy idGenerationStrategy,
      final Sampler sampler,
      final HttpCodec.Injector injector,
      final HttpCodec.Extractor extractor,
      final AgentScopeManager scopeManager,
      final Map<String, String> localRootSpanTags,
      final Map<String, String> defaultSpanTags,
      final Map<String, String> serviceNameMappings,
      final Map<String, String> taggedHeaders,
      final int partialFlushMinSpans,
      final StatsDClient statsDClient) {

    assert localRootSpanTags != null;
    assert defaultSpanTags != null;
    assert serviceNameMappings != null;
    assert taggedHeaders != null;

    this.serviceName = serviceName;
    this.sampler = sampler;
    this.injector = injector;
    this.extractor = extractor;
    this.localRootSpanTags = localRootSpanTags;
    this.defaultSpanTags = defaultSpanTags;
    this.serviceNameMappings = serviceNameMappings;
    this.partialFlushMinSpans = partialFlushMinSpans;
    this.idGenerationStrategy =
        null == idGenerationStrategy
            ? Config.get().getIdGenerationStrategy()
            : idGenerationStrategy;

    if (statsDClient == null) {
      this.statsDClient = createStatsDClient(config);
    } else {
      this.statsDClient = statsDClient;
    }
    this.monitoring =
        config.isHealthMetricsEnabled()
            ? new Monitoring(this.statsDClient, 10, TimeUnit.SECONDS)
            : Monitoring.DISABLED;
    this.performanceMonitoring =
        config.isPerfMetricsEnabled()
            ? new Monitoring(this.statsDClient, 10, TimeUnit.SECONDS)
            : Monitoring.DISABLED;
    this.traceWriteTimer = performanceMonitoring.newThreadLocalTimer("trace.write");
    if (scopeManager == null) {
      this.scopeManager =
          new ContinuableScopeManager(
              config.getScopeDepthLimit(),
              createScopeEventFactory(),
              this.statsDClient,
              config.isScopeStrictMode());
    } else {
      this.scopeManager = scopeManager;
    }

    if (writer == null) {
      this.writer = createWriter(config, sampler, this.statsDClient, monitoring);
    } else {
      this.writer = writer;
    }

    this.writer.start();

    shutdownCallback = new ShutdownHook(this);
    try {
      Runtime.getRuntime().addShutdownHook(shutdownCallback);
    } catch (final IllegalStateException ex) {
      // The JVM is already shutting down.
    }

    log.info("New instance: {}", this);

    final List<AbstractTagInterceptor> tagInterceptors =
        TagInterceptorsFactory.createTagInterceptors();
    for (final AbstractTagInterceptor interceptor : tagInterceptors) {
      addTagInterceptor(interceptor);
    }

    registerClassLoader(ClassLoader.getSystemClassLoader());

    // Ensure that PendingTrace.SPAN_CLEANER is initialized in this thread:
    // FIXME: add test to verify the span cleaner thread is started with this call.
    PendingTrace.initialize();

    StatusLogger.logStatus(config);
  }

  @Override
  public void finalize() {
    try {
      shutdownCallback.run();
      Runtime.getRuntime().removeShutdownHook(shutdownCallback);
    } catch (final IllegalStateException e) {
      // Do nothing.  Already shutting down
    } catch (final Exception e) {
      log.error("Error while finalizing DDTracer.", e);
    }
  }

  /**
   * Returns the list of span tag interceptors
   *
   * @return the list of span tag interceptors
   */
  public List<AbstractTagInterceptor> getSpanTagInterceptors(final String tag) {
    return spanTagInterceptors.get(tag);
  }

  /**
   * Add a new interceptor in the list ({@link AbstractTagInterceptor})
   *
   * @param interceptor The interceptor in the list
   */
  private void addTagInterceptor(final AbstractTagInterceptor interceptor) {
    List<AbstractTagInterceptor> list = spanTagInterceptors.get(interceptor.getMatchingTag());
    if (list == null) {
      list = new ArrayList<>();
    }
    list.add(interceptor);

    spanTagInterceptors.put(interceptor.getMatchingTag(), list);
    log.debug(
        "Decorator added: '{}' -> {}",
        interceptor.getMatchingTag(),
        interceptor.getClass().getName());
  }

  /**
   * If an application is using a non-system classloader, that classloader should be registered
   * here. Due to the way Spring Boot structures its' executable jar, this might log some warnings.
   *
   * @param classLoader to register.
   */
  private void registerClassLoader(final ClassLoader classLoader) {
    try {
      for (final TraceInterceptor interceptor :
          ServiceLoader.load(TraceInterceptor.class, classLoader)) {
        addTraceInterceptor(interceptor);
      }
    } catch (final ServiceConfigurationError e) {
      log.warn("Problem loading TraceInterceptor for classLoader: " + classLoader, e);
    }
  }

  @Override
  public CoreSpanBuilder buildSpan(final CharSequence operationName) {
    return new CoreSpanBuilder(operationName);
  }

  @Override
  public AgentSpan startSpan(final CharSequence spanName) {
    return buildSpan(spanName).start();
  }

  @Override
  public AgentSpan startSpan(final CharSequence spanName, final long startTimeMicros) {
    return buildSpan(spanName).withStartTimestamp(startTimeMicros).start();
  }

  @Override
  public AgentSpan startSpan(final CharSequence spanName, final AgentSpan.Context parent) {
    return buildSpan(spanName).ignoreActiveSpan().asChildOf(parent).start();
  }

  @Override
  public AgentSpan startSpan(
      final CharSequence spanName, final AgentSpan.Context parent, final long startTimeMicros) {
    return buildSpan(spanName)
        .ignoreActiveSpan()
        .asChildOf(parent)
        .withStartTimestamp(startTimeMicros)
        .start();
  }

  public AgentScope activateSpan(final AgentSpan span) {
    return scopeManager.activate(span, ScopeSource.INSTRUMENTATION);
  }

  @Override
  public AgentScope activateSpan(final AgentSpan span, final ScopeSource source) {
    return scopeManager.activate(span, source);
  }

  @Override
  public AgentSpan activeSpan() {
    return scopeManager.activeSpan();
  }

  @Override
  public TraceScope activeScope() {
    return scopeManager.active();
  }

  @Override
  public AgentPropagation propagate() {
    return this;
  }

  @Override
  public AgentSpan noopSpan() {
    return AgentTracer.NoopAgentSpan.INSTANCE;
  }

  @Override
  public <C> void inject(final AgentSpan span, final C carrier, final Setter<C> setter) {

    inject(span.context(), carrier, setter);
  }

  @Override
  public <C> void inject(final AgentSpan.Context context, final C carrier, final Setter<C> setter) {
    if (!(context instanceof DDSpanContext)) {
      return;
    }

    final DDSpanContext ddSpanContext = (DDSpanContext) context;

    final DDSpan rootSpan = ddSpanContext.getTrace().getRootSpan();
    setSamplingPriorityIfNecessary(rootSpan);

    injector.inject(ddSpanContext, carrier, setter);
  }

  @Override
  public <C> AgentSpan.Context extract(final C carrier, final ContextVisitor<C> getter) {
    return extractor.extract(carrier, getter);
  }

  /**
   * We use the sampler to know if the trace has to be reported/written. The sampler is called on
   * the first span (root span) of the trace. If the trace is marked as a sample, we report it.
   *
   * @param trace a list of the spans related to the same trace
   */
  void write(final List<DDSpan> trace) {
    if (trace.isEmpty()) {
      return;
    }
    List<DDSpan> writtenTrace = trace;
    if (!interceptors.isEmpty()) {
      Collection<? extends MutableSpan> interceptedTrace = new ArrayList<>(trace);
      for (final TraceInterceptor interceptor : interceptors) {
        interceptedTrace = interceptor.onTraceComplete(interceptedTrace);
      }
      writtenTrace = new ArrayList<>(interceptedTrace.size());
      for (final MutableSpan span : interceptedTrace) {
        if (span instanceof DDSpan) {
          writtenTrace.add((DDSpan) span);
        }
      }
    }

    if (!writtenTrace.isEmpty()) {
      final DDSpan rootSpan = writtenTrace.get(0).getLocalRootSpan();
      setSamplingPriorityIfNecessary(rootSpan);

      final DDSpan spanToSample = rootSpan == null ? writtenTrace.get(0) : rootSpan;
      if (sampler.sample(spanToSample)) {
        writer.write(writtenTrace);
      } else {
        incrementTraceCount();
      }
    }
  }

  void setSamplingPriorityIfNecessary(final DDSpan rootSpan) {
    // There's a race where multiple threads can see PrioritySampling.UNSET here
    // This check skips potential complex sampling priority logic when we know its redundant
    // Locks inside DDSpanContext ensure the correct behavior in the race case

    if (sampler instanceof PrioritySampler
        && rootSpan != null
        && rootSpan.context().getSamplingPriority() == PrioritySampling.UNSET) {

      ((PrioritySampler) sampler).setSamplingPriority(rootSpan);
    }
  }

  /** Increment the reported trace count, but do not write a trace. */
  void incrementTraceCount() {
    writer.incrementTraceCount();
  }

  @Override
  public String getTraceId() {
    final AgentSpan activeSpan = activeSpan();
    if (activeSpan instanceof DDSpan) {
      return ((DDSpan) activeSpan).getTraceId().toString();
    }
    return "0";
  }

  @Override
  public String getSpanId() {
    final AgentSpan activeSpan = activeSpan();
    if (activeSpan instanceof DDSpan) {
      return ((DDSpan) activeSpan).getSpanId().toString();
    }
    return "0";
  }

  @Override
  public boolean addTraceInterceptor(final TraceInterceptor interceptor) {
    return interceptors.add(interceptor);
  }

  @Override
  public void addScopeListener(final ScopeListener listener) {
    if (scopeManager instanceof ContinuableScopeManager) {
      ((ContinuableScopeManager) scopeManager).addScopeListener(listener);
    }
  }

  @Override
  public void close() {
    PendingTrace.close();
    writer.close();
  }

  @Override
  public String toString() {
    return "DDTracer-"
        + Integer.toHexString(hashCode())
        + "{ serviceName="
        + serviceName
        + ", writer="
        + writer
        + ", sampler="
        + sampler
        + ", defaultSpanTags="
        + defaultSpanTags
        + '}';
  }

  private static DDScopeEventFactory createScopeEventFactory() {
    try {
      return (DDScopeEventFactory)
          Class.forName("datadog.trace.core.jfr.openjdk.ScopeEventFactory").newInstance();
    } catch (final ClassFormatError | ReflectiveOperationException | NoClassDefFoundError e) {
      log.debug("Profiling of ScopeEvents is not available");
    }
    return new DDNoopScopeEventFactory();
  }

  private static Writer createWriter(
      final Config config,
      final Sampler sampler,
      final StatsDClient statsDClient,
      final Monitoring monitoring) {
    final String configuredType = config.getWriterType();

    if (LOGGING_WRITER_TYPE.equals(configuredType)) {
      return new LoggingWriter();
    } else if (PRINTING_WRITER_TYPE.equals(configuredType)) {
      return new PrintingWriter(System.out, true);
    } else if (configuredType.startsWith(TRACE_STRUCTURE_WRITER_TYPE)) {
      return new TraceStructureWriter(configuredType.replace(TRACE_STRUCTURE_WRITER_TYPE, ""));
    }

    if (!DD_AGENT_WRITER_TYPE.equals(configuredType)) {
      log.warn(
          "Writer type not configured correctly: Type {} not recognized. Ignoring", configuredType);
    }

    if (config.isAgentConfiguredUsingDefault()
        && ServerlessInfo.get().isRunningInServerlessEnvironment()) {
      log.info("Detected serverless environment.  Using PrintingWriter");
      return new PrintingWriter(System.out, true);
    }

    String unixDomainSocket = config.getAgentUnixDomainSocket();
    if (unixDomainSocket != ConfigDefaults.DEFAULT_AGENT_UNIX_DOMAIN_SOCKET && isWindows()) {
      log.warn(
          "{} setting not supported on {}.  Reverting to the default.",
          TracerConfig.AGENT_UNIX_DOMAIN_SOCKET,
          System.getProperty("os.name"));
      unixDomainSocket = ConfigDefaults.DEFAULT_AGENT_UNIX_DOMAIN_SOCKET;
    }

    final DDAgentApi ddAgentApi =
        new DDAgentApi(
            config.getAgentHost(),
            config.getAgentPort(),
            unixDomainSocket,
            TimeUnit.SECONDS.toMillis(config.getAgentTimeout()),
            Config.get().isTraceAgentV05Enabled(),
            monitoring);

    final String prioritizationType = config.getPrioritizationType();
    Prioritization prioritization = null;
    if (ENSURE_TRACE_TYPE.equals(prioritizationType)) {
      prioritization = Prioritization.ENSURE_TRACE;
      log.info(
          "Using 'EnsureTrace' prioritization type. (Do not use this type if your application is running in production mode)");
    }

    final DDAgentWriter ddAgentWriter =
        DDAgentWriter.builder()
            .agentApi(ddAgentApi)
            .prioritization(prioritization)
            .healthMetrics(new HealthMetrics(statsDClient))
            .monitoring(monitoring)
            .build();

    if (sampler instanceof DDAgentResponseListener) {
      ddAgentWriter.addResponseListener((DDAgentResponseListener) sampler);
    }

    return ddAgentWriter;
  }

  private static boolean isWindows() {
    // https://mkyong.com/java/how-to-detect-os-in-java-systemgetpropertyosname/
    final String os = System.getProperty("os.name").toLowerCase();
    return os.contains("win");
  }

  private static StatsDClient createStatsDClient(final Config config) {
    if (!config.isHealthMetricsEnabled()) {
      return new NoOpStatsDClient();
    } else {
      String host = config.getHealthMetricsStatsdHost();
      if (host == null) {
        host = config.getJmxFetchStatsdHost();
      }
      if (host == null) {
        host = config.getAgentHost();
      }

      Integer port = config.getHealthMetricsStatsdPort();
      if (port == null) {
        port = config.getJmxFetchStatsdPort();
      }

      return new NonBlockingStatsDClient(
          "datadog.tracer", host, port, generateConstantTags(config));
    }
  }

  private static String[] generateConstantTags(final Config config) {
    final List<String> constantTags = new ArrayList<>();

    constantTags.add(statsdTag(LANG_STATSD_TAG, "java"));
    constantTags.add(statsdTag(LANG_VERSION_STATSD_TAG, DDTraceCoreInfo.JAVA_VERSION));
    constantTags.add(statsdTag(LANG_INTERPRETER_STATSD_TAG, DDTraceCoreInfo.JAVA_VM_NAME));
    constantTags.add(statsdTag(LANG_INTERPRETER_VENDOR_STATSD_TAG, DDTraceCoreInfo.JAVA_VM_VENDOR));
    constantTags.add(statsdTag(TRACER_VERSION_STATSD_TAG, DDTraceCoreInfo.VERSION));
    constantTags.add(statsdTag("service", config.getServiceName()));

    final Map<String, String> mergedSpanTags = config.getMergedSpanTags();
    final String version = mergedSpanTags.get(GeneralConfig.VERSION);
    if (version != null && !version.isEmpty()) {
      constantTags.add(statsdTag("version", version));
    }

    final String env = mergedSpanTags.get(GeneralConfig.ENV);
    if (env != null && !env.isEmpty()) {
      constantTags.add(statsdTag("env", env));
    }

    return constantTags.toArray(new String[0]);
  }

  Recording writeTimer() {
    return traceWriteTimer.start();
  }

  private static String statsdTag(final String tagPrefix, final String tagValue) {
    return tagPrefix + ":" + tagValue;
  }

  /** Spans are built using this builder */
  public class CoreSpanBuilder implements AgentTracer.SpanBuilder {
    private final CharSequence operationName;

    // Builder attributes
    private Map<String, Object> tags;
    private long timestampMicro;
    private Object parent;
    private String serviceName;
    private String resourceName;
    private boolean errorFlag;
    private String spanType;
    private boolean ignoreScope = false;

    public CoreSpanBuilder(final CharSequence operationName) {
      this.operationName = operationName;
    }

    @Override
    public CoreSpanBuilder ignoreActiveSpan() {
      ignoreScope = true;
      return this;
    }

    private DDSpan buildSpan() {
      return DDSpan.create(timestampMicro, buildSpanContext());
    }

    @Override
    public AgentSpan start() {
      final AgentSpan span = buildSpan();
      return span;
    }

    @Override
    public CoreSpanBuilder withTag(final String tag, final Number number) {
      return withTag(tag, (Object) number);
    }

    @Override
    public CoreSpanBuilder withTag(final String tag, final String string) {
      return withTag(tag, (Object) string);
    }

    @Override
    public CoreSpanBuilder withTag(final String tag, final boolean bool) {
      return withTag(tag, (Object) bool);
    }

    @Override
    public CoreSpanBuilder withStartTimestamp(final long timestampMicroseconds) {
      timestampMicro = timestampMicroseconds;
      return this;
    }

    @Override
    public CoreSpanBuilder withServiceName(final String serviceName) {
      this.serviceName = serviceName;
      return this;
    }

    @Override
    public CoreSpanBuilder withResourceName(final String resourceName) {
      this.resourceName = resourceName;
      return this;
    }

    @Override
    public CoreSpanBuilder withErrorFlag() {
      errorFlag = true;
      return this;
    }

    @Override
    public CoreSpanBuilder withSpanType(final String spanType) {
      this.spanType = spanType;
      return this;
    }

    @Override
    public CoreSpanBuilder asChildOf(final AgentSpan.Context spanContext) {
      parent = spanContext;
      return this;
    }

    public CoreSpanBuilder asChildOf(final AgentSpan agentSpan) {
      parent = agentSpan.context();
      return this;
    }

    @Override
    public CoreSpanBuilder withTag(final String tag, final Object value) {
      Map<String, Object> tagMap = tags;
      if (tagMap == null) {
        tags = tagMap = new LinkedHashMap<>(); // Insertion order is important
      }
      if (value == null || (value instanceof String && ((String) value).isEmpty())) {
        tagMap.remove(tag);
      } else {
        tagMap.put(tag, value);
      }
      return this;
    }

    /**
     * Build the SpanContext, if the actual span has a parent, the following attributes must be
     * propagated: - ServiceName - Baggage - Trace (a list of all spans related) - SpanType
     *
     * @return the context
     */
    private DDSpanContext buildSpanContext() {
      final DDId traceId;
      final DDId spanId = idGenerationStrategy.generate();
      final DDId parentSpanId;
      final Map<String, String> baggage;
      final PendingTrace parentTrace;
      final int samplingPriority;
      final String origin;
      final Map<String, String> coreTags;
      final Map<String, String> rootSpanTags;

      final DDSpanContext context;

      // FIXME [API] parentContext should be an interface implemented by ExtractedContext,
      // TagContext, DDSpanContext, AgentSpan.Context
      Object parentContext = parent;
      if (parentContext == null && !ignoreScope) {
        // use the Scope as parent unless overridden or ignored.
        final AgentSpan activeSpan = scopeManager.activeSpan();
        if (activeSpan != null) {
          parentContext = activeSpan.context();
        }
      }

      // Propagate internal trace.
      // Note: if we are not in the context of distributed tracing and we are starting the first
      // root span, parentContext will be null at this point.
      if (parentContext instanceof DDSpanContext) {
        final DDSpanContext ddsc = (DDSpanContext) parentContext;
        traceId = ddsc.getTraceId();
        parentSpanId = ddsc.getSpanId();
        baggage = ddsc.getBaggageItems();
        parentTrace = ddsc.getTrace();
        samplingPriority = PrioritySampling.UNSET;
        origin = null;
        coreTags = null;
        rootSpanTags = null;
        if (serviceName == null) {
          serviceName = ddsc.getServiceName();
        }

      } else {
        if (parentContext instanceof ExtractedContext) {
          // Propagate external trace
          final ExtractedContext extractedContext = (ExtractedContext) parentContext;
          traceId = extractedContext.getTraceId();
          parentSpanId = extractedContext.getSpanId();
          samplingPriority = extractedContext.getSamplingPriority();
          baggage = extractedContext.getBaggage();
        } else {
          // Start a new trace
          traceId = IdGenerationStrategy.RANDOM.generate();
          parentSpanId = DDId.ZERO;
          samplingPriority = PrioritySampling.UNSET;
          baggage = null;
        }

        // Get header tags and set origin whether propagating or not.
        if (parentContext instanceof TagContext) {
          coreTags = ((TagContext) parentContext).getTags();
          origin = ((TagContext) parentContext).getOrigin();
        } else {
          coreTags = null;
          origin = null;
        }

        rootSpanTags = localRootSpanTags;

        parentTrace = PendingTrace.create(CoreTracer.this, traceId);
      }

      if (serviceName == null) {
        serviceName = CoreTracer.this.serviceName;
      }

      final CharSequence operationName =
          this.operationName != null ? this.operationName : resourceName;

      final int tagsSize =
          (null == tags ? 0 : tags.size())
              + defaultSpanTags.size()
              + (null == coreTags ? 0 : coreTags.size())
              + (null == rootSpanTags ? 0 : rootSpanTags.size());
      // some attributes are inherited from the parent
      context =
          new DDSpanContext(
              traceId,
              spanId,
              parentSpanId,
              serviceName,
              operationName,
              resourceName,
              samplingPriority,
              origin,
              baggage,
              errorFlag,
              spanType,
              tagsSize,
              parentTrace,
              CoreTracer.this,
              serviceNameMappings);

      // By setting the tags on the context we apply decorators to any tags that have been set via
      // the builder. This is the order that the tags were added previously, but maybe the `tags`
      // set in the builder should come last, so that they override other tags.
      context.setAllTags(defaultSpanTags);
      context.setAllTags(tags);
      context.setAllTags(coreTags);
      context.setAllTags(rootSpanTags);
      return context;
    }
  }

  private static class ShutdownHook extends Thread {
    private final WeakReference<CoreTracer> reference;

    private ShutdownHook(final CoreTracer tracer) {
      super("dd-tracer-shutdown-hook");
      reference = new WeakReference<>(tracer);
    }

    @Override
    public void run() {
      final CoreTracer tracer = reference.get();
      if (tracer != null) {
        tracer.close();
      }
    }
  }
}

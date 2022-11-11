package datadog.trace.core;

import static datadog.communication.monitor.DDAgentStatsDClientManager.statsDClientManager;
import static datadog.trace.api.ConfigDefaults.DEFAULT_ASYNC_PROPAGATING;
import static datadog.trace.common.metrics.MetricsAggregatorFactory.createMetricsAggregator;
import static datadog.trace.util.AgentThreadFactory.AGENT_THREAD_GROUP;
import static datadog.trace.util.CollectionUtils.tryMakeImmutableMap;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.communication.ddagent.ExternalAgentLauncher;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.communication.monitor.Monitoring;
import datadog.communication.monitor.Recording;
import datadog.trace.api.Checkpointer;
import datadog.trace.api.Config;
import datadog.trace.api.DDId;
import datadog.trace.api.EndpointCheckpointer;
import datadog.trace.api.IdGenerationStrategy;
import datadog.trace.api.Platform;
import datadog.trace.api.PropagationStyle;
import datadog.trace.api.SamplingCheckpointer;
import datadog.trace.api.StatsDClient;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.InstrumentationGateway;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.gateway.SubscriptionService;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.api.profiling.TracingContextTrackerFactory;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.api.time.TimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentScopeManager;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ContextThreadListener;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import datadog.trace.bootstrap.instrumentation.api.ScopeSource;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.civisibility.CiVisibilityTraceInterceptor;
import datadog.trace.common.metrics.MetricsAggregator;
import datadog.trace.common.sampling.PrioritySampler;
import datadog.trace.common.sampling.Sampler;
import datadog.trace.common.writer.DDAgentWriter;
import datadog.trace.common.writer.Writer;
import datadog.trace.common.writer.WriterFactory;
import datadog.trace.common.writer.ddintake.DDIntakeTraceInterceptor;
import datadog.trace.context.ScopeListener;
import datadog.trace.core.datastreams.DataStreamsCheckpointer;
import datadog.trace.core.datastreams.StubDataStreamsCheckpointer;
import datadog.trace.core.monitor.MonitoringImpl;
import datadog.trace.core.propagation.DatadogTags;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.core.propagation.HttpCodec;
import datadog.trace.core.scopemanager.ContinuableScopeManager;
import datadog.trace.core.taginterceptor.RuleFlags;
import datadog.trace.core.taginterceptor.TagInterceptor;
import datadog.trace.lambda.LambdaHandler;
import datadog.trace.relocate.api.RatelimitedLogger;
import datadog.trace.util.AgentTaskScheduler;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentSkipListSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entrypoint into the tracer implementation. In addition to implementing
 * datadog.trace.api.Tracer and TracerAPI, it coordinates many functions necessary creating,
 * reporting, and propagating traces
 */
public class CoreTracer implements AgentTracer.TracerAPI {
  private static final Logger log = LoggerFactory.getLogger(CoreTracer.class);
  // UINT64 max value
  public static final BigInteger TRACE_ID_MAX =
      BigInteger.valueOf(2).pow(64).subtract(BigInteger.ONE);

  public static CoreTracerBuilder builder() {
    return new CoreTracerBuilder();
  }

  private static final String LANG_STATSD_TAG = "lang";
  private static final String LANG_VERSION_STATSD_TAG = "lang_version";
  private static final String LANG_INTERPRETER_STATSD_TAG = "lang_interpreter";
  private static final String LANG_INTERPRETER_VENDOR_STATSD_TAG = "lang_interpreter_vendor";
  private static final String TRACER_VERSION_STATSD_TAG = "tracer_version";

  /** Tracer start time in nanoseconds measured up to a millisecond accuracy */
  private final long startTimeNano;
  /** Nanosecond ticks value at tracer start */
  private final long startNanoTicks;
  /** How often should traced threads check clock ticks against the wall clock */
  private final long clockSyncPeriod;
  /** Last time (in nanosecond ticks) the clock was checked for drift */
  private volatile long lastSyncTicks;
  /** Nanosecond offset to counter clock drift */
  private volatile long counterDrift;

  private final PendingTraceBuffer pendingTraceBuffer;

  /** Default service name if none provided on the trace or span */
  final String serviceName;
  /** Writer is an charge of reporting traces and spans to the desired endpoint */
  final Writer writer;
  /** Sampler defines the sampling policy in order to reduce the number of traces for instance */
  final Sampler<DDSpan> sampler;
  /** Scope manager is in charge of managing the scopes from which spans are created */
  final AgentScopeManager scopeManager;

  final MetricsAggregator metricsAggregator;

  /** A set of tags that are added only to the application's root span */
  private final Map<String, ?> localRootSpanTags;
  /** A set of tags that are added to every span */
  private final Map<String, ?> defaultSpanTags;
  /** A configured mapping of service names to update with new values */
  private final Map<String, String> serviceNameMappings;

  /** number of spans in a pending trace before they get flushed */
  private final int partialFlushMinSpans;

  private final StatsDClient statsDClient;
  private final Monitoring monitoring;
  private final Monitoring performanceMonitoring;
  private final Recording traceWriteTimer;
  private final IdGenerationStrategy idGenerationStrategy;
  private final PendingTrace.Factory pendingTraceFactory;
  private final SamplingCheckpointer spanCheckpointer;
  private final DataStreamsCheckpointer dataStreamsCheckpointer;
  private final ExternalAgentLauncher externalAgentLauncher;
  private boolean disableSamplingMechanismValidation;
  private final TimeSource timeSource;

  /**
   * JVM shutdown callback, keeping a reference to it to remove this if DDTracer gets destroyed
   * earlier
   */
  private final Thread shutdownCallback;

  /**
   * Span tag interceptors. This Map is only ever added to during initialization, so it doesn't need
   * to be concurrent.
   */
  private final TagInterceptor tagInterceptor;

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

  private final InstrumentationGateway instrumentationGateway;
  private final CallbackProvider callbackProviderAppSec;
  private final CallbackProvider callbackProviderIast;
  private final CallbackProvider universalCallbackProvider;

  private final DatadogTags.Factory datadogTagsFactory;

  DatadogTags.Factory getDatadogTagsFactory() {
    return datadogTagsFactory;
  }

  @Override
  public AgentScope.Continuation capture() {
    final AgentScope activeScope = activeScope();

    return activeScope == null ? null : activeScope.capture();
  }

  @Override
  public void checkpoint(AgentSpan span, int flags) {
    spanCheckpointer.checkpoint(span, flags);
  }

  @Override
  public void onStartWork(AgentSpan span) {
    spanCheckpointer.onStartWork(span);
  }

  @Override
  public void onFinishWork(AgentSpan span) {
    spanCheckpointer.onFinishWork(span);
  }

  @Override
  public void onRootSpanFinished(AgentSpan root, boolean published) {
    spanCheckpointer.onRootSpanFinished(root, published);
  }

  @Override
  public void onRootSpanStarted(AgentSpan root) {
    spanCheckpointer.onRootSpanStarted(root);
  }

  public static class CoreTracerBuilder {

    private Config config;
    private String serviceName;
    private SharedCommunicationObjects sharedCommunicationObjects;
    private Writer writer;
    private IdGenerationStrategy idGenerationStrategy;
    private Sampler<DDSpan> sampler;
    private HttpCodec.Injector injector;
    private HttpCodec.Extractor extractor;
    private AgentScopeManager scopeManager;
    private Map<String, ?> localRootSpanTags;
    private Map<String, ?> defaultSpanTags;
    private Map<String, String> serviceNameMappings;
    private Map<String, String> taggedHeaders;
    private int partialFlushMinSpans;
    private StatsDClient statsDClient;
    private TagInterceptor tagInterceptor;
    private boolean strictTraceWrites;
    private InstrumentationGateway instrumentationGateway;
    private TimeSource timeSource;
    private DataStreamsCheckpointer dataStreamsCheckpointer;

    public CoreTracerBuilder serviceName(String serviceName) {
      this.serviceName = serviceName;
      return this;
    }

    public CoreTracerBuilder sharedCommunicationObjects(
        SharedCommunicationObjects sharedCommunicationObjects) {
      this.sharedCommunicationObjects = sharedCommunicationObjects;
      return this;
    }

    public CoreTracerBuilder writer(Writer writer) {
      this.writer = writer;
      return this;
    }

    public CoreTracerBuilder idGenerationStrategy(IdGenerationStrategy idGenerationStrategy) {
      this.idGenerationStrategy = idGenerationStrategy;
      return this;
    }

    public CoreTracerBuilder sampler(Sampler<DDSpan> sampler) {
      this.sampler = sampler;
      return this;
    }

    public CoreTracerBuilder injector(HttpCodec.Injector injector) {
      this.injector = injector;
      return this;
    }

    public CoreTracerBuilder extractor(HttpCodec.Extractor extractor) {
      this.extractor = extractor;
      return this;
    }

    public CoreTracerBuilder scopeManager(AgentScopeManager scopeManager) {
      this.scopeManager = scopeManager;
      return this;
    }

    public CoreTracerBuilder localRootSpanTags(Map<String, ?> localRootSpanTags) {
      this.localRootSpanTags = tryMakeImmutableMap(localRootSpanTags);
      return this;
    }

    public CoreTracerBuilder defaultSpanTags(Map<String, ?> defaultSpanTags) {
      this.defaultSpanTags = tryMakeImmutableMap(defaultSpanTags);
      return this;
    }

    public CoreTracerBuilder serviceNameMappings(Map<String, String> serviceNameMappings) {
      this.serviceNameMappings = tryMakeImmutableMap(serviceNameMappings);
      return this;
    }

    public CoreTracerBuilder taggedHeaders(Map<String, String> taggedHeaders) {
      this.taggedHeaders = tryMakeImmutableMap(taggedHeaders);
      return this;
    }

    public CoreTracerBuilder partialFlushMinSpans(int partialFlushMinSpans) {
      this.partialFlushMinSpans = partialFlushMinSpans;
      return this;
    }

    public CoreTracerBuilder statsDClient(StatsDClient statsDClient) {
      this.statsDClient = statsDClient;
      return this;
    }

    public CoreTracerBuilder tagInterceptor(TagInterceptor tagInterceptor) {
      this.tagInterceptor = tagInterceptor;
      return this;
    }

    public CoreTracerBuilder statsDClient(TagInterceptor tagInterceptor) {
      this.tagInterceptor = tagInterceptor;
      return this;
    }

    public CoreTracerBuilder strictTraceWrites(boolean strictTraceWrites) {
      this.strictTraceWrites = strictTraceWrites;
      return this;
    }

    public CoreTracerBuilder instrumentationGateway(InstrumentationGateway instrumentationGateway) {
      this.instrumentationGateway = instrumentationGateway;
      return this;
    }

    public CoreTracerBuilder timeSource(TimeSource timeSource) {
      this.timeSource = timeSource;
      return this;
    }

    public CoreTracerBuilder dataStreamsCheckpointer(
        DataStreamsCheckpointer dataStreamsCheckpointer) {
      this.dataStreamsCheckpointer = dataStreamsCheckpointer;
      return this;
    }

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
      sampler(Sampler.Builder.<DDSpan>forConfig(config));
      instrumentationGateway(new InstrumentationGateway());
      injector(HttpCodec.createInjector(config));
      extractor(HttpCodec.createExtractor(config, config.getRequestHeaderTags()));
      // Explicitly skip setting scope manager because it depends on statsDClient
      localRootSpanTags(config.getLocalRootSpanTags());
      defaultSpanTags(config.getMergedSpanTags());
      serviceNameMappings(config.getServiceMapping());
      taggedHeaders(config.getRequestHeaderTags());
      partialFlushMinSpans(config.getPartialFlushMinSpans());
      strictTraceWrites(config.isTraceStrictWritesEnabled());

      return this;
    }

    public CoreTracer build() {
      return new CoreTracer(
          config,
          serviceName,
          sharedCommunicationObjects,
          writer,
          idGenerationStrategy,
          sampler,
          injector,
          extractor,
          scopeManager,
          localRootSpanTags,
          defaultSpanTags,
          serviceNameMappings,
          taggedHeaders,
          partialFlushMinSpans,
          statsDClient,
          tagInterceptor,
          strictTraceWrites,
          instrumentationGateway,
          timeSource,
          dataStreamsCheckpointer);
    }
  }

  // These field names must be stable to ensure the builder api is stable.
  private CoreTracer(
      final Config config,
      final String serviceName,
      SharedCommunicationObjects sharedCommunicationObjects,
      final Writer writer,
      final IdGenerationStrategy idGenerationStrategy,
      final Sampler<DDSpan> sampler,
      final HttpCodec.Injector injector,
      final HttpCodec.Extractor extractor,
      final AgentScopeManager scopeManager,
      final Map<String, ?> localRootSpanTags,
      final Map<String, ?> defaultSpanTags,
      final Map<String, String> serviceNameMappings,
      final Map<String, String> taggedHeaders,
      final int partialFlushMinSpans,
      final StatsDClient statsDClient,
      final TagInterceptor tagInterceptor,
      final boolean strictTraceWrites,
      final InstrumentationGateway instrumentationGateway,
      final TimeSource timeSource,
      final DataStreamsCheckpointer dataStreamsCheckpointer) {

    assert localRootSpanTags != null;
    assert defaultSpanTags != null;
    assert serviceNameMappings != null;
    assert taggedHeaders != null;

    this.timeSource = timeSource == null ? SystemTimeSource.INSTANCE : timeSource;
    this.startTimeNano = this.timeSource.getCurrentTimeNanos();
    this.startNanoTicks = this.timeSource.getNanoTicks();
    this.clockSyncPeriod = Math.max(1_000_000L, SECONDS.toNanos(config.getClockSyncPeriod()));
    this.lastSyncTicks = startNanoTicks;

    this.spanCheckpointer = SamplingCheckpointer.create();
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

    if (statsDClient != null) {
      this.statsDClient = statsDClient;
    } else if (writer == null || writer instanceof DDAgentWriter) {
      this.statsDClient = createStatsDClient(config);
    } else {
      // avoid creating internal StatsD client when using external trace writer
      this.statsDClient = StatsDClient.NO_OP;
    }

    this.monitoring =
        config.isHealthMetricsEnabled()
            ? new MonitoringImpl(this.statsDClient, 10, SECONDS)
            : Monitoring.DISABLED;
    this.performanceMonitoring =
        config.isPerfMetricsEnabled()
            ? new MonitoringImpl(this.statsDClient, 10, SECONDS)
            : Monitoring.DISABLED;
    this.traceWriteTimer = performanceMonitoring.newThreadLocalTimer("trace.write");
    if (scopeManager == null) {
      ContinuableScopeManager csm =
          new ContinuableScopeManager(
              config.getScopeDepthLimit(),
              this.statsDClient,
              config.isScopeStrictMode(),
              config.isScopeInheritAsyncPropagation());
      this.scopeManager = csm;

    } else {
      this.scopeManager = scopeManager;
    }

    this.externalAgentLauncher = new ExternalAgentLauncher(config);

    this.disableSamplingMechanismValidation = config.isSamplingMechanismValidationDisabled();

    if (sharedCommunicationObjects == null) {
      sharedCommunicationObjects = new SharedCommunicationObjects();
    }
    sharedCommunicationObjects.monitoring = monitoring;
    sharedCommunicationObjects.createRemaining(config);

    if (writer == null) {
      this.writer =
          WriterFactory.createWriter(
              config, sharedCommunicationObjects, sampler, this.statsDClient);
    } else {
      this.writer = writer;
    }

    this.pendingTraceBuffer =
        strictTraceWrites
            ? PendingTraceBuffer.discarding()
            : PendingTraceBuffer.delaying(this.timeSource);
    pendingTraceFactory =
        new PendingTrace.Factory(this, pendingTraceBuffer, this.timeSource, strictTraceWrites);
    pendingTraceBuffer.start();

    this.writer.start();

    metricsAggregator = createMetricsAggregator(config, sharedCommunicationObjects);
    // Schedule the metrics aggregator to begin reporting after a random delay of 1 to 10 seconds
    // (using milliseconds granularity.) This avoids a fleet of traced applications starting at the
    // same time from sending metrics in sync.
    AgentTaskScheduler.INSTANCE.scheduleWithJitter(
        new AgentTaskScheduler.Task<MetricsAggregator>() {
          @Override
          public void run(MetricsAggregator target) {
            target.start();
          }
        },
        metricsAggregator,
        1,
        SECONDS);

    if (dataStreamsCheckpointer == null) {
      this.dataStreamsCheckpointer =
          createDataStreamsCheckpointer(config, sharedCommunicationObjects, this.timeSource);
    } else {
      this.dataStreamsCheckpointer = dataStreamsCheckpointer;
    }
    this.dataStreamsCheckpointer.start();

    this.tagInterceptor =
        null == tagInterceptor ? new TagInterceptor(new RuleFlags(config)) : tagInterceptor;

    if (config.isCiVisibilityEnabled()) {
      addTraceInterceptor(CiVisibilityTraceInterceptor.INSTANCE);
      if (config.isCiVisibilityAgentlessEnabled()) {
        addTraceInterceptor(DDIntakeTraceInterceptor.INSTANCE);
      }
    }

    this.instrumentationGateway = instrumentationGateway;
    this.callbackProviderAppSec =
        instrumentationGateway.getCallbackProvider(RequestContextSlot.APPSEC);
    this.callbackProviderIast = instrumentationGateway.getCallbackProvider(RequestContextSlot.IAST);
    this.universalCallbackProvider = instrumentationGateway.getUniversalCallbackProvider();

    shutdownCallback = new ShutdownHook(this);
    try {
      Runtime.getRuntime().addShutdownHook(shutdownCallback);
    } catch (final IllegalStateException ex) {
      // The JVM is already shutting down.
    }

    registerClassLoader(ClassLoader.getSystemClassLoader());

    StatusLogger.logStatus(config);

    datadogTagsFactory = DatadogTags.factory(config);
  }

  @Override
  protected void finalize() {
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
   * Only visible for benchmarking purposes
   *
   * @return a PendingTrace
   */
  PendingTrace createTrace(DDId id) {
    return pendingTraceFactory.create(id);
  }

  public String mapServiceName(String serviceName) {
    String mapped = serviceNameMappings.get(serviceName);
    return null == mapped ? serviceName : mapped;
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

  /**
   * Timestamp in nanoseconds for the current {@code nanoTicks}.
   *
   * <p>Note: it is not possible to get 'real' nanosecond time. This method uses tracer start time
   * (with millisecond precision) as a reference and applies relative time with nanosecond precision
   * after that. This means time measured with same Tracer in different Spans is relatively correct
   * with nanosecond precision.
   *
   * @param nanoTicks as returned by {@link TimeSource#getNanoTicks()}
   * @return timestamp in nanoseconds
   */
  long getTimeWithNanoTicks(long nanoTicks) {
    long computedNanoTime = startTimeNano + Math.max(0, nanoTicks - startNanoTicks);
    if (nanoTicks - lastSyncTicks >= clockSyncPeriod) {
      long drift = computedNanoTime - timeSource.getCurrentTimeNanos();
      if (Math.abs(drift + counterDrift) >= 1_000_000L) { // allow up to 1ms of drift
        counterDrift = -MILLISECONDS.toNanos(NANOSECONDS.toMillis(drift));
      }
      lastSyncTicks = nanoTicks;
    }
    return computedNanoTime + counterDrift;
  }

  @Override
  public CoreSpanBuilder buildSpan(final CharSequence operationName) {
    return new CoreSpanBuilder(operationName, this);
  }

  @Override
  public AgentSpan startSpan(final CharSequence spanName, boolean emitCheckpoint) {
    AgentTracer.SpanBuilder builder = buildSpan(spanName);
    if (!emitCheckpoint) {
      builder = builder.suppressCheckpoints();
    }
    return builder.start();
  }

  @Override
  public AgentSpan startSpan(
      final CharSequence spanName, final long startTimeMicros, boolean emitCheckpoint) {
    AgentTracer.SpanBuilder builder = buildSpan(spanName).withStartTimestamp(startTimeMicros);
    if (!emitCheckpoint) {
      builder = builder.suppressCheckpoints();
    }
    return builder.start();
  }

  @Override
  public AgentSpan startSpan(
      final CharSequence spanName, final AgentSpan.Context parent, boolean emitCheckpoint) {
    AgentTracer.SpanBuilder builder = buildSpan(spanName).ignoreActiveSpan().asChildOf(parent);
    if (!emitCheckpoint) {
      builder = builder.suppressCheckpoints();
    }
    return builder.start();
  }

  @Override
  public AgentSpan startSpan(
      final CharSequence spanName,
      final AgentSpan.Context parent,
      final long startTimeMicros,
      boolean emitCheckpoint) {
    AgentTracer.SpanBuilder builder =
        buildSpan(spanName)
            .ignoreActiveSpan()
            .asChildOf(parent)
            .withStartTimestamp(startTimeMicros);
    if (!emitCheckpoint) {
      builder = builder.suppressCheckpoints();
    }
    return builder.start();
  }

  public AgentScope activateSpan(final AgentSpan span) {
    return scopeManager.activate(span, ScopeSource.INSTRUMENTATION, DEFAULT_ASYNC_PROPAGATING);
  }

  @Override
  public AgentScope activateSpan(final AgentSpan span, final ScopeSource source) {
    return scopeManager.activate(span, source);
  }

  @Override
  public AgentScope activateSpan(AgentSpan span, ScopeSource source, boolean isAsyncPropagating) {
    return scopeManager.activate(span, source, isAsyncPropagating);
  }

  @Override
  public AgentScope.Continuation captureSpan(final AgentSpan span, ScopeSource source) {
    return scopeManager.captureSpan(span, source);
  }

  @Override
  public void closePrevious(boolean finishSpan) {
    scopeManager.closePrevious(finishSpan);
  }

  @Override
  public AgentScope activateNext(AgentSpan span) {
    return scopeManager.activateNext(span);
  }

  public TagInterceptor getTagInterceptor() {
    return tagInterceptor;
  }

  public int getPartialFlushMinSpans() {
    return partialFlushMinSpans;
  }

  @Override
  public AgentSpan activeSpan() {
    return scopeManager.activeSpan();
  }

  @Override
  public AgentScope activeScope() {
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
    inject(span.context(), carrier, setter, null);
  }

  @Override
  public <C> void inject(final AgentSpan.Context context, final C carrier, final Setter<C> setter) {
    inject(context, carrier, setter, null);
  }

  @Override
  public <C> void inject(AgentSpan span, C carrier, Setter<C> setter, PropagationStyle style) {
    inject(span.context(), carrier, setter, style);
  }

  @Override
  public <C> void injectBinaryPathwayContext(
      AgentSpan span, C carrier, BinarySetter<C> setter, LinkedHashMap<String, String> sortedTags) {
    PathwayContext pathwayContext = span.context().getPathwayContext();
    pathwayContext.setCheckpoint(sortedTags, dataStreamsCheckpointer);

    try {
      byte[] encodedContext = span.context().getPathwayContext().encode();

      if (encodedContext != null) {
        log.debug("Injecting pathway context {}", pathwayContext);
        setter.set(carrier, PathwayContext.PROPAGATION_KEY, encodedContext);
      }
    } catch (IOException e) {
      log.debug("Unable to set encode pathway context", e);
    }
  }

  @Override
  public <C> void injectPathwayContext(
      AgentSpan span, C carrier, Setter<C> setter, LinkedHashMap<String, String> sortedTags) {
    PathwayContext pathwayContext = span.context().getPathwayContext();
    pathwayContext.setCheckpoint(sortedTags, dataStreamsCheckpointer);
    try {
      String encodedContext = pathwayContext.strEncode();
      if (encodedContext != null) {
        setter.set(carrier, PathwayContext.PROPAGATION_KEY_BASE64, encodedContext);
      }
    } catch (IOException e) {
      log.debug("Unable to set encode pathway context", e);
    }
  }

  private <C> void inject(
      AgentSpan.Context context, C carrier, Setter<C> setter, PropagationStyle style) {
    if (!(context instanceof DDSpanContext)) {
      return;
    }

    final DDSpanContext ddSpanContext = (DDSpanContext) context;
    final DDSpan rootSpan = ddSpanContext.getTrace().getRootSpan();
    setSamplingPriorityIfNecessary(rootSpan);

    if (null == style) {
      injector.inject(ddSpanContext, carrier, setter);
    } else {
      HttpCodec.inject(ddSpanContext, carrier, setter, style);
    }
  }

  @Override
  public <C> AgentSpan.Context.Extracted extract(final C carrier, final ContextVisitor<C> getter) {
    return extractor.extract(carrier, getter);
  }

  @Override
  public <C> PathwayContext extractBinaryPathwayContext(C carrier, BinaryContextVisitor<C> getter) {
    return dataStreamsCheckpointer.extractBinaryPathwayContext(carrier, getter);
  }

  @Override
  public <C> PathwayContext extractPathwayContext(C carrier, ContextVisitor<C> getter) {
    return dataStreamsCheckpointer.extractPathwayContext(carrier, getter);
  }

  @Override
  public void setDataStreamCheckpoint(AgentSpan span, LinkedHashMap<String, String> sortedTags) {
    span.context().getPathwayContext().setCheckpoint(sortedTags, dataStreamsCheckpointer);
  }

  @Override
  public AgentSpan.Context notifyExtensionStart(Object event) {
    return LambdaHandler.notifyStartInvocation(event, datadogTagsFactory);
  }

  @Override
  public void notifyExtensionEnd(AgentSpan span, boolean isError) {
    LambdaHandler.notifyEndInvocation(span, isError);
  }

  private final RatelimitedLogger rlLog = new RatelimitedLogger(log, 1, MINUTES);

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
        try {
          // If one TraceInterceptor throws an exception, then continue with the next one
          interceptedTrace = interceptor.onTraceComplete(interceptedTrace);
        } catch (Exception e) {
          String interceptorName = interceptor.getClass().getName();
          rlLog.warn("Exception in TraceInterceptor {}", interceptorName, e);
        }
      }
      writtenTrace = new ArrayList<>(interceptedTrace.size());
      for (final MutableSpan span : interceptedTrace) {
        if (span instanceof DDSpan) {
          writtenTrace.add((DDSpan) span);
        }
      }
    }

    if (!writtenTrace.isEmpty()) {
      boolean forceKeep = metricsAggregator.publish(writtenTrace);

      DDSpan rootSpan = writtenTrace.get(0).getLocalRootSpan();
      setSamplingPriorityIfNecessary(rootSpan);

      DDSpan spanToSample = rootSpan == null ? writtenTrace.get(0) : rootSpan;
      spanToSample.forceKeep(forceKeep);
      boolean published = forceKeep || sampler.sample(spanToSample);
      if (published) {
        if (TracingContextTrackerFactory.isTrackingAvailable()) {
          for (DDSpan span : writtenTrace) {
            int stored = span.storeContextToTag();
            if (stored > -1) {
              log.trace(
                  "Sending statsd metric 'tracing.context.size'={} (client={})",
                  stored,
                  statsDClient);
              statsDClient.histogram("tracing.context.size", stored);
            }
          }
        }
        writer.write(writtenTrace);
      } else {
        // with span streaming this won't work - it needs to be changed
        // to track an effective sampling rate instead, however, tests
        // checking that a hard reference on a continuation prevents
        // reporting fail without this, so will need to be fixed first.
        writer.incrementDropCounts(writtenTrace.size());
      }
      if (null != rootSpan) {
        onRootSpanFinished(rootSpan, published);

        // request context is propagated to contexts in child spans
        // Assume here that if present it will be so starting in the top span
        RequestContext requestContext = rootSpan.getRequestContext();
        if (requestContext != null) {
          try {
            requestContext.close();
          } catch (IOException e) {
            log.warn("Error closing request context data", e);
          }
        }
      }
    }
  }

  @SuppressWarnings("unchecked")
  void setSamplingPriorityIfNecessary(final DDSpan rootSpan) {
    // There's a race where multiple threads can see PrioritySampling.UNSET here
    // This check skips potential complex sampling priority logic when we know its redundant
    // Locks inside DDSpanContext ensure the correct behavior in the race case

    if (sampler instanceof PrioritySampler
        && rootSpan != null
        && rootSpan.context().getSamplingPriority() == PrioritySampling.UNSET) {

      ((PrioritySampler<DDSpan>) sampler).setSamplingPriority(rootSpan);
    }
  }

  @Override
  public String getTraceId() {
    final AgentSpan activeSpan = activeSpan();
    if (activeSpan instanceof DDSpan) {
      return activeSpan.getTraceId().toString();
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
  public void addThreadContextListener(ContextThreadListener listener) {
    if (scopeManager instanceof ContinuableScopeManager) {
      ((ContinuableScopeManager) scopeManager).addContextThreadListener(listener);
    }
  }

  @Override
  public void detach() {
    if (scopeManager instanceof ContinuableScopeManager) {
      ((ContinuableScopeManager) scopeManager).detach();
    }
  }

  @Override
  public void registerCheckpointer(Checkpointer checkpointer) {
    this.spanCheckpointer.register(checkpointer);
  }

  @Override
  public void registerCheckpointer(EndpointCheckpointer checkpointer) {
    this.spanCheckpointer.register(checkpointer);
  }

  @Override
  public SubscriptionService getSubscriptionService(RequestContextSlot slot) {
    return (SubscriptionService) this.instrumentationGateway.getCallbackProvider(slot);
  }

  @Override
  public CallbackProvider getCallbackProvider(RequestContextSlot slot) {
    if (slot == RequestContextSlot.APPSEC) {
      return callbackProviderAppSec;
    } else if (slot == RequestContextSlot.IAST) {
      return callbackProviderIast;
    } else {
      return CallbackProvider.CallbackProviderNoop.INSTANCE;
    }
  }

  @Override
  public CallbackProvider getUniversalCallbackProvider() {
    return universalCallbackProvider;
  }

  @Override
  public void close() {
    pendingTraceBuffer.close();
    writer.close();
    statsDClient.close();
    metricsAggregator.close();
    dataStreamsCheckpointer.close();
    externalAgentLauncher.close();
  }

  @Override
  public void flush() {
    pendingTraceBuffer.flush();
    writer.flush();
  }

  private static StatsDClient createStatsDClient(final Config config) {
    if (!config.isHealthMetricsEnabled()) {
      return StatsDClient.NO_OP;
    } else {
      String host = config.getHealthMetricsStatsdHost();
      if (host == null) {
        host = config.getJmxFetchStatsdHost();
      }
      Integer port = config.getHealthMetricsStatsdPort();
      if (port == null) {
        port = config.getJmxFetchStatsdPort();
      }

      return statsDClientManager()
          .statsDClient(
              host,
              port,
              config.getDogStatsDNamedPipe(),
              "datadog.tracer",
              generateConstantTags(config));
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

  @SuppressForbidden
  private static DataStreamsCheckpointer createDataStreamsCheckpointer(
      Config config, SharedCommunicationObjects sharedCommunicationObjects, TimeSource timeSource) {

    if (config.isDataStreamsEnabled() && Platform.isJavaVersionAtLeast(8)) {
      try {
        // Use reflection to load the class because it should only be loaded on Java 8+

        return (DataStreamsCheckpointer)
            Class.forName("datadog.trace.core.datastreams.DefaultDataStreamsCheckpointer")
                .getConstructor(Config.class, SharedCommunicationObjects.class, TimeSource.class)
                .newInstance(config, sharedCommunicationObjects, timeSource);
      } catch (InstantiationException
          | InvocationTargetException
          | NoSuchMethodException
          | IllegalAccessException
          | ClassNotFoundException e) {
        log.error("Failed to instantiate data streams checkpointer", e);
        return new StubDataStreamsCheckpointer();
      }
    } else {
      log.debug("Data streams monitoring not enabled.");
      return new StubDataStreamsCheckpointer();
    }
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
    private final CoreTracer tracer;

    // Builder attributes
    private Map<String, Object> tags;
    private long timestampMicro;
    private Object parent;
    private String serviceName;
    private String resourceName;
    private boolean errorFlag;
    private CharSequence spanType;
    private boolean ignoreScope = false;
    private boolean emitCheckpoints = true;

    CoreSpanBuilder(final CharSequence operationName, CoreTracer tracer) {
      this.operationName = operationName;
      this.tracer = tracer;
    }

    @Override
    public CoreSpanBuilder ignoreActiveSpan() {
      ignoreScope = true;
      return this;
    }

    private DDSpan buildSpan() {
      DDSpan span = DDSpan.create(timestampMicro, buildSpanContext(), emitCheckpoints);
      if (span.isLocalRootSpan()) {
        tracer.onRootSpanStarted(span);
      }
      return span;
    }

    @Override
    public AgentSpan start() {
      return buildSpan();
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
    public CoreSpanBuilder withSpanType(final CharSequence spanType) {
      this.spanType = spanType;
      return this;
    }

    @Override
    public AgentTracer.SpanBuilder suppressCheckpoints() {
      this.emitCheckpoints = false;
      return this;
    }

    @Override
    public CoreSpanBuilder asChildOf(final AgentSpan.Context spanContext) {
      // TODO we will start propagating stack trace hash and it will need to
      //  be extracted here if available
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
      final Map<String, ?> rootSpanTags;

      final DDSpanContext context;
      Object requestContextDataAppSec;
      Object requestContextDataIast;
      final PathwayContext pathwayContext;
      final DatadogTags datadogTags;

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

      String parentServiceName = null;

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
        parentServiceName = ddsc.getServiceName();
        if (serviceName == null) {
          serviceName = parentServiceName;
        }
        RequestContext requestContext = ((DDSpanContext) parentContext).getRequestContext();
        if (requestContext != null) {
          requestContextDataAppSec = requestContext.getData(RequestContextSlot.APPSEC);
          requestContextDataIast = requestContext.getData(RequestContextSlot.IAST);
        } else {
          requestContextDataAppSec = null;
          requestContextDataIast = null;
        }
        pathwayContext =
            ddsc.getPathwayContext().isStarted()
                ? ddsc.getPathwayContext()
                : dataStreamsCheckpointer.newPathwayContext();
        datadogTags = datadogTagsFactory.empty();
      } else {
        long endToEndStartTime;

        if (parentContext instanceof ExtractedContext) {
          // Propagate external trace
          final ExtractedContext extractedContext = (ExtractedContext) parentContext;
          traceId = extractedContext.getTraceId();
          parentSpanId = extractedContext.getSpanId();
          samplingPriority = extractedContext.getSamplingPriority();
          endToEndStartTime = extractedContext.getEndToEndStartTime();
          baggage = extractedContext.getBaggage();
          datadogTags = extractedContext.getDatadogTags();
        } else {
          // Start a new trace
          traceId = IdGenerationStrategy.RANDOM.generate();
          parentSpanId = DDId.ZERO;
          samplingPriority = PrioritySampling.UNSET;
          endToEndStartTime = 0;
          baggage = null;
          datadogTags = datadogTagsFactory.empty();
        }

        // Get header tags and set origin whether propagating or not.
        if (parentContext instanceof TagContext) {
          TagContext tc = (TagContext) parentContext;
          coreTags = tc.getTags();
          origin = tc.getOrigin();
          requestContextDataAppSec = tc.getRequestContextDataAppSec();
          requestContextDataIast = tc.getRequestContextDataIast();
        } else {
          coreTags = null;
          origin = null;
          requestContextDataAppSec = null;
          requestContextDataIast = null;
        }

        rootSpanTags = localRootSpanTags;

        parentTrace = createTrace(traceId);

        if (endToEndStartTime > 0) {
          parentTrace.beginEndToEnd(endToEndStartTime);
        }

        pathwayContext = dataStreamsCheckpointer.newPathwayContext();
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
              parentServiceName,
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
              requestContextDataAppSec,
              requestContextDataIast,
              pathwayContext,
              disableSamplingMechanismValidation,
              datadogTags);

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
      super(AGENT_THREAD_GROUP, "dd-tracer-shutdown-hook");
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

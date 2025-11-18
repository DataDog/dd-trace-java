package datadog.trace.core;

import static datadog.communication.monitor.DDAgentStatsDClientManager.statsDClientManager;
import static datadog.trace.api.DDTags.DJM_ENABLED;
import static datadog.trace.api.DDTags.DSM_ENABLED;
import static datadog.trace.api.DDTags.PROFILING_CONTEXT_ENGINE;
import static datadog.trace.api.TracePropagationBehaviorExtract.IGNORE;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.BAGGAGE_CONCERN;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.DSM_CONCERN;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.INFERRED_PROXY_CONCERN;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.TRACING_CONCERN;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.XRAY_TRACING_CONCERN;
import static datadog.trace.common.metrics.MetricsAggregatorFactory.createMetricsAggregator;
import static datadog.trace.util.AgentThreadFactory.AGENT_THREAD_GROUP;
import static datadog.trace.util.CollectionUtils.tryMakeImmutableMap;
import static java.util.Collections.emptyList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.ExternalAgentLauncher;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.communication.monitor.Monitoring;
import datadog.communication.monitor.Recording;
import datadog.context.propagation.Propagators;
import datadog.environment.ThreadUtils;
import datadog.trace.api.ClassloaderConfigurationOverrides;
import datadog.trace.api.Config;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.DynamicConfig;
import datadog.trace.api.EndpointTracker;
import datadog.trace.api.IdGenerationStrategy;
import datadog.trace.api.StatsDClient;
import datadog.trace.api.TagMap;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.api.datastreams.AgentDataStreamsMonitoring;
import datadog.trace.api.datastreams.PathwayContext;
import datadog.trace.api.experimental.DataStreamsCheckpointer;
import datadog.trace.api.flare.TracerFlare;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.InstrumentationGateway;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.gateway.SubscriptionService;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.api.metrics.SpanMetricRegistry;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.api.remoteconfig.ServiceNameCollector;
import datadog.trace.api.rum.RumInjector;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.scopemanager.ScopeListener;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.api.time.TimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentHistogram;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Baggage;
import datadog.trace.bootstrap.instrumentation.api.BlackHoleSpan;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import datadog.trace.bootstrap.instrumentation.api.SpanAttributes;
import datadog.trace.bootstrap.instrumentation.api.SpanLink;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.civisibility.interceptor.CiVisibilityApmProtocolInterceptor;
import datadog.trace.civisibility.interceptor.CiVisibilityTelemetryInterceptor;
import datadog.trace.civisibility.interceptor.CiVisibilityTraceInterceptor;
import datadog.trace.common.GitMetadataTraceInterceptor;
import datadog.trace.common.metrics.MetricsAggregator;
import datadog.trace.common.metrics.NoOpMetricsAggregator;
import datadog.trace.common.sampling.Sampler;
import datadog.trace.common.sampling.SingleSpanSampler;
import datadog.trace.common.sampling.SpanSamplingRules;
import datadog.trace.common.sampling.TraceSamplingRules;
import datadog.trace.common.writer.DDAgentWriter;
import datadog.trace.common.writer.Writer;
import datadog.trace.common.writer.WriterFactory;
import datadog.trace.common.writer.ddintake.DDIntakeTraceInterceptor;
import datadog.trace.context.TraceScope;
import datadog.trace.core.baggage.BaggagePropagator;
import datadog.trace.core.datastreams.DataStreamsMonitoring;
import datadog.trace.core.datastreams.DefaultDataStreamsMonitoring;
import datadog.trace.core.histogram.Histograms;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.monitor.MonitoringImpl;
import datadog.trace.core.monitor.TracerHealthMetrics;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.core.propagation.HttpCodec;
import datadog.trace.core.propagation.InferredProxyPropagator;
import datadog.trace.core.propagation.PropagationTags;
import datadog.trace.core.propagation.TracingPropagator;
import datadog.trace.core.propagation.XRayPropagator;
import datadog.trace.core.scopemanager.ContinuableScopeManager;
import datadog.trace.core.servicediscovery.ServiceDiscovery;
import datadog.trace.core.servicediscovery.ServiceDiscoveryFactory;
import datadog.trace.core.taginterceptor.RuleFlags;
import datadog.trace.core.taginterceptor.TagInterceptor;
import datadog.trace.core.traceinterceptor.LatencyTraceInterceptor;
import datadog.trace.lambda.LambdaHandler;
import datadog.trace.relocate.api.RatelimitedLogger;
import datadog.trace.util.AgentTaskScheduler;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entrypoint into the tracer implementation. In addition to implementing
 * datadog.trace.api.Tracer and TracerAPI, it coordinates many functions necessary creating,
 * reporting, and propagating traces
 */
public class CoreTracer implements AgentTracer.TracerAPI, TracerFlare.Reporter {
  private static final Logger log = LoggerFactory.getLogger(CoreTracer.class);
  // UINT64 max value
  public static final BigInteger TRACE_ID_MAX =
      BigInteger.valueOf(2).pow(64).subtract(BigInteger.ONE);

  public static final CoreTracerBuilder builder() {
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

  /** If the tracer can create inferred services */
  private final boolean allowInferredServices;

  /** Last time (in nanosecond ticks) the clock was checked for drift */
  private volatile long lastSyncTicks;

  /** Nanosecond offset to counter clock drift */
  private volatile long counterDrift;

  private final TracingConfigPoller tracingConfigPoller;

  private final PendingTraceBuffer pendingTraceBuffer;

  /** Default service name if none provided on the trace or span */
  final String serviceName;

  /** Writer is in charge of reporting traces and spans to the desired endpoint */
  final Writer writer;

  /** Sampler defines the sampling policy in order to reduce the number of traces for instance */
  final Sampler initialSampler;

  /** Scope manager is in charge of managing the scopes from which spans are created */
  final ContinuableScopeManager scopeManager;

  volatile MetricsAggregator metricsAggregator;

  /** Initial static configuration associated with the tracer. */
  final Config initialConfig;

  /** Maintains dynamic configuration associated with the tracer */
  private final DynamicConfig<ConfigSnapshot> dynamicConfig;

  /** A set of tags that are added only to the application's root span */
  private final TagMap localRootSpanTags;

  private final boolean localRootSpanTagsNeedIntercept;

  /** A set of tags that are added to every span */
  private final TagMap defaultSpanTags;

  private final boolean defaultSpanTagsNeedsIntercept;

  /** number of spans in a pending trace before they get flushed */
  private final int partialFlushMinSpans;

  private final StatsDClient statsDClient;
  private final Monitoring monitoring;
  private final Monitoring performanceMonitoring;

  private final HealthMetrics healthMetrics;
  private final Recording traceWriteTimer;
  private final IdGenerationStrategy idGenerationStrategy;
  private final TraceCollector.Factory traceCollectorFactory;
  private final DataStreamsMonitoring dataStreamsMonitoring;
  private final ExternalAgentLauncher externalAgentLauncher;
  private final boolean disableSamplingMechanismValidation;
  private final TimeSource timeSource;
  private final ProfilingContextIntegration profilingContextIntegration;
  private final boolean injectBaggageAsTags;
  private final boolean flushOnClose;
  private final Collection<Runnable> shutdownListeners = new CopyOnWriteArrayList<>();

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
      new ConcurrentSkipListSet<>(Comparator.comparingInt(TraceInterceptor::priority));

  private final boolean logs128bTraceIdEnabled;

  private final InstrumentationGateway instrumentationGateway;
  private final CallbackProvider callbackProviderAppSec;
  private final CallbackProvider callbackProviderIast;
  private final CallbackProvider universalCallbackProvider;

  private final PropagationTags.Factory propagationTagsFactory;

  // DQH - storing into a static constant, so value will constant propagate and dead code eliminate
  // the other branch in singleSpanBuilder
  private static final boolean SPAN_BUILDER_REUSE_ENABLED =
      Config.get().isSpanBuilderReuseEnabled();

  // Cache used by buildSpan - instance so it can capture the CoreTracer
  private final ReusableSingleSpanBuilderThreadLocalCache spanBuilderThreadLocalCache =
      SPAN_BUILDER_REUSE_ENABLED ? new ReusableSingleSpanBuilderThreadLocalCache(this) : null;

  @Override
  public ConfigSnapshot captureTraceConfig() {
    return dynamicConfig.captureTraceConfig();
  }

  @Override
  public AgentHistogram newHistogram(double relativeAccuracy, int maxNumBins) {
    return Histograms.newHistogram(relativeAccuracy, maxNumBins);
  }

  @Override
  public void updatePreferredServiceName(String serviceName) {
    dynamicConfig.current().setPreferredServiceName(serviceName).apply();
    ServiceNameCollector.get().addService(serviceName);
  }

  PropagationTags.Factory getPropagationTagsFactory() {
    return propagationTagsFactory;
  }

  /**
   * Called when a root span is finished before it is serialized. This is might be called multiple
   * times per root span. If a child span is part of a partial flush, this method will be called for
   * its root even if not finished.
   */
  @Override
  public void onRootSpanFinished(AgentSpan root, EndpointTracker tracker) {
    if (!root.isOutbound()) {
      profilingContextIntegration.onRootSpanFinished(root, tracker);
    }
  }

  /**
   * Called when a root span is finished before it is serialized. This is guaranteed to be called
   * exactly once per root span.
   */
  void onRootSpanPublished(final AgentSpan root) {
    // Request context is propagated to contexts in child spans.
    // Assume here that if present it will be so starting in the top span.
    RequestContext requestContext = root.getRequestContext();
    if (requestContext != null) {
      try {
        requestContext.close();
      } catch (IOException e) {
        log.warn("Error closing request context data", e);
      }
    }
  }

  @Override
  public EndpointTracker onRootSpanStarted(AgentSpan root) {
    if (!root.isOutbound()) {
      return profilingContextIntegration.onRootSpanStarted(root);
    }
    return null;
  }

  public static class CoreTracerBuilder {

    private Config config;
    private String serviceName;
    private SharedCommunicationObjects sharedCommunicationObjects;
    private Writer writer;
    private IdGenerationStrategy idGenerationStrategy;
    private Sampler sampler;
    private SingleSpanSampler singleSpanSampler;
    private HttpCodec.Injector injector;
    private HttpCodec.Extractor extractor;
    private TagMap localRootSpanTags;
    private TagMap defaultSpanTags;
    private Map<String, String> serviceNameMappings;
    private Map<String, String> taggedHeaders;
    private Map<String, String> baggageMapping;
    private int partialFlushMinSpans;
    private StatsDClient statsDClient;
    private HealthMetrics healthMetrics;
    private TagInterceptor tagInterceptor;
    private boolean strictTraceWrites;
    private InstrumentationGateway instrumentationGateway;
    private ServiceDiscoveryFactory serviceDiscoveryFactory;
    private TimeSource timeSource;
    private DataStreamsMonitoring dataStreamsMonitoring;
    private ProfilingContextIntegration profilingContextIntegration =
        ProfilingContextIntegration.NoOp.INSTANCE;
    private boolean reportInTracerFlare;
    private boolean pollForTracingConfiguration;
    private boolean injectBaggageAsTags;
    private boolean flushOnClose;

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

    public CoreTracerBuilder sampler(Sampler sampler) {
      this.sampler = sampler;
      return this;
    }

    public CoreTracerBuilder singleSpanSampler(SingleSpanSampler singleSpanSampler) {
      this.singleSpanSampler = singleSpanSampler;
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

    public CoreTracerBuilder localRootSpanTags(Map<String, ?> localRootSpanTags) {
      this.localRootSpanTags = TagMap.fromMapImmutable(localRootSpanTags);
      return this;
    }

    public CoreTracerBuilder localRootSpanTags(TagMap tagMap) {
      this.localRootSpanTags = tagMap.immutableCopy();
      return this;
    }

    public CoreTracerBuilder defaultSpanTags(Map<String, ?> defaultSpanTags) {
      this.defaultSpanTags = TagMap.fromMapImmutable(defaultSpanTags);
      return this;
    }

    public CoreTracerBuilder defaultSpanTags(TagMap defaultSpanTags) {
      this.defaultSpanTags = defaultSpanTags.immutableCopy();
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

    public CoreTracerBuilder baggageMapping(Map<String, String> baggageMapping) {
      this.baggageMapping = tryMakeImmutableMap(baggageMapping);
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

    public CoreTracerBuilder healthMetrics(HealthMetrics healthMetrics) {
      this.healthMetrics = healthMetrics;
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

    public CoreTracerBuilder serviceDiscoveryFactory(
        ServiceDiscoveryFactory serviceDiscoveryFactory) {
      this.serviceDiscoveryFactory = serviceDiscoveryFactory;
      return this;
    }

    public CoreTracerBuilder timeSource(TimeSource timeSource) {
      this.timeSource = timeSource;
      return this;
    }

    public CoreTracerBuilder dataStreamsMonitoring(DataStreamsMonitoring dataStreamsMonitoring) {
      this.dataStreamsMonitoring = dataStreamsMonitoring;
      return this;
    }

    public CoreTracerBuilder profilingContextIntegration(
        ProfilingContextIntegration profilingContextIntegration) {
      this.profilingContextIntegration = profilingContextIntegration;
      return this;
    }

    public CoreTracerBuilder reportInTracerFlare() {
      this.reportInTracerFlare = true;
      return this;
    }

    public CoreTracerBuilder pollForTracingConfiguration() {
      this.pollForTracingConfiguration = true;
      return this;
    }

    public CoreTracerBuilder injectBaggageAsTags(boolean injectBaggageAsTags) {
      this.injectBaggageAsTags = injectBaggageAsTags;
      return this;
    }

    public CoreTracerBuilder flushOnClose(boolean flushOnClose) {
      this.flushOnClose = flushOnClose;
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
      sampler(Sampler.Builder.forConfig(config, null));
      singleSpanSampler(SingleSpanSampler.Builder.forConfig(config));
      instrumentationGateway(new InstrumentationGateway());
      injector(
          HttpCodec.createInjector(
              config,
              config.getTracePropagationStylesToInject(),
              invertMap(config.getBaggageMapping())));
      // Explicitly skip setting scope manager because it depends on statsDClient
      localRootSpanTags(config.getLocalRootSpanTags());
      defaultSpanTags(withTracerTags(config.getMergedSpanTags(), config, null));
      serviceNameMappings(config.getServiceMapping());
      taggedHeaders(config.getRequestHeaderTags());
      baggageMapping(config.getBaggageMapping());
      partialFlushMinSpans(config.getPartialFlushMinSpans());
      strictTraceWrites(config.isTraceStrictWritesEnabled());
      injectBaggageAsTags(config.isInjectBaggageAsTagsEnabled());
      flushOnClose(config.isCiVisibilityEnabled());
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
          singleSpanSampler,
          injector,
          extractor,
          localRootSpanTags,
          defaultSpanTags,
          serviceNameMappings,
          taggedHeaders,
          baggageMapping,
          partialFlushMinSpans,
          statsDClient,
          healthMetrics,
          tagInterceptor,
          strictTraceWrites,
          instrumentationGateway,
          serviceDiscoveryFactory,
          timeSource,
          dataStreamsMonitoring,
          profilingContextIntegration,
          reportInTracerFlare,
          pollForTracingConfiguration,
          injectBaggageAsTags,
          flushOnClose);
    }
  }

  @Deprecated
  private CoreTracer(
      final Config config,
      final String serviceName,
      SharedCommunicationObjects sharedCommunicationObjects,
      final Writer writer,
      final IdGenerationStrategy idGenerationStrategy,
      final Sampler sampler,
      final SingleSpanSampler singleSpanSampler,
      final HttpCodec.Injector injector,
      final HttpCodec.Extractor extractor,
      final Map<String, Object> localRootSpanTags,
      final TagMap defaultSpanTags,
      final Map<String, String> serviceNameMappings,
      final Map<String, String> taggedHeaders,
      final Map<String, String> baggageMapping,
      final int partialFlushMinSpans,
      final StatsDClient statsDClient,
      final HealthMetrics healthMetrics,
      final TagInterceptor tagInterceptor,
      final boolean strictTraceWrites,
      final InstrumentationGateway instrumentationGateway,
      final TimeSource timeSource,
      final DataStreamsMonitoring dataStreamsMonitoring,
      final ProfilingContextIntegration profilingContextIntegration,
      final boolean reportInTracerFlare,
      final boolean pollForTracingConfiguration,
      final boolean injectBaggageAsTags,
      final boolean flushOnClose) {
    this(
        config,
        serviceName,
        sharedCommunicationObjects,
        writer,
        idGenerationStrategy,
        sampler,
        singleSpanSampler,
        injector,
        extractor,
        TagMap.fromMap(localRootSpanTags),
        defaultSpanTags,
        serviceNameMappings,
        taggedHeaders,
        baggageMapping,
        partialFlushMinSpans,
        statsDClient,
        healthMetrics,
        tagInterceptor,
        strictTraceWrites,
        instrumentationGateway,
        null,
        timeSource,
        dataStreamsMonitoring,
        profilingContextIntegration,
        reportInTracerFlare,
        pollForTracingConfiguration,
        injectBaggageAsTags,
        flushOnClose);
  }

  // These field names must be stable to ensure the builder api is stable.
  private CoreTracer(
      final Config config,
      final String serviceName,
      SharedCommunicationObjects sharedCommunicationObjects,
      final Writer writer,
      final IdGenerationStrategy idGenerationStrategy,
      final Sampler sampler,
      final SingleSpanSampler singleSpanSampler,
      final HttpCodec.Injector injector,
      final HttpCodec.Extractor extractor,
      final TagMap localRootSpanTags,
      final TagMap defaultSpanTags,
      final Map<String, String> serviceNameMappings,
      final Map<String, String> taggedHeaders,
      final Map<String, String> baggageMapping,
      final int partialFlushMinSpans,
      final StatsDClient statsDClient,
      final HealthMetrics healthMetrics,
      final TagInterceptor tagInterceptor,
      final boolean strictTraceWrites,
      final InstrumentationGateway instrumentationGateway,
      final ServiceDiscoveryFactory serviceDiscoveryFactory,
      final TimeSource timeSource,
      final DataStreamsMonitoring dataStreamsMonitoring,
      final ProfilingContextIntegration profilingContextIntegration,
      final boolean reportInTracerFlare,
      final boolean pollForTracingConfiguration,
      final boolean injectBaggageAsTags,
      final boolean flushOnClose) {

    assert localRootSpanTags != null;
    assert defaultSpanTags != null;
    assert serviceNameMappings != null;
    assert taggedHeaders != null;
    assert baggageMapping != null;

    if (reportInTracerFlare) {
      TracerFlare.addReporter(this);
    }
    this.timeSource = timeSource == null ? SystemTimeSource.INSTANCE : timeSource;
    startTimeNano = this.timeSource.getCurrentTimeNanos();
    startNanoTicks = this.timeSource.getNanoTicks();
    clockSyncPeriod = Math.max(1_000_000L, SECONDS.toNanos(config.getClockSyncPeriod()));
    lastSyncTicks = startNanoTicks;

    this.serviceName = serviceName;

    this.initialConfig = config;
    this.initialSampler = sampler;

    // Get initial Trace Sampling Rules from config
    String traceSamplingRulesJson = config.getTraceSamplingRules();
    TraceSamplingRules traceSamplingRules;
    if (traceSamplingRulesJson == null) {
      traceSamplingRulesJson = "[]";
      traceSamplingRules = TraceSamplingRules.EMPTY;
    } else {
      traceSamplingRules = TraceSamplingRules.deserialize(traceSamplingRulesJson);
    }
    // Get initial Span Sampling Rules from config
    String spanSamplingRulesJson = config.getSpanSamplingRules();
    String spanSamplingRulesFile = config.getSpanSamplingRulesFile();
    SpanSamplingRules spanSamplingRules = SpanSamplingRules.EMPTY;
    if (spanSamplingRulesJson != null) {
      spanSamplingRules = SpanSamplingRules.deserialize(spanSamplingRulesJson);
    } else if (spanSamplingRulesFile != null) {
      spanSamplingRules = SpanSamplingRules.deserializeFile(spanSamplingRulesFile);
    }

    this.tagInterceptor =
        null == tagInterceptor ? new TagInterceptor(new RuleFlags(config)) : tagInterceptor;

    this.defaultSpanTags = defaultSpanTags;
    this.defaultSpanTagsNeedsIntercept = this.tagInterceptor.needsIntercept(this.defaultSpanTags);

    this.dynamicConfig =
        DynamicConfig.create(ConfigSnapshot::new)
            .setTracingEnabled(true) // implied by installation of CoreTracer
            .setRuntimeMetricsEnabled(config.isRuntimeMetricsEnabled())
            .setLogsInjectionEnabled(config.isLogsInjectionEnabled())
            .setDataStreamsEnabled(config.isDataStreamsEnabled())
            .setServiceMapping(serviceNameMappings)
            .setHeaderTags(taggedHeaders)
            .setBaggageMapping(baggageMapping)
            .setTraceSampleRate(config.getTraceSampleRate())
            .setSpanSamplingRules(spanSamplingRules.getRules())
            .setTraceSamplingRules(traceSamplingRules.getRules(), traceSamplingRulesJson)
            .setTracingTags(config.getMergedSpanTags())
            .apply();

    this.logs128bTraceIdEnabled = Config.get().isLogs128bitTraceIdEnabled();
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

    monitoring =
        config.isHealthMetricsEnabled()
            ? new MonitoringImpl(this.statsDClient, 10, SECONDS)
            : Monitoring.DISABLED;

    this.healthMetrics =
        healthMetrics != null
            ? healthMetrics
            : (config.isHealthMetricsEnabled()
                ? new TracerHealthMetrics(this.statsDClient)
                : HealthMetrics.NO_OP);
    this.healthMetrics.start();

    performanceMonitoring =
        config.isPerfMetricsEnabled()
            ? new MonitoringImpl(this.statsDClient, 10, SECONDS)
            : Monitoring.DISABLED;

    traceWriteTimer = performanceMonitoring.newThreadLocalTimer("trace.write");

    scopeManager =
        new ContinuableScopeManager(
            config.getScopeDepthLimit(),
            config.isScopeStrictMode(),
            profilingContextIntegration,
            this.healthMetrics);

    externalAgentLauncher = new ExternalAgentLauncher(config);

    disableSamplingMechanismValidation = config.isSamplingMechanismValidationDisabled();

    if (sharedCommunicationObjects == null) {
      sharedCommunicationObjects = new SharedCommunicationObjects();
    }
    sharedCommunicationObjects.monitoring = monitoring;
    sharedCommunicationObjects.createRemaining(config);

    tracingConfigPoller = new TracingConfigPoller(dynamicConfig);
    if (pollForTracingConfiguration) {
      tracingConfigPoller.start(config, sharedCommunicationObjects);
    }

    if (writer == null) {
      this.writer =
          WriterFactory.createWriter(
              config, sharedCommunicationObjects, sampler, singleSpanSampler, this.healthMetrics);
    } else {
      this.writer = writer;
    }

    DDAgentFeaturesDiscovery featuresDiscovery =
        sharedCommunicationObjects.featuresDiscovery(config);

    if (config.isCiVisibilityEnabled()) {
      // ensure updated discovery and sync if the another discovery currently being done
      featuresDiscovery.discoverIfOutdated();
    }

    if (config.isCiVisibilityEnabled()
        && (config.isCiVisibilityAgentlessEnabled() || featuresDiscovery.supportsEvpProxy())) {
      pendingTraceBuffer = PendingTraceBuffer.discarding();
      traceCollectorFactory =
          new StreamingTraceCollector.Factory(this, this.timeSource, this.healthMetrics);
    } else {
      pendingTraceBuffer =
          strictTraceWrites
              ? PendingTraceBuffer.discarding()
              : PendingTraceBuffer.delaying(
                  this.timeSource, config, sharedCommunicationObjects, this.healthMetrics);
      traceCollectorFactory =
          new PendingTrace.Factory(
              this, pendingTraceBuffer, this.timeSource, strictTraceWrites, this.healthMetrics);
    }
    pendingTraceBuffer.start();

    sharedCommunicationObjects.whenReady(this.writer::start);
    // temporary assign a no-op instance. The final one will be resolved when the discovery will be
    // allowed
    metricsAggregator = NoOpMetricsAggregator.INSTANCE;
    final SharedCommunicationObjects sco = sharedCommunicationObjects;
    // asynchronously create the aggregator to avoid triggering expensive classloading during the
    // tracer initialisation.
    sharedCommunicationObjects.whenReady(
        () ->
            AgentTaskScheduler.get()
                .execute(
                    () -> {
                      metricsAggregator = createMetricsAggregator(config, sco, this.healthMetrics);
                      // Schedule the metrics aggregator to begin reporting after a random delay of
                      // 1 to 10 seconds (using milliseconds granularity.)
                      // This avoids a fleet of traced applications starting at the same time from
                      // sending metrics in sync.
                      AgentTaskScheduler.get()
                          .scheduleWithJitter(
                              MetricsAggregator::start, metricsAggregator, 1, SECONDS);
                    }));

    if (dataStreamsMonitoring == null) {
      this.dataStreamsMonitoring =
          new DefaultDataStreamsMonitoring(
              config, sharedCommunicationObjects, this.timeSource, this::captureTraceConfig);
    } else {
      this.dataStreamsMonitoring = dataStreamsMonitoring;
    }

    sharedCommunicationObjects.whenReady(this.dataStreamsMonitoring::start);

    // Register context propagators
    HttpCodec.Extractor tracingExtractor =
        extractor == null ? HttpCodec.createExtractor(config, this::captureTraceConfig) : extractor;
    TracingPropagator tracingPropagator =
        new TracingPropagator(config.isApmTracingEnabled(), injector, tracingExtractor);
    Propagators.register(TRACING_CONCERN, tracingPropagator);
    Propagators.register(XRAY_TRACING_CONCERN, new XRayPropagator(config), false);
    if (config.isDataStreamsEnabled()) {
      Propagators.register(DSM_CONCERN, this.dataStreamsMonitoring.propagator());
    }
    if (config.isBaggagePropagationEnabled()
        && config.getTracePropagationBehaviorExtract() != IGNORE) {
      Propagators.register(BAGGAGE_CONCERN, new BaggagePropagator(config));
    }
    if (config.isInferredProxyPropagationEnabled()) {
      Propagators.register(INFERRED_PROXY_CONCERN, new InferredProxyPropagator());
    }

    if (config.isCiVisibilityEnabled()) {
      if (config.isCiVisibilityTraceSanitationEnabled()) {
        addTraceInterceptor(CiVisibilityTraceInterceptor.INSTANCE);
      }

      if (config.isCiVisibilityAgentlessEnabled()) {
        addTraceInterceptor(DDIntakeTraceInterceptor.INSTANCE);
      } else {
        featuresDiscovery.discoverIfOutdated();
        if (!featuresDiscovery.supportsEvpProxy()) {
          // CI Test Cycle protocol is not available
          addTraceInterceptor(CiVisibilityApmProtocolInterceptor.INSTANCE);
        }
      }

      if (config.isCiVisibilityTelemetryEnabled()) {
        addTraceInterceptor(new CiVisibilityTelemetryInterceptor());
      }
    }

    if (config.isTraceGitMetadataEnabled()) {
      addTraceInterceptor(GitMetadataTraceInterceptor.INSTANCE);
    }

    if (config.isTraceKeepLatencyThresholdEnabled()) {
      addTraceInterceptor(LatencyTraceInterceptor.INSTANCE);
    }

    this.instrumentationGateway = instrumentationGateway;
    callbackProviderAppSec = instrumentationGateway.getCallbackProvider(RequestContextSlot.APPSEC);
    callbackProviderIast = instrumentationGateway.getCallbackProvider(RequestContextSlot.IAST);
    universalCallbackProvider = instrumentationGateway.getUniversalCallbackProvider();

    shutdownCallback = new ShutdownHook(this);
    try {
      Runtime.getRuntime().addShutdownHook(shutdownCallback);
    } catch (final IllegalStateException ex) {
      // The JVM is already shutting down.
    }

    registerClassLoader(ClassLoader.getSystemClassLoader());

    StatusLogger.logStatus(config);

    propagationTagsFactory = PropagationTags.factory(config);
    this.profilingContextIntegration = profilingContextIntegration;
    this.injectBaggageAsTags = injectBaggageAsTags;
    this.flushOnClose = flushOnClose;
    this.allowInferredServices = SpanNaming.instance().namingSchema().allowInferredServices();
    if (profilingContextIntegration != ProfilingContextIntegration.NoOp.INSTANCE) {
      TagMap tmp = TagMap.fromMap(localRootSpanTags);
      tmp.put(PROFILING_CONTEXT_ENGINE, profilingContextIntegration.name());
      this.localRootSpanTags = tmp.freeze();
    } else {
      this.localRootSpanTags = TagMap.fromMapImmutable(localRootSpanTags);
    }

    this.localRootSpanTagsNeedIntercept =
        this.tagInterceptor.needsIntercept(this.localRootSpanTags);
    if (serviceDiscoveryFactory != null) {
      AgentTaskScheduler.get()
          .schedule(
              () -> {
                final ServiceDiscovery serviceDiscovery = serviceDiscoveryFactory.get();
                if (serviceDiscovery != null) {
                  // JNA can do ldconfig and other commands. Those are hidden since internal.
                  try (final TraceScope blackhole = muteTracing()) {
                    serviceDiscovery.writeTracerMetadata(config);
                  }
                }
              },
              1,
              SECONDS);
    }
  }

  /** Used by AgentTestRunner to inject configuration into the test tracer. */
  public void rebuildTraceConfig(Config config) {
    dynamicConfig
        .initial()
        .setTracingEnabled(true) // implied by installation of CoreTracer
        .setRuntimeMetricsEnabled(config.isRuntimeMetricsEnabled())
        .setLogsInjectionEnabled(config.isLogsInjectionEnabled())
        .setDataStreamsEnabled(config.isDataStreamsEnabled())
        .setServiceMapping(config.getServiceMapping())
        .setHeaderTags(config.getRequestHeaderTags())
        .setBaggageMapping(config.getBaggageMapping())
        .setTraceSampleRate(config.getTraceSampleRate())
        .setTracingTags(config.getGlobalTags())
        .apply();
  }

  @Override
  protected void finalize() {
    if (null != shutdownCallback) {
      try {
        shutdownCallback.run();
        Runtime.getRuntime().removeShutdownHook(shutdownCallback);
      } catch (final IllegalStateException e) {
        // Do nothing.  Already shutting down
      } catch (final Exception e) {
        log.error("Error while finalizing DDTracer.", e);
      }
    }
  }

  /**
   * Only visible for benchmarking purposes
   *
   * @return a PendingTrace
   */
  public TraceCollector createTraceCollector(DDTraceId id) {
    return traceCollectorFactory.create(id);
  }

  TraceCollector createTraceCollector(DDTraceId id, ConfigSnapshot traceConfig) {
    return traceCollectorFactory.create(id, traceConfig);
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
      log.warn("Problem loading TraceInterceptor for classLoader: {}", classLoader, e);
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
  public CoreSpanBuilder buildSpan(
      final String instrumentationName, final CharSequence operationName) {
    return createMultiSpanBuilder(instrumentationName, operationName);
  }

  MultiSpanBuilder createMultiSpanBuilder(
      final String instrumentationName, final CharSequence operationName) {
    return new MultiSpanBuilder(this, instrumentationName, operationName);
  }

  @Override
  public CoreSpanBuilder singleSpanBuilder(
      final String instrumentationName, final CharSequence operationName) {
    return SPAN_BUILDER_REUSE_ENABLED
        ? reuseSingleSpanBuilder(instrumentationName, operationName)
        : createMultiSpanBuilder(instrumentationName, operationName);
  }

  ReusableSingleSpanBuilder createSingleSpanBuilder(
      final String instrumentationName, final CharSequence oprationName) {
    ReusableSingleSpanBuilder singleSpanBuilder = new ReusableSingleSpanBuilder(this);
    singleSpanBuilder.init(instrumentationName, oprationName);
    return singleSpanBuilder;
  }

  CoreSpanBuilder reuseSingleSpanBuilder(
      final String instrumentationName, final CharSequence operationName) {
    return reuseSingleSpanBuilder(
        this, spanBuilderThreadLocalCache, instrumentationName, operationName);
  }

  static final ReusableSingleSpanBuilder reuseSingleSpanBuilder(
      final CoreTracer tracer,
      final ReusableSingleSpanBuilderThreadLocalCache tlCache,
      final String instrumentationName,
      final CharSequence operationName) {
    if (ThreadUtils.isCurrentThreadVirtual()) {
      // Since virtual threads are created and destroyed often,
      // cautiously decided not to create a thread local for the virtual threads.

      // TODO: This could probably be improved by having a single thread local that
      // holds the core things that we need for tracing.  e.g. context, etc
      return tracer.createSingleSpanBuilder(instrumentationName, operationName);
    }

    // retrieve the thread's typical SpanBuilder and try to reset it
    // reset will fail if the ReusableSingleSpanBuilder is still "in-use"
    ReusableSingleSpanBuilder tlSpanBuilder = tlCache.get();
    boolean wasReset = tlSpanBuilder.reset(instrumentationName, operationName);
    if (wasReset) return tlSpanBuilder;

    // TODO: counter for how often the fallback is used?
    ReusableSingleSpanBuilder newSpanBuilder =
        tracer.createSingleSpanBuilder(instrumentationName, operationName);

    // DQH - Debated how best to handle the case of someone requesting a SpanBuilder
    // and then not using it.  Without the ability to replace the cached SpanBuilder,
    // that case could result in permanently burning the cache for a given thread.

    // That could be solved with additional logic during ReusableSingleSpanBuilder#start
    // that checks to see if the cached Builder is in use and then replaces it
    // with the freed Builder, but that would put extra logic in the common path.

    // Instead of making the release process more complicated, I'm chosing to just
    // override here when we know we're already in an uncommon path.
    tlCache.set(newSpanBuilder);

    return newSpanBuilder;
  }

  @Override
  public AgentSpan startSpan(final String instrumentationName, final CharSequence spanName) {
    return singleSpanBuilder(instrumentationName, spanName).start();
  }

  @Override
  public AgentSpan startSpan(
      final String instrumentationName, final CharSequence spanName, final long startTimeMicros) {
    return singleSpanBuilder(instrumentationName, spanName)
        .withStartTimestamp(startTimeMicros)
        .start();
  }

  @Override
  public AgentSpan startSpan(
      String instrumentationName, final CharSequence spanName, final AgentSpanContext parent) {
    return singleSpanBuilder(instrumentationName, spanName)
        .ignoreActiveSpan()
        .asChildOf(parent)
        .start();
  }

  @Override
  public AgentSpan startSpan(
      final String instrumentationName,
      final CharSequence spanName,
      final AgentSpanContext parent,
      final long startTimeMicros) {
    return buildSpan(instrumentationName, spanName)
        .ignoreActiveSpan()
        .asChildOf(parent)
        .withStartTimestamp(startTimeMicros)
        .start();
  }

  @Override
  public AgentScope activateSpan(AgentSpan span) {
    return scopeManager.activateSpan(span);
  }

  @Override
  public AgentScope activateManualSpan(final AgentSpan span) {
    return scopeManager.activateManualSpan(span);
  }

  @Override
  @SuppressWarnings("resource")
  public void activateSpanWithoutScope(AgentSpan span) {
    scopeManager.activateSpan(span);
  }

  @Override
  public AgentScope.Continuation captureActiveSpan() {
    return scopeManager.captureActiveSpan();
  }

  @Override
  public AgentScope.Continuation captureSpan(final AgentSpan span) {
    return scopeManager.captureSpan(span);
  }

  @Override
  public boolean isAsyncPropagationEnabled() {
    return scopeManager.isAsyncPropagationEnabled();
  }

  @Override
  public void setAsyncPropagationEnabled(boolean asyncPropagationEnabled) {
    scopeManager.setAsyncPropagationEnabled(asyncPropagationEnabled);
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
  public void checkpointActiveForRollback() {
    this.scopeManager.checkpointActiveForRollback();
  }

  @Override
  public void rollbackActiveToCheckpoint() {
    this.scopeManager.rollbackActiveToCheckpoint();
  }

  @Override
  public void closeActive() {
    AgentScope activeScope = this.scopeManager.active();
    if (activeScope != null) {
      activeScope.close();
    }
  }

  @Override
  public AgentSpanContext notifyExtensionStart(Object event) {
    return LambdaHandler.notifyStartInvocation(this, event);
  }

  @Override
  public void notifyExtensionEnd(AgentSpan span, Object result, boolean isError) {
    LambdaHandler.notifyEndInvocation(span, result, isError);
  }

  @Override
  public AgentDataStreamsMonitoring getDataStreamsMonitoring() {
    return dataStreamsMonitoring;
  }

  private final RatelimitedLogger rlLog = new RatelimitedLogger(log, 1, MINUTES);

  /**
   * We use the sampler to know if the trace has to be reported/written. The sampler is called on
   * the first span (root span) of the trace. If the trace is marked as a sample, we report it.
   *
   * @param trace a list of the spans related to the same trace
   */
  void write(final List<DDSpan> trace) {
    if (trace.isEmpty() || !trace.get(0).traceConfig().isTraceEnabled()) {
      return;
    }
    List<DDSpan> writtenTrace = interceptCompleteTrace(trace);
    if (writtenTrace.isEmpty()) {
      return;
    }

    // run early tag postprocessors before publishing to the metrics writer since peer / base
    // service are needed
    for (DDSpan span : writtenTrace) {
      span.processServiceTags();
    }
    boolean forceKeep = metricsAggregator.publish(writtenTrace);

    TraceCollector traceCollector = writtenTrace.get(0).context().getTraceCollector();
    traceCollector.setSamplingPriorityIfNecessary();

    DDSpan rootSpan = traceCollector.getRootSpan();
    DDSpan spanToSample = rootSpan == null ? writtenTrace.get(0) : rootSpan;
    spanToSample.forceKeep(forceKeep);
    boolean published = forceKeep || traceCollector.sample(spanToSample);
    if (published) {
      writer.write(writtenTrace);
    } else {
      // with span streaming this won't work - it needs to be changed
      // to track an effective sampling rate instead, however, tests
      // checking that a hard reference on a continuation prevents
      // reporting fail without this, so will need to be fixed first.
      writer.incrementDropCounts(writtenTrace.size());
    }
    if (null != rootSpan) {
      onRootSpanFinished(rootSpan, rootSpan.getEndpointTracker());
    }
  }

  private List<DDSpan> interceptCompleteTrace(List<DDSpan> trace) {
    if (!interceptors.isEmpty() && !trace.isEmpty()) {
      Collection<? extends MutableSpan> interceptedTrace = new ArrayList<>(trace);
      for (final TraceInterceptor interceptor : interceptors) {
        try {
          // If one TraceInterceptor throws an exception, then continue with the next one
          interceptedTrace = interceptor.onTraceComplete(interceptedTrace);
        } catch (Throwable e) {
          String interceptorName = interceptor.getClass().getName();
          rlLog.warn("Throwable raised in TraceInterceptor {}", interceptorName, e);
        }
        if (interceptedTrace == null) {
          interceptedTrace = emptyList();
        }
      }

      trace = new ArrayList<>(interceptedTrace.size());
      for (final MutableSpan span : interceptedTrace) {
        if (span instanceof DDSpan) {
          trace.add((DDSpan) span);
        }
      }
    }
    return trace;
  }

  @Override
  public String getTraceId() {
    return getTraceId(activeSpan());
  }

  @Override
  public String getSpanId() {
    return getSpanId(activeSpan());
  }

  @Override
  public String getTraceId(AgentSpan span) {
    if (span != null && span.getTraceId() != null) {
      DDTraceId traceId = span.getTraceId();
      // Return padded hexadecimal string representation if 128-bit TraceId logging is enabled and
      // TraceId is a 128-bit ID, otherwise use the default numerical string representation.
      if (this.logs128bTraceIdEnabled && traceId.toHighOrderLong() != 0) {
        return traceId.toHexString();
      } else {
        return traceId.toString();
      }
    }
    return "0";
  }

  @Override
  public String getSpanId(AgentSpan span) {
    if (span != null) {
      return DDSpanId.toString(span.getSpanId());
    }
    return "0";
  }

  @Override
  public boolean addTraceInterceptor(final TraceInterceptor interceptor) {
    if (interceptors.add(interceptor)) {
      return true;
    } else {
      Comparator<? super TraceInterceptor> interceptorComparator = interceptors.comparator();
      if (interceptorComparator != null) {
        TraceInterceptor anotherInterceptor =
            interceptors.stream()
                .filter(i -> interceptorComparator.compare(i, interceptor) == 0)
                .findFirst()
                .orElse(null);
        log.warn(
            "Interceptor {} will NOT be registered with the tracer, "
                + "as already registered interceptor {} is considered its duplicate",
            interceptor,
            anotherInterceptor);
      }
      return false;
    }
  }

  @Override
  public TraceScope muteTracing() {
    return activateSpan(blackholeSpan());
  }

  @Override
  public DataStreamsCheckpointer getDataStreamsCheckpointer() {
    return this.dataStreamsMonitoring;
  }

  @Override
  public void addShutdownListener(Runnable listener) {
    this.shutdownListeners.add(listener);
  }

  @Override
  public void addScopeListener(final ScopeListener listener) {
    this.scopeManager.addScopeListener(listener);
  }

  @Override
  public SubscriptionService getSubscriptionService(RequestContextSlot slot) {
    return (SubscriptionService) instrumentationGateway.getCallbackProvider(slot);
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
    for (Runnable shutdownListener : shutdownListeners) {
      try {
        shutdownListener.run();
      } catch (Exception e) {
        log.error("Error while running shutdown listener", e);
      }
    }
    if (flushOnClose) {
      flush();
    }
    tracingConfigPoller.stop();
    pendingTraceBuffer.close();
    writer.close();
    RumInjector.shutdownTelemetry();
    statsDClient.close();
    metricsAggregator.close();
    dataStreamsMonitoring.close();
    externalAgentLauncher.close();
    healthMetrics.close();
  }

  @Override
  public void addScopeListener(
      Runnable afterScopeActivatedCallback, Runnable afterScopeClosedCallback) {
    addScopeListener(
        new ScopeListener() {
          @Override
          public void afterScopeActivated() {
            afterScopeActivatedCallback.run();
          }

          @Override
          public void afterScopeClosed() {
            afterScopeClosedCallback.run();
          }
        });
  }

  @Override
  public void flush() {
    pendingTraceBuffer.flush();
    writer.flush();
  }

  @Override
  public void flushMetrics() {
    try {
      metricsAggregator.forceReport().get(2_500, MILLISECONDS);
    } catch (InterruptedException | ExecutionException | TimeoutException e) {
      log.debug("Failed to wait for metrics flush.", e);
    }
  }

  @Override
  public ProfilingContextIntegration getProfilingContext() {
    return profilingContextIntegration;
  }

  @Override
  public TraceSegment getTraceSegment() {
    AgentSpan activeSpan = activeSpan();
    if (activeSpan == null) {
      return null;
    }
    AgentSpanContext ctx = activeSpan.context();
    if (ctx instanceof DDSpanContext) {
      return ((DDSpanContext) ctx).getTraceSegment();
    }
    return null;
  }

  @Override
  public void addReportToFlare(ZipOutputStream zip) throws IOException {
    TracerFlare.addText(zip, "dynamic_config.txt", dynamicConfig.toString());
    TracerFlare.addText(zip, "tracer_health.txt", healthMetrics.summary());
    TracerFlare.addText(zip, "span_metrics.txt", SpanMetricRegistry.getInstance().summary());
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
              // use replace to stop string being changed to 'ddtrot.dd.tracer' in dd-trace-ot
              "datadog:tracer".replace(':', '.'),
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

  Recording writeTimer() {
    return traceWriteTimer.start();
  }

  private static String statsdTag(final String tagPrefix, final String tagValue) {
    return tagPrefix + ":" + tagValue;
  }

  private static <K, V> Map<V, K> invertMap(Map<K, V> map) {
    Map<V, K> inverted = new HashMap<>(map.size());
    for (Map.Entry<K, V> entry : map.entrySet()) {
      inverted.put(entry.getValue(), entry.getKey());
    }
    return Collections.unmodifiableMap(inverted);
  }

  /** Spans are built using this builder */
  public abstract static class CoreSpanBuilder implements AgentTracer.SpanBuilder {
    protected final CoreTracer tracer;

    protected String instrumentationName;
    protected CharSequence operationName;

    // Builder attributes
    // Make sure any fields added here are also reset properly in ReusableSingleSpanBuilder.reset
    protected TagMap.Ledger tagLedger;
    protected long timestampMicro;
    protected AgentSpanContext parent;
    protected String serviceName;
    protected String resourceName;
    protected boolean errorFlag;
    protected CharSequence spanType;
    protected boolean ignoreScope = false;
    protected Object builderRequestContextDataAppSec;
    protected Object builderRequestContextDataIast;
    protected Object builderCiVisibilityContextData;
    protected List<AgentSpanLink> links;
    protected long spanId;
    // Make sure any fields added here are also reset properly in ReusableSingleSpanBuilder.reset

    CoreSpanBuilder(CoreTracer tracer) {
      this.tracer = tracer;
    }

    @Override
    public final CoreSpanBuilder ignoreActiveSpan() {
      ignoreScope = true;
      return this;
    }

    protected final DDSpan buildSpan() {
      return buildSpan(tracer, instrumentationName, timestampMicro, links, buildSpanContext());
    }
    
    protected static final DDSpan buildSpan(
      CoreTracer tracer,
      String instrumentationName,
      long timestampMicro,
      List<AgentSpanLink> links,
      DDSpanContext spanContext)
    {
      DDSpan span = DDSpan.create(instrumentationName, timestampMicro, spanContext, links);
      if (span.isLocalRootSpan()) {
        EndpointTracker tracker = tracer.onRootSpanStarted(span);
        if (tracker != null) {
          span.setEndpointTracker(tracker);
        }
      }
      return span;
    }

    private static final List<AgentSpanLink> addParentContextLink(
      List<AgentSpanLink> links,
      AgentSpanContext parentContext) {
      SpanLink link;
      if (parentContext instanceof ExtractedContext) {
        String headers = ((ExtractedContext) parentContext).getPropagationStyle().toString();
        SpanAttributes attributes =
            SpanAttributes.builder()
                .put("reason", "propagation_behavior_extract")
                .put("context_headers", headers)
                .build();
        link = DDSpanLink.from((ExtractedContext) parentContext, attributes);
      } else {
        link = SpanLink.from(parentContext);
      }
      return addLink(links, link);
    }

    protected static final List<AgentSpanLink> addTerminatedContextAsLinks(
      List<AgentSpanLink> links,
      AgentSpanContext parentContext)
    {
      if (parentContext instanceof TagContext) {
        List<AgentSpanLink> terminatedContextLinks =
            ((TagContext) parentContext).getTerminatedContextLinks();
        if (!terminatedContextLinks.isEmpty()) {
          return addLinks(links, terminatedContextLinks);
        }
      }
      return links;
    }
    
    protected static final List<AgentSpanLink> addLink(
      List<AgentSpanLink> links,
      AgentSpanLink link)
    {
      if ( links == null ) links = new ArrayList<>();
      links.add(link);
      return links;
    }

    protected static final List<AgentSpanLink> addLinks(
      List<AgentSpanLink> links,
      List<AgentSpanLink> additionalLinks)
    {
      if ( links == null ) {
    	links = new ArrayList<>(additionalLinks);
      } else {
    	links.addAll(additionalLinks);
      }
      return links;
    }

    @Override
    public abstract AgentSpan start();

    protected AgentSpan startImpl() {
      AgentSpanContext pc = parent;
      if (pc == null && !ignoreScope) {
        final AgentSpan span = tracer.activeSpan();
        if (span != null) {
          pc = span.context();
        }
      }

      if (pc == BlackHoleSpan.Context.INSTANCE) {
        return new BlackHoleSpan(pc.getTraceId());
      }
      return buildSpan();
    }

    @Override
    public final CoreSpanBuilder withTag(final String tag, final Number number) {
      return withTag(tag, (Object) number);
    }

    @Override
    public final CoreSpanBuilder withTag(final String tag, final String string) {
      return withTag(tag, (Object) (string == null || string.isEmpty() ? null : string));
    }

    @Override
    public final CoreSpanBuilder withTag(final String tag, final boolean bool) {
      return withTag(tag, (Object) bool);
    }

    @Override
    public final CoreSpanBuilder withStartTimestamp(final long timestampMicroseconds) {
      timestampMicro = timestampMicroseconds;
      return this;
    }

    @Override
    public final CoreSpanBuilder withServiceName(final String serviceName) {
      this.serviceName = serviceName;
      return this;
    }

    @Override
    public final CoreSpanBuilder withResourceName(final String resourceName) {
      this.resourceName = resourceName;
      return this;
    }

    @Override
    public final CoreSpanBuilder withErrorFlag() {
      errorFlag = true;
      return this;
    }

    @Override
    public final CoreSpanBuilder withSpanType(final CharSequence spanType) {
      this.spanType = spanType;
      return this;
    }

    @Override
    public final CoreSpanBuilder asChildOf(final AgentSpanContext spanContext) {
      // TODO we will start propagating stack trace hash and it will need to
      //  be extracted here if available
      parent = spanContext;
      return this;
    }

    public final CoreSpanBuilder asChildOf(final AgentSpan agentSpan) {
      parent = agentSpan.context();
      return this;
    }

    @Override
    public final CoreSpanBuilder withTag(final String tag, final Object value) {
      if (tag == null) {
        return this;
      }
      TagMap.Ledger tagLedger = this.tagLedger;
      if (tagLedger == null) {
        // Insertion order is important, so using TagLedger which builds up a set
        // of Entry modifications in order
        this.tagLedger = tagLedger = TagMap.ledger();
      }
      if (value == null) {
        // DQH - Use of smartRemove is important to avoid clobbering entries added by another map
        // smartRemove only records the removal if a prior matching put has already occurred in the
        // ledger
        // smartRemove is O(n) but since removes are rare, this is preferable to a more complicated
        // implementation in setAll
        tagLedger.smartRemove(tag);
      } else {
        tagLedger.set(tag, value);
      }
      return this;
    }

    @Override
    public final <T> AgentTracer.SpanBuilder withRequestContextData(
        RequestContextSlot slot, T data) {
      switch (slot) {
        case APPSEC:
          builderRequestContextDataAppSec = data;
          break;
        case CI_VISIBILITY:
          builderCiVisibilityContextData = data;
          break;
        case IAST:
          builderRequestContextDataIast = data;
          break;
      }
      return this;
    }

    @Override
    public final AgentTracer.SpanBuilder withLink(AgentSpanLink link) {
      if (link != null) {
        if (this.links == null) {
          this.links = new ArrayList<>();
        }
        this.links.add(link);
      }
      return this;
    }

    @Override
    public final CoreSpanBuilder withSpanId(final long spanId) {
      this.spanId = spanId;
      return this;
    }

    /**
     * Build the SpanContext, if the actual span has a parent, the following attributes must be
     * propagated: - ServiceName - Baggage - Trace (a list of all spans related) - SpanType
     *
     * @return the context
     */
    private final DDSpanContext buildSpanContext() {
      return this.buildSpanContext(
    	this.tracer,
    	this.spanId,
    	this.serviceName,
    	this.operationName,
    	this.resourceName,
    	this.parent,
    	this.ignoreScope,
    	this.errorFlag,
    	this.spanType,
    	this.tagLedger,
    	this.links,
    	this.builderRequestContextDataAppSec,
    	this.builderRequestContextDataIast,
    	this.builderCiVisibilityContextData);
    }
    
    protected static final DDSpanContext buildSpanContext(
      final CoreTracer tracer,
      long spanId,
      String serviceName,
      CharSequence operationName,
      String resourceName,
      AgentSpanContext incomingParentContext,
      boolean ignoreScope,
      boolean errorFlag,
      CharSequence spanType,
      TagMap.Ledger tagLedger,
      List<AgentSpanLink> links,
      Object builderRequestContextDataAppSec,
      Object builderRequestContextDataIast,
      Object builderCiVisibilityContextData)
    {
      DDTraceId traceId;
      long parentSpanId;
      final Map<String, String> baggage;
      final Baggage w3cBaggage;
      final TraceCollector parentTraceCollector;
      final int samplingPriority;
      final CharSequence origin;
      final TagMap coreTags;
      final boolean coreTagsNeedsIntercept;
      final TagMap rootSpanTags;
      final boolean rootSpanTagsNeedsIntercept;
      final DDSpanContext context;
      Object requestContextDataAppSec;
      Object requestContextDataIast;
      Object ciVisibilityContextData;
      final PathwayContext pathwayContext;
      final PropagationTags propagationTags;
    	
      if (spanId == 0) {
        spanId = tracer.idGenerationStrategy.generateSpanId();
      }

      // Find the parent context
      AgentSpanContext parentContext = incomingParentContext;
      if (parentContext == null && !ignoreScope) {
        // use the Scope as parent unless overridden or ignored.
        final AgentSpan activeSpan = tracer.scopeManager.activeSpan();
        if (activeSpan != null) {
          parentContext = activeSpan.context();
        }
      }
      
      // Handle remote terminated context as span links
      if (parentContext != null && parentContext.isRemote()) {
        switch (Config.get().getTracePropagationBehaviorExtract()) {
          case RESTART:
            links = addParentContextLink(links, parentContext);
            parentContext = null;
            break;
          case IGNORE:
            parentContext = null;
            break;
          case CONTINUE:
          default:
            links = addTerminatedContextAsLinks(links, incomingParentContext);
        }
      }

      String parentServiceName = null;
      // Propagate internal trace.
      // Note: if we are not in the context of distributed tracing, and we are starting the first
      // root span, parentContext will be null at this point.
      if (parentContext instanceof DDSpanContext) {
        final DDSpanContext ddsc = (DDSpanContext) parentContext;
        traceId = ddsc.getTraceId();
        parentSpanId = ddsc.getSpanId();
        baggage = ddsc.getBaggageItems();
        w3cBaggage = null;
        parentTraceCollector = ddsc.getTraceCollector();
        samplingPriority = PrioritySampling.UNSET;
        origin = null;
        coreTags = null;
        coreTagsNeedsIntercept = false;
        rootSpanTags = null;
        rootSpanTagsNeedsIntercept = false;
        parentServiceName = ddsc.getServiceName();
        if (serviceName == null) {
          serviceName = parentServiceName;
        }
        RequestContext requestContext = ((DDSpanContext) parentContext).getRequestContext();
        if (requestContext != null) {
          requestContextDataAppSec = requestContext.getData(RequestContextSlot.APPSEC);
          requestContextDataIast = requestContext.getData(RequestContextSlot.IAST);
          ciVisibilityContextData = requestContext.getData(RequestContextSlot.CI_VISIBILITY);
        } else {
          requestContextDataAppSec = null;
          requestContextDataIast = null;
          ciVisibilityContextData = null;
        }
        propagationTags = tracer.propagationTagsFactory.empty();
      } else {
        long endToEndStartTime;

        if (parentContext instanceof ExtractedContext) {
          // Propagate external trace
          final ExtractedContext extractedContext = (ExtractedContext) parentContext;
          traceId = extractedContext.getTraceId();
          parentSpanId = extractedContext.getSpanId();
          samplingPriority = extractedContext.getSamplingPriority();
          endToEndStartTime = extractedContext.getEndToEndStartTime();
          propagationTags = extractedContext.getPropagationTags();
        } else if (parentContext != null) {
          traceId =
              parentContext.getTraceId() == DDTraceId.ZERO
                  ? tracer.idGenerationStrategy.generateTraceId()
                  : parentContext.getTraceId();
          parentSpanId = parentContext.getSpanId();
          samplingPriority = parentContext.getSamplingPriority();
          endToEndStartTime = 0;
          propagationTags = tracer.propagationTagsFactory.empty();
        } else {
          // Start a new trace
          traceId = tracer.idGenerationStrategy.generateTraceId();
          parentSpanId = DDSpanId.ZERO;
          samplingPriority = PrioritySampling.UNSET;
          endToEndStartTime = 0;
          propagationTags = tracer.propagationTagsFactory.empty();
        }

        ConfigSnapshot traceConfig;

        // Get header tags and set origin whether propagating or not.
        if (parentContext instanceof TagContext) {
          TagContext tc = (TagContext) parentContext;
          traceConfig = (ConfigSnapshot) tc.getTraceConfig();
          coreTags = tc.getTags();
          coreTagsNeedsIntercept = true; // maybe intercept isn't needed?
          origin = tc.getOrigin();
          baggage = tc.getBaggage();
          w3cBaggage = tc.getW3CBaggage();
          requestContextDataAppSec = tc.getRequestContextDataAppSec();
          requestContextDataIast = tc.getRequestContextDataIast();
          ciVisibilityContextData = tc.getCiVisibilityContextData();
        } else {
          traceConfig = null;
          coreTags = null;
          coreTagsNeedsIntercept = false;
          origin = null;
          baggage = null;
          w3cBaggage = null;
          requestContextDataAppSec = null;
          requestContextDataIast = null;
          ciVisibilityContextData = null;
        }

        rootSpanTags = tracer.localRootSpanTags;
        rootSpanTagsNeedsIntercept = tracer.localRootSpanTagsNeedIntercept;

        parentTraceCollector = tracer.createTraceCollector(traceId, traceConfig);

        if (endToEndStartTime > 0) {
          parentTraceCollector.beginEndToEnd(endToEndStartTime);
        }
      }

      ConfigSnapshot traceConfig = parentTraceCollector.getTraceConfig();

      // Use parent pathwayContext if present and started
      pathwayContext =
          parentContext != null
                  && parentContext.getPathwayContext() != null
                  && parentContext.getPathwayContext().isStarted()
              ? parentContext.getPathwayContext()
              : tracer.dataStreamsMonitoring.newPathwayContext();

      // when removing fake services the best upward service name to pick is the local root one
      // since a split by tag (i.e. servlet context) might have happened on it.
      if (!tracer.allowInferredServices) {
        final DDSpan rootSpan = parentTraceCollector.getRootSpan();
        serviceName = rootSpan != null ? rootSpan.getServiceName() : null;
      }
      if (serviceName == null) {
        serviceName = traceConfig.getPreferredServiceName();
      }
      Map<String, Object> contextualTags = null;
      if (parentServiceName == null) {
        // only fetch this on local root spans
        final ClassloaderConfigurationOverrides.ContextualInfo contextualInfo =
            ClassloaderConfigurationOverrides.maybeGetContextualInfo();
        if (contextualInfo != null) {
          // in this case we have a local root without service name.
          // We can try to see if we can find one from the thread context classloader
          if (serviceName == null) {
            serviceName = contextualInfo.getServiceName();
          }
          contextualTags = contextualInfo.getTags();
        }
      }
      if (serviceName == null) {
        // it could be on the initial snapshot but may be overridden to null and service name
        // cannot be null
        serviceName = tracer.serviceName;
      }

      if ( operationName == null ) operationName = resourceName;

      final TagMap mergedTracerTags = traceConfig.mergedTracerTags;
      boolean mergedTracerTagsNeedsIntercept = traceConfig.mergedTracerTagsNeedsIntercept;

      final int tagsSize =
          mergedTracerTags.size()
              + (null == tagLedger ? 0 : tagLedger.estimateSize())
              + (null == coreTags ? 0 : coreTags.size())
              + (null == rootSpanTags ? 0 : rootSpanTags.size())
              + (null == contextualTags ? 0 : contextualTags.size());

      if (builderRequestContextDataAppSec != null) {
        requestContextDataAppSec = builderRequestContextDataAppSec;
      }
      if (builderCiVisibilityContextData != null) {
        ciVisibilityContextData = builderCiVisibilityContextData;
      }
      if (builderRequestContextDataIast != null) {
        requestContextDataIast = builderRequestContextDataIast;
      }

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
              w3cBaggage,
              errorFlag,
              spanType,
              tagsSize,
              parentTraceCollector,
              requestContextDataAppSec,
              requestContextDataIast,
              ciVisibilityContextData,
              pathwayContext,
              tracer.disableSamplingMechanismValidation,
              propagationTags,
              tracer.profilingContextIntegration,
              tracer.injectBaggageAsTags);

      // By setting the tags on the context we apply decorators to any tags that have been set via
      // the builder. This is the order that the tags were added previously, but maybe the `tags`
      // set in the builder should come last, so that they override other tags.
      context.setAllTags(mergedTracerTags, mergedTracerTagsNeedsIntercept);
      context.setAllTags(tagLedger);
      context.setAllTags(coreTags, coreTagsNeedsIntercept);
      context.setAllTags(rootSpanTags, rootSpanTagsNeedsIntercept);
      context.setAllTags(contextualTags);
      return context;
    }
  }

  /** CoreSpanBuilder that can be used to produce multiple spans */
  static final class MultiSpanBuilder extends CoreSpanBuilder {
    MultiSpanBuilder(CoreTracer tracer, String instrumentationName, CharSequence operationName) {
      super(tracer);
      this.instrumentationName = instrumentationName;
      this.operationName = operationName;
    }

    @Override
    public AgentSpan start() {
      return this.startImpl();
    }
  }

  static final class ReusableSingleSpanBuilderThreadLocalCache
      extends ThreadLocal<ReusableSingleSpanBuilder> {
    private final CoreTracer tracer;

    public ReusableSingleSpanBuilderThreadLocalCache(CoreTracer tracer) {
      this.tracer = tracer;
    }

    @Override
    protected ReusableSingleSpanBuilder initialValue() {
      return new ReusableSingleSpanBuilder(this.tracer);
    }
  }

  /**
   * Reusable CoreSpanBuilder that can be used to build one and only one span before being reset
   *
   * <p>{@link CoreTracer#singleSpanBuilder(String, CharSequence)} reuses instances of this object
   * to reduce the overhead of span construction
   */
  static final class ReusableSingleSpanBuilder extends CoreSpanBuilder {
    // Used to track whether the ReusableSingleSpanBuilder is actively being used
    // ReusableSingleSpanBuilder becomes "inUse" after a succesful init/reset and remains "inUse"
    // until "start" is called
    boolean inUse;

    ReusableSingleSpanBuilder(CoreTracer tracer) {
      super(tracer);
      this.inUse = false;
    }

    /** Similar to reset, but only valid on first use */
    void init(String instrumentationName, CharSequence operationName) {
      assert !this.inUse;

      this.instrumentationName = instrumentationName;
      this.operationName = operationName;

      this.inUse = true;
    }

    /**
     * Resets the {@link ReusableSingleSpanBuilder}, so it may be used to build another single span
     *
     * @returns <code>true</code> if the reset was successful, otherwise <code>false</code> if this
     *     <code>ReusableSingleSpanBuilder</code> is still "in-use".
     */
    final boolean reset(String instrumentationName, CharSequence operationName) {
      if (this.inUse) return false;
      this.inUse = true;

      this.instrumentationName = instrumentationName;
      this.operationName = operationName;

      if (this.tagLedger != null) this.tagLedger.reset();
      this.timestampMicro = 0L;
      this.parent = null;
      this.serviceName = null;
      this.resourceName = null;
      this.errorFlag = false;
      this.spanType = null;
      this.ignoreScope = false;
      this.builderRequestContextDataAppSec = null;
      this.builderRequestContextDataIast = null;
      this.builderCiVisibilityContextData = null;
      this.links = null;
      this.spanId = 0L;

      return true;
    }

    /*
     * Clears the inUse boolean, so this ReusableSpanBuilder can be reset
     */
    @Override
    public AgentSpan start() {
      assert this.inUse
          : "ReusableSingleSpanBuilder not reset properly -- multiple span construction?";

      AgentSpan span = this.startImpl();
      this.inUse = false;
      return span;
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

  protected class ConfigSnapshot extends DynamicConfig.Snapshot {
    final Sampler sampler;

    final TagMap mergedTracerTags;
    final boolean mergedTracerTagsNeedsIntercept;

    protected ConfigSnapshot(
        DynamicConfig<ConfigSnapshot>.Builder builder, ConfigSnapshot oldSnapshot) {
      super(builder, oldSnapshot);

      if (null == oldSnapshot) {
        sampler = CoreTracer.this.initialSampler;
      } else if (Objects.equals(getTraceSampleRate(), oldSnapshot.getTraceSampleRate())
          && Objects.equals(getTraceSamplingRules(), oldSnapshot.getTraceSamplingRules())) {
        sampler = oldSnapshot.sampler;
      } else {
        sampler = Sampler.Builder.forConfig(CoreTracer.this.initialConfig, this);
      }

      if (null == oldSnapshot) {
        mergedTracerTags = CoreTracer.this.defaultSpanTags.immutableCopy();
        this.mergedTracerTagsNeedsIntercept = CoreTracer.this.defaultSpanTagsNeedsIntercept;
      } else if (getTracingTags().equals(oldSnapshot.getTracingTags())) {
        mergedTracerTags = oldSnapshot.mergedTracerTags;
        mergedTracerTagsNeedsIntercept = oldSnapshot.mergedTracerTagsNeedsIntercept;
      } else {
        mergedTracerTags = withTracerTags(getTracingTags(), CoreTracer.this.initialConfig, this);
        mergedTracerTagsNeedsIntercept =
            CoreTracer.this.tagInterceptor.needsIntercept(mergedTracerTags);
      }
    }
  }

  /**
   * Tags added by the tracer to all spans; combines user-supplied tags with tracer-defined tags.
   */
  static TagMap withTracerTags(
      Map<String, ?> userSpanTags, Config config, TraceConfig traceConfig) {
    final TagMap result = TagMap.create(userSpanTags.size() + 5);
    result.putAll(userSpanTags);
    if (null != config) { // static
      if (!config.getEnv().isEmpty()) {
        result.put("env", config.getEnv());
      }
      if (!config.getVersion().isEmpty()) {
        result.put("version", config.getVersion());
      }
      if (config.isDataJobsEnabled()) {
        result.put(DJM_ENABLED, 1);
      }
      if (config.isDataStreamsEnabled()) {
        result.put(DSM_ENABLED, 1);
      }
    }
    if (null != traceConfig) { // dynamic
      if (traceConfig.isDataStreamsEnabled()) {
        result.put(DSM_ENABLED, 1);
      } else {
        result.remove(DSM_ENABLED);
      }
    }
    return result.freeze();
  }
}

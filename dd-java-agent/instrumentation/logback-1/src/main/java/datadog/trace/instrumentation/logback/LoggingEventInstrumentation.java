package datadog.trace.instrumentation.logback;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.google.auto.service.AutoService;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.log.UnionMap;
import datadog.trace.api.Config;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.Platform;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.CoreTracer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class LoggingEventInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {
  public LoggingEventInstrumentation() {
    super("logback");
  }

  @Override
  protected boolean defaultEnabled() {
    return InstrumenterConfig.get().isLogsInjectionEnabled();
  }

  @Override
  public String hierarchyMarkerType() {
    return "ch.qos.logback.classic.spi.ILoggingEvent";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(
        "ch.qos.logback.classic.spi.ILoggingEvent", AgentSpan.Context.class.getName());
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("getMDCPropertyMap").or(named("getMdc"))).and(takesArguments(0)),
        LoggingEventInstrumentation.class.getName() + "$GetMdcAdvice");
  }

  @Override
  public String[] muzzleIgnoredClassNames() {
    String[] ignore = super.muzzleIgnoredClassNames();
    ignore = Arrays.copyOf(ignore, ignore.length + 2);
    ignore[ignore.length - 2] = "datadog.trace.core.CoreTracer";
    ignore[ignore.length - 1] = "datadog.trace.core.CoreTracer$CoreTracerBuilder";
    return ignore;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.agent.tooling.log.UnionMap",
      "datadog.trace.agent.tooling.log.UnionMap$1",
      "datadog.trace.agent.tooling.log.UnionMap$1$1",
      "datadog.communication.ddagent.SharedCommunicationObjects",
      "datadog.trace.agent.tooling.TracerInstaller",
      "datadog.trace.agent.core.CoreTracer",
      "datadog.communication.monitor.Monitoring",
      "datadog.trace.agent.core.CoreTracer$ShutdownHook",
      "datadog.trace.agent.core.CoreSpan",
      "datadog.trace.agent.core.datastreams.DataStreamsCheckpointer",
      "datadog.trace.agent.core.CoreTracer$CoreTracerBuilder",
      "datadog.trace.agent.common.sampling.Sampler$Builder",
      "datadog.trace.agent.common.sampling.Sampler",
      "datadog.trace.agent.common.sampling.PrioritySampler",
      "datadog.trace.agent.common.writer.RemoteResponseListener",
      "datadog.trace.agent.common.sampling.RateByServiceSampler",
      "datadog.trace.agent.common.sampling.RateSampler",
      "datadog.trace.agent.common.sampling.RateByServiceSampler$RateSamplersByEnvAndService",
      "datadog.trace.agent.common.sampling.DeterministicSampler",
      "datadog.trace.agent.core.propagation.HttpCodec",
      "datadog.trace.agent.core.propagation.HttpCodec$Injector",
      "datadog.trace.agent.core.propagation.HttpCodec$Extractor",
      "datadog.trace.agent.core.propagation.HttpCodec$1",
      "datadog.trace.agent.core.propagation.DatadogHttpCodec",
      "datadog.trace.agent.core.propagation.ContextInterpreter$Factory",
      "datadog.trace.agent.core.propagation.DatadogHttpCodec$1",
      "datadog.trace.agent.core.propagation.DatadogHttpCodec$Injector",
      "datadog.trace.agent.core.propagation.HttpCodec$CompoundInjector",
      "datadog.trace.agent.core.propagation.TagContextExtractor",
      "datadog.trace.agent.core.propagation.TagContextExtractor$1",
      "datadog.trace.agent.core.propagation.ContextInterpreter",
      "datadog.trace.agent.core.propagation.DatadogHttpCodec$DatadogContextInterpreter",
      "datadog.trace.agent.core.propagation.HttpCodec$CompoundExtractor",
      "datadog.trace.agent.core.CoreTracer$1",
      "datadog.trace.agent.relocate.api.RatelimitedLogger",
      "datadog.communication.monitor.DDAgentStatsDClientManager",
      "datadog.trace.agent.core.DDTraceCoreInfo",
      "datadog.communication.monitor.DDAgentStatsDClientManager$NameResolver",
      "datadog.communication.monitor.DDAgentStatsDClientManager$NameResolver$1",
      "datadog.communication.monitor.DDAgentStatsDClientManager$TagCombiner",
      "datadog.communication.monitor.DDAgentStatsDClientManager$TagCombiner$1",
      "datadog.communication.monitor.DDAgentStatsDClient",
      "com.timgroup.statsd.StatsDClientErrorHandler",
      "datadog.communication.monitor.DDAgentStatsDConnection",
      "com.timgroup.statsd.StatsDClient",
      "datadog.trace.agent.relocate.api.IOLogger",
      "com.timgroup.statsd.NoOpStatsDClient",
      "com.timgroup.statsd.NonBlockingStatsDClientBuilder",
      "datadog.communication.monitor.DDAgentStatsDConnection$ConnectTask",
      "datadog.trace.agent.core.monitor.MonitoringImpl",
      "datadog.trace.agent.core.monitor.MonitoringImpl$1",
      "datadog.communication.monitor.Recording",
      "datadog.trace.agent.core.monitor.ThreadLocalRecording",
      "datadog.communication.monitor.Counter",
      "datadog.trace.agent.core.monitor.Timer",
      "datadog.communication.monitor.Monitoring$DisabledMonitoring",
      "datadog.communication.monitor.NoOpRecording",
      "datadog.trace.agent.core.scopemanager.ContinuableScopeManager",
      "datadog.trace.agent.core.scopemanager.ContinuableScopeManager$Continuation",
      "datadog.trace.agent.core.scopemanager.ContinuableScopeManager$SingleContinuation",
      "datadog.trace.agent.core.scopemanager.ContinuableScopeManager$ContinuableScope",
      "datadog.trace.agent.core.scopemanager.ContinuableScopeManager$ContinuingScope",
      "datadog.trace.agent.core.scopemanager.ContinuableScopeManager$ScopeStackThreadLocal",
      "datadog.communication.ddagent.ExternalAgentLauncher",
      "okhttp3.HttpUrl",
      "okhttp3.HttpUrl$Builder",
      "okhttp3.internal.Util",
      "okhttp3.ResponseBody",
      "okio.Source",
      "okio.BufferedSource",
      "okhttp3.ResponseBody$1",
      "okhttp3.ResponseBody$BomAwareReader",
      "okio.Sink",
      "okio.BufferedSink",
      "okio.Buffer",
      "okio.Buffer$1",
      "okio.Buffer$2",
      "okio.ByteString",
      "okio.SegmentedByteString",
      "okio.Util",
      "okhttp3.RequestBody",
      "okhttp3.RequestBody$3",
      "okhttp3.RequestBody$2",
      "okhttp3.RequestBody$1",
      "okhttp3.internal.Util$1",
      "datadog.common.socket.SocketUtils",
      "datadog.communication.http.OkHttpUtils",
      "datadog.communication.http.OkHttpUtils$ByteBufferRequestBody",
      "datadog.communication.http.OkHttpUtils$GZipByteBufferRequestBody",
      "datadog.common.socket.UnixDomainSocketFactory",
      "datadog.common.socket.NamedPipeSocketFactory",
      "okhttp3.Authenticator",
      "okhttp3.OkHttpClient$Builder",
      "okhttp3.internal.proxy.NullProxySelector",
      "okhttp3.Dispatcher",
      "okhttp3.Call$Factory",
      "okhttp3.WebSocket$Factory",
      "okhttp3.OkHttpClient",
      "okhttp3.internal.Internal",
      "okhttp3.OkHttpClient$1",
      "okhttp3.WebSocket",
      "okhttp3.Call",
      "okhttp3.Protocol",
      "okhttp3.ConnectionSpec",
      "okhttp3.CipherSuite",
      "okhttp3.CipherSuite$1",
      "okhttp3.ConnectionSpec$Builder",
      "okhttp3.TlsVersion",
      "okhttp3.EventListener",
      "okhttp3.EventListener$1",
      "okhttp3.EventListener$Factory",
      "okhttp3.EventListener$2",
      "okhttp3.CookieJar",
      "okhttp3.CookieJar$1",
      "okhttp3.internal.tls.OkHostnameVerifier",
      "okhttp3.CertificatePinner",
      "okhttp3.CertificatePinner$Builder",
      "okhttp3.Authenticator$1",
      "okhttp3.ConnectionPool",
      "okhttp3.internal.Util$2",
      "okhttp3.ConnectionPool$1",
      "okhttp3.internal.connection.RouteDatabase",
      "okhttp3.Dns",
      "okhttp3.Dns$1",
      "datadog.communication.http.RejectingExecutorService",
      "datadog.trace.agent.common.writer.WriterFactory",
      "datadog.trace.agent.common.writer.Writer",
      "datadog.trace.agent.common.writer.ddagent.Prioritization",
      "datadog.trace.agent.common.writer.RemoteWriter",
      "datadog.trace.agent.common.writer.DDIntakeWriter",
      "datadog.trace.agent.common.writer.DDAgentWriter",
      "datadog.trace.agent.common.writer.ddagent.Prioritization$1",
      "datadog.trace.agent.common.writer.ddagent.Prioritization$2",
      "datadog.trace.agent.common.writer.ddagent.PrioritizationStrategy",
      "datadog.common.container.ServerlessInfo",
      "datadog.trace.agent.common.writer.RemoteApi",
      "datadog.trace.agent.common.writer.ddagent.DDAgentApi",
      "com.squareup.moshi.Moshi$Builder",
      "com.squareup.moshi.JsonAdapter$Factory",
      "com.squareup.moshi.Moshi",
      "com.squareup.moshi.StandardJsonAdapters",
      "com.squareup.moshi.JsonAdapter",
      "com.squareup.moshi.StandardJsonAdapters$2",
      "com.squareup.moshi.StandardJsonAdapters$3",
      "com.squareup.moshi.StandardJsonAdapters$4",
      "com.squareup.moshi.StandardJsonAdapters$5",
      "com.squareup.moshi.StandardJsonAdapters$6",
      "com.squareup.moshi.StandardJsonAdapters$7",
      "com.squareup.moshi.StandardJsonAdapters$8",
      "com.squareup.moshi.StandardJsonAdapters$9",
      "com.squareup.moshi.StandardJsonAdapters$10",
      "com.squareup.moshi.JsonDataException",
      "com.squareup.moshi.StandardJsonAdapters$1",
      "com.squareup.moshi.internal.NonNullJsonAdapter",
      "com.squareup.moshi.JsonWriter",
      "com.squareup.moshi.JsonValueWriter",
      "com.squareup.moshi.JsonReader",
      "com.squareup.moshi.JsonValueReader",
      "com.squareup.moshi.JsonAdapter$1",
      "com.squareup.moshi.JsonAdapter$3",
      "com.squareup.moshi.internal.NullSafeJsonAdapter",
      "com.squareup.moshi.JsonAdapter$2",
      "com.squareup.moshi.JsonAdapter$4",
      "com.squareup.moshi.CollectionJsonAdapter",
      "com.squareup.moshi.CollectionJsonAdapter$2",
      "com.squareup.moshi.CollectionJsonAdapter$3",
      "com.squareup.moshi.CollectionJsonAdapter$1",
      "com.squareup.moshi.MapJsonAdapter",
      "com.squareup.moshi.MapJsonAdapter$1",
      "com.squareup.moshi.ArrayJsonAdapter",
      "com.squareup.moshi.ArrayJsonAdapter$1",
      "com.squareup.moshi.ClassJsonAdapter",
      "com.squareup.moshi.ClassJsonAdapter$1",
      "com.squareup.moshi.Types",
      "com.squareup.moshi.internal.Util$ParameterizedTypeImpl",
      "com.squareup.moshi.internal.Util",
      "com.squareup.moshi.Moshi$LookupChain",
      "com.squareup.moshi.Moshi$Lookup",
      "com.squareup.moshi.JsonClass",
      "datadog.communication.ddagent.DroppingPolicy",
      "datadog.communication.ddagent.DDAgentFeaturesDiscovery",
      "com.squareup.moshi.StandardJsonAdapters$ObjectJsonAdapter",
      "datadog.trace.agent.core.histogram.Histograms",
      "datadog.trace.agent.core.histogram.HistogramFactory",
      "datadog.trace.agent.core.histogram.DDSketchHistogramFactory",
      "datadog.trace.agent.core.histogram.Histogram",
      "datadog.trace.agent.core.histogram.DDSketchHistogram",
      "datadog.trace.agent.core.histogram.StubHistogram",
      "com.datadoghq.sketch.ddsketch.mapping.IndexMapping",
      "com.datadoghq.sketch.ddsketch.store.Store",
      "com.datadoghq.sketch.QuantileSketch",
      "com.datadoghq.sketch.ddsketch.DDSketch",
      "com.datadoghq.sketch.ddsketch.encoding.MalformedInputException",
      "com.datadoghq.sketch.ddsketch.mapping.BitwiseLinearlyInterpolatedMapping",
      "com.datadoghq.sketch.ddsketch.store.DenseStore",
      "com.datadoghq.sketch.ddsketch.store.CollapsingDenseStore",
      "com.datadoghq.sketch.ddsketch.store.CollapsingLowestDenseStore",
      "datadog.trace.agent.core.monitor.Utils",
      "okhttp3.Request$Builder",
      "okhttp3.Headers$Builder",
      "okhttp3.Request",
      "okhttp3.Headers",
      "okhttp3.RealCall",
      "okio.Timeout",
      "okio.AsyncTimeout",
      "okhttp3.RealCall$1",
      "okhttp3.Interceptor$Chain",
      "okhttp3.Interceptor",
      "okhttp3.internal.http.RetryAndFollowUpInterceptor",
      "okhttp3.internal.connection.RouteException",
      "okio.Timeout$1",
      "okhttp3.internal.platform.Platform",
      "okhttp3.internal.tls.CertificateChainCleaner",
      "okhttp3.internal.tls.BasicCertificateChainCleaner",
      "okhttp3.internal.tls.TrustRootIndex",
      "okhttp3.internal.platform.ConscryptPlatform",
      "okhttp3.internal.platform.Jdk9Platform",
      "okhttp3.internal.http.BridgeInterceptor",
      "okhttp3.internal.http.RealResponseBody",
      "okhttp3.internal.cache.CacheInterceptor",
      "okhttp3.internal.connection.ConnectInterceptor",
      "okhttp3.internal.http.CallServerInterceptor",
      "okhttp3.internal.http.RealInterceptorChain",
      "okhttp3.Connection",
      "okhttp3.internal.connection.StreamAllocation",
      "okhttp3.Address",
      "okhttp3.internal.connection.RouteSelector",
      "okhttp3.internal.Version",
      "okhttp3.internal.cache.CacheStrategy$Factory",
      "okhttp3.internal.cache.CacheStrategy",
      "okhttp3.CacheControl",
      "okhttp3.CacheControl$Builder",
      "okhttp3.Route",
      "okhttp3.internal.connection.RouteSelector$Selection",
      "okhttp3.internal.http2.Http2Connection$Listener",
      "okhttp3.internal.connection.RealConnection",
      "okhttp3.internal.http2.Http2Connection$Listener$1",
      "okhttp3.internal.http2.StreamResetException",
      "okhttp3.internal.ws.RealWebSocket$Streams",
      "okhttp3.internal.connection.RealConnection$1",
      "okhttp3.internal.http.HttpCodec",
      "okhttp3.internal.connection.StreamAllocation$StreamAllocationReference",
      "okhttp3.internal.connection.ConnectionSpecSelector",
      "okio.Okio",
      "okio.Okio$4",
      "okio.Okio$2",
      "okio.AsyncTimeout$2",
      "okio.RealBufferedSource",
      "okio.RealBufferedSource$1",
      "okio.Okio$1",
      "okio.AsyncTimeout$1",
      "okio.RealBufferedSink",
      "okio.RealBufferedSink$1",
      "okhttp3.internal.http1.Http1Codec",
      "okhttp3.internal.http.RequestLine",
      "okio.SegmentPool",
      "okio.Segment",
      "okhttp3.internal.http.HttpMethod",
      "okio.AsyncTimeout$Watchdog",
      "okhttp3.internal.http.StatusLine",
      "okhttp3.Response$Builder",
      "okhttp3.Response",
      "okhttp3.internal.http.HttpHeaders",
      "okhttp3.internal.http1.Http1Codec$AbstractSource",
      "okhttp3.internal.http1.Http1Codec$FixedLengthSource",
      "okio.ForwardingTimeout",
      "okhttp3.MediaType",
      "com.squareup.moshi.JsonEncodingException",
      "com.squareup.moshi.JsonUtf8Reader",
      "com.squareup.moshi.JsonReader$Token",
      "com.squareup.moshi.LinkedHashTreeMap",
      "com.squareup.moshi.LinkedHashTreeMap$1",
      "com.squareup.moshi.LinkedHashTreeMap$Node",
      "com.squareup.moshi.StandardJsonAdapters$11",
      "com.datadoghq.sketch.ddsketch.mapping.DoubleBitOperationHelper",
      "com.datadoghq.sketch.ddsketch.store.DenseStore$2",
      "com.datadoghq.sketch.ddsketch.store.DenseStore$1",
      "com.datadoghq.sketch.ddsketch.store.Bin",
      "datadog.trace.agent.core.monitor.StatsDCounter",
      "datadog.trace.agent.common.writer.RemoteMapperDiscovery",
      "datadog.trace.agent.common.writer.DDAgentWriter$DDAgentWriterBuilder",
      "datadog.trace.agent.core.monitor.HealthMetrics",
      "datadog.trace.agent.core.monitor.HealthMetrics$1",
      "org.jctools.counters.CountersFactory",
      "org.jctools.counters.Counter",
      "org.jctools.counters.FixedSizeStripedLongCounterPrePad",
      "org.jctools.counters.FixedSizeStripedLongCounterFields",
      "org.jctools.counters.FixedSizeStripedLongCounter",
      "org.jctools.counters.FixedSizeStripedLongCounterV6",
      "org.jctools.counters.FixedSizeStripedLongCounterV8",
      "org.jctools.util.UnsafeAccess",
      "org.jctools.util.PortableJvmInfo",
      "org.jctools.util.Pow2",
      "datadog.trace.agent.common.writer.ddagent.DDAgentMapperDiscovery",
      "datadog.communication.serialization.Mapper",
      "datadog.trace.agent.common.writer.RemoteMapper",
      "datadog.trace.agent.common.writer.ddagent.TraceMapper",
      "datadog.communication.serialization.ByteBufferConsumer",
      "datadog.trace.agent.common.writer.PayloadDispatcher",
      "datadog.communication.serialization.StreamingBuffer",
      "datadog.communication.serialization.Writable",
      "datadog.communication.serialization.MessageFormatter",
      "datadog.communication.serialization.WritableFormatter",
      "datadog.trace.agent.common.writer.TraceProcessingWorker",
      "org.jctools.queues.MessagePassingQueue",
      "org.jctools.queues.QueueProgressIndicators",
      "org.jctools.queues.IndexedQueueSizeUtil$IndexedQueue",
      "org.jctools.queues.MpscBlockingConsumerArrayQueuePad1",
      "org.jctools.queues.MpscBlockingConsumerArrayQueueColdProducerFields",
      "org.jctools.queues.MpscBlockingConsumerArrayQueuePad2",
      "org.jctools.queues.MpscBlockingConsumerArrayQueueProducerFields",
      "org.jctools.queues.MpscBlockingConsumerArrayQueuePad3",
      "org.jctools.queues.MpscBlockingConsumerArrayQueueConsumerFields",
      "org.jctools.queues.MpscBlockingConsumerArrayQueue",
      "org.jctools.util.UnsafeRefArrayAccess",
      "org.jctools.util.RangeUtil",
      "datadog.trace.agent.common.writer.ddagent.Prioritization$PrioritizationStrategyWithFlush",
      "datadog.trace.agent.common.writer.ddagent.Prioritization$FastLaneStrategy",
      "org.jctools.queues.MessagePassingQueue$Consumer",
      "datadog.trace.agent.common.writer.TraceProcessingWorker$TraceSerializingHandler",
      "datadog.trace.agent.core.PendingTraceBuffer",
      "datadog.trace.agent.core.PendingTraceBuffer$DiscardingPendingTraceBuffer",
      "datadog.trace.agent.core.PendingTraceBuffer$DelayingPendingTraceBuffer",
      "datadog.trace.agent.core.PendingTraceBuffer$DelayingPendingTraceBuffer$Worker",
      "datadog.trace.agent.core.PendingTrace$Factory",
      "org.jctools.queues.LinkedArrayQueueUtil",
      "datadog.trace.agent.core.monitor.HealthMetrics$Flush",
      "datadog.trace.agent.common.metrics.MetricsAggregatorFactory",
      "datadog.trace.agent.common.metrics.MetricsAggregator",
      "datadog.trace.agent.common.metrics.NoOpMetricsAggregator",
      "datadog.trace.agent.core.CoreTracer$2",
      "datadog.trace.agent.core.datastreams.StubDataStreamsCheckpointer",
      "datadog.trace.agent.core.taginterceptor.TagInterceptor",
      "datadog.trace.agent.core.taginterceptor.RuleFlags",
      "datadog.trace.agent.core.taginterceptor.RuleFlags$Feature",
      "datadog.trace.agent.core.StatusLogger",
      "datadog.trace.agent.core.propagation.DatadogTags",
      "datadog.trace.agent.core.propagation.DatadogTags$Factory",
      "datadog.trace.agent.core.propagation.DatadogTagsFactory",
      "datadog.trace.agent.core.propagation.DatadogTagsFactory$ValidDatadogTags",
      "datadog.trace.agent.core.propagation.DatadogTagsFactory$InvalidDatadogTags",
      "datadog.trace.agent.core.CoreTracer$CoreSpanBuilder",
      "datadog.trace.agent.core.scopemanager.ContinuableScopeManager$ScopeStack",
      "datadog.trace.agent.core.scopemanager.ContinuableScopeManager$ScopeStackThreadLocal$1",
      "datadog.trace.agent.core.PendingTraceBuffer$Element",
      "datadog.trace.agent.core.PendingTrace",
      "datadog.trace.agent.core.DDSpan",
      "datadog.trace.agent.core.DDSpanContext",
      "datadog.trace.agent.core.tagprocessor.TagsPostProcessor",
      "datadog.trace.agent.core.tagprocessor.QueryObfuscator",
      "com.google.re2j.PatternSyntaxException",
      "com.google.re2j.Pattern",
      "com.google.re2j.RE2",
      "com.google.re2j.RE2$ReplaceFunc",
      "com.google.re2j.RE2$DeliverFunc",
      "com.google.re2j.Parser",
      "com.google.re2j.Parser$Stack",
      "com.google.re2j.Parser$StringIterator",
      "com.google.re2j.Regexp$Op",
      "com.google.re2j.Regexp",
      "com.google.re2j.Unicode",
      "com.google.re2j.UnicodeTables",
      "com.google.re2j.Characters",
      "com.google.re2j.Utils",
      "com.google.re2j.Regexp$1",
      "com.google.re2j.CharClass",
      "com.google.re2j.CharGroup",
      "com.google.re2j.Parser$1",
      "com.google.re2j.Simplify",
      "com.google.re2j.Simplify$1",
      "com.google.re2j.Compiler",
      "com.google.re2j.Prog",
      "com.google.re2j.Inst",
      "com.google.re2j.Compiler$Frag",
      "com.google.re2j.Compiler$1",
      "datadog.trace.agent.core.EndpointTracker",
      "datadog.trace.agent.core.DDSpan$1",
      "com.squareup.moshi.JsonUtf8Writer",
      "datadog.trace.agent.common.sampling.RateByServiceSampler$EnvAndService",
      "datadog.trace.agent.common.sampling.RateByServiceSampler$EnvAndService$1",
      "datadog.trace.agent.core.PendingTrace$PublishState",
      "datadog.trace.agent.common.writer.ddagent.TraceMapperV0_4",
      "datadog.trace.agent.core.MetadataConsumer",
      "datadog.trace.agent.common.writer.ddagent.TraceMapperV0_4$MetaWriter",
      "datadog.trace.agent.common.writer.Payload",
      "datadog.trace.agent.common.writer.ddagent.TraceMapperV0_4$PayloadV0_4",
      "datadog.communication.serialization.msgpack.MsgPackWriter",
      "datadog.communication.serialization.FlushingBuffer",
      "datadog.communication.serialization.Codec",
      "datadog.communication.serialization.ValueWriter",
      "datadog.trace.agent.common.writer.RemoteMapper$NoopRemoteMapper",
      "datadog.communication.serialization.Codec$1",
      "datadog.communication.serialization.Codec$BooleanArrayWriter",
      "datadog.communication.serialization.Codec$BooleanWriter",
      "datadog.communication.serialization.Codec$ByteArrayWriter",
      "datadog.communication.serialization.Codec$ByteBufferWriter",
      "datadog.communication.serialization.Codec$CharArrayWriter",
      "datadog.communication.serialization.Codec$CharSequenceWriter",
      "datadog.communication.serialization.Codec$CollectionWriter",
      "datadog.communication.serialization.Codec$DefaultWriter",
      "datadog.communication.serialization.Codec$DoubleArrayWriter",
      "datadog.communication.serialization.Codec$DoubleWriter",
      "datadog.communication.serialization.Codec$FloatArrayWriter",
      "datadog.communication.serialization.Codec$FloatWriter",
      "datadog.communication.serialization.Codec$IntArrayWriter",
      "datadog.communication.serialization.Codec$IntWriter",
      "datadog.communication.serialization.Codec$LongArrayWriter",
      "datadog.communication.serialization.Codec$LongWriter",
      "datadog.communication.serialization.Codec$MapWriter",
      "datadog.communication.serialization.Codec$NumberDoubleWriter",
      "datadog.communication.serialization.Codec$ObjectArrayWriter",
      "datadog.communication.serialization.Codec$ShortArrayWriter",
      "datadog.communication.serialization.Codec$ShortWriter",
      "datadog.trace.agent.core.Metadata",
      "org.jctools.queues.MessagePassingQueueUtil",
      "datadog.communication.http.SafeRequestBuilder",
      "datadog.common.container.ContainerInfo",
      "okio.ForwardingSink",
      "okhttp3.internal.http.CallServerInterceptor$CountingSink",
      "okhttp3.internal.http1.Http1Codec$FixedLengthSink",
      "com.squareup.moshi.LinkedHashTreeMap$EntrySet",
      "com.squareup.moshi.LinkedHashTreeMap$LinkedTreeMapIterator",
      "com.squareup.moshi.LinkedHashTreeMap$EntrySet$1",
      "datadog.trace.agent.common.writer.RemoteApi$Response",
      "datadog.trace.agent.common.writer.ddagent.FlushEvent",
      "datadog.trace.agent.core.propagation.ExtractedContext"
    };
  }

  public static class GetMdcAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.This ILoggingEvent event,
        @Advice.Return(typing = Assigner.Typing.DYNAMIC, readOnly = false)
            Map<String, String> mdc) {

      if (mdc instanceof UnionMap || Platform.isIsNativeImageBuilder()) {
        return;
      }

      AgentSpan.Context context =
          InstrumentationContext.get(ILoggingEvent.class, AgentSpan.Context.class).get(event);
      boolean mdcTagsInjectionEnabled = Config.get().isLogsMDCTagsInjectionEnabled();

      // Nothing to add so return early
      if (context == null && !mdcTagsInjectionEnabled) {
        return;
      }

      Map<String, String> correlationValues = new HashMap<>(8);

      if (context != null) {
        correlationValues.put(
            CorrelationIdentifier.getTraceIdKey(), context.getTraceId().toString());
        correlationValues.put(CorrelationIdentifier.getSpanIdKey(), context.getSpanId().toString());
      }

      if (mdcTagsInjectionEnabled) {
        String serviceName = Config.get().getServiceName();
        if (null != serviceName && !serviceName.isEmpty()) {
          correlationValues.put(Tags.DD_SERVICE, serviceName);
        }
        String env = Config.get().getEnv();
        if (null != env && !env.isEmpty()) {
          correlationValues.put(Tags.DD_ENV, env);
        }
        String version = Config.get().getVersion();
        if (null != version && !version.isEmpty()) {
          correlationValues.put(Tags.DD_VERSION, version);
        }
      }

      try {
        if (!(GlobalTracer.get() instanceof CoreTracer)) {
          CoreTracer tracer =
              CoreTracer.builder()
                  .sharedCommunicationObjects(new SharedCommunicationObjects())
                  .build();
          GlobalTracer.registerIfAbsent(tracer);
          AgentTracer.registerIfAbsent(tracer);
        }
      } catch (Throwable e) {
        e.printStackTrace();
      }

      mdc = null != mdc ? new UnionMap<>(mdc, correlationValues) : correlationValues;
    }
  }
}

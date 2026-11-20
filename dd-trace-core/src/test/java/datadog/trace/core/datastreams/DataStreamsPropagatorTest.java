package datadog.trace.core.datastreams;

import static datadog.trace.api.datastreams.DataStreamsContext.fromTags;
import static datadog.trace.api.datastreams.PathwayContext.PROPAGATION_KEY_BASE64;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_SMART_NULLS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.context.Context;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.api.time.ControllableTimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.core.DDCoreJavaSpecification;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DataStreamsPropagatorTest extends DDCoreJavaSpecification {
  private final ControllableTimeSource timeSource = new ControllableTimeSource();
  private final DataStreamsPropagator propagator =
      new DataStreamsPropagator(mock(DataStreamsMonitoring.class), timeSource, new ThreadLocal<>());

  private volatile boolean dataStreamsEnabled;
  private AgentTracer.TracerAPI originalTracer;

  @BeforeEach
  void registerTracerWithLiveDataStreamsConfig() {
    TraceConfig traceConfig = mock(TraceConfig.class, RETURNS_SMART_NULLS);
    when(traceConfig.isDataStreamsEnabled()).thenAnswer(invocation -> dataStreamsEnabled);
    AgentTracer.TracerAPI tracerApi = mock(AgentTracer.TracerAPI.class, RETURNS_SMART_NULLS);
    when(tracerApi.captureTraceConfig()).thenReturn(traceConfig);
    originalTracer = AgentTracer.get();
    AgentTracer.forceRegister(tracerApi);
  }

  @AfterEach
  void restoreTracer() {
    AgentTracer.forceRegister(originalTracer);
  }

  @Test
  void injectFollowsLiveDataStreamsConfig() {
    dataStreamsEnabled = false;
    Map<String, String> carrier = new HashMap<>();
    DefaultPathwayContext pathwayContext = new DefaultPathwayContext(timeSource, null);
    propagator.inject(kafkaProduceContext(pathwayContext), carrier, Map::put);
    assertTrue(carrier.isEmpty());
    assertFalse(pathwayContext.isStarted());

    // e.g. enabled by remote config after startup
    dataStreamsEnabled = true;
    propagator.inject(kafkaProduceContext(pathwayContext), carrier, Map::put);
    assertNotNull(carrier.get(PROPAGATION_KEY_BASE64));
    assertTrue(pathwayContext.isStarted());

    dataStreamsEnabled = false;
    carrier.clear();
    propagator.inject(kafkaProduceContext(pathwayContext), carrier, Map::put);
    assertTrue(carrier.isEmpty());
  }

  @Test
  void extractFollowsLiveDataStreamsConfig() throws Exception {
    DefaultPathwayContext produced = new DefaultPathwayContext(timeSource, null);
    produced.setCheckpoint(fromTags(outboundKafkaTags()), point -> {});
    Map<String, String> carrier = new HashMap<>();
    carrier.put(PROPAGATION_KEY_BASE64, produced.encode());

    dataStreamsEnabled = false;
    Context root = Context.root();
    assertSame(root, propagator.extract(root, carrier, DataStreamsPropagatorTest::forEachKey));

    dataStreamsEnabled = true;
    Context extracted = propagator.extract(root, carrier, DataStreamsPropagatorTest::forEachKey);
    AgentSpan extractedSpan = AgentSpan.fromContext(extracted);
    assertNotNull(extractedSpan);
    assertTrue(extractedSpan.spanContext().getPathwayContext().isStarted());
  }

  private static Context kafkaProduceContext(DefaultPathwayContext pathwayContext) {
    AgentSpan span = AgentSpan.fromSpanContext(new TagContext().withPathwayContext(pathwayContext));
    return span.with(fromTags(outboundKafkaTags()));
  }

  private static DataStreamsTags outboundKafkaTags() {
    return DataStreamsTags.create("kafka", DataStreamsTags.Direction.OUTBOUND, "topic");
  }

  private static void forEachKey(Map<String, String> carrier, BiConsumer<String, String> visitor) {
    carrier.forEach(visitor);
  }
}

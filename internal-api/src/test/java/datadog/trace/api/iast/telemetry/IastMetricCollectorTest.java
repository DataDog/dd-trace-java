package datadog.trace.api.iast.telemetry;

import static datadog.trace.api.gateway.RequestContextSlot.IAST;
import static datadog.trace.api.iast.VulnerabilityTypes.SQL_INJECTION;
import static datadog.trace.api.iast.telemetry.IastMetric.TRACE_METRIC_PREFIX;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.iast.IastConstantConverter;
import datadog.trace.api.iast.telemetry.IastMetric.Tag;
import datadog.trace.api.iast.telemetry.IastMetricCollector.HasMetricCollector;
import datadog.trace.api.iast.telemetry.IastMetricCollector.IastMetricData;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.converter.ConvertWith;
import org.tabletest.junit.TableTest;

class IastMetricCollectorTest {

  private static final TracerAPI ORIGINAL_TRACER = AgentTracer.get();

  private static final IastMetricCollector ORIGINAL_COLLECTOR = IastMetricCollector.get();

  private HasMetricCollector iastCtx;
  private RequestContext ctx;
  private AgentSpan span;
  private TracerAPI api;
  private ExecutorService executor;

  @BeforeEach
  void setup() {
    executor = Executors.newFixedThreadPool(8);
    iastCtx = mock(HasMetricCollector.class);
    ctx = mock(RequestContext.class);
    when(ctx.getData(IAST)).thenReturn(iastCtx);
    span = mock(AgentSpan.class);
    when(span.getRequestContext()).thenReturn(ctx);
    api = mock(TracerAPI.class);
    when(api.activeSpan()).thenReturn(span);
    AgentTracer.forceRegister(api);
  }

  @AfterEach
  void cleanup() {
    executor.shutdown();
    AgentTracer.forceRegister(ORIGINAL_TRACER);
    IastMetricCollector.register(ORIGINAL_COLLECTOR);
  }

  @Test
  void testEmptyCollector() {
    IastMetricCollector requestCollector = new IastMetricCollector();

    requestCollector.prepareMetrics();
    Collection<IastMetricData> result = requestCollector.drain();

    assertTrue(result.isEmpty());
  }

  @Test
  void testCollectorInHeavyConcurrency() throws Exception {
    int times = 1000;
    int value = 5;
    long total = times * value;
    CountDownLatch latch = new CountDownLatch(1);
    IastMetricCollector globalCollector = new IastMetricCollector();
    IastMetricCollector.register(globalCollector);
    IastMetricCollector requestCollector = new IastMetricCollector();
    Random random = new Random();
    IastMetric[] metrics = IastMetric.values();
    when(iastCtx.getMetricCollector()).thenReturn(requestCollector);

    List<Future<?>> futures = new ArrayList<>(times);
    for (int i = 0; i < times; i++) {
      futures.add(
          executor.submit(
              () -> {
                IastMetric metric = metrics[random.nextInt(metrics.length)];
                latch.await();
                if (metric.getTag() == null) {
                  IastMetricCollector.add(metric, value);
                } else {
                  byte tag = (byte) random.nextInt(metric.getTag().values.length);
                  IastMetricCollector.add(metric, tag, value);
                }
                return null;
              }));
    }
    latch.countDown();
    for (Future<?> future : futures) {
      future.get(10, TimeUnit.SECONDS);
    }
    requestCollector.prepareMetrics();
    globalCollector.merge(requestCollector.drain());

    globalCollector.prepareMetrics();
    Collection<IastMetricData> result = globalCollector.drain();
    long computedTotal = 0;
    for (IastMetricData data : result) {
      computedTotal += data.value.longValue();
    }
    assertEquals(total, computedTotal);
  }

  @Test
  void testNoOpCollectorDoesNothing() throws Exception {
    IastMetricCollector collector = newNoOpInstance();
    IastMetric metric = IastMetric.EXECUTED_SINK;
    byte tag = SQL_INJECTION;

    // adding a metric
    collector.addMetric(metric, tag, 1);
    collector.prepareMetrics();
    Collection<IastMetricData> collectorMetrics = collector.drain();

    assertTrue(collectorMetrics.isEmpty());

    // merging metrics
    collector.merge(singletonList(new IastMetricData(metric, tag, 100)));
    collector.prepareMetrics();
    Collection<IastMetricData> mergedMetrics = collector.drain();

    assertTrue(mergedMetrics.isEmpty());
  }

  @Test
  void testGlobalRequestScopedMetrics() {
    IastMetricCollector requestCollector = mock(IastMetricCollector.class);
    IastMetric globalMetric = IastMetric.INSTRUMENTED_SINK;
    IastMetric requestMetric = IastMetric.EXECUTED_SINK;
    byte tag = SQL_INJECTION;
    int value = 1;
    when(iastCtx.getMetricCollector()).thenReturn(requestCollector);

    IastMetricCollector.add(globalMetric, tag, value);

    // global metrics do not access the request context
    verifyNoInteractions(api, span, ctx, iastCtx, requestCollector);

    IastMetricCollector.add(requestMetric, tag, value);

    // request metrics require access to the request context
    verify(api).activeSpan();
    verify(span).getRequestContext();
    verify(ctx).getData(IAST);
    verify(iastCtx).getMetricCollector();
    verify(requestCollector).addMetric(requestMetric, tag, value);
    verifyNoMoreInteractions(api, span, ctx, iastCtx, requestCollector);

    clearInvocations(api, span, ctx, iastCtx, requestCollector);
    IastMetricCollector.add(requestMetric, tag, value, ctx);

    // if request context is provided no access to the active span is needed
    verify(ctx).getData(IAST);
    verify(iastCtx).getMetricCollector();
    verify(requestCollector).addMetric(requestMetric, tag, value);
    verifyNoMoreInteractions(api, span, ctx, iastCtx, requestCollector);
  }

  @TableTest({
    "scenario            | metric              | tag                ",
    "INSTRUMENTED_SINK   | INSTRUMENTED_SINK   | SQL_INJECTION      ",
    "INSTRUMENTED_SOURCE | INSTRUMENTED_SOURCE | REQUEST_HEADER_NAME",
    "EXECUTED_TAINTED    | EXECUTED_TAINTED    | -1                 "
  })
  void testMetricTagsSpanTags(
      IastMetric metric, @ConvertWith(IastConstantConverter.class) byte tag) {
    IastMetricData data = new IastMetricData(metric, tag, 1);

    Tag metricTag = metric.getTag();
    if (metricTag != null) {
      String tagString = metricTag.values[tag];
      assertEquals(1, data.tags.size());
      assertEquals(metricTag.getName() + ":" + tagString, data.tags.get(0));
      assertEquals(
          TRACE_METRIC_PREFIX + metric.getName() + "." + tagString.toLowerCase().replace('.', '_'),
          data.getSpanTag());
    } else {
      assertTrue(data.tags.isEmpty());
      assertEquals(TRACE_METRIC_PREFIX + metric.getName(), data.getSpanTag());
    }
  }

  @TableTest({
    "scenario                                | metric              | tag                ",
    "INSTRUMENTED_SINK SQL_INJECTION         | INSTRUMENTED_SINK   | SQL_INJECTION      ",
    "EXECUTED_SINK SQL_INJECTION             | EXECUTED_SINK       | SQL_INJECTION      ",
    "INSTRUMENTED_SINK RESPONSE_HEADER       | INSTRUMENTED_SINK   | RESPONSE_HEADER    ",
    "EXECUTED_SINK RESPONSE_HEADER           | EXECUTED_SINK       | RESPONSE_HEADER    ",
    "INSTRUMENTED_SINK SPRING_RESPONSE       | INSTRUMENTED_SINK   | SPRING_RESPONSE    ",
    "EXECUTED_SINK SPRING_RESPONSE           | EXECUTED_SINK       | SPRING_RESPONSE    ",
    "INSTRUMENTED_SINK APPLICATION           | INSTRUMENTED_SINK   | APPLICATION        ",
    "EXECUTED_SINK APPLICATION               | EXECUTED_SINK       | APPLICATION        ",
    "INSTRUMENTED_SOURCE REQUEST_HEADER_NAME | INSTRUMENTED_SOURCE | REQUEST_HEADER_NAME",
    "EXECUTED_SOURCE REQUEST_HEADER_NAME     | EXECUTED_SOURCE     | REQUEST_HEADER_NAME",
    "INSTRUMENTED_SOURCE KAFKA_MESSAGE       | INSTRUMENTED_SOURCE | KAFKA_MESSAGE      ",
    "EXECUTED_SOURCE KAFKA_MESSAGE           | EXECUTED_SOURCE     | KAFKA_MESSAGE      ",
    "EXECUTED_TAINTED                        | EXECUTED_TAINTED    | -1                 "
  })
  void testMetric(IastMetric metric, @ConvertWith(IastConstantConverter.class) byte tag) {
    IastMetricCollector collector = new IastMetricCollector();
    int value = 125;
    Tag metricTag = metric.getTag();

    collector.addMetric(metric, metricTag == null ? (byte) -1 : tag, value);
    collector.prepareMetrics();
    Collection<IastMetricData> result = collector.drain();

    if (metricTag != null) {
      // wrapped tags (response headers, spring response, application vulns, kafka sources)
      byte[] unwrapped = metricTag.unwrap(tag);
      byte[] assertedTags = unwrapped != null ? unwrapped : new byte[] {tag};
      assertEquals(assertedTags.length, result.size());
      Map<String, IastMetricData> grouped = new HashMap<>();
      for (IastMetricData data : result) {
        grouped.put(data.tags.get(0), data);
      }
      for (byte assertedTag : assertedTags) {
        String tagValue = metricTag.getName() + ":" + metricTag.values[assertedTag];
        IastMetricData data = grouped.get(tagValue);
        assertEquals(metric, data.getMetric());
        assertEquals(value, data.value.longValue());
        assertEquals(1, data.tags.size());
        assertEquals(tagValue, data.tags.get(0));
      }
    } else {
      assertEquals(1, result.size());
      IastMetricData data = result.iterator().next();
      assertEquals(metric, data.getMetric());
      assertEquals(value, data.value.longValue());
      assertTrue(data.tags.isEmpty());
    }
  }

  private static IastMetricCollector newNoOpInstance() throws ReflectiveOperationException {
    Constructor<?> constructor =
        Class.forName(IastMetricCollector.class.getName() + "$NoOpInstance")
            .getDeclaredConstructor();
    constructor.setAccessible(true);
    return (IastMetricCollector) constructor.newInstance();
  }
}

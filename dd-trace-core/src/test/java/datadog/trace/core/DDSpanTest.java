package datadog.trace.core;

import static datadog.trace.api.TracePropagationStyle.DATADOG;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.api.sampling.SamplingMechanism.SPAN_SAMPLING_RATE;
import static datadog.trace.core.DDSpanContext.SPAN_SAMPLING_MAX_PER_SECOND_TAG;
import static datadog.trace.core.DDSpanContext.SPAN_SAMPLING_MECHANISM_TAG;
import static datadog.trace.core.DDSpanContext.SPAN_SAMPLING_RULE_RATE_TAG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTags;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.TagMap;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.ErrorPriorities;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.common.sampling.RateByServiceTraceSampler;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.core.propagation.PropagationTags;
import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tabletest.junit.TableTest;

public class DDSpanTest extends DDCoreJavaSpecification {

  private ListWriter writer;
  private CoreTracer tracer;

  @BeforeEach
  void setup() {
    writer = new ListWriter();
    tracer = tracerBuilder().writer(writer).sampler(new RateByServiceTraceSampler()).build();
  }

  @Test
  void gettersAndSetters() {
    DDSpan span =
        (DDSpan)
            tracer
                .buildSpan("fakeOperation")
                .withServiceName("fakeService")
                .withResourceName("fakeResource")
                .withSpanType("fakeType")
                .start();

    span.setServiceName("service");
    assertEquals("service", span.getServiceName());

    span.setOperationName("operation");
    assertEquals("operation", span.getOperationName().toString());

    span.setResourceName("resource");
    assertEquals("resource", span.getResourceName().toString());

    span.setSpanType("type");
    assertEquals("type", span.getType());

    span.setSamplingPriority(PrioritySampling.UNSET);
    assertNull(span.getSamplingPriority());

    span.setSamplingPriority(PrioritySampling.SAMPLER_KEEP);
    assertEquals(PrioritySampling.SAMPLER_KEEP, (int) span.getSamplingPriority());

    span.context().lockSamplingPriority();
    span.setSamplingPriority(PrioritySampling.USER_KEEP);
    assertEquals(PrioritySampling.SAMPLER_KEEP, (int) span.getSamplingPriority());
  }

  @Test
  void resourceNameEqualsOperationNameIfNull() {
    String opName = "operationName";
    DDSpan span = (DDSpan) tracer.buildSpan(opName).start();
    assertEquals(opName, span.getResourceName().toString());
    assertFalse(span.getServiceName().isEmpty());

    String resourceName = "fake";
    String serviceName = "myService";
    span =
        (DDSpan)
            tracer
                .buildSpan(opName)
                .withResourceName(resourceName)
                .withServiceName(serviceName)
                .start();
    assertEquals(resourceName, span.getResourceName().toString());
    assertEquals(serviceName, span.getServiceName());
  }

  @Test
  void durationMeasuredInNanoseconds() {
    long mod = TimeUnit.MILLISECONDS.toNanos(1);
    long start = System.nanoTime();
    DDSpan span = (DDSpan) tracer.buildSpan("test").start();
    long between = System.nanoTime();
    long betweenDur = System.nanoTime() - between;
    span.finish();
    long total = System.nanoTime() - start;

    assertTrue(
        Math.abs(
                TimeUnit.NANOSECONDS.toSeconds(span.getStartTime())
                    - TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()))
            < 5);
    assertTrue(span.getDurationNano() > betweenDur);
    assertTrue(span.getDurationNano() < total);
    assertTrue(span.getDurationNano() % mod > 0);
  }

  @Test
  void phasedFinishCapturesDurationButDoesNotPublishImmediately() throws Exception {
    long mod = TimeUnit.MILLISECONDS.toNanos(1);
    long start = System.nanoTime();
    DDSpan span = (DDSpan) tracer.buildSpan("test").start();
    long between = System.nanoTime();
    long betweenDur = System.nanoTime() - between;

    span.publish();
    assertEquals(0, span.getDurationNano());
    assertEquals(1, pendingReferenceCount(span));
    assertEquals(0, writer.size());

    boolean finish = span.phasedFinish();
    long total = System.nanoTime() - start;

    assertTrue(finish);
    assertEquals(1, pendingReferenceCount(span));
    assertTrue(spans(span).isEmpty());
    assertTrue(writer.isEmpty());

    assertTrue(span.getDurationNano() < 0);
    long actualDurationNano = span.getDurationNano() & Long.MAX_VALUE;
    assertTrue(
        Math.abs(
                TimeUnit.NANOSECONDS.toSeconds(span.getStartTime())
                    - TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()))
            < 5);
    assertTrue(actualDurationNano > betweenDur);
    assertTrue(actualDurationNano < total);
    assertTrue(actualDurationNano % mod > 0);

    finish = span.phasedFinish();
    span.finish();
    assertFalse(finish);
    assertEquals(1, pendingReferenceCount(span));
    assertTrue(spans(span).isEmpty());
    assertTrue(writer.isEmpty());

    span.publish();
    assertTrue(span.getDurationNano() > 0);
    assertEquals(actualDurationNano, span.getDurationNano());
    assertEquals(0, pendingReferenceCount(span));
    assertEquals(1, writer.size());

    span.publish();
    assertEquals(0, pendingReferenceCount(span));
    assertEquals(1, writer.size());
  }

  @Test
  void startingWithTimestampDisablesNanotime() {
    long mod = TimeUnit.MILLISECONDS.toNanos(1);
    long start = System.currentTimeMillis();
    DDSpan span =
        (DDSpan)
            tracer
                .buildSpan("test")
                .withStartTimestamp(TimeUnit.MILLISECONDS.toMicros(System.currentTimeMillis()))
                .start();
    long between = System.currentTimeMillis();
    long betweenDur = System.currentTimeMillis() - between;
    span.finish();
    long total = Math.max(1, System.currentTimeMillis() - start);

    assertTrue(
        Math.abs(
                TimeUnit.NANOSECONDS.toSeconds(span.getStartTime())
                    - TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()))
            < 5);
    assertTrue(span.getDurationNano() >= TimeUnit.MILLISECONDS.toNanos(betweenDur));
    assertTrue(span.getDurationNano() <= TimeUnit.MILLISECONDS.toNanos(total));
    assertTrue(span.getDurationNano() % mod == 0 || span.getDurationNano() == 1);
  }

  @Test
  void stoppingWithTimestampDisablesNanotime() {
    long mod = TimeUnit.MILLISECONDS.toNanos(1);
    long start = System.currentTimeMillis();
    DDSpan span = (DDSpan) tracer.buildSpan("test").start();
    long between = System.currentTimeMillis();
    long betweenDur = System.currentTimeMillis() - between;
    span.finish(TimeUnit.MILLISECONDS.toMicros(System.currentTimeMillis() + 1));
    long total = System.currentTimeMillis() - start + 1;

    assertTrue(
        Math.abs(
                TimeUnit.NANOSECONDS.toSeconds(span.getStartTime())
                    - TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()))
            < 5);
    assertTrue(span.getDurationNano() >= TimeUnit.MILLISECONDS.toNanos(betweenDur));
    assertTrue(span.getDurationNano() <= TimeUnit.MILLISECONDS.toNanos(total));
    assertTrue(span.getDurationNano() % mod == 0 || span.getDurationNano() == 1);
  }

  @Test
  void stoppingWithTimestampBeforeStartTimeYieldsMinDurationOfOne() {
    DDSpan span = (DDSpan) tracer.buildSpan("test").start();
    span.finish(
        TimeUnit.MILLISECONDS.toMicros(TimeUnit.NANOSECONDS.toMillis(span.getStartTimeNano()))
            - 10);
    assertEquals(1, span.getDurationNano());
  }

  @Test
  void prioritySamplingMetricSetOnlyOnRootSpan() {
    DDSpan parent = (DDSpan) tracer.buildSpan("testParent").start();
    DDSpan child1 = (DDSpan) tracer.buildSpan("testChild1").asChildOf(parent.context()).start();

    child1.setSamplingPriority(PrioritySampling.SAMPLER_KEEP);
    child1.context().lockSamplingPriority();
    parent.setSamplingPriority(PrioritySampling.SAMPLER_DROP);
    child1.finish();
    DDSpan child2 = (DDSpan) tracer.buildSpan("testChild2").asChildOf(parent.context()).start();
    child2.finish();
    parent.finish();

    assertEquals(PrioritySampling.SAMPLER_KEEP, parent.context().getSamplingPriority());
    assertEquals(PrioritySampling.SAMPLER_KEEP, (int) parent.getSamplingPriority());
    assertTrue(parent.hasSamplingPriority());
    assertEquals(parent.getSamplingPriority(), child1.getSamplingPriority());
    assertEquals(parent.getSamplingPriority(), child2.getSamplingPriority());
    assertFalse(child1.hasSamplingPriority());
    assertFalse(child2.hasSamplingPriority());
  }

  static Stream<Arguments> originSetOnlyOnRootSpanArguments() {
    return Stream.of(
        arguments("TagContext", new TagContext("some-origin", TagMap.fromMap(new HashMap<>()))),
        arguments(
            "ExtractedContext",
            new ExtractedContext(
                DDTraceId.ONE,
                2,
                SAMPLER_DROP,
                "some-origin",
                PropagationTags.factory().empty(),
                DATADOG)));
  }

  @ParameterizedTest
  @MethodSource("originSetOnlyOnRootSpanArguments")
  void originSetOnlyOnRootSpan(String scenario, AgentSpanContext extractedContext)
      throws Exception {
    DDSpanContext parent =
        (DDSpanContext)
            tracer.buildSpan("testParent").asChildOf(extractedContext).start().context();
    DDSpanContext child =
        (DDSpanContext) tracer.buildSpan("testChild1").asChildOf(parent).start().context();

    assertEquals("some-origin", parent.getOrigin().toString());
    Field originField = DDSpanContext.class.getDeclaredField("origin");
    originField.setAccessible(true);
    assertEquals("some-origin", originField.get(parent).toString());
    assertEquals("some-origin", child.getOrigin().toString());
    assertNull(originField.get(child));
  }

  static Stream<Arguments> isRootSpanArguments() {
    return Stream.of(
        arguments("no parent", null, true),
        arguments(
            "distributed parent",
            new ExtractedContext(
                DDTraceId.from(123),
                456,
                SAMPLER_KEEP,
                "789",
                PropagationTags.factory().empty(),
                DATADOG),
            false));
  }

  @ParameterizedTest
  @MethodSource("isRootSpanArguments")
  void isRootSpanInAndNotInContextOfDistributedTracing(
      String scenario, AgentSpanContext extractedContext, boolean isTraceRootSpan) {
    DDSpan root = (DDSpan) tracer.buildSpan("root").asChildOf(extractedContext).start();
    DDSpan child = (DDSpan) tracer.buildSpan("child").asChildOf(root.context()).start();

    assertEquals(isTraceRootSpan, root.isRootSpan());
    assertFalse(child.isRootSpan());

    child.finish();
    root.finish();
  }

  static Stream<Arguments> getApplicationRootSpanArguments() {
    return Stream.of(
        arguments("no parent", null),
        arguments(
            "distributed parent",
            new ExtractedContext(
                DDTraceId.from(123),
                456,
                SAMPLER_KEEP,
                "789",
                PropagationTags.factory().empty(),
                DATADOG)));
  }

  @ParameterizedTest
  @MethodSource("getApplicationRootSpanArguments")
  void getApplicationRootSpanInAndNotInContextOfDistributedTracing(
      String scenario, AgentSpanContext extractedContext) {
    DDSpan root = (DDSpan) tracer.buildSpan("root").asChildOf(extractedContext).start();
    DDSpan child = (DDSpan) tracer.buildSpan("child").asChildOf(root.context()).start();

    assertEquals(root, root.getLocalRootSpan());
    assertEquals(root, child.getLocalRootSpan());
    assertEquals(root, root.getRootSpan());
    assertEquals(root, child.getRootSpan());

    child.finish();
    root.finish();
  }

  @Test
  void publishingOfRootSpanClosesRequestContextData() throws Exception {
    Closeable reqContextData = mock(Closeable.class);
    TagContext context = new TagContext().withRequestContextDataAppSec(reqContextData);
    DDSpan root = (DDSpan) tracer.buildSpan("root").asChildOf(context).start();
    DDSpan child = (DDSpan) tracer.buildSpan("child").asChildOf(root.context()).start();

    assertEquals(reqContextData, root.getRequestContext().getData(RequestContextSlot.APPSEC));
    assertEquals(reqContextData, child.getRequestContext().getData(RequestContextSlot.APPSEC));

    child.finish();
    verify(reqContextData, never()).close();

    root.finish();
    verify(reqContextData, times(1)).close();
  }

  static Stream<Arguments> inferTopLevelFromParentServiceNameArguments() {
    return Stream.of(
        arguments("String foo", "foo", true),
        arguments("UTF8BytesString foo", UTF8BytesString.create("foo"), true),
        arguments("String fakeService", "fakeService", false),
        arguments("UTF8BytesString fakeService", UTF8BytesString.create("fakeService"), false),
        arguments("empty string", "", true),
        arguments("null", null, true));
  }

  @ParameterizedTest
  @MethodSource("inferTopLevelFromParentServiceNameArguments")
  void inferTopLevelFromParentServiceName(
      String scenario, CharSequence parentServiceName, boolean expectTopLevel) {
    DDSpanContext context =
        new DDSpanContext(
            DDTraceId.ONE,
            1,
            DDSpanId.ZERO,
            parentServiceName,
            "fakeService",
            "fakeOperation",
            "fakeResource",
            PrioritySampling.UNSET,
            null,
            Collections.emptyMap(),
            false,
            "fakeType",
            0,
            tracer.createTraceCollector(DDTraceId.ONE),
            null,
            null,
            NoopPathwayContext.INSTANCE,
            false,
            PropagationTags.factory().empty());
    assertEquals(expectTopLevel, context.isTopLevel());
  }

  @Test
  void brokenPipeExceptionDoesNotCreateErrorSpan() {
    DDSpan span = (DDSpan) tracer.buildSpan("root").start();
    span.addThrowable(new IOException("Broken pipe"));
    assertFalse(span.isError());
    assertNull(span.getTag(DDTags.ERROR_STACK));
    assertEquals("Broken pipe", span.getTag(DDTags.ERROR_MSG));
  }

  @Test
  void wrappedBrokenPipeExceptionDoesNotCreateErrorSpan() {
    DDSpan span = (DDSpan) tracer.buildSpan("root").start();
    span.addThrowable(new RuntimeException(new IOException("Broken pipe")));
    assertFalse(span.isError());
    assertNull(span.getTag(DDTags.ERROR_STACK));
    assertEquals("java.io.IOException: Broken pipe", span.getTag(DDTags.ERROR_MSG));
  }

  @Test
  void nullExceptionSafeToAdd() {
    DDSpan span = (DDSpan) tracer.buildSpan("root").start();
    span.addThrowable(null);
    assertFalse(span.isError());
    assertNull(span.getTag(DDTags.ERROR_STACK));
  }

  @TableTest({
    "scenario         | rate | limit     ",
    "rate=1.0 lim=10  | 1.0  | 10        ",
    "rate=0.5 lim=100 | 0.5  | 100       ",
    "rate=0.25 no lim | 0.25 | 2147483647"
  })
  void setSingleSpanSamplingTags(String scenario, double rate, int limit) {
    DDSpan span = (DDSpan) tracer.buildSpan("testSpan").start();
    assertEquals(UNSET, span.samplingPriority());

    span.setSpanSamplingPriority(rate, limit);

    assertEquals((int) SPAN_SAMPLING_RATE, span.getTag(SPAN_SAMPLING_MECHANISM_TAG));
    assertEquals(rate, span.getTag(SPAN_SAMPLING_RULE_RATE_TAG));
    assertEquals(
        limit == Integer.MAX_VALUE ? null : (double) limit,
        span.getTag(SPAN_SAMPLING_MAX_PER_SECOND_TAG));
    assertEquals(UNSET, span.samplingPriority());
  }

  @Test
  void errorPrioritiesShouldBeRespected() {
    DDSpan span = (DDSpan) tracer.buildSpan("testSpan").start();
    assertFalse(span.isError());

    span.setError(true);
    assertTrue(span.isError());

    span.setError(false);
    assertFalse(span.isError());

    span.setError(true, ErrorPriorities.HTTP_SERVER_DECORATOR);
    assertFalse(span.isError());

    span.setError(true, ErrorPriorities.MANUAL_INSTRUMENTATION);
    assertTrue(span.isError());

    span.setError(true, Byte.MAX_VALUE);
    assertTrue(span.isError());
  }

  private static int pendingReferenceCount(DDSpan span) {
    PendingTrace trace = (PendingTrace) span.context().getTraceCollector();
    return PendingTraceTestBridge.getPendingReferenceCount(trace);
  }

  private static Collection<DDSpan> spans(DDSpan span) {
    PendingTrace trace = (PendingTrace) span.context().getTraceCollector();
    return trace.getSpans();
  }
}

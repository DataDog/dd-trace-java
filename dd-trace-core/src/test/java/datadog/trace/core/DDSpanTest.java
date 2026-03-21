package datadog.trace.core;

import static datadog.trace.api.TracePropagationStyle.DATADOG;
import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.api.sampling.SamplingMechanism.SPAN_SAMPLING_RATE;
import static datadog.trace.core.DDSpanContext.SPAN_SAMPLING_MAX_PER_SECOND_TAG;
import static datadog.trace.core.DDSpanContext.SPAN_SAMPLING_MECHANISM_TAG;
import static datadog.trace.core.DDSpanContext.SPAN_SAMPLING_RULE_RATE_TAG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTags;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.TagMap;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ErrorPriorities;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.common.sampling.RateByServiceTraceSampler;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.core.test.DDCoreSpecification;
import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

public class DDSpanTest extends DDCoreSpecification {

  static ListWriter sharedWriter;
  static RateByServiceTraceSampler sharedSampler;
  static CoreTracer sharedTracer;
  static datadog.trace.core.propagation.PropagationTags.Factory propagationTagsFactory;

  @BeforeAll
  static void setupShared() {
    sharedWriter = new ListWriter();
    sharedSampler = new RateByServiceTraceSampler();
    sharedTracer = CoreTracer.builder().writer(sharedWriter).sampler(sharedSampler).build();
    propagationTagsFactory = sharedTracer.getPropagationTagsFactory();
  }

  @AfterAll
  static void cleanupShared() throws Exception {
    if (sharedTracer != null) {
      sharedTracer.close();
    }
  }

  @Test
  void gettersAndSetters() throws Exception {
    datadog.trace.bootstrap.instrumentation.api.AgentSpan span =
        sharedTracer
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
    assertEquals("type", ((DDSpan) span).getType());

    span.setSamplingPriority((int) PrioritySampling.UNSET);
    assertNull(span.getSamplingPriority());

    span.setSamplingPriority((int) PrioritySampling.SAMPLER_KEEP);
    assertEquals((int) PrioritySampling.SAMPLER_KEEP, span.getSamplingPriority());

    ((DDSpanContext) span.context()).lockSamplingPriority();
    span.setSamplingPriority((int) PrioritySampling.USER_KEEP);
    assertEquals((int) PrioritySampling.SAMPLER_KEEP, span.getSamplingPriority());
  }

  @Test
  void resourceNameEqualsOperationNameIfNull() throws Exception {
    String opName = "operationName";

    datadog.trace.bootstrap.instrumentation.api.AgentSpan span =
        sharedTracer.buildSpan(opName).start();
    assertEquals(opName, span.getResourceName().toString());
    assertNotEquals("", span.getServiceName());

    String resourceName = "fake";
    String serviceName = "myService";
    span =
        sharedTracer
            .buildSpan(opName)
            .withResourceName(resourceName)
            .withServiceName(serviceName)
            .start();
    assertEquals(resourceName, span.getResourceName().toString());
    assertEquals(serviceName, span.getServiceName());
  }

  @Test
  void durationMeasuredInNanoseconds() throws Exception {
    long mod = TimeUnit.MILLISECONDS.toNanos(1);
    AgentTracer.SpanBuilder builder = sharedTracer.buildSpan("test");
    long start = System.nanoTime();
    datadog.trace.bootstrap.instrumentation.api.AgentSpan span = builder.start();
    long between = System.nanoTime();
    long betweenDur = System.nanoTime() - between;
    span.finish();
    long total = System.nanoTime() - start;

    // Generous 5 seconds to execute this test
    assertTrue(
        Math.abs(
                TimeUnit.NANOSECONDS.toSeconds(((DDSpan) span).getStartTime())
                    - TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()))
            < 5);
    assertTrue(((DDSpan) span).getDurationNano() > betweenDur);
    assertTrue(((DDSpan) span).getDurationNano() < total);
    assertTrue(
        ((DDSpan) span).getDurationNano() % mod > 0); // Very slim chance of a false negative.
  }

  @Test
  void phasedFinishCapturesDurationButDoesntPublishImmediately() throws Exception {
    long mod = TimeUnit.MILLISECONDS.toNanos(1);
    AgentTracer.SpanBuilder builder = sharedTracer.buildSpan("test");
    long start = System.nanoTime();
    DDSpan span = (DDSpan) builder.start();
    long between = System.nanoTime();
    long betweenDur = System.nanoTime() - between;
    int initialWriterSize = sharedWriter.size();

    // calling publish before phasedFinish has no effect
    span.publish();

    assertEquals(0, span.getDurationNano());
    assertEquals(1, ((PendingTrace) span.context().getTraceCollector()).getPendingReferenceCount());
    assertEquals(initialWriterSize, sharedWriter.size());

    boolean finish = span.phasedFinish();
    long total = System.nanoTime() - start;

    assertTrue(finish);
    assertEquals(1, ((PendingTrace) span.context().getTraceCollector()).getPendingReferenceCount());
    assertTrue(((PendingTrace) span.context().getTraceCollector()).getSpans().isEmpty());
    assertEquals(initialWriterSize, sharedWriter.size());

    // duration is recorded as negative to allow publishing
    assertTrue(span.getDurationNano() < 0);
    long actualDurationNano = span.getDurationNano() & Long.MAX_VALUE;
    // Generous 5 seconds to execute this test
    assertTrue(
        Math.abs(
                TimeUnit.NANOSECONDS.toSeconds(span.getStartTime())
                    - TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()))
            < 5);
    assertTrue(actualDurationNano > betweenDur);
    assertTrue(actualDurationNano < total);
    assertTrue(actualDurationNano % mod > 0); // Very slim chance of a false negative.

    // extra finishes have no effect
    finish = span.phasedFinish();
    span.finish(); // verify conflicting finishes are ignored

    assertFalse(finish);
    assertEquals(1, ((PendingTrace) span.context().getTraceCollector()).getPendingReferenceCount());
    assertTrue(((PendingTrace) span.context().getTraceCollector()).getSpans().isEmpty());
    assertEquals(initialWriterSize, sharedWriter.size());

    span.publish();

    // duration is flipped to positive
    assertTrue(span.getDurationNano() > 0);
    assertEquals(actualDurationNano, span.getDurationNano());
    assertEquals(0, ((PendingTrace) span.context().getTraceCollector()).getPendingReferenceCount());
    assertEquals(initialWriterSize + 1, sharedWriter.size());

    // duplicate call to publish has no effect
    span.publish();

    assertEquals(0, ((PendingTrace) span.context().getTraceCollector()).getPendingReferenceCount());
    assertEquals(initialWriterSize + 1, sharedWriter.size());
  }

  @Test
  void startingWithATimestampDisablesNanotime() throws Exception {
    long mod = TimeUnit.MILLISECONDS.toNanos(1);
    long start = System.currentTimeMillis();
    AgentTracer.SpanBuilder builder =
        sharedTracer
            .buildSpan("test")
            .withStartTimestamp(TimeUnit.MILLISECONDS.toMicros(System.currentTimeMillis()));
    DDSpan span = (DDSpan) builder.start();
    long between = System.currentTimeMillis();
    long betweenDur = System.currentTimeMillis() - between;
    span.finish();
    long total = Math.max(1, System.currentTimeMillis() - start);

    // Generous 5 seconds to execute this test
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
  void stoppingWithATimestampDisablesNanotime() throws Exception {
    long mod = TimeUnit.MILLISECONDS.toNanos(1);
    AgentTracer.SpanBuilder builder = sharedTracer.buildSpan("test");
    long start = System.currentTimeMillis();
    DDSpan span = (DDSpan) builder.start();
    long between = System.currentTimeMillis();
    long betweenDur = System.currentTimeMillis() - between;
    span.finish(TimeUnit.MILLISECONDS.toMicros(System.currentTimeMillis() + 1));
    long total = System.currentTimeMillis() - start + 1;

    // Generous 5 seconds to execute this test
    assertTrue(
        Math.abs(
                TimeUnit.NANOSECONDS.toSeconds(span.getStartTime())
                    - TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()))
            < 5);
    assertTrue(span.getDurationNano() >= TimeUnit.MILLISECONDS.toNanos(betweenDur));
    assertTrue(span.getDurationNano() <= TimeUnit.MILLISECONDS.toNanos(total));
    // true span duration can be <1ms if clock was about to tick over, so allow for that
    assertTrue(span.getDurationNano() % mod == 0 || span.getDurationNano() == 1);
  }

  @Test
  void stoppingWithATimestampBeforeStartTimeYieldsMinDurationOf1() throws Exception {
    DDSpan span = (DDSpan) sharedTracer.buildSpan("test").start();

    // remove tick precision part of our internal time to match previous test condition
    span.finish(
        TimeUnit.MILLISECONDS.toMicros(TimeUnit.NANOSECONDS.toMillis(span.getStartTime())) - 10);

    assertEquals(1, span.getDurationNano());
  }

  @Test
  void prioritySamplingMetricSetOnlyOnRootSpan() throws Exception {
    datadog.trace.bootstrap.instrumentation.api.AgentSpan parent =
        sharedTracer.buildSpan("testParent").start();
    datadog.trace.bootstrap.instrumentation.api.AgentSpan child1 =
        sharedTracer.buildSpan("testChild1").asChildOf(parent.context()).start();

    child1.setSamplingPriority((int) PrioritySampling.SAMPLER_KEEP);
    ((DDSpanContext) child1.context()).lockSamplingPriority();
    parent.setSamplingPriority((int) PrioritySampling.SAMPLER_DROP);
    child1.finish();
    datadog.trace.bootstrap.instrumentation.api.AgentSpan child2 =
        sharedTracer.buildSpan("testChild2").asChildOf(parent.context()).start();
    child2.finish();
    parent.finish();

    assertEquals(
        (int) PrioritySampling.SAMPLER_KEEP, ((DDSpan) parent).context().getSamplingPriority());
    assertEquals((int) PrioritySampling.SAMPLER_KEEP, parent.getSamplingPriority());
    assertTrue(((DDSpan) parent).hasSamplingPriority());
    assertEquals(parent.getSamplingPriority(), child1.getSamplingPriority());
    assertEquals(parent.getSamplingPriority(), child2.getSamplingPriority());
    assertFalse(((DDSpan) child1).hasSamplingPriority());
    assertFalse(((DDSpan) child2).hasSamplingPriority());
  }

  @ParameterizedTest
  @MethodSource("originSetOnlyOnRootSpanArguments")
  void originSetOnlyOnRootSpan(AgentSpanContext extractedContext) throws Exception {
    DDSpanContext parent =
        (DDSpanContext)
            sharedTracer.buildSpan("testParent").asChildOf(extractedContext).start().context();
    DDSpanContext child =
        (DDSpanContext) sharedTracer.buildSpan("testChild1").asChildOf(parent).start().context();

    assertEquals("some-origin", parent.getOrigin());
    // verify field directly via reflection - parent stores origin directly
    Field originField = DDSpanContext.class.getDeclaredField("origin");
    originField.setAccessible(true);
    assertEquals("some-origin", originField.get(parent));
    assertEquals("some-origin", child.getOrigin());
    // child does not store origin directly, only inherits from root via getter
    assertNull(originField.get(child));
  }

  static Stream<Arguments> originSetOnlyOnRootSpanArguments() {
    return Stream.of(
        Arguments.of(new TagContext("some-origin", TagMap.fromMap(new java.util.HashMap<>()))),
        Arguments.of(
            new ExtractedContext(
                DDTraceId.ONE,
                2,
                (int) PrioritySampling.SAMPLER_DROP,
                "some-origin",
                propagationTagsFactory.empty(),
                DATADOG)));
  }

  @ParameterizedTest
  @MethodSource("isRootSpanArguments")
  void isRootSpanInAndNotInContextOfDistributedTracing(
      AgentSpanContext extractedContext, boolean isTraceRootSpan) throws Exception {
    datadog.trace.bootstrap.instrumentation.api.AgentSpan root =
        sharedTracer.buildSpan("root").asChildOf(extractedContext).start();
    datadog.trace.bootstrap.instrumentation.api.AgentSpan child =
        sharedTracer.buildSpan("child").asChildOf(root.context()).start();

    assertEquals(isTraceRootSpan, ((DDSpan) root).isRootSpan());
    assertFalse(((DDSpan) child).isRootSpan());

    child.finish();
    root.finish();
  }

  static Stream<Arguments> isRootSpanArguments() {
    return Stream.of(
        Arguments.of((AgentSpanContext) null, true),
        Arguments.of(
            new ExtractedContext(
                DDTraceId.from(123),
                456,
                (int) PrioritySampling.SAMPLER_KEEP,
                "789",
                propagationTagsFactory.empty(),
                DATADOG),
            false));
  }

  @ParameterizedTest
  @MethodSource("getApplicationRootSpanArguments")
  void getApplicationRootSpanInAndNotInContextOfDistributedTracing(
      AgentSpanContext extractedContext) throws Exception {
    datadog.trace.bootstrap.instrumentation.api.AgentSpan root =
        sharedTracer.buildSpan("root").asChildOf(extractedContext).start();
    datadog.trace.bootstrap.instrumentation.api.AgentSpan child =
        sharedTracer.buildSpan("child").asChildOf(root.context()).start();

    assertEquals(root, ((DDSpan) root).getLocalRootSpan());
    assertEquals(root, ((DDSpan) child).getLocalRootSpan());
    // Checking for backward compatibility method names
    assertEquals(root, ((DDSpan) root).getRootSpan());
    assertEquals(root, ((DDSpan) child).getRootSpan());

    child.finish();
    root.finish();
  }

  static Stream<Arguments> getApplicationRootSpanArguments() {
    return Stream.of(
        Arguments.of((AgentSpanContext) null),
        Arguments.of(
            new ExtractedContext(
                DDTraceId.from(123),
                456,
                (int) PrioritySampling.SAMPLER_KEEP,
                "789",
                propagationTagsFactory.empty(),
                DATADOG)));
  }

  @Test
  void publishingOfRootSpanClosesRequestContextData() throws Exception {
    Closeable reqContextData = Mockito.mock(Closeable.class);
    TagContext context = new TagContext().withRequestContextDataAppSec(reqContextData);
    datadog.trace.bootstrap.instrumentation.api.AgentSpan root =
        sharedTracer.buildSpan("root").asChildOf(context).start();
    datadog.trace.bootstrap.instrumentation.api.AgentSpan child =
        sharedTracer.buildSpan("child").asChildOf(root.context()).start();

    assertTrue(root.getRequestContext().getData(RequestContextSlot.APPSEC) == reqContextData);
    assertTrue(child.getRequestContext().getData(RequestContextSlot.APPSEC) == reqContextData);

    child.finish();

    Mockito.verify(reqContextData, Mockito.times(0)).close();

    root.finish();

    Mockito.verify(reqContextData, Mockito.times(1)).close();
  }

  @ParameterizedTest
  @MethodSource("inferTopLevelFromParentServiceNameArguments")
  void inferTopLevelFromParentServiceName(Object parentServiceName, boolean expectTopLevel)
      throws Exception {
    datadog.trace.core.propagation.PropagationTags.Factory factory =
        sharedTracer.getPropagationTagsFactory();
    DDSpanContext context =
        new DDSpanContext(
            DDTraceId.ONE,
            1,
            DDSpanId.ZERO,
            (CharSequence) parentServiceName,
            "fakeService",
            "fakeOperation",
            "fakeResource",
            PrioritySampling.UNSET,
            null,
            java.util.Collections.emptyMap(),
            false,
            "fakeType",
            0,
            sharedTracer.createTraceCollector(DDTraceId.ONE),
            null,
            null,
            NoopPathwayContext.INSTANCE,
            false,
            factory.empty());

    assertEquals(expectTopLevel, context.isTopLevel());
  }

  static Stream<Arguments> inferTopLevelFromParentServiceNameArguments() {
    return Stream.of(
        Arguments.of("foo", true),
        Arguments.of(UTF8BytesString.create("foo"), true),
        Arguments.of("fakeService", false),
        Arguments.of(UTF8BytesString.create("fakeService"), false),
        Arguments.of("", true),
        Arguments.of(null, true));
  }

  @Test
  void brokenPipeExceptionDoesNotCreateErrorSpan() throws Exception {
    datadog.trace.bootstrap.instrumentation.api.AgentSpan span =
        sharedTracer.buildSpan("root").start();
    span.addThrowable(new IOException("Broken pipe"));
    assertFalse(span.isError());
    assertNull(span.getTag(DDTags.ERROR_STACK));
    assertEquals("Broken pipe", span.getTag(DDTags.ERROR_MSG));
  }

  @Test
  void wrappedBrokenPipeExceptionDoesNotCreateErrorSpan() throws Exception {
    datadog.trace.bootstrap.instrumentation.api.AgentSpan span =
        sharedTracer.buildSpan("root").start();
    span.addThrowable(new RuntimeException(new IOException("Broken pipe")));
    assertFalse(span.isError());
    assertNull(span.getTag(DDTags.ERROR_STACK));
    assertEquals("java.io.IOException: Broken pipe", span.getTag(DDTags.ERROR_MSG));
  }

  @Test
  void nullExceptionSafeToAdd() throws Exception {
    datadog.trace.bootstrap.instrumentation.api.AgentSpan span =
        sharedTracer.buildSpan("root").start();
    span.addThrowable(null);
    assertFalse(span.isError());
    assertNull(span.getTag(DDTags.ERROR_STACK));
  }

  @ParameterizedTest
  @MethodSource("setSingleSpanSamplingTagsArguments")
  void setSingleSpanSamplingTags(double rate, int limit) throws Exception {
    DDSpan span = (DDSpan) sharedTracer.buildSpan("testSpan").start();

    assertEquals((int) UNSET, span.samplingPriority());

    span.setSpanSamplingPriority(rate, limit);

    assertEquals((int) SPAN_SAMPLING_RATE, span.getTag(SPAN_SAMPLING_MECHANISM_TAG));
    assertEquals(rate, span.getTag(SPAN_SAMPLING_RULE_RATE_TAG));
    assertEquals(
        limit == Integer.MAX_VALUE ? null : (double) limit,
        span.getTag(SPAN_SAMPLING_MAX_PER_SECOND_TAG));
    // single span sampling should not change the trace sampling priority
    assertEquals((int) UNSET, span.samplingPriority());
  }

  static Stream<Arguments> setSingleSpanSamplingTagsArguments() {
    return Stream.of(
        Arguments.of(1.0, 10), Arguments.of(0.5, 100), Arguments.of(0.25, Integer.MAX_VALUE));
  }

  @Test
  void errorPrioritiesShouldBeRespected() throws Exception {
    DDSpan span = (DDSpan) sharedTracer.buildSpan("testSpan").start();

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
}

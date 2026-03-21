package datadog.trace.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.TestInterceptor;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.test.DDCoreSpecification;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Timeout(10)
public class TraceInterceptorTest extends DDCoreSpecification {

  ListWriter writer = new ListWriter();
  CoreTracer tracer;

  @BeforeEach
  void setup() {
    injectSysConfig(TracerConfig.TRACE_GIT_METADATA_ENABLED, "false");
    tracer = tracerBuilder().writer(writer).build();
  }

  @AfterEach
  void cleanup() {
    if (tracer != null) {
      tracer.close();
    }
  }

  @Test
  void interceptorIsRegisteredAsAService() {
    assertTrue(tracer.interceptors.iterator().hasNext());
    assertTrue(tracer.interceptors.iterator().next() instanceof TestInterceptor);
  }

  @Test
  void interceptorsWithTheSamePriorityReplaced() {
    int priority = 999;
    ((TestInterceptor) tracer.interceptors.iterator().next()).priority = priority;
    tracer.interceptors.add(
        new TraceInterceptor() {
          @Override
          public Collection<? extends MutableSpan> onTraceComplete(
              Collection<? extends MutableSpan> trace) {
            return new ArrayList<>();
          }

          @Override
          public int priority() {
            return priority;
          }
        });

    assertEquals(1, tracer.interceptors.size());
    assertTrue(tracer.interceptors.iterator().next() instanceof TestInterceptor);
  }

  @ParameterizedTest
  @MethodSource("interceptorsWithDifferentPrioritySortedArguments")
  void interceptorsWithDifferentPrioritySorted(int score) {
    TraceInterceptor existingInterceptor = tracer.interceptors.iterator().next();
    int priority = score;
    TraceInterceptor newInterceptor =
        new TraceInterceptor() {
          @Override
          public Collection<? extends MutableSpan> onTraceComplete(
              Collection<? extends MutableSpan> trace) {
            return new ArrayList<>();
          }

          @Override
          public int priority() {
            return priority;
          }
        };
    tracer.interceptors.add(newInterceptor);

    // Both -1 and 1 are less than TestInterceptor.priority (999), so new comes first in ascending
    // sort
    assertEquals(2, tracer.interceptors.size());
    assertTrue(tracer.interceptors.containsAll(Arrays.asList(newInterceptor, existingInterceptor)));
  }

  static Stream<Arguments> interceptorsWithDifferentPrioritySortedArguments() {
    return Stream.of(Arguments.of(-1), Arguments.of(1));
  }

  @ParameterizedTest
  @MethodSource("interceptorCanDiscardATraceArguments")
  void interceptorCanDiscardATrace(int score, int expectedSize) throws Exception {
    AtomicBoolean called = new AtomicBoolean(false);
    CountDownLatch latch = new CountDownLatch(1);
    int priority = score;
    tracer.interceptors.add(
        new TraceInterceptor() {
          @Override
          public Collection<? extends MutableSpan> onTraceComplete(
              Collection<? extends MutableSpan> trace) {
            called.set(true);
            latch.countDown();
            return new ArrayList<>();
          }

          @Override
          public int priority() {
            return priority;
          }
        });
    tracer.buildSpan("test " + score).start().finish();
    if (score == TestInterceptor.priority) {
      // the interceptor didn't get added, so latch will never be released.
      writer.waitForTraces(1);
    } else {
      latch.await(5, TimeUnit.SECONDS);
    }

    assertEquals(expectedSize, tracer.interceptors.size());
    assertEquals(score != TestInterceptor.priority, called.get());
    assertEquals(score != TestInterceptor.priority, writer.isEmpty());
  }

  static Stream<Arguments> interceptorCanDiscardATraceArguments() {
    return Stream.of(
        Arguments.of(TestInterceptor.priority - 1, 2),
        Arguments.of(
            TestInterceptor.priority,
            1), // This conflicts with TestInterceptor, so it won't be added.
        Arguments.of(TestInterceptor.priority + 1, 2));
  }

  @Test
  void interceptorCanModifyASpan() throws Exception {
    tracer.interceptors.add(
        new TraceInterceptor() {
          @Override
          public Collection<? extends MutableSpan> onTraceComplete(
              Collection<? extends MutableSpan> trace) {
            for (MutableSpan span : trace) {
              span.setOperationName("modifiedON-" + span.getOperationName())
                  .setServiceName("modifiedSN-" + span.getServiceName())
                  .setResourceName("modifiedRN-" + span.getResourceName())
                  .setSpanType("modifiedST-" + span.getSpanType())
                  .setTag("boolean-tag", true)
                  .setTag("number-tag", 5.0)
                  .setTag("string-tag", "howdy")
                  .setError(true);
            }
            return trace;
          }

          @Override
          public int priority() {
            return 1;
          }
        });
    tracer.buildSpan("test").start().finish();
    writer.waitForTraces(1);

    List<? extends MutableSpan> trace = writer.firstTrace();
    assertEquals(1, trace.size());

    MutableSpan span = trace.get(0);

    assertEquals("modifiedON-test", ((DDSpan) span).context().getOperationName().toString());
    assertTrue(span.getServiceName().startsWith("modifiedSN-"));
    assertEquals("modifiedRN-modifiedON-test", span.getResourceName().toString());
    assertEquals("modifiedST-null", span.getSpanType());
    assertTrue(((DDSpan) span).context().getErrorFlag());

    java.util.Map<String, Object> tags = ((DDSpan) span).context().getTags();

    assertEquals(true, tags.get("boolean-tag"));
    assertEquals(5.0, tags.get("number-tag"));
    assertEquals("howdy", tags.get("string-tag"));

    assertNotNull(tags.get("thread.name"));
    assertNotNull(tags.get("thread.id"));
    assertNotNull(tags.get("runtime-id"));
    assertNotNull(tags.get("language"));
    assertTrue(tags.size() >= 7);
  }

  @Test
  void shouldBeRobustWhenInterceptorReturnANullTrace() throws Exception {
    tracer.interceptors.add(
        new TraceInterceptor() {
          @Override
          public Collection<? extends MutableSpan> onTraceComplete(
              Collection<? extends MutableSpan> trace) {
            return null;
          }

          @Override
          public int priority() {
            return 0;
          }
        });

    DDSpan span = (DDSpan) tracer.startSpan("test", "test");
    span.phasedFinish();
    assertDoesNotThrow(() -> tracer.write(SpanList.of(span)));
  }

  @Test
  void registerInterceptorThroughBridge() {
    GlobalTracer.registerIfAbsent(tracer);
    TraceInterceptor interceptor =
        new TraceInterceptor() {
          @Override
          public Collection<? extends MutableSpan> onTraceComplete(
              Collection<? extends MutableSpan> trace) {
            return trace;
          }

          @Override
          public int priority() {
            return 38;
          }
        };

    assertTrue(GlobalTracer.get().addTraceInterceptor(interceptor));
    assertTrue(tracer.interceptors.contains(interceptor));
  }
}

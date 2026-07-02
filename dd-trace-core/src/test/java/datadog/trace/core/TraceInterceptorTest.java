package datadog.trace.core;

import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.TestInterceptor;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.TagMap;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.junit.utils.config.WithConfig;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.tabletest.junit.TableTest;

@Timeout(10)
@WithConfig(key = TracerConfig.TRACE_GIT_METADATA_ENABLED, value = "false")
public class TraceInterceptorTest extends DDCoreJavaSpecification {

  private ListWriter writer;
  private CoreTracer tracer;

  @BeforeEach
  void setup() {
    writer = new ListWriter();
    tracer = tracerBuilder().writer(writer).build();
  }

  @AfterEach
  void cleanup() {
    if (tracer != null) {
      tracer.close();
    }
  }

  @Test
  void interceptorIsRegisteredAsService() {
    assertInstanceOf(TestInterceptor.class, tracer.getInterceptors().interceptors()[0]);
  }

  @Test
  void interceptorsWithSamePriorityReplaced() {
    int priority = 999;
    TestInterceptor.priority = priority;
    tracer
        .getInterceptors()
        .add(
            new TraceInterceptor() {
              @Override
              public Collection<? extends MutableSpan> onTraceComplete(
                  Collection<? extends MutableSpan> trace) {
                return emptyList();
              }

              @Override
              public int priority() {
                return priority;
              }
            });

    TraceInterceptor[] interceptors = tracer.getInterceptors().interceptors();
    assertEquals(1, interceptors.length);
    assertInstanceOf(TestInterceptor.class, interceptors[0]);
  }

  @TableTest({
    "scenario             | score | reverse",
    "lower than existing  | -1    | false  ",
    "higher than existing | 1000  | true   "
  })
  void interceptorsWithDifferentPrioritySorted(int score, boolean reverse) {
    TraceInterceptor existingInterceptor = tracer.getInterceptors().interceptors()[0];
    TraceInterceptor newInterceptor =
        new TraceInterceptor() {
          @Override
          public Collection<? extends MutableSpan> onTraceComplete(
              Collection<? extends MutableSpan> trace) {
            return emptyList();
          }

          @Override
          public int priority() {
            return score;
          }
        };
    tracer.getInterceptors().add(newInterceptor);

    List<TraceInterceptor> sorted = Arrays.asList(tracer.getInterceptors().interceptors());
    assertEquals(2, sorted.size());
    if (reverse) {
      assertEquals(existingInterceptor, sorted.get(0));
      assertEquals(newInterceptor, sorted.get(1));
    } else {
      assertEquals(newInterceptor, sorted.get(0));
      assertEquals(existingInterceptor, sorted.get(1));
    }
  }

  @TableTest({
    "scenario       | deltaPriority | expectedSize",
    "below priority | -1            | 2           ",
    "same priority  | 0             | 1           ",
    "above priority | 1             | 2           "
  })
  void interceptorCanDiscardTrace(int deltaPriority, int expectedSize)
      throws InterruptedException, TimeoutException {
    int score = TestInterceptor.priority + deltaPriority;
    AtomicBoolean called = new AtomicBoolean(false);
    CountDownLatch latch = new CountDownLatch(1);
    tracer
        .getInterceptors()
        .add(
            new TraceInterceptor() {
              @Override
              public Collection<? extends MutableSpan> onTraceComplete(
                  Collection<? extends MutableSpan> trace) {
                called.set(true);
                latch.countDown();
                return emptyList();
              }

              @Override
              public int priority() {
                return score;
              }
            });

    tracer.buildSpan("datadog", "test " + score).start().finish();
    if (score == TestInterceptor.priority) {
      writer.waitForTraces(1);
    } else {
      latch.await(5, TimeUnit.SECONDS);
    }

    TraceInterceptor[] interceptors = tracer.getInterceptors().interceptors();
    assertEquals(expectedSize, interceptors.length);
    assertEquals(score != TestInterceptor.priority, called.get());
    assertEquals(score != TestInterceptor.priority, writer.isEmpty());
  }

  @Test
  void interceptorCanModifySpan() throws InterruptedException, TimeoutException {
    tracer
        .getInterceptors()
        .add(
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

    tracer.buildSpan("datadog", "test").start().finish();
    writer.waitForTraces(1);

    List<DDSpan> trace = writer.firstTrace();
    assertEquals(1, trace.size());

    DDSpan span = trace.get(0);
    assertEquals("modifiedON-test", span.spanContext().getOperationName().toString());
    assertTrue(span.getServiceName().startsWith("modifiedSN-"));
    assertEquals("modifiedRN-modifiedON-test", span.getResourceName().toString());
    assertEquals("modifiedST-null", span.getSpanType());
    assertTrue(span.spanContext().getErrorFlag());

    TagMap tags = span.spanContext().getTags();
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
  void robustWhenInterceptorReturnsNull() {
    tracer
        .getInterceptors()
        .add(
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
    assertTrue(Arrays.asList(tracer.getInterceptors().interceptors()).contains(interceptor));
  }
}

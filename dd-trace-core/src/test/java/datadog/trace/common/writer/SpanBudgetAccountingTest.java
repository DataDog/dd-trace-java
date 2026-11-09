package datadog.trace.common.writer;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static datadog.trace.common.writer.ddagent.Prioritization.FAST_LANE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.common.sampling.SingleSpanSampler;
import datadog.trace.common.writer.ddagent.SpanBudget;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.DDSpan;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Spans are added to and released from the budget on different threads along many paths. A release
 * that is missed or doubled skews every later decision and never corrects, so this drives every
 * path at once and checks that a drained writer holds exactly zero spans.
 */
class SpanBudgetAccountingTest extends DDJavaSpecification {

  private static final int[] PRIORITIES = {UNSET, SAMPLER_DROP, SAMPLER_KEEP, USER_DROP, USER_KEEP};

  @ParameterizedTest(name = "single span sampling: {0}")
  @ValueSource(booleans = {false, true})
  void drainedWriterHoldsNoSpans(boolean singleSpanSampling) throws Exception {
    AtomicLong serializerFailures = new AtomicLong();
    PayloadDispatcherImpl dispatcher = mock(PayloadDispatcherImpl.class);
    doAnswer(
            inv -> {
              ThreadLocalRandom random = ThreadLocalRandom.current();
              int roll = random.nextInt(100);
              if (roll < 5) {
                serializerFailures.incrementAndGet();
                throw new IllegalStateException("serialization failure");
              }
              if (roll < 10) {
                // a slow send, so the queues back up and the budget sheds
                Thread.sleep(2);
              }
              return null;
            })
        .when(dispatcher)
        .addTrace(any());

    SingleSpanSampler sampler = null;
    if (singleSpanSampling) {
      // not a mock: it is called per span, and a mock would record every call
      sampler =
          new SingleSpanSampler() {
            @Override
            public <T extends CoreSpan<T>> boolean setSamplingPriority(T span) {
              return ThreadLocalRandom.current().nextBoolean();
            }
          };
    }

    AtomicBoolean droppingPolicy = new AtomicBoolean();
    DDSpan root = mock(DDSpan.class);
    DDSpan forceKeptRoot = mock(DDSpan.class);
    when(forceKeptRoot.isForceKeep()).thenReturn(true);
    DDSpan span = mock(DDSpan.class);

    SpanBudget budget = SpanBudget.of(2_000);
    AtomicLong admitted = new AtomicLong();
    AtomicLong refused = new AtomicLong();
    try (TraceProcessingWorker worker =
        new TraceProcessingWorker(
            // a small trace capacity, so queue-full drops happen after admission too
            16,
            budget,
            mock(HealthMetrics.class),
            dispatcher,
            droppingPolicy::get,
            FAST_LANE,
            1,
            TimeUnit.MILLISECONDS,
            sampler)) {
      worker.start();

      int threads = 8;
      int publishesPerThread = 5_000;
      ExecutorService executor = Executors.newFixedThreadPool(threads);
      CountDownLatch start = new CountDownLatch(1);
      Future<?>[] futures = new Future<?>[threads];
      for (int t = 0; t < threads; t++) {
        futures[t] =
            executor.submit(() -> {
              start.await();
              ThreadLocalRandom random = ThreadLocalRandom.current();
              for (int i = 0; i < publishesPerThread; i++) {
                if (random.nextInt(500) == 0) {
                  droppingPolicy.set(!droppingPolicy.get());
                }
                // mostly small traces, some partial-flush sized ones
                int spans =
                    random.nextInt(20) == 0 ? 500 + random.nextInt(1000) : 1 + random.nextInt(20);
                List<DDSpan> trace = Collections.nCopies(spans, span);
                DDSpan traceRoot = random.nextInt(50) == 0 ? forceKeptRoot : root;
                int priority = PRIORITIES[random.nextInt(PRIORITIES.length)];
                switch (worker.publish(traceRoot, priority, trace)) {
                  case ENQUEUED_FOR_SERIALIZATION:
                  case ENQUEUED_FOR_SINGLE_SPAN_SAMPLING:
                    admitted.incrementAndGet();
                    break;
                  default:
                    refused.incrementAndGet();
                }
              }
              return null;
            });
      }
      start.countDown();
      for (Future<?> future : futures) {
        future.get(60, TimeUnit.SECONDS);
      }
      executor.shutdown();

      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (budget.used() != 0 && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }

      // the run must have exercised the paths it is meant to cover
      assertTrue(admitted.get() > 0, "nothing was admitted");
      assertTrue(refused.get() > 0, "nothing was refused");
      assertTrue(serializerFailures.get() > 0, "no serializer failures");
      assertEquals(0, budget.used());
    }
  }
}

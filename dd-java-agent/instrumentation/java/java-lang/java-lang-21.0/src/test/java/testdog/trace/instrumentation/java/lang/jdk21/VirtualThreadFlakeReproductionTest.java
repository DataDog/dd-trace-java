package testdog.trace.instrumentation.java.lang.jdk21;

import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.IdGenerationStrategy;
import datadog.trace.api.Trace;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Deterministic reproduction of the nested virtual thread trace race condition.
 *
 * <p>Uses strictTraceWrites=false (matching production behavior) to enable the
 * DelayingPendingTraceBuffer. With this buffer, traces enqueued as ROOT_BUFFERED are written after
 * 500ms of inactivity. When child virtual threads are delayed (by pinning carrier threads), the
 * buffer writes a partial trace containing only the parent span.
 */
public class VirtualThreadFlakeReproductionTest extends AbstractInstrumentationTest {

  @DisplayName("reproduce nested VT trace race with non-strict writes")
  @Test
  void testNestedVirtualThreadsFlakeReproduction() throws InterruptedException {
    this.tracer.close();
    ListWriter nonStrictWriter = new ListWriter();
    CoreTracer nonStrictTracer =
        CoreTracer.builder()
            .writer(nonStrictWriter)
            .idGenerationStrategy(IdGenerationStrategy.fromName("SEQUENTIAL"))
            .strictTraceWrites(false)
            .build();
    TracerInstaller.forceInstallGlobalTracer(nonStrictTracer);

    try {
      Thread.Builder.OfVirtual threadBuilder = Thread.ofVirtual();
      int parallelism = Runtime.getRuntime().availableProcessors();
      CountDownLatch startedLatch = new CountDownLatch(parallelism);
      AtomicBoolean stopBlockers = new AtomicBoolean(false);

      // Pin all carrier threads to prevent child VTs from mounting
      for (int i = 0; i < parallelism; i++) {
        final Object pinLock = new Object();
        Thread.startVirtualThread(
            () -> {
              synchronized (pinLock) {
                startedLatch.countDown();
                while (!stopBlockers.get()) {
                  Thread.onSpinWait();
                }
              }
            });
      }
      startedLatch.await();

      // Run the nested virtual thread trace — parent returns immediately,
      // child VTs are queued but cannot mount (carrier threads are pinned)
      new Runnable() {
        @Trace(operationName = "parent")
        @Override
        public void run() {
          threadBuilder.start(
              new Runnable() {
                @Trace(operationName = "child")
                @Override
                public void run() {
                  threadBuilder.start(
                      new Runnable() {
                        @Trace(operationName = "great-child")
                        @Override
                        public void run() {
                          threadBuilder.start(
                              new Runnable() {
                                @Trace(operationName = "great-great-child")
                                @Override
                                public void run() {
                                  System.out.println("complete");
                                }
                              });
                        }
                      });
                }
              });
        }
      }.run();

      // Hold pin for 800ms — past the buffer's 500ms SEND_DELAY_NS threshold.
      // The buffer writes a partial trace with only the parent span.
      Thread.sleep(800);

      // Release pins so child VTs can execute
      stopBlockers.set(true);
      Thread.sleep(3000);

      // Verify the race: multiple trace writes (partial parent + late children)
      assertTrue(
          nonStrictWriter.size() > 1,
          "Expected multiple trace writes (partial + late arrivals), got "
              + nonStrictWriter.size());
      List<DDSpan> firstTrace = nonStrictWriter.get(0);
      assertTrue(
          firstTrace.size() < 4,
          "First trace should be partial (< 4 spans), got " + firstTrace.size());
    } finally {
      nonStrictTracer.close();
      nonStrictWriter.close();
      CoreTracer originalTracer =
          CoreTracer.builder()
              .writer(this.writer)
              .idGenerationStrategy(IdGenerationStrategy.fromName("SEQUENTIAL"))
              .strictTraceWrites(true)
              .build();
      TracerInstaller.forceInstallGlobalTracer(originalTracer);
      this.tracer = originalTracer;
    }
  }
}

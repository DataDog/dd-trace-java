package com.datadog.featureflag;

import static java.util.Collections.emptyMap;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.communication.BackendApi;
import datadog.trace.api.Config;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.exposure.Allocation;
import datadog.trace.api.featureflag.exposure.ExposureEvent;
import datadog.trace.api.featureflag.exposure.Flag;
import datadog.trace.api.featureflag.exposure.Subject;
import datadog.trace.api.featureflag.exposure.Variant;
import datadog.trace.api.featureflag.flagevaluation.FlagEvalEvent;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class FeatureFlagShutdownBudgetTest {
  private final CountDownLatch bothPostsStarted = new CountDownLatch(2);
  private final CountDownLatch releasePosts = new CountDownLatch(1);
  private Thread stopping;

  @AfterEach
  void cleanup() throws Exception {
    releasePosts.countDown();
    if (stopping != null) {
      stopping.join(5000);
      assertFalse(stopping.isAlive());
    }
    FeatureFlaggingSystem.stop();
    FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(true);
  }

  @Test
  void stopSignalsBothWritersBeforeWaitingForEither() throws Exception {
    startWriters();
    stopping = new Thread(FeatureFlaggingSystem::stop);
    stopping.start();
    assertTrue(
        bothPostsStarted.await(500, MILLISECONDS),
        "Exposure shutdown was delayed by the evaluation writer's wait");
  }

  @Test
  void stopUsesOneBoundedBudgetForBothBlockedPosts() throws Exception {
    startWriters();
    final long started = System.nanoTime();
    FeatureFlaggingSystem.stop();
    final long elapsed = NANOSECONDS.toMillis(System.nanoTime() - started);
    assertTrue(elapsed < 1300, "Two writer waits consumed " + elapsed + " ms");
  }

  private void startWriters() throws Exception {
    final BackendApi api = mock(BackendApi.class);
    when(api.post(any(), any(), any(), any(), anyBoolean()))
        .thenAnswer(
            invocation -> {
              bothPostsStarted.countDown();
              boolean interrupted = false;
              while (releasePosts.getCount() != 0) {
                try {
                  assertTrue(releasePosts.await(12, SECONDS));
                } catch (InterruptedException ignored) {
                  interrupted = true;
                }
              }
              if (interrupted) {
                Thread.currentThread().interrupt();
              }
              return null;
            });
    final ExposureWriterImpl exposures =
        new ExposureWriterImpl(16, Long.MAX_VALUE, NANOSECONDS, () -> api, Config.get(), 5000);
    final FlagEvaluationWriterImpl evaluations =
        new FlagEvaluationWriterImpl(16, Long.MAX_VALUE, NANOSECONDS, () -> api, Config.get());
    setWriter("EXPOSURE_WRITER", exposures);
    setWriter("FLAG_EVAL_WRITER", evaluations);
    FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(true);
    exposures.init();
    evaluations.start();
    exposures.accept(
        new ExposureEvent(
            1,
            new Allocation("alloc"),
            new Flag("flag"),
            new Variant("on"),
            new Subject("user", emptyMap())));
    evaluations.enqueue(new FlagEvalEvent("flag", "on", "alloc", "user", 1, emptyMap()));
  }

  private static void setWriter(String name, Object writer) throws Exception {
    final Field field = FeatureFlaggingSystem.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(null, writer);
  }
}

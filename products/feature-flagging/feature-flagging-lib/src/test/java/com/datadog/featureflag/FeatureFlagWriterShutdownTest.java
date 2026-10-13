package com.datadog.featureflag;

import static com.datadog.featureflag.FlagEvaluationTestSupport.cfg;
import static com.datadog.featureflag.FlagEvaluationTestSupport.simpleEvent;
import static java.util.Collections.emptyMap;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.communication.BackendApi;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.exposure.Allocation;
import datadog.trace.api.featureflag.exposure.ExposureEvent;
import datadog.trace.api.featureflag.exposure.Flag;
import datadog.trace.api.featureflag.exposure.Subject;
import datadog.trace.api.featureflag.exposure.Variant;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FeatureFlagWriterShutdownTest {
  @Test
  void awaitThreadDoesNotJoinItself() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(2),
        () ->
            FeatureFlagEventWriter.awaitThread(
                Thread.currentThread(), System.nanoTime() + SECONDS.toNanos(30)));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void awaitThreadStopsWaitingWithoutInterruptingWorker(boolean interruptedCaller)
      throws Exception {
    final CountDownLatch releaseWorker = new CountDownLatch(1);
    final FutureTask<Boolean> task =
        new FutureTask<>(
            () -> {
              releaseWorker.await();
              return Thread.currentThread().isInterrupted();
            });
    final Thread worker = new Thread(task);
    worker.start();
    try {
      assertTimeoutPreemptively(
          Duration.ofSeconds(2),
          () -> {
            try {
              if (interruptedCaller) {
                Thread.currentThread().interrupt();
              }
              final long deadline =
                  System.nanoTime() + (interruptedCaller ? SECONDS.toNanos(30) : -1);
              FeatureFlagEventWriter.awaitThread(worker, deadline);
              assertEquals(interruptedCaller, Thread.currentThread().isInterrupted());
            } finally {
              Thread.interrupted();
            }
          });
      assertTrue(worker.isAlive(), "Stopping the wait must not terminate the worker");
    } finally {
      releaseWorker.countDown();
      worker.join(5000);
    }
    assertFalse(worker.isAlive());
    assertFalse(task.get(5, SECONDS), "Waiting must not interrupt the worker");
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void closeDoesNotInterruptAnInFlightDelivery(boolean exposures) throws Exception {
    final CountDownLatch postStarted = new CountDownLatch(1);
    final CountDownLatch releasePost = new CountDownLatch(1);
    final CountDownLatch postFinished = new CountDownLatch(1);
    final CountDownLatch interrupted = new CountDownLatch(1);
    final BackendApi api = mock(BackendApi.class);
    when(api.post(any(), any(), any(), any(), anyBoolean()))
        .thenAnswer(
            invocation -> {
              postStarted.countDown();
              try {
                assertTrue(releasePost.await(5, SECONDS));
              } catch (InterruptedException failure) {
                interrupted.countDown();
              } finally {
                postFinished.countDown();
              }
              return null;
            });
    final AutoCloseable writer;
    FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(true);
    if (exposures) {
      final ExposureWriterImpl exposureWriter =
          new ExposureWriterImpl(16, 0, MILLISECONDS, () -> api, cfg(), 800);
      writer = exposureWriter;
      exposureWriter.init();
      exposureWriter.accept(
          new ExposureEvent(
              1,
              new Allocation("alloc"),
              new Flag("flag"),
              new Variant("on"),
              new Subject("user", emptyMap())));
    } else {
      final FlagEvaluationWriterImpl evaluationWriter =
          new FlagEvaluationWriterImpl(16, 0, MILLISECONDS, () -> api, cfg());
      writer = evaluationWriter;
      evaluationWriter.startForTest();
      evaluationWriter.enqueue(simpleEvent("flag", "on"));
    }
    final FutureTask<Void> close =
        new FutureTask<>(
            () -> {
              writer.close();
              return null;
            });
    final Thread closer = new Thread(close);
    try {
      assertTrue(postStarted.await(5, SECONDS));
      closer.start();
      close.get(5, SECONDS);
      assertEquals(1, releasePost.getCount(), "Shutdown must finish before delivery is released");
      assertEquals(1, postFinished.getCount(), "The active delivery must still be pending");
      assertEquals(1, interrupted.getCount(), "Shutdown interrupted the active POST");
    } finally {
      releasePost.countDown();
      closer.join(5000);
      writer.close();
      FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(true);
    }
    assertFalse(closer.isAlive());
  }
}

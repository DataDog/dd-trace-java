package com.datadog.featureflag;

import static com.datadog.featureflag.FlagEvaluationTestSupport.cfg;
import static com.datadog.featureflag.FlagEvaluationTestSupport.simpleEvent;
import static java.util.Collections.emptyMap;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FeatureFlagWriterShutdownTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void closeDoesNotInterruptAnInFlightDelivery(boolean exposures) throws Exception {
    final CountDownLatch postStarted = new CountDownLatch(1);
    final CountDownLatch releasePost = new CountDownLatch(1);
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
    final Thread closer =
        new Thread(
            () -> {
              try {
                writer.close();
              } catch (Exception failure) {
                throw new AssertionError(failure);
              }
            });
    try {
      assertTrue(postStarted.await(5, SECONDS));
      closer.start();
      assertFalse(interrupted.await(200, MILLISECONDS), "Shutdown interrupted the active POST");
    } finally {
      releasePost.countDown();
      closer.join(5000);
      writer.close();
      FeatureFlaggingGateway.setFlagEvaluationEnqueueEnabled(true);
    }
    assertFalse(closer.isAlive());
  }
}

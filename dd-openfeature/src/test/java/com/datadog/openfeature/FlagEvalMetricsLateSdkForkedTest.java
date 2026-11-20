package com.datadog.openfeature;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongCounterBuilder;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterBuilder;
import io.opentelemetry.api.metrics.MeterProvider;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class FlagEvalMetricsLateSdkForkedTest {
  @Test
  void evaluationBeforeSdkRegistrationDoesNotClaimTheGlobalProvider() {
    GlobalOpenTelemetry.resetForTest();
    final FlagEvalMetrics metrics = new FlagEvalMetrics(0);
    metrics.record("before", "on", "STATIC", null, null);
    assertFalse(GlobalOpenTelemetry.isSet());

    final OpenTelemetry telemetry = mock(OpenTelemetry.class);
    final MeterProvider provider = mock(MeterProvider.class);
    final MeterBuilder meterBuilder = mock(MeterBuilder.class);
    final Meter meter = mock(Meter.class);
    final LongCounterBuilder counterBuilder = mock(LongCounterBuilder.class);
    final LongCounter counter = mock(LongCounter.class);
    when(telemetry.getMeterProvider()).thenReturn(provider);
    when(provider.meterBuilder("ddtrace.openfeature")).thenReturn(meterBuilder);
    when(meterBuilder.build()).thenReturn(meter);
    when(meter.counterBuilder("feature_flag.evaluations")).thenReturn(counterBuilder);
    when(counterBuilder.setUnit("{evaluation}")).thenReturn(counterBuilder);
    when(counterBuilder.setDescription("Number of feature flag evaluations"))
        .thenReturn(counterBuilder);
    when(counterBuilder.build()).thenReturn(counter);
    try {
      assertDoesNotThrow(() -> GlobalOpenTelemetry.set(telemetry));
      metrics.record("after", "on", "STATIC", null, null);
      verify(counter).add(eq(1L), any(Attributes.class));
    } finally {
      metrics.close();
      GlobalOpenTelemetry.resetForTest();
    }
  }

  @Test
  void evaluationsRacingCounterInitializationAreStillRecorded() throws Exception {
    GlobalOpenTelemetry.resetForTest();
    final CountDownLatch building = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final OpenTelemetry telemetry = mock(OpenTelemetry.class);
    final MeterProvider provider = mock(MeterProvider.class);
    final MeterBuilder meterBuilder = mock(MeterBuilder.class);
    final Meter meter = mock(Meter.class);
    final LongCounterBuilder counterBuilder = mock(LongCounterBuilder.class);
    final LongCounter counter = mock(LongCounter.class);
    when(telemetry.getMeterProvider()).thenReturn(provider);
    when(provider.meterBuilder("ddtrace.openfeature"))
        .thenAnswer(invocation -> {
          building.countDown();
          release.await(); // hold the first evaluation inside counter initialization
          return meterBuilder;
        });
    when(meterBuilder.build()).thenReturn(meter);
    when(meter.counterBuilder("feature_flag.evaluations")).thenReturn(counterBuilder);
    when(counterBuilder.setUnit("{evaluation}")).thenReturn(counterBuilder);
    when(counterBuilder.setDescription("Number of feature flag evaluations"))
        .thenReturn(counterBuilder);
    when(counterBuilder.build()).thenReturn(counter);
    GlobalOpenTelemetry.set(telemetry);
    // A long recheck interval: a throttled evaluation would be dropped, not retried.
    final FlagEvalMetrics metrics = new FlagEvalMetrics(TimeUnit.DAYS.toNanos(1));
    try {
      final Thread first = new Thread(() -> metrics.record("first", "on", "STATIC", null, null));
      first.start();
      assertTrue(building.await(10, TimeUnit.SECONDS));
      final Thread second = new Thread(() -> metrics.record("second", "on", "STATIC", null, null));
      second.start();
      // Wait until the second evaluation is blocked behind the first's initialization.
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (second.getState() != Thread.State.BLOCKED && second.isAlive()) {
        if (System.nanoTime() > deadline) {
          throw new AssertionError("second evaluation never reached the initializer");
        }
        Thread.sleep(1);
      }
      release.countDown();
      first.join(10_000);
      second.join(10_000);
      verify(counter, times(2)).add(eq(1L), any(Attributes.class));
    } finally {
      release.countDown();
      metrics.close();
      GlobalOpenTelemetry.resetForTest();
    }
  }
}

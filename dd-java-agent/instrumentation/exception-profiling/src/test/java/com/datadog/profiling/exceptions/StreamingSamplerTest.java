package com.datadog.profiling.exceptions;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class StreamingSamplerTest {
  private static final class TimestampProvider implements Supplier<Long> {
    private final Random rnd = new Random();
    private final AtomicLong ts = new AtomicLong(0L);
    private final long step;

    TimestampProvider(long totalDuration, long expectedEvents) {
      step = Math.round(totalDuration / (double) expectedEvents);
    }

    @Override
    public Long get() {
      double diff = Math.max(step + ((rnd.nextGaussian() * step) * 0.5d), 0);
      return ts.getAndAdd(Math.round(diff));
    }
  }

  @ParameterizedTest(name = "{index}")
  @MethodSource("samplerParams")
  void sample(
      int threadCnt,
      long windowDurationMs,
      int samplesPerWindow,
      long totalDurationMs,
      long totalEvents)
      throws Exception {

    TimestampProvider tsProvider =
        new TimestampProvider(
            TimeUnit.NANOSECONDS.convert(totalDurationMs, TimeUnit.MILLISECONDS), totalEvents);
    final StreamingSampler instance =
        new StreamingSampler(
            Duration.of(windowDurationMs, ChronoUnit.MILLIS), samplesPerWindow, 5, tsProvider);

    final AtomicInteger allCnt = new AtomicInteger(0);
    Thread[] threads = new Thread[threadCnt];

    long expectedSamples =
        Math.round((totalDurationMs / (double) windowDurationMs) * samplesPerWindow);
    final long eventsPerThread = totalEvents / threadCnt;
    long startTs = tsProvider.get();
    System.out.println(
        "===> threads: "
            + threadCnt
            + ", events per thread: "
            + eventsPerThread
            + ", total events: "
            + totalEvents
            + ", total duration[ms]: "
            + totalDurationMs
            + ", window duration[ms]: "
            + windowDurationMs
            + ", samples per window: "
            + samplesPerWindow
            + ", expected samples: "
            + expectedSamples);
    for (int j = 0; j < threads.length; j++) {
      threads[j] =
          new Thread(
              () -> {
                int cnt = 0;
                for (long i = 0; i <= eventsPerThread; i++) {
                  if (instance.sample()) {
                    cnt += 1;
                  }
                }
                allCnt.addAndGet(cnt);
              });
      threads[j].start();
    }
    for (Thread thread : threads) {
      thread.join();
    }
    long allSamples = allCnt.get();
    long realDuration =
        TimeUnit.MILLISECONDS.convert(tsProvider.get() - startTs, TimeUnit.NANOSECONDS);

    double allSamplesRate = allSamples / (double) realDuration;
    double expectedSamplesRate = expectedSamples / (double) totalDurationMs;

    double dev = allSamplesRate - expectedSamplesRate;
    double ratio = Math.abs(dev / expectedSamplesRate);

    System.out.println(
        "===> sample rates: all = "
            + allSamplesRate
            + " ~ expected "
            + expectedSamplesRate
            + ", relative diff: "
            + Math.round((ratio * 100))
            + "%");
    System.out.println();
    double errorMargin = 0.1d;
    Assertions.assertTrue(
        ratio <= errorMargin,
        allSamplesRate
            + " is outside of its limits: <"
            + expectedSamplesRate * (1 - errorMargin)
            + ", "
            + expectedSamplesRate * (1 + errorMargin)
            + ">");
  }

  private static Stream<Arguments> samplerParams() {
    if (false) {
      return Stream.of(Arguments.of(1, 10, 5, 30, 511));
    } else {
      List<Arguments> args = new ArrayList<>();
      for (int threadCnt = 1; threadCnt <= 64; threadCnt *= 4) {
        for (int samples = 10; samples <= 40; samples *= 2) {
          // simulate 10 seconds window for a recording lasting for 30 seconds and containing
          // unsampled 25k exception events
          args.add(Arguments.of(threadCnt, 10_000, samples, 30_000, 25_000));
          // simulate 10 seconds window for a recording lasting for 120 seconds and containing
          // unsampled 300k exception events
          args.add(Arguments.of(threadCnt, 10_000, samples, 120_000, 300_000));
          // simulate 10 seconds window for a recording lasting for 600 seconds and containing
          // unsampled 1M exception events
          args.add(Arguments.of(threadCnt, 10_000, samples, 600_000, 1_000_000));
        }
      }
      return args.stream();
    }
  }
}

package datadog.trace.bootstrap;

import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class AdaptiveIntervalSamplerTest {
  @ParameterizedTest
  @MethodSource("samplerParams")
  public void sampleConcurrent(int threadCnt, int minInterval, long timeWindowMs, long maxSamples)
      throws Exception {
    AdaptiveIntervalSampler instance =
        new AdaptiveIntervalSampler("test", minInterval, timeWindowMs, maxSamples);

    AtomicLong allCnt = new AtomicLong(0);
    long latency = 1_000L;
    Thread[] threads = new Thread[threadCnt];
    long hits = (timeWindowMs * (1_000_000L / latency));
    for (int j = 0; j < threads.length; j++) {
      threads[j] =
          new Thread(
              () -> {
                Random rnd = new Random();
                int cnt = 0;
                for (long i = 0; i < hits / threads.length; i++) {
                  cnt += instance.sample() ? 1 : 0;
                  LockSupport.parkNanos(rnd.nextInt((int) latency) + 100);
                }

                allCnt.addAndGet(cnt);
              });
      threads[j].start();
    }
    for (int j = 0; j < threads.length; j++) {
      threads[j].join();
    }
    System.err.println("=== " + allCnt.get());
    Assertions.assertTrue(allCnt.get() <= maxSamples * 2);
  }

  private static Stream<Arguments> samplerParams() {
    return Stream.of(
        Arguments.of(1, 1, 100L, 500L),
        Arguments.of(1, 1, 100L, 2000L),
        Arguments.of(1, 10, 100L, 500L),
        Arguments.of(1, 10, 100L, 2000L),
        Arguments.of(1, 100, 100L, 500L),
        Arguments.of(1, 100, 100L, 2000L),
        Arguments.of(2, 1, 100L, 500L),
        Arguments.of(2, 1, 100L, 2000L),
        Arguments.of(2, 10, 100L, 500L),
        Arguments.of(2, 10, 100L, 2000L),
        Arguments.of(2, 100, 100L, 500L),
        Arguments.of(2, 100, 100L, 2000L),
        Arguments.of(8, 1, 100L, 500L),
        Arguments.of(8, 1, 100L, 2000L),
        Arguments.of(8, 10, 100L, 500L),
        Arguments.of(8, 10, 100L, 2000L),
        Arguments.of(8, 100, 100L, 500L),
        Arguments.of(8, 100, 100L, 2000L),
        Arguments.of(64, 1, 100L, 500L),
        Arguments.of(64, 1, 100L, 2000L),
        Arguments.of(64, 10, 100L, 500L),
        Arguments.of(64, 10, 100L, 2000L),
        Arguments.of(64, 100, 100L, 500L),
        Arguments.of(64, 100, 100L, 2000L));
  }
}

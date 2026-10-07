package datadog.trace.instrumentation.spray;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RequestCompletionTest {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void finishesOnceAfterBothEvents(boolean responseFirst) {
    AgentSpan span = mock(AgentSpan.class);
    AtomicInteger completion = new AtomicInteger();
    Runnable first =
        responseFirst
            ? () -> SprayHelper.responseComplete(span, completion)
            : () -> SprayHelper.scopeClosed(span, completion);
    Runnable second =
        responseFirst
            ? () -> SprayHelper.scopeClosed(span, completion)
            : () -> SprayHelper.responseComplete(span, completion);

    first.run();
    first.run();
    verify(span, never()).finish();

    second.run();
    first.run();
    second.run();
    verify(span).finish();
  }

  @Test
  void finishesOnceWhenResponseAndScopeClosureRace() throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < 100; i++) {
        AgentSpan span = mock(AgentSpan.class);
        AtomicInteger completion = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> response =
            executor.submit(
                () -> {
                  ready.countDown();
                  assertTrue(start.await(5, TimeUnit.SECONDS));
                  SprayHelper.responseComplete(span, completion);
                  return null;
                });
        Future<?> route =
            executor.submit(
                () -> {
                  ready.countDown();
                  assertTrue(start.await(5, TimeUnit.SECONDS));
                  SprayHelper.scopeClosed(span, completion);
                  return null;
                });
        try {
          assertTrue(ready.await(5, TimeUnit.SECONDS));
        } finally {
          start.countDown();
        }
        response.get(5, TimeUnit.SECONDS);
        route.get(5, TimeUnit.SECONDS);
        verify(span).finish();
      }
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }
}

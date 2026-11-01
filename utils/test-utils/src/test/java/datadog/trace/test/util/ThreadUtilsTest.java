package datadog.trace.test.util;

import static datadog.trace.test.util.ThreadUtils.runConcurrently;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ThreadUtilsTest {

  @ParameterizedTest
  @ValueSource(ints = {1, 4})
  void runsEveryInvocation(int concurrency) throws Throwable {
    AtomicInteger count = new AtomicInteger();

    assertTrue(runConcurrently(concurrency, 11, count::incrementAndGet));

    assertEquals(11, count.get());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 4})
  void propagatesCheckedException(int concurrency) {
    IOException failure = new IOException("action failed");

    IOException actual =
        assertThrows(
            IOException.class,
            () ->
                runConcurrently(
                    concurrency,
                    11,
                    () -> {
                      throw failure;
                    }));

    assertSame(failure, actual);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 4})
  void propagatesRuntimeException(int concurrency) {
    IllegalStateException failure = new IllegalStateException("action failed");

    IllegalStateException actual =
        assertThrows(
            IllegalStateException.class,
            () ->
                runConcurrently(
                    concurrency,
                    11,
                    () -> {
                      throw failure;
                    }));

    assertSame(failure, actual);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 4})
  void propagatesError(int concurrency) {
    AssertionError failure = new AssertionError("action failed");

    AssertionError actual =
        assertThrows(
            AssertionError.class,
            () ->
                runConcurrently(
                    concurrency,
                    11,
                    () -> {
                      throw failure;
                    }));

    assertSame(failure, actual);
  }
}

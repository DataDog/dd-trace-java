package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

class OutputThreadsTest {

  @Test
  void closeOnlyJoinsEnumeratedThreads() {
    ThreadGroup shrinkingThreadGroup =
        new ThreadGroup("shrinking-smoke-output") {
          @Override
          public int activeCount() {
            return 1;
          }

          @Override
          public int enumerate(Thread[] threads) {
            return 0;
          }
        };

    assertDoesNotThrow(() -> new OutputThreads(shrinkingThreadGroup).close());
  }
}

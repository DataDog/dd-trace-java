package datadog.smoketest.concurrent;

import org.junit.jupiter.api.Test;

class SimpleRunnableTest extends SimpleTest {
  @Override
  protected String testCaseName() {
    return "SimpleRunnableTask";
  }

  @Test
  void testSimpleRunnable() throws Exception {
    receivedCorrectTrace();
  }
}

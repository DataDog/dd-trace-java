package datadog.smoketest.concurrent;

import org.junit.jupiter.api.Test;

class SimpleCallableTest extends SimpleTest {
  @Override
  protected String testCaseName() {
    return "SimpleCallableTask";
  }

  @Test
  void testSimpleCallable() throws Exception {
    receivedCorrectTrace();
  }
}

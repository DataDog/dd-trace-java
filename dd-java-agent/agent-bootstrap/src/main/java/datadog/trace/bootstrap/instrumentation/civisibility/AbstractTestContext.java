package datadog.trace.bootstrap.instrumentation.civisibility;

abstract class AbstractTestContext implements TestContext {

  private String status = TestEventsHandler.TEST_SKIP;

  @Override
  public synchronized void reportChildStatus(String childStatus) {
    switch (childStatus) {
      case TestEventsHandler.TEST_PASS:
        if (TestEventsHandler.TEST_SKIP.equals(status)) {
          status = TestEventsHandler.TEST_PASS;
        }
        break;
      case TestEventsHandler.TEST_FAIL:
        status = TestEventsHandler.TEST_FAIL;
        break;
      default:
        break;
    }
  }

  @Override
  public synchronized String getStatus() {
    return status;
  }
}

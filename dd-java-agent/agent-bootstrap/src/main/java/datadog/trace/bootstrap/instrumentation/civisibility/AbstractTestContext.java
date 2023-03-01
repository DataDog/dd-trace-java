package datadog.trace.bootstrap.instrumentation.civisibility;

abstract class AbstractTestContext implements TestContext {

  private String status = Constants.TEST_SKIP;

  @Override
  public synchronized void reportChildStatus(String childStatus) {
    switch (childStatus) {
      case Constants.TEST_PASS:
        if (Constants.TEST_SKIP.equals(status)) {
          status = Constants.TEST_PASS;
        }
        break;
      case Constants.TEST_FAIL:
        status = Constants.TEST_FAIL;
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

package datadog.trace.civisibility.context;

import datadog.trace.api.civisibility.CIConstants;

abstract class AbstractTestContext implements TestContext {

  private String status = CIConstants.TEST_SKIP;

  @Override
  public synchronized void reportChildStatus(String childStatus) {
    switch (childStatus) {
      case CIConstants.TEST_PASS:
        if (CIConstants.TEST_SKIP.equals(status)) {
          status = CIConstants.TEST_PASS;
        }
        break;
      case CIConstants.TEST_FAIL:
        status = CIConstants.TEST_FAIL;
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

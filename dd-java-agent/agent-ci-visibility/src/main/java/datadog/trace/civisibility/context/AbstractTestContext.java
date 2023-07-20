package datadog.trace.civisibility.context;

import datadog.trace.api.civisibility.CIConstants;

abstract class AbstractTestContext implements TestContext {

  private String status;

  @Override
  public synchronized void reportChildStatus(String childStatus) {
    if (childStatus == null) {
      return;
    }
    switch (childStatus) {
      case CIConstants.TEST_PASS:
        if (status == null || CIConstants.TEST_SKIP.equals(status)) {
          status = CIConstants.TEST_PASS;
        }
        break;
      case CIConstants.TEST_FAIL:
        status = CIConstants.TEST_FAIL;
        break;
      case CIConstants.TEST_SKIP:
        if (status == null) {
          status = CIConstants.TEST_SKIP;
        }
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

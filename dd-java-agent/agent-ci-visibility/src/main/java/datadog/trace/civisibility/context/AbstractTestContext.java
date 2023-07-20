package datadog.trace.civisibility.context;

import datadog.trace.api.civisibility.CIConstants;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

abstract class AbstractTestContext implements TestContext {

  private final Map<String, Object> childTags = new ConcurrentHashMap<>();
  private String status;

  @Override
  public synchronized void reportChildStatus(String childStatus) {
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

  @Override
  public void reportChildTag(String key, Object value) {
    if (value != null) {
      childTags.put(key, value);
    }
  }

  @Override
  public Object getChildTag(String key) {
    return childTags.get(key);
  }
}

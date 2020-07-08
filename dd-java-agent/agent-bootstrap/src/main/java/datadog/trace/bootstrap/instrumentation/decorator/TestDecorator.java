package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.bootstrap.instrumentation.api.Tags;

public abstract class TestDecorator extends BaseDecorator {
  protected static final String TEST_PASS = "PASS";
  protected static final String TEST_FAIL = "FAIL";
  protected static final String TEST_SKIP = "SKIP";

  protected String spanKind() {
    return Tags.SPAN_KIND_TEST;
  }
}

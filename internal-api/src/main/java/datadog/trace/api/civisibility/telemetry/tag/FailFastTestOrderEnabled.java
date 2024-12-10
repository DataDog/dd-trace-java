package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

public enum FailFastTestOrderEnabled implements TagValue {
  TRUE;

  @Override
  public String asString() {
    return "fail_fast_test_order_enabled:true";
  }
}

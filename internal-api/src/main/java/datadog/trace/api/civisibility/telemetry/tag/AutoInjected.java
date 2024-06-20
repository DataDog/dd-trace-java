package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

/** Whether the tracer was injected using CI-provider-specific auto-instrumentation. */
public enum AutoInjected implements TagValue {
  TRUE;

  @Override
  public String asString() {
    return "auto_injected:true";
  }
}

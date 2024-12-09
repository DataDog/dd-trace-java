package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;

public enum AgentlessLogSubmissionEnabled implements TagValue {
  TRUE;

  @Override
  public String asString() {
    return "agentless_log_submission_enabled:true";
  }
}

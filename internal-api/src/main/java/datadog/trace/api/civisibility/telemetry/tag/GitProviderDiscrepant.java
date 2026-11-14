package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;
import java.util.Locale;

public enum GitProviderDiscrepant implements TagValue {
  USER_SUPPLIED,
  CI_PROVIDER,
  LOCAL_GIT,
  GIT_CLIENT,
  EMBEDDED;

  @Override
  public String asString() {
    return "discrepant_provider:" + name().toLowerCase(Locale.ROOT);
  }
}

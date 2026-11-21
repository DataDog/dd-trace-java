package datadog.trace.api.civisibility.telemetry.tag;

import datadog.trace.api.civisibility.telemetry.TagValue;
import java.util.Locale;

public enum GitShaMatch implements TagValue {
  TRUE,
  FALSE;

  @Override
  public String asString() {
    return "matched:" + name().toLowerCase(Locale.ROOT);
  }
}

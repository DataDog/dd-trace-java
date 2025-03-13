package datadog.trace.api;

import java.util.Locale;

/** Trace propagation styles for injecting and extracting trace propagation headers. */
public enum TracePropagationBehaviorExtract {
  CONTINUE,
  RESTART,
  IGNORE;

  private String displayName;

  @Override
  public String toString() {
    String string = displayName;
    if (displayName == null) {
      string = displayName = name().toLowerCase(Locale.ROOT);
    }
    return string;
  }
}

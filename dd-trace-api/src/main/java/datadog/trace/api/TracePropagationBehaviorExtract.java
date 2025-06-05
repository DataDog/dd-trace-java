package datadog.trace.api;

import java.util.Locale;

/** Trace propagation styles for injecting and extracting trace propagation headers. */
public enum TracePropagationBehaviorExtract {
  CONTINUE,
  RESTART,
  IGNORE;

  private String displayName;

  TracePropagationBehaviorExtract() {
    this.displayName = name().toLowerCase(Locale.ROOT);
  }

  @Override
  public String toString() {
    if (displayName == null) {
      displayName = name().toLowerCase(Locale.ROOT);
    }
    return displayName;
  }
}

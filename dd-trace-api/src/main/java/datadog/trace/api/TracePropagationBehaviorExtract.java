package datadog.trace.api;

import java.util.Locale;

/** Trace propagation styles for injecting and extracting trace propagation headers. */
public enum TracePropagationBehaviorExtract {
  CONTINUE("continue"),
  RESTART("restart"),
  IGNORE("ignore");

  private String displayName;

  TracePropagationBehaviorExtract(String displayName) {
    this.displayName = displayName;
  }

  @Override
  public String toString() {
    String string = displayName;
    if (displayName == null) {
      string = displayName = name().toLowerCase(Locale.ROOT);
    }
    return string;
  }
}

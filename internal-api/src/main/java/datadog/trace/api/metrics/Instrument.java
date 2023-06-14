package datadog.trace.api.metrics;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** This class describes an abstract instrument capable of recording timed measures. */
public abstract class Instrument {
  protected final String name;
  protected final boolean common;
  protected final List<String> tags;
  protected AtomicBoolean updated;

  protected Instrument(String name, boolean common, List<String> tags) {
    this.name = name;
    this.common = common;
    this.tags = tags;
    this.updated = new AtomicBoolean(false);
  }

  public String getName() {
    return this.name;
  }

  public abstract String getType();

  public boolean isCommon() {
    return this.common;
  }

  public List<String> getTags() {
    return this.tags;
  }

  public abstract Number getValue();

  /** Clear instrument value and updated flag. */
  public void reset() {
    this.updated.set(false);
    resetValue();
  }

  public abstract void resetValue();
}

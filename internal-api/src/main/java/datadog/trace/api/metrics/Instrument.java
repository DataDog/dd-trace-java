package datadog.trace.api.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** This class describes an abstract instrument capable of recording timed measures. */
public abstract class Instrument {
  protected final String name;
  protected final boolean common;
  protected final List<String> tags;
  protected AtomicBoolean updated;
  protected List<List<Number>> values; // TODO Thread safety

  protected Instrument(String name, boolean common, List<String> tags) {
    this.name = name;
    this.common = common;
    this.tags = tags;
    this.updated = new AtomicBoolean(false);
    this.values = new ArrayList<>();
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

  public List<List<Number>> getValues() {
    return values;
  }

  /** Clear instrument values and updated flag. */
  public void reset() {
    this.updated.set(false);
    this.values.clear();
  }
}

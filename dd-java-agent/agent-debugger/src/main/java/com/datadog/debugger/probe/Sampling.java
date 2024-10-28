package com.datadog.debugger.probe;

import com.datadog.debugger.agent.Generated;
import java.util.Objects;

/** Stores sampling configuration */
public final class Sampling {
  private final double snapshotsPerSecond;

  public Sampling(double snapshotsPerSecond) {
    this.snapshotsPerSecond = snapshotsPerSecond;
  }

  public double getSnapshotsPerSecond() {
    return snapshotsPerSecond;
  }

  @Generated
  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    Sampling sampling = (Sampling) o;
    return Double.compare(sampling.snapshotsPerSecond, snapshotsPerSecond) == 0;
  }

  @Generated
  @Override
  public int hashCode() {
    return Objects.hash(snapshotsPerSecond);
  }

  @Generated
  @Override
  public String toString() {
    return "Sampling{" + "snapshotsPerSecond=" + snapshotsPerSecond + '}';
  }
}

package com.datadog.debugger.probe;

import java.util.Objects;
import datadog.trace.util.HashingUtils;

/** Stores sampling configuration */
public class Sampling {
  private int coolDownInSeconds;
  private double eventsPerSecond;
  private volatile long nextExecution;
  private int executionInterval = -1;

  public Sampling() {}

  public Sampling(int coolDownInSeconds) {
    this.coolDownInSeconds = coolDownInSeconds;
    this.executionInterval = coolDownInSeconds * 1000;
  }

  public Sampling(double eventsPerSecond) {
    this.eventsPerSecond = eventsPerSecond;
  }

  public Sampling(int coolDownInSeconds, double eventsPerSecond) {
    this.coolDownInSeconds = coolDownInSeconds;
    this.executionInterval = coolDownInSeconds * 1000;

    this.eventsPerSecond = eventsPerSecond;
  }

  public int getCoolDownInSeconds() {
    return coolDownInSeconds;
  }

  public boolean inCoolDown() {
    if (System.currentTimeMillis() > nextExecution) {
      nextExecution = System.currentTimeMillis() + getExecutionInterval();
      return false;
    }
    return true;
  }

  public double getEventsPerSecond() {
    return eventsPerSecond;
  }

  private int getExecutionInterval() {
    if (executionInterval == -1) {
      executionInterval = coolDownInSeconds * 1000;
    }
    return executionInterval;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Sampling)) {
      return false;
    }
    Sampling sampling = (Sampling) o;
    return Objects.equals(coolDownInSeconds, sampling.coolDownInSeconds)
        && Objects.equals(eventsPerSecond, sampling.eventsPerSecond);
  }

  @Override
  public int hashCode() {
    return HashingUtils.hash(coolDownInSeconds, eventsPerSecond);
  }

  @Override
  public String toString() {
    return String.format(
        "Sampling{coolDownInSeconds=%d, eventsPerSecond=%s}", coolDownInSeconds, eventsPerSecond);
  }
}

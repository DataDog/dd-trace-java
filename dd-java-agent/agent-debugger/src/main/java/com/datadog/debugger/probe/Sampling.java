package com.datadog.debugger.probe;

import java.util.Objects;

/** Stores sampling configuration */
public class Sampling {
  private Integer coolDownInSeconds;
  private Double eventsPerSecond;
  private volatile long nextExecution;
  private int executionInterval = -1;

  public Sampling() {}

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

  public Sampling setCoolDownInSeconds(int coolDownInSeconds) {
    this.coolDownInSeconds = coolDownInSeconds;
    this.executionInterval = coolDownInSeconds * 1000;
    return this;
  }

  public Double getEventsPerSecond() {
    return eventsPerSecond;
  }

  public Sampling setEventsPerSecond(Double eventsPerSecond) {
    this.eventsPerSecond = eventsPerSecond;
    return this;
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
    return Objects.hash(coolDownInSeconds, eventsPerSecond);
  }

  @Override
  public String toString() {
    return String.format(
        "Sampling{coolDownInSeconds=%d, eventsPerSecond=%s}", coolDownInSeconds, eventsPerSecond);
  }
}

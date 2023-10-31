package com.datadog.iast;

import com.datadog.iast.overhead.OverheadController;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.Config;
import datadog.trace.util.stacktrace.StackWalker;
import javax.annotation.Nonnull;

public class Dependencies {

  private final Config config;
  private final Reporter reporter;
  private final OverheadController overheadController;
  private final StackWalker stackWalker;
  private final TaintedObjects taintedObjects;

  public Dependencies(
      @Nonnull final Config config,
      @Nonnull final Reporter reporter,
      @Nonnull final OverheadController overheadController,
      @Nonnull final StackWalker stackWalker,
      @Nonnull final TaintedObjects taintedObjects) {
    this.config = config;
    this.reporter = reporter;
    this.overheadController = overheadController;
    this.stackWalker = stackWalker;
    this.taintedObjects = taintedObjects;
  }

  public Config getConfig() {
    return config;
  }

  public Reporter getReporter() {
    return reporter;
  }

  public OverheadController getOverheadController() {
    return overheadController;
  }

  public StackWalker getStackWalker() {
    return stackWalker;
  }

  public TaintedObjects getTaintedObjects() {
    return taintedObjects;
  }
}

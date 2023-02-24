package com.datadog.iast;

import com.datadog.iast.overhead.OverheadController;
import com.datadog.iast.telemetry.IastTelemetry;
import datadog.trace.api.Config;
import datadog.trace.util.stacktrace.StackWalker;
import javax.annotation.Nonnull;

public interface HasDependencies {

  void registerDependencies(@Nonnull Dependencies dependencies);

  class Dependencies {
    private final Config config;
    private final Reporter reporter;
    private final OverheadController overheadController;
    private final StackWalker stackWalker;
    private final IastTelemetry telemetry;

    public Dependencies(
        @Nonnull final Config config,
        @Nonnull final Reporter reporter,
        @Nonnull final OverheadController overheadController,
        @Nonnull final IastTelemetry telemetry,
        @Nonnull final StackWalker stackWalker) {
      this.config = config;
      this.reporter = reporter;
      this.overheadController = overheadController;
      this.telemetry = telemetry;
      this.stackWalker = stackWalker;
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

    public IastTelemetry getTelemetry() {
      return telemetry;
    }
  }
}

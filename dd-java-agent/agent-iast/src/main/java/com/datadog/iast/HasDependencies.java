package com.datadog.iast;

import com.datadog.iast.overhead.OverheadController;
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

    public Dependencies(
        @Nonnull final Config config,
        @Nonnull final Reporter reporter,
        @Nonnull final OverheadController overheadController,
        @Nonnull final StackWalker stackWalker) {
      this.config = config;
      this.reporter = reporter;
      this.overheadController = overheadController;
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
  }
}

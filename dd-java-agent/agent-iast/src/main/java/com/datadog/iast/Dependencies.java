package com.datadog.iast;

import com.datadog.iast.overhead.OverheadController;
import datadog.trace.api.Config;
import datadog.trace.api.iast.IastContext;
import datadog.trace.instrumentation.iastinstrumenter.IastJSPClassListener;
import datadog.trace.util.stacktrace.StackWalker;
import javax.annotation.Nonnull;

public class Dependencies {

  private final Config config;
  private final Reporter reporter;
  private final OverheadController overheadController;
  private final StackWalker stackWalker;

  private final IastJSPClassListener iastJSPClassListener;

  final IastContext.Provider contextProvider;

  public Dependencies(
      @Nonnull final Config config,
      @Nonnull final Reporter reporter,
      @Nonnull final OverheadController overheadController,
      @Nonnull final StackWalker stackWalker,
      @Nonnull final IastContext.Provider contextProvider,
      @Nonnull final IastJSPClassListener iastJSPClassListener) {
    this.config = config;
    this.reporter = reporter;
    this.overheadController = overheadController;
    this.stackWalker = stackWalker;
    this.contextProvider = contextProvider;
    this.iastJSPClassListener = iastJSPClassListener;
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

  public IastJSPClassListener getIastJSPClassListener() {
    return iastJSPClassListener;
  }
}

package com.datadog.iast;

import com.datadog.iast.overhead.OverheadController;
import datadog.trace.api.Config;
import datadog.trace.api.iast.IastModule;
import datadog.trace.util.stacktrace.StackWalker;
import javax.annotation.Nonnull;

public abstract class IastModuleBase implements IastModule, HasDependencies {

  protected Config config;
  protected Reporter reporter;
  protected OverheadController overheadController;
  protected StackWalker stackWalker;

  @Override
  public void registerDependencies(@Nonnull final Dependencies dependencies) {
    this.config = dependencies.getConfig();
    this.reporter = dependencies.getReporter();
    this.overheadController = dependencies.getOverheadController();
    this.stackWalker = dependencies.getStackWalker();
  }
}

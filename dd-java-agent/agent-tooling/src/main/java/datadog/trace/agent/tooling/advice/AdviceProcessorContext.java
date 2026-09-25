package datadog.trace.agent.tooling.advice;

import datadog.trace.agent.tooling.InstrumenterModule;
import java.io.File;

/** Module and output directory shared by advice processors. */
public final class AdviceProcessorContext {
  private final InstrumenterModule module;
  private final File targetDirectory;

  AdviceProcessorContext(InstrumenterModule module, File targetDirectory) {
    this.module = module;
    this.targetDirectory = targetDirectory;
  }

  public InstrumenterModule getModule() {
    return module;
  }

  public File getTargetDirectory() {
    return targetDirectory;
  }
}

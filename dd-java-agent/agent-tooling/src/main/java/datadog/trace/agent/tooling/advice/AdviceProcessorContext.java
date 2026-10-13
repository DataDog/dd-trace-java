package datadog.trace.agent.tooling.advice;

import datadog.trace.agent.tooling.InstrumenterModule;
import java.io.File;

/** Module, output directory, and resolved helpers shared by advice processors. */
public final class AdviceProcessorContext {
  private final InstrumenterModule module;
  private final File targetDirectory;
  private final String[] helperClassNames;

  AdviceProcessorContext(
      InstrumenterModule module, File targetDirectory, String[] helperClassNames) {
    this.module = module;
    this.targetDirectory = targetDirectory;
    this.helperClassNames = helperClassNames;
  }

  public InstrumenterModule getModule() {
    return module;
  }

  public File getTargetDirectory() {
    return targetDirectory;
  }

  public String[] getHelperClassNames() {
    return helperClassNames;
  }
}

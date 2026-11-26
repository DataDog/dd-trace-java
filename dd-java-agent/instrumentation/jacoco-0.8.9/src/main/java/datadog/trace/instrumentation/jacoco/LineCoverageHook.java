package datadog.trace.instrumentation.jacoco;

import datadog.trace.api.Config;

final class LineCoverageHook {
  private LineCoverageHook() {}

  /**
   * Whether JaCoCo's probes report per-test lines to the tracer. Line probes collect those lines
   * without JaCoCo, so with line probes on the hook would only add cost.
   */
  static boolean isEnabled() {
    Config config = Config.get();
    return config.isCiVisibilityCoverageLinesEnabled()
        && !config.isCiVisibilityCoverageLineProbesEnabled();
  }
}

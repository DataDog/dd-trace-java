package datadog.trace.instrumentation.jacoco;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.coverage.CoverageBridge;
import org.jacoco.agent.rt.IAgent;
import org.jacoco.agent.rt.RT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CoverageDataInjector {

  private static final Logger log = LoggerFactory.getLogger(CoverageDataInjector.class);

  static {
    try {
      if (Config.get().isCiVisibilityCodeCoveragePercentageCalculationEnabled()) {
        IAgent agent = RT.getAgent();
        CoverageBridge.registerCoverageDataSupplier(() -> agent.getExecutionData(false));
      }
    } catch (Exception e) {
      log.info("Could not register coverage execution data factory", e);
    }
  }

  public static void init() {
    // this is just to trigger evaluation of the static block above
  }
}

package datadog.trace.civisibility;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.coverage.CoverageStore;
import datadog.trace.api.civisibility.coverage.JacocoCoverageBridge;
import datadog.trace.api.civisibility.coverage.NoOpCoverageStore;
import datadog.trace.civisibility.config.ExecutionSettings;
import datadog.trace.civisibility.coverage.SkippableAwareCoverageStoreFactory;
import datadog.trace.civisibility.coverage.file.FileCoverageStore;
import datadog.trace.civisibility.coverage.line.LineCoverageStore;
import datadog.trace.civisibility.coverage.percentage.CoverageCalculator;
import datadog.trace.civisibility.coverage.percentage.JacocoCoverageCalculator;
import datadog.trace.civisibility.coverage.percentage.child.ChildProcessCoverageReporter;
import datadog.trace.civisibility.coverage.percentage.child.JacocoChildProcessCoverageReporter;
import datadog.trace.civisibility.domain.buildsystem.ModuleSignalRouter;
import java.util.Collection;

/**
 * Services that are related to coverage calculation (both per-test coverage and total coverage
 * percentage). The scope is session/module.
 */
public class CiVisibilityCoverageServices {

  /** Services used in the parent process (build system). */
  static class Parent {
    final ModuleSignalRouter moduleSignalRouter;
    final CoverageCalculator.Factory<?> coverageCalculatorFactory;

    Parent(CiVisibilityServices services, CiVisibilityRepoServices repoServices) {
      moduleSignalRouter = new ModuleSignalRouter();
      coverageCalculatorFactory =
          new JacocoCoverageCalculator.Factory(
              services.config,
              repoServices.repoIndexProvider,
              repoServices.repoRoot,
              moduleSignalRouter);
    }
  }

  /** Services used in the children processes (JVMs forked to run tests). */
  static class Child {
    final CoverageStore.Factory coverageStoreFactory;
    final ChildProcessCoverageReporter coverageReporter;

    Child(
        CiVisibilityServices services,
        CiVisibilityRepoServices repoServices,
        ExecutionSettings executionSettings) {
      coverageReporter =
          new JacocoChildProcessCoverageReporter(JacocoCoverageBridge::getJacocoCoverageData);

      coverageStoreFactory = buildCoverageStoreFactory(services, repoServices, executionSettings);
    }

    private static CoverageStore.Factory buildCoverageStoreFactory(
        CiVisibilityServices services,
        CiVisibilityRepoServices repoServices,
        ExecutionSettings executionSettings) {

      CoverageStore.Factory factory;
      if (!services.config.isCiVisibilityCodeCoverageEnabled()) {
        factory = new NoOpCoverageStore.Factory();
      } else if (services.config.isCiVisibilityCoverageLinesEnabled()) {
        factory =
            new LineCoverageStore.Factory(
                services.metricCollector, repoServices.sourcePathResolver);
      } else {
        factory =
            new FileCoverageStore.Factory(
                services.metricCollector, repoServices.sourcePathResolver);
      }

      if (executionSettings.isItrEnabled()) {
        Collection<TestIdentifier> skippableTests =
            executionSettings.getSkippableTests(repoServices.moduleName);
        return new SkippableAwareCoverageStoreFactory(skippableTests, factory);
      } else {
        return factory;
      }
    }
  }
}

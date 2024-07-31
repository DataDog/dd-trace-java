package datadog.trace.civisibility;

import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.coverage.CoverageStore;
import datadog.trace.api.civisibility.coverage.JacocoCoverageBridge;
import datadog.trace.api.civisibility.coverage.NoOpCoverageStore;
import datadog.trace.civisibility.coverage.SkippableAwareCoverageStoreFactory;
import datadog.trace.civisibility.coverage.file.FileCoverageStore;
import datadog.trace.civisibility.coverage.line.LineCoverageStore;
import datadog.trace.civisibility.coverage.percentage.CoverageCalculator;
import datadog.trace.civisibility.coverage.percentage.ItrCoverageCalculator;
import datadog.trace.civisibility.coverage.percentage.JacocoCoverageCalculator;
import datadog.trace.civisibility.coverage.percentage.child.ChildProcessCoverageReporter;
import datadog.trace.civisibility.coverage.percentage.child.ItrChildProcessCoverageReporter;
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

      if (services.config.isCiVisibilityItrCodeCoveragePercentageCalculationEnabled()) {
        coverageCalculatorFactory =
            new ItrCoverageCalculator.Factory(repoServices.repoRoot, moduleSignalRouter);
      } else {
        coverageCalculatorFactory =
            new JacocoCoverageCalculator.Factory(
                services.config,
                repoServices.repoIndexProvider,
                repoServices.repoRoot,
                moduleSignalRouter);
      }
    }
  }

  /** Services used in the children processes (JVMs forked to run tests). */
  static class Child {
    final CoverageStore.Factory coverageStoreFactory;
    final ChildProcessCoverageReporter coverageReporter;

    Child(
        CiVisibilityServices services,
        CiVisibilityRepoServices repoServices,
        ModuleExecutionSettings moduleExecutionSettings) {
      if (services.config.isCiVisibilityItrCodeCoveragePercentageCalculationEnabled()) {
        coverageReporter = new ItrChildProcessCoverageReporter();
      } else {
        coverageReporter =
            new JacocoChildProcessCoverageReporter(JacocoCoverageBridge::getJacocoCoverageData);
      }

      coverageStoreFactory =
          buildCoverageStoreFactory(
              services, repoServices, moduleExecutionSettings, coverageReporter);
    }

    private static CoverageStore.Factory buildCoverageStoreFactory(
        CiVisibilityServices services,
        CiVisibilityRepoServices repoServices,
        ModuleExecutionSettings moduleExecutionSettings,
        ChildProcessCoverageReporter coverageReporter) {
      if (services.config.isCiVisibilityItrCodeCoveragePercentageCalculationEnabled()) {
        return coverageReporter.wrapCoverageStoreFactory(
            new LineCoverageStore.Factory(
                services.metricCollector, repoServices.sourcePathResolver));
      }
      if (!services.config.isCiVisibilityCodeCoverageEnabled()) {
        return new NoOpCoverageStore.Factory();
      }
      FileCoverageStore.Factory fileCoverageStoreFactory =
          new FileCoverageStore.Factory(services.metricCollector, repoServices.sourcePathResolver);
      if (moduleExecutionSettings.isItrEnabled()) {
        Collection<TestIdentifier> skippableTests =
            moduleExecutionSettings.getSkippableTests(repoServices.moduleName);
        return new SkippableAwareCoverageStoreFactory(skippableTests, fileCoverageStoreFactory);
      } else {
        return fileCoverageStoreFactory;
      }
    }
  }
}

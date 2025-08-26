package datadog.trace.civisibility;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestMetadata;
import datadog.trace.api.civisibility.coverage.CoveragePercentageBridge;
import datadog.trace.api.civisibility.coverage.CoverageStore;
import datadog.trace.api.civisibility.coverage.NoOpCoverageStore;
import datadog.trace.civisibility.config.ExecutionSettings;
import datadog.trace.civisibility.coverage.SkippableAwareCoverageStoreFactory;
import datadog.trace.civisibility.coverage.file.FileCoverageStore;
import datadog.trace.civisibility.coverage.line.LineCoverageStore;
import datadog.trace.civisibility.coverage.report.CoverageProcessor;
import datadog.trace.civisibility.coverage.report.CoverageReportUploader;
import datadog.trace.civisibility.coverage.report.JacocoCoverageProcessor;
import datadog.trace.civisibility.coverage.report.child.ChildProcessCoverageReporter;
import datadog.trace.civisibility.coverage.report.child.JacocoChildProcessCoverageReporter;
import datadog.trace.civisibility.domain.buildsystem.ModuleSignalRouter;
import java.util.Map;

/**
 * Services that are related to per-test code coverage and coverage report uploads. The scope is
 * session/module.
 */
public class CiVisibilityCoverageServices {

  /** Services used in the parent process (build system). */
  static class Parent {
    final ModuleSignalRouter moduleSignalRouter;
    final CoverageProcessor.Factory<?> coverageProcessorFactory;

    Parent(CiVisibilityServices services, CiVisibilityRepoServices repoServices) {
      moduleSignalRouter = new ModuleSignalRouter();
      CoverageReportUploader coverageReportUploader =
          new CoverageReportUploader(
              services.ciIntake, repoServices.ciTags, services.metricCollector);
      coverageProcessorFactory =
          new JacocoCoverageProcessor.Factory(
              services.config,
              repoServices.repoIndexProvider,
              coverageReportUploader,
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
          new JacocoChildProcessCoverageReporter(CoveragePercentageBridge::getJacocoCoverageData);

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
        Map<TestIdentifier, TestMetadata> skippableTests = executionSettings.getSkippableTests();
        return new SkippableAwareCoverageStoreFactory(skippableTests.keySet(), factory);
      } else {
        return factory;
      }
    }
  }
}

package datadog.trace.civisibility.coverage.percentage;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import datadog.trace.api.civisibility.domain.ModuleLayout;
import datadog.trace.api.civisibility.domain.SourceSet;
import datadog.trace.civisibility.domain.buildsystem.ModuleSignalRouter;
import datadog.trace.civisibility.ipc.AckResponse;
import datadog.trace.civisibility.ipc.ModuleCoverageDataJacoco;
import datadog.trace.civisibility.ipc.SignalResponse;
import datadog.trace.civisibility.ipc.SignalType;
import datadog.trace.civisibility.source.index.RepoIndex;
import datadog.trace.civisibility.source.index.RepoIndexProvider;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.data.ExecutionDataReader;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.report.FileMultiReportOutput;
import org.jacoco.report.IReportVisitor;
import org.jacoco.report.InputStreamSourceFileLocator;
import org.jacoco.report.html.HTMLFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Uses Jacoco execution data to calculate the percentage of covered lines. */
public class JacocoCoverageCalculator implements CoverageCalculator {

  public static final class Factory
      implements CoverageCalculator.Factory<JacocoCoverageCalculator> {
    private final Config config;
    private final RepoIndexProvider repoIndexProvider;
    private final String repoRoot;
    private final ModuleSignalRouter moduleSignalRouter;

    public Factory(
        Config config,
        RepoIndexProvider repoIndexProvider,
        String repoRoot,
        ModuleSignalRouter moduleSignalRouter) {
      this.config = config;
      this.repoIndexProvider = repoIndexProvider;
      this.repoRoot = repoRoot;
      this.moduleSignalRouter = moduleSignalRouter;
    }

    @Override
    public JacocoCoverageCalculator sessionCoverage(long sessionId) {
      return new JacocoCoverageCalculator(config, repoIndexProvider, repoRoot, sessionId);
    }

    @Override
    public JacocoCoverageCalculator moduleCoverage(
        long moduleId,
        ModuleLayout moduleLayout,
        ModuleExecutionSettings moduleExecutionSettings,
        JacocoCoverageCalculator sessionCoverage) {
      return new JacocoCoverageCalculator(
          config,
          repoIndexProvider,
          repoRoot,
          moduleId,
          moduleLayout,
          moduleSignalRouter,
          sessionCoverage);
    }
  }

  private static final Logger LOGGER = LoggerFactory.getLogger(JacocoCoverageCalculator.class);

  @Nullable private final JacocoCoverageCalculator parent;

  private final Config config;

  private final RepoIndexProvider repoIndexProvider;

  private final String repoRoot;

  private final long eventId;

  private final Object coverageDataLock = new Object();

  @GuardedBy("coverageDataLock")
  private final ExecutionDataStore coverageData = new ExecutionDataStore();

  @GuardedBy("coverageDataLock")
  private final Collection<File> outputClassesDirs = new HashSet<>();

  private JacocoCoverageCalculator(
      Config config, RepoIndexProvider repoIndexProvider, String repoRoot, long sessionId) {
    this.parent = null;
    this.config = config;
    this.repoIndexProvider = repoIndexProvider;
    this.repoRoot = repoRoot;
    this.eventId = sessionId;
  }

  private JacocoCoverageCalculator(
      Config config,
      RepoIndexProvider repoIndexProvider,
      String repoRoot,
      long moduleId,
      ModuleLayout moduleLayout,
      ModuleSignalRouter moduleSignalRouter,
      @Nonnull JacocoCoverageCalculator parent) {
    this.parent = parent;
    this.config = config;
    this.repoIndexProvider = repoIndexProvider;
    this.repoRoot = repoRoot;
    this.eventId = moduleId;

    addModuleLayout(moduleLayout);
    moduleSignalRouter.registerModuleHandler(
        moduleId, SignalType.MODULE_COVERAGE_DATA_JACOCO, this::addCoverageData);
  }

  private void addModuleLayout(ModuleLayout moduleLayout) {
    synchronized (coverageDataLock) {
      for (SourceSet sourceSet : moduleLayout.getSourceSets()) {
        if (sourceSet.getType() == SourceSet.Type.TEST) {
          // test sources should not be considered when calculating code coverage percentage
          continue;
        }
        this.outputClassesDirs.add(sourceSet.getOutput());
      }
    }
    if (parent != null) {
      parent.addModuleLayout(moduleLayout);
    }
  }

  private SignalResponse addCoverageData(ModuleCoverageDataJacoco moduleCoverageData) {
    byte[] rawCoverageData = moduleCoverageData.getCoverageData();
    ExecutionDataStore parsedCoverageData = parseCoverageData(rawCoverageData);
    if (parsedCoverageData != null) {
      synchronized (coverageDataLock) {
        // add received coverage data to aggregated coverage data
        parsedCoverageData.accept(coverageData);
      }
    }

    if (parent != null) {
      // it is important that modules and sessions each parse their own instances of
      // ExecutionDataStore and not share them:
      // ExecutionData instances that reside inside the store are mutable,
      // and modifying an ExecutionData in one module is going
      // to be visible in another module
      // (see internal implementation of org.jacoco.core.data.ExecutionDataStore.accept)
      parent.addCoverageData(moduleCoverageData);
    }

    return AckResponse.INSTANCE;
  }

  private static ExecutionDataStore parseCoverageData(byte[] rawCoverageData) {
    if (rawCoverageData == null) {
      return null;
    }
    try {
      SessionInfoStore sessionInfoStore = new SessionInfoStore();
      ExecutionDataStore executionDataStore = new ExecutionDataStore();

      ByteArrayInputStream input = new ByteArrayInputStream(rawCoverageData);
      ExecutionDataReader dataReader = new ExecutionDataReader(input);
      dataReader.setSessionInfoVisitor(sessionInfoStore);
      dataReader.setExecutionDataVisitor(executionDataStore);
      dataReader.read();

      return executionDataStore;
    } catch (Exception e) {
      LOGGER.error("Error while parsing coverage data", e);
      return null;
    }
  }

  @Override
  @Nullable
  public Long calculateCoveragePercentage() {
    IBundleCoverage coverageBundle = buildCoverageBundle();
    if (coverageBundle == null) {
      return null;
    }

    File coverageReportFolder = getCoverageReportFolder();
    if (coverageReportFolder != null) {
      dumpCoverageReport(coverageBundle, coverageReportFolder);
    }
    return getCoveragePercentage(coverageBundle);
  }

  private IBundleCoverage buildCoverageBundle() {
    synchronized (coverageDataLock) {
      if (coverageData.getContents().isEmpty()) {
        return null;
      }

      try {
        CoverageBuilder coverageBuilder = new CoverageBuilder();
        Analyzer analyzer = new Analyzer(coverageData, coverageBuilder);
        for (File outputClassesDir : outputClassesDirs) {
          if (outputClassesDir.exists()) {
            analyzer.analyzeAll(outputClassesDir);
          }
        }

        return coverageBuilder.getBundle("Module coverage data");
      } catch (Exception e) {
        LOGGER.error("Error while creating coverage bundle", e);
        return null;
      }
    }
  }

  private File getCoverageReportFolder() {
    String coverageReportDumpDir = config.getCiVisibilityCodeCoverageReportDumpDir();
    if (coverageReportDumpDir != null) {
      return Paths.get(
              coverageReportDumpDir,
              (parent == null ? "session" : "module") + "-" + eventId,
              "aggregated")
          .toAbsolutePath()
          .toFile();
    } else {
      return null;
    }
  }

  private void dumpCoverageReport(IBundleCoverage coverageBundle, File reportFolder) {
    if (!reportFolder.exists() && !reportFolder.mkdirs()) {
      LOGGER.debug("Skipping report generation, could not create report dir: {}", reportFolder);
      return;
    }
    try {
      final HTMLFormatter htmlFormatter = new HTMLFormatter();
      final IReportVisitor visitor =
          htmlFormatter.createVisitor(new FileMultiReportOutput(reportFolder));
      visitor.visitInfo(Collections.emptyList(), Collections.emptyList());
      visitor.visitBundle(
          coverageBundle, new RepoIndexFileLocator(repoIndexProvider.getIndex(), repoRoot));
      visitor.visitEnd();
    } catch (Exception e) {
      LOGGER.error("Error while creating report in {}", reportFolder, e);
    }
  }

  private static long getCoveragePercentage(IBundleCoverage coverageBundle) {
    ICounter lineCounter = coverageBundle.getLineCounter();
    int coveredLines = lineCounter.getCoveredCount();
    int totalLines = lineCounter.getTotalCount();
    return Math.round((100d * coveredLines) / totalLines);
  }

  private static final class RepoIndexFileLocator extends InputStreamSourceFileLocator {
    private final RepoIndex repoIndex;
    private final String repoRoot;

    private RepoIndexFileLocator(RepoIndex repoIndex, String repoRoot) {
      super("utf-8", 4);
      this.repoIndex = repoIndex;
      this.repoRoot = repoRoot;
    }

    @Override
    protected InputStream getSourceStream(String path) throws IOException {
      String relativePath = repoIndex.getSourcePath(path);
      if (relativePath == null) {
        return null;
      }
      String absolutePath =
          repoRoot + (!repoRoot.endsWith(File.separator) ? File.separator : "") + relativePath;
      return new BufferedInputStream(Files.newInputStream(Paths.get(absolutePath)));
    }
  }
}

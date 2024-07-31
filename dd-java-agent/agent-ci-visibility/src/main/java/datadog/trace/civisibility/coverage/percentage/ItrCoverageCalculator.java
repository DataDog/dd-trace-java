package datadog.trace.civisibility.coverage.percentage;

import datadog.trace.api.civisibility.config.ModuleExecutionSettings;
import datadog.trace.api.civisibility.domain.ModuleLayout;
import datadog.trace.api.civisibility.domain.SourceSet;
import datadog.trace.civisibility.domain.buildsystem.ModuleSignalRouter;
import datadog.trace.civisibility.ipc.AckResponse;
import datadog.trace.civisibility.ipc.ModuleCoverageDataItr;
import datadog.trace.civisibility.ipc.SignalResponse;
import datadog.trace.civisibility.ipc.SignalType;
import java.io.File;
import java.io.IOException;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.data.ExecutionDataStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Uses ad-hoc execution data to calculate the percentage of covered lines. This is needed to
 * support calculating code coverage when ITR is enabled. Since we are skipping some tests, the
 * coverage data for those tests is sent to us by the backend, rather than being gathered at tests
 * execution. TO calculate total coverage we join these "skippable coverage data" with the coverage
 * data obtained by running the tests.
 */
public class ItrCoverageCalculator implements CoverageCalculator {

  private static final Logger LOGGER = LoggerFactory.getLogger(ItrCoverageCalculator.class);

  public static final class Factory implements CoverageCalculator.Factory<ItrCoverageCalculator> {
    private final String repoRoot;
    private final ModuleSignalRouter moduleSignalRouter;

    public Factory(String repoRoot, ModuleSignalRouter moduleSignalRouter) {
      this.repoRoot = repoRoot;
      this.moduleSignalRouter = moduleSignalRouter;
    }

    @Override
    public ItrCoverageCalculator sessionCoverage(long sessionId) {
      return new ItrCoverageCalculator(repoRoot);
    }

    @Override
    public ItrCoverageCalculator moduleCoverage(
        long moduleId,
        ModuleLayout moduleLayout,
        ModuleExecutionSettings moduleExecutionSettings,
        ItrCoverageCalculator sessionCoverage) {
      return new ItrCoverageCalculator(
          repoRoot,
          moduleId,
          moduleLayout,
          moduleExecutionSettings,
          moduleSignalRouter,
          sessionCoverage);
    }
  }

  private final String repoRoot;

  @Nullable private final ItrCoverageCalculator parent;

  private final Object coverageDataLock = new Object();

  // FIXME nikita: consider using a Trie
  @GuardedBy("coverageDataLock")
  private final Collection<String> sourceDirsRelativeToRepoRoot = new HashSet<>();

  @GuardedBy("coverageDataLock")
  private final Collection<File> outputClassesDirs = new HashSet<>();

  @GuardedBy("coverageDataLock")
  private final Map<String, BitSet> coveredLinesBySourcePath = new HashMap<>();

  private ItrCoverageCalculator(String repoRoot) {
    this.repoRoot = repoRoot;
    this.parent = null;
  }

  private ItrCoverageCalculator(
      String repoRoot,
      long moduleId,
      ModuleLayout moduleLayout,
      ModuleExecutionSettings moduleExecutionSettings,
      ModuleSignalRouter moduleSignalRouter,
      @Nullable ItrCoverageCalculator parent) {
    this.repoRoot = repoRoot;
    this.parent = parent;

    // this is the data that would've been covered if we ran all the skippable tests
    Map<String, BitSet> skippableTestsCoverage =
        moduleExecutionSettings.getSkippableTestsCoverage();
    if (skippableTestsCoverage != null) {
      addCoverageData(new ModuleCoverageDataItr(-1, moduleId, skippableTestsCoverage));
    }

    addModuleLayout(moduleLayout);
    moduleSignalRouter.registerModuleHandler(
        moduleId, SignalType.MODULE_COVERAGE_DATA_ITR, this::addCoverageData);
  }

  private void addModuleLayout(ModuleLayout moduleLayout) {
    synchronized (coverageDataLock) {
      for (SourceSet sourceSet : moduleLayout.getSourceSets()) {
        if (sourceSet.getType() == SourceSet.Type.TEST) {
          // test sources should not be considered when calculating code coverage percentage
          continue;
        }
        for (File source : sourceSet.getSources()) {
          String sourceString = source.toString();
          if (sourceString.startsWith(repoRoot)) {
            sourceDirsRelativeToRepoRoot.add(
                sourceString.substring(repoRoot.length() + (repoRoot.endsWith("/") ? 0 : 1)));
          }
        }
        outputClassesDirs.add(sourceSet.getOutput());
      }
    }
    if (parent != null) {
      parent.addModuleLayout(moduleLayout);
    }
  }

  private SignalResponse addCoverageData(ModuleCoverageDataItr moduleCoverageData) {
    synchronized (coverageDataLock) {
      Map<String, BitSet> moduleCoveredLines =
          moduleCoverageData.getCoveredLinesByRelativeSourcePath();
      for (Map.Entry<String, BitSet> e : moduleCoveredLines.entrySet()) {
        String sourceFileName = e.getKey();
        BitSet coveredLines = e.getValue();
        coveredLinesBySourcePath
            .computeIfAbsent(sourceFileName, sf -> new BitSet())
            .or(coveredLines);
      }
    }

    if (parent != null) {
      parent.addCoverageData(moduleCoverageData);
    }

    return AckResponse.INSTANCE;
  }

  @Nullable
  @Override
  public Long calculateCoveragePercentage() {
    int coveredLines = 0;
    synchronized (coverageDataLock) {
      for (Map.Entry<String, BitSet> e : coveredLinesBySourcePath.entrySet()) {
        String sourceFileName = e.getKey();
        if (!include(sourceFileName)) {
          continue;
        }
        BitSet sourceFileCoveredLines = e.getValue();
        coveredLines += sourceFileCoveredLines.cardinality();
      }
    }

    int totalLines;
    try {
      totalLines = getTotalExecutableLines();
      return Math.round((100d * coveredLines) / totalLines);

    } catch (IOException e) {
      LOGGER.warn("Could not calculate coverage percentage", e);
      return null;
    }
  }

  private boolean include(String sourceFileName) {
    for (String sourceDir : sourceDirsRelativeToRepoRoot) {
      if (sourceFileName.startsWith(sourceDir)) {
        return true;
      }
    }
    return false;
  }

  // TODO see if this can be done without relying on Jacoco
  private int getTotalExecutableLines() throws IOException {
    CoverageBuilder coverageBuilder = new CoverageBuilder();
    Analyzer analyzer = new Analyzer(new ExecutionDataStore(), coverageBuilder);
    for (File outputClassesDir : outputClassesDirs) {
      if (outputClassesDir.exists()) {
        analyzer.analyzeAll(outputClassesDir);
      }
    }
    IBundleCoverage coverageBundle = coverageBuilder.getBundle("Module coverage data");
    ICounter lineCounter = coverageBundle.getLineCounter();
    return lineCounter.getTotalCount();
  }
}

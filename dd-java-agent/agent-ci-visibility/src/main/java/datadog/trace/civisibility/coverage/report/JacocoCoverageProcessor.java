package datadog.trace.civisibility.coverage.report;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.domain.BuildModuleLayout;
import datadog.trace.api.civisibility.domain.SourceSet;
import datadog.trace.civisibility.config.ExecutionSettings;
import datadog.trace.civisibility.domain.buildsystem.ModuleSignalRouter;
import datadog.trace.civisibility.ipc.AckResponse;
import datadog.trace.civisibility.ipc.ModuleCoverageDataJacoco;
import datadog.trace.civisibility.ipc.SignalResponse;
import datadog.trace.civisibility.ipc.SignalType;
import datadog.trace.civisibility.source.SourceResolutionException;
import datadog.trace.civisibility.source.index.RepoIndex;
import datadog.trace.civisibility.source.index.RepoIndexProvider;
import datadog.trace.util.Strings;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.ILine;
import org.jacoco.core.analysis.IPackageCoverage;
import org.jacoco.core.analysis.ISourceFileCoverage;
import org.jacoco.core.analysis.ISourceNode;
import org.jacoco.core.data.ExecutionDataReader;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.report.FileMultiReportOutput;
import org.jacoco.report.IReportVisitor;
import org.jacoco.report.ISourceFileLocator;
import org.jacoco.report.InputStreamSourceFileLocator;
import org.jacoco.report.html.HTMLFormatter;
import org.jacoco.report.xml.XMLFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Processes Jacoco coverage reports. */
public class JacocoCoverageProcessor implements CoverageProcessor {

  public static final class Factory implements CoverageProcessor.Factory<JacocoCoverageProcessor> {
    private final Config config;
    private final RepoIndexProvider repoIndexProvider;
    private final CoverageReportUploader coverageReportUploader;
    @Nullable private final String repoRoot;
    private final ModuleSignalRouter moduleSignalRouter;

    public Factory(
        Config config,
        RepoIndexProvider repoIndexProvider,
        CoverageReportUploader coverageReportUploader,
        @Nullable String repoRoot,
        ModuleSignalRouter moduleSignalRouter) {
      this.config = config;
      this.repoIndexProvider = repoIndexProvider;
      this.coverageReportUploader = coverageReportUploader;
      this.repoRoot = repoRoot;
      this.moduleSignalRouter = moduleSignalRouter;
    }

    @Override
    public JacocoCoverageProcessor sessionCoverage(long sessionId) {
      return new JacocoCoverageProcessor(
          config, repoIndexProvider, coverageReportUploader, repoRoot, sessionId);
    }

    @Override
    public JacocoCoverageProcessor moduleCoverage(
        long moduleId,
        @Nullable BuildModuleLayout moduleLayout,
        ExecutionSettings executionSettings,
        JacocoCoverageProcessor sessionCoverage) {
      return new JacocoCoverageProcessor(
          config,
          repoIndexProvider,
          null, // do not upload coverage reports for individual modules
          executionSettings,
          repoRoot,
          moduleId,
          moduleLayout,
          moduleSignalRouter,
          sessionCoverage);
    }
  }

  private static final Logger LOGGER = LoggerFactory.getLogger(JacocoCoverageProcessor.class);

  @Nullable private final JacocoCoverageProcessor parent;

  private final Config config;

  private final RepoIndexProvider repoIndexProvider;

  @Nullable private final CoverageReportUploader coverageReportUploader;

  @Nullable private final String repoRoot;

  private final long eventId;

  private final Object coverageDataLock = new Object();

  @GuardedBy("coverageDataLock")
  private final ExecutionDataStore coverageData = new ExecutionDataStore();

  @GuardedBy("coverageDataLock")
  private final Map<String, BitSet> backendCoverageData = new HashMap<>();

  @GuardedBy("coverageDataLock")
  private final Collection<File> outputClassesDirs = new HashSet<>();

  private JacocoCoverageProcessor(
      Config config,
      RepoIndexProvider repoIndexProvider,
      @Nullable CoverageReportUploader coverageReportUploader,
      @Nullable String repoRoot,
      long sessionId) {
    this.parent = null;
    this.config = config;
    this.repoIndexProvider = repoIndexProvider;
    this.coverageReportUploader = coverageReportUploader;
    this.repoRoot = repoRoot;
    this.eventId = sessionId;
  }

  private JacocoCoverageProcessor(
      Config config,
      RepoIndexProvider repoIndexProvider,
      @Nullable CoverageReportUploader coverageReportUploader,
      ExecutionSettings executionSettings,
      @Nullable String repoRoot,
      long moduleId,
      @Nullable BuildModuleLayout moduleLayout,
      ModuleSignalRouter moduleSignalRouter,
      @Nonnull JacocoCoverageProcessor parent) {
    this.parent = parent;
    this.config = config;
    this.repoIndexProvider = repoIndexProvider;
    this.coverageReportUploader = coverageReportUploader;
    this.repoRoot = repoRoot;
    this.eventId = moduleId;

    if (executionSettings.isTestSkippingEnabled()) {
      // this is the data that would've been covered if we ran all the skippable tests
      addBackendCoverageData(executionSettings.getSkippableTestsCoverage());
    }

    addModuleLayout(moduleLayout);
    moduleSignalRouter.registerModuleHandler(
        moduleId, SignalType.MODULE_COVERAGE_DATA_JACOCO, this::addCoverageData);
  }

  private void addModuleLayout(@Nullable BuildModuleLayout moduleLayout) {
    if (moduleLayout == null) {
      LOGGER.debug("Received null module layout, will not be able to calculate coverage");
      return;
    }
    synchronized (coverageDataLock) {
      for (SourceSet sourceSet : moduleLayout.getSourceSets()) {
        if (sourceSet.getType() == SourceSet.Type.TEST) {
          // test sources should not be considered when calculating code coverage percentage
          continue;
        }
        this.outputClassesDirs.addAll(sourceSet.getDestinations());
      }
    }
    if (parent != null) {
      parent.addModuleLayout(moduleLayout);
    }
  }

  /** Handles skipped tests' coverage data received from the backend */
  private void addBackendCoverageData(@Nonnull Map<String, BitSet> skippableTestsCoverage) {
    synchronized (coverageDataLock) {
      for (Map.Entry<String, BitSet> e : skippableTestsCoverage.entrySet()) {
        backendCoverageData.merge(e.getKey(), e.getValue(), JacocoCoverageProcessor::mergeBitSets);
      }
    }
    if (parent != null) {
      parent.addBackendCoverageData(skippableTestsCoverage);
    }
  }

  private static BitSet mergeBitSets(BitSet a, BitSet b) {
    // we need to create a instance to avoid sharing objects between modules and session
    BitSet merged = new BitSet(Math.max(a.size(), b.size()));
    merged.or(a);
    merged.or(b);
    return merged;
  }

  /** Handles executed tests' coverage data received from a JVM that ran tests */
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
  public Long processCoverageData() {
    IBundleCoverage coverageBundle = buildCoverageBundle();
    if (coverageBundle == null) {
      return null;
    }

    File coverageReportFolder = getCoverageReportFolder();
    if (coverageReportFolder != null) {
      dumpCoverageReport(coverageBundle, coverageReportFolder);
    }

    if (backendCoverageData.isEmpty()) {
      uploadCoverageReport(coverageBundle);
      return getLocalCoveragePercentage(coverageBundle);
    } else {
      return mergeAndUploadCoverageReport(coverageBundle);
    }
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

      final IReportVisitor htmlVisitor =
          htmlFormatter.createVisitor(new FileMultiReportOutput(reportFolder));
      htmlVisitor.visitInfo(Collections.emptyList(), Collections.emptyList());
      htmlVisitor.visitBundle(coverageBundle, createSourceFileLocator());
      htmlVisitor.visitEnd();

      File xmlReport = new File(reportFolder, "jacoco.xml");
      try (FileOutputStream xmlReportStream = new FileOutputStream(xmlReport)) {
        XMLFormatter xmlFormatter = new XMLFormatter();
        IReportVisitor xmlVisitor = xmlFormatter.createVisitor(xmlReportStream);
        xmlVisitor.visitInfo(Collections.emptyList(), Collections.emptyList());
        xmlVisitor.visitBundle(coverageBundle, createSourceFileLocator());
        xmlVisitor.visitEnd();
      }
    } catch (Exception e) {
      LOGGER.error("Error while creating report in {}", reportFolder, e);
    }
  }

  private ISourceFileLocator createSourceFileLocator() {
    return repoRoot != null
        ? new RepoIndexFileLocator(repoIndexProvider.getIndex(), repoRoot)
        : NoOpFileLocator.INSTANCE;
  }

  private static final class RepoIndexFileLocator extends InputStreamSourceFileLocator {
    private final RepoIndex repoIndex;
    @Nonnull private final String repoRoot;

    private RepoIndexFileLocator(RepoIndex repoIndex, @Nonnull String repoRoot) {
      super("utf-8", 4);
      this.repoIndex = repoIndex;
      this.repoRoot = repoRoot;
    }

    @Override
    protected InputStream getSourceStream(String path) throws IOException {
      try {
        String relativePath = repoIndex.getSourcePath(path);
        if (relativePath == null) {
          return null;
        }
        String absolutePath =
            repoRoot + (!repoRoot.endsWith(File.separator) ? File.separator : "") + relativePath;
        return new BufferedInputStream(Files.newInputStream(Paths.get(absolutePath)));

      } catch (SourceResolutionException e) {
        LOGGER.debug("Could not resolve source for path {}", path, e);
        return null;
      }
    }
  }

  private static final class NoOpFileLocator implements ISourceFileLocator {
    private static final NoOpFileLocator INSTANCE = new NoOpFileLocator();

    private NoOpFileLocator() {}

    @Override
    public Reader getSourceFile(String s, String s1) {
      return null;
    }

    @Override
    public int getTabWidth() {
      return 0;
    }
  }

  private void uploadCoverageReport(IBundleCoverage coverageBundle) {
    if (coverageReportUploader == null) {
      return;
    }

    try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
      XMLFormatter xmlFormatter = new XMLFormatter();
      IReportVisitor xmlVisitor = xmlFormatter.createVisitor(baos);
      xmlVisitor.visitInfo(Collections.emptyList(), Collections.emptyList());
      xmlVisitor.visitBundle(coverageBundle, createSourceFileLocator());
      xmlVisitor.visitEnd();

      coverageReportUploader.upload("jacoco", new ByteArrayInputStream(baos.toByteArray()));

    } catch (IOException e) {
      LOGGER.error("Error while uploading coverage report", e);
    }
  }

  /**
   * Gets coverage percentage data directly from Jacoco bundle (can only be used if ITR did not skip
   * any tests).
   */
  private static long getLocalCoveragePercentage(IBundleCoverage coverageBundle) {
    ICounter lineCounter = coverageBundle.getLineCounter();
    int coveredLines = lineCounter.getCoveredCount();
    int totalLines = lineCounter.getTotalCount();
    return Math.round((100d * coveredLines) / totalLines);
  }

  private static final BitSet EMPTY_BIT_SET = new BitSet();

  /**
   * Merges coverage from Jacoco bundle with skipped tests' coverage received from the backend and
   * uploads the merge result if applicable.
   *
   * @return total coverage percentage
   */
  private long mergeAndUploadCoverageReport(IBundleCoverage coverageBundle) {
    RepoIndex repoIndex = repoIndexProvider.getIndex();

    Map<String, LinesCoverage> mergedCoverageData = new TreeMap<>();

    int totalLines = 0, coveredLines = 0;
    for (IPackageCoverage packageCoverage : coverageBundle.getPackages()) {
      for (ISourceFileCoverage sourceFile : packageCoverage.getSourceFiles()) {
        String packageName = sourceFile.getPackageName();
        String fileName = sourceFile.getName();
        String pathRelativeToSourceRoot =
            (Strings.isNotBlank(packageName) ? packageName + "/" : "") + fileName;
        String pathRelativeToIndexRoot;
        try {
          pathRelativeToIndexRoot = repoIndex.getSourcePath(pathRelativeToSourceRoot);
        } catch (SourceResolutionException e) {
          LOGGER.debug("Could not resolve source for path {}", pathRelativeToSourceRoot, e);
          continue;
        }

        if (pathRelativeToIndexRoot == null) {
          LOGGER.debug("Could not resolve source for path {}", pathRelativeToSourceRoot);
          continue;
        }

        LinesCoverage linesCoverage = getLinesCoverage(sourceFile);
        // backendCoverageData contains data for all modules in the repo,
        // but coverageBundle bundle only has source files that are relevant for the given module,
        // so we are not taking into account any backend coverage that is not relevant
        linesCoverage.coveredLines.or(
            backendCoverageData.getOrDefault(pathRelativeToIndexRoot, EMPTY_BIT_SET));

        mergedCoverageData.put(pathRelativeToIndexRoot, linesCoverage);

        coveredLines += linesCoverage.coveredLines.cardinality();
        totalLines += sourceFile.getLineCounter().getTotalCount();
      }
    }

    uploadMergedCoverageReport(mergedCoverageData);

    return Math.round((100d * coveredLines) / totalLines);
  }

  private void uploadMergedCoverageReport(Map<String, LinesCoverage> mergedCoverageData) {
    if (coverageReportUploader == null) {
      return;
    }

    String lcovReport = LcovReportWriter.toString(mergedCoverageData);
    try {
      coverageReportUploader.upload(
          "lcov", new ByteArrayInputStream(lcovReport.getBytes(StandardCharsets.UTF_8)));
    } catch (IOException e) {
      LOGGER.error("Error while uploading coverage report", e);
    }
  }

  private static LinesCoverage getLinesCoverage(ISourceNode coverage) {
    LinesCoverage linesCoverage = new LinesCoverage();

    int firstLine = coverage.getFirstLine();
    if (firstLine == -1) {
      return linesCoverage;
    }

    int lastLine = coverage.getLastLine();
    for (int lineIdx = firstLine; lineIdx <= lastLine; lineIdx++) {
      ILine line = coverage.getLine(lineIdx);
      if (line.getStatus() > ICounter.EMPTY) {
        linesCoverage.executableLines.set(lineIdx);
        if (line.getStatus() >= ICounter.FULLY_COVERED) {
          linesCoverage.coveredLines.set(lineIdx);
        }
      }
    }

    return linesCoverage;
  }
}

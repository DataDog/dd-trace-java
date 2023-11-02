package datadog.trace.civisibility.coverage;

import datadog.trace.civisibility.source.index.RepoIndex;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import javax.annotation.Nullable;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.data.ExecutionDataReader;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.report.FileMultiReportOutput;
import org.jacoco.report.IReportVisitor;
import org.jacoco.report.InputStreamSourceFileLocator;
import org.jacoco.report.html.HTMLFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class CoverageUtils {
  private static final Logger LOGGER = LoggerFactory.getLogger(CoverageUtils.class);

  public static ExecutionDataStore parse(byte[] rawCoverageData) {
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

  @Nullable
  public static IBundleCoverage createCoverageBundle(
      ExecutionDataStore coverageData, Collection<File> classesDirs) {
    try {
      CoverageBuilder coverageBuilder = new CoverageBuilder();
      Analyzer analyzer = new Analyzer(coverageData, coverageBuilder);
      for (File outputClassesDir : classesDirs) {
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

  public static void dumpCoverageReport(
      IBundleCoverage coverageBundle, RepoIndex repoIndex, String repoRoot, File reportFolder) {
    if (!reportFolder.exists() && !reportFolder.mkdirs()) {
      LOGGER.info("Skipping report generation, could not create report dir: {}", reportFolder);
      return;
    }
    try {
      final HTMLFormatter htmlFormatter = new HTMLFormatter();
      final IReportVisitor visitor =
          htmlFormatter.createVisitor(new FileMultiReportOutput(reportFolder));
      visitor.visitInfo(Collections.emptyList(), Collections.emptyList());
      visitor.visitBundle(coverageBundle, new RepoIndexFileLocator(repoIndex, repoRoot));
      visitor.visitEnd();
    } catch (Exception e) {
      LOGGER.error("Error while creating report in {}", reportFolder, e);
    }
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

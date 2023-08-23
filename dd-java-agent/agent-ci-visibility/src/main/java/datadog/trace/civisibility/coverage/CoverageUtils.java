package datadog.trace.civisibility.coverage;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.Collection;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.data.ExecutionDataReader;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
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

  public static long calculateCoveragePercentage(
      ExecutionDataStore coverageData, Collection<File> classesDirs) {
    try {
      CoverageBuilder coverageBuilder = new CoverageBuilder();
      Analyzer analyzer = new Analyzer(coverageData, coverageBuilder);
      for (File outputClassesDir : classesDirs) {
        if (outputClassesDir.exists()) {
          analyzer.analyzeAll(outputClassesDir);
        }
      }

      IBundleCoverage coverageBundle = coverageBuilder.getBundle("Module coverage data");
      ICounter instructionCounter = coverageBundle.getInstructionCounter();
      int totalInstructionsCount = instructionCounter.getTotalCount();
      int coveredInstructionsCount = instructionCounter.getCoveredCount();
      return Math.round((100d * coveredInstructionsCount) / totalInstructionsCount);

    } catch (Exception e) {
      LOGGER.error("Error while calculating coverage percentage", e);
      return -1;
    }
  }
}

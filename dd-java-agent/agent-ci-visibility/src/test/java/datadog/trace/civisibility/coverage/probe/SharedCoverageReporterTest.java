package datadog.trace.civisibility.coverage.probe;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.civisibility.coverage.SharedPart;
import datadog.trace.api.civisibility.coverage.SuiteSetupWindow;
import datadog.trace.api.civisibility.coverage.TestPreparationWindow;
import datadog.trace.api.civisibility.coverage.TestReport;
import datadog.trace.api.civisibility.coverage.TestReportFileEntry;
import datadog.trace.civisibility.coverage.probe.fixtures.SharedPartsWorkload;
import datadog.trace.civisibility.source.SourcePathResolver;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Shared is reported once per process, in one record per part, each with no span. */
class SharedCoverageReporterTest {

  private static final String FIXTURES = SharedPartsWorkload.class.getPackage().getName() + ".";
  private static final String WORKLOAD = SharedPartsWorkload.class.getName();

  /** The workload's file relative to its source root, as reference lines name it. */
  private static final String WORKLOAD_FILE = WORKLOAD.replace('.', '/') + ".java";

  private static final String SOURCE_ROOT = "src/test/java/";

  /**
   * Knows the workload's file only: no other test runs the workload, so its lines are this test's.
   */
  private static final SourcePathResolver WORKLOAD_FILE_ONLY =
      new SourcePathResolver() {
        @Override
        public Collection<String> getSourcePaths(@Nonnull Class<?> c) {
          return emptyList(); // source files resolve lines by path
        }

        @Override
        public Collection<String> getResourcePaths(@Nullable String relativePath) {
          return WORKLOAD_FILE.equals(relativePath)
              ? singletonList(SOURCE_ROOT + relativePath)
              : emptyList();
        }
      };

  @BeforeAll
  static void enableWindows() {
    TestPreparationWindow.enable();
  }

  @Test
  void reportsTheLinesOfEachPartWithNoSpan() throws Exception {
    // the lines of each step, by a recorder that shares no logic with the probes
    ReferenceLines.drain();
    Class<?> reference =
        Class.forName(WORKLOAD, true, new FixtureLoader(ReferenceLines::instrument, FIXTURES));
    BitSet initLines = ReferenceLines.drain().get(WORKLOAD_FILE);
    reference.getMethod("setUp").invoke(null);
    BitSet setupLines = ReferenceLines.drain().get(WORKLOAD_FILE);
    reference.getMethod("tearDown").invoke(null);
    BitSet teardownLines = ReferenceLines.drain().get(WORKLOAD_FILE);

    // the initializer runs with no window open
    Class<?> workload = Class.forName(WORKLOAD, true, new FixtureLoader(null, FIXTURES));
    Object suite = new Object();
    SuiteSetupWindow.open(suite, null, true);
    workload.getMethod("setUp").invoke(null);
    SuiteSetupWindow.finish(suite);
    workload.getMethod("tearDown").invoke(null);

    List<TestReport> reports =
        SharedCoverageReporter.buildReports(new SourceFiles(WORKLOAD_FILE_ONLY), DDTraceId.from(7));

    assertNotNull(initLines);
    assertEquals(initLines, workloadLines(reports, SharedPart.INIT));
    assertEquals(setupLines, workloadLines(reports, SharedPart.SUITE_SETUP));
    assertEquals(teardownLines, workloadLines(reports, SharedPart.UNOWNED));
    for (TestReport report : reports) {
      assertEquals(DDTraceId.from(7), report.getTestSessionId());
      assertNull(report.getTestSuiteId());
    }
  }

  /** The lines of the workload's file in the report of a part, or null if it has none. */
  @Nullable
  private static BitSet workloadLines(List<TestReport> reports, SharedPart part) {
    for (TestReport report : reports) {
      if (report.getSharedPart() != part) {
        continue;
      }
      for (TestReportFileEntry entry : report.getTestReportFileEntries()) {
        if (entry.getSourceFileName().equals(SOURCE_ROOT + WORKLOAD_FILE)) {
          return entry.getCoveredLines();
        }
      }
    }
    return null;
  }
}

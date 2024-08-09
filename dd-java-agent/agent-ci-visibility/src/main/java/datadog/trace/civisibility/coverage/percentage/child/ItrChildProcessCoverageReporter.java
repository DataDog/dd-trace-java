package datadog.trace.civisibility.coverage.percentage.child;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.coverage.CoverageProbes;
import datadog.trace.api.civisibility.coverage.CoverageStore;
import datadog.trace.api.civisibility.coverage.TestReport;
import datadog.trace.api.civisibility.coverage.TestReportFileEntry;
import datadog.trace.civisibility.ipc.ModuleCoverageDataItr;
import datadog.trace.civisibility.ipc.ModuleSignal;
import java.util.BitSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;

public class ItrChildProcessCoverageReporter implements ChildProcessCoverageReporter {

  private final Map<String, BitSet> coveredLinesByRelativeSourcePath = new ConcurrentHashMap<>();

  @Nullable
  @Override
  public ModuleSignal createCoverageSignal(long sessionId, long moduleId) {
    return new ModuleCoverageDataItr(sessionId, moduleId, coveredLinesByRelativeSourcePath);
  }

  /** The coverage stores created by the wrapped factory inform this class of their covered data. */
  @Override
  public CoverageStore.Factory wrapCoverageStoreFactory(CoverageStore.Factory delegate) {
    return new ItrCoverageStoreFactory(delegate);
  }

  private class ItrCoverageStoreFactory implements CoverageStore.Factory {
    private final CoverageStore.Factory delegate;

    private ItrCoverageStoreFactory(CoverageStore.Factory delegate) {
      this.delegate = delegate;
    }

    @Override
    public CoverageStore create(@Nullable TestIdentifier testIdentifier) {
      return new ItrCoverageStore(delegate.create(testIdentifier));
    }

    @Override
    public void setTotalProbeCount(String className, int totalProbeCount) {
      delegate.setTotalProbeCount(className, totalProbeCount);
    }
  }

  private class ItrCoverageStore implements CoverageStore {
    private final CoverageStore delegate;

    private ItrCoverageStore(CoverageStore delegate) {
      this.delegate = delegate;
    }

    @Override
    public CoverageProbes getProbes() {
      return delegate.getProbes();
    }

    @Override
    public boolean report(Long testSessionId, Long testSuiteId, long testSpanId) {
      boolean coverageGathered = delegate.report(testSessionId, testSuiteId, testSpanId);
      if (coverageGathered) {
        TestReport report = delegate.getReport();
        if (report != null) {
          for (TestReportFileEntry testReportFileEntry : report.getTestReportFileEntries()) {
            String sourcePath = testReportFileEntry.getSourceFileName();
            BitSet coveredLines = testReportFileEntry.getCoveredLines();
            if (coveredLines != null) {
              coveredLinesByRelativeSourcePath
                  .computeIfAbsent(sourcePath, sf -> new BitSet())
                  .or(coveredLines);
            }
          }
        }
      }
      return coverageGathered;
    }

    @Nullable
    @Override
    public TestReport getReport() {
      return delegate.getReport();
    }
  }
}

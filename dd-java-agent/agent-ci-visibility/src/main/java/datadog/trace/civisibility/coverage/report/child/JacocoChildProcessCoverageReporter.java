package datadog.trace.civisibility.coverage.report.child;

import datadog.trace.api.DDTraceId;
import datadog.trace.civisibility.ipc.ModuleCoverageDataJacoco;
import datadog.trace.civisibility.ipc.ModuleSignal;
import java.util.function.Supplier;
import javax.annotation.Nullable;

public class JacocoChildProcessCoverageReporter implements ChildProcessCoverageReporter {

  private final Supplier<byte[]> coverageDataSupplier;

  public JacocoChildProcessCoverageReporter(Supplier<byte[]> coverageDataSupplier) {
    this.coverageDataSupplier = coverageDataSupplier;
  }

  @Nullable
  @Override
  public ModuleSignal createCoverageSignal(DDTraceId sessionId, long moduleId) {
    byte[] coverageData = coverageDataSupplier.get();
    if (coverageData != null) {
      return new ModuleCoverageDataJacoco(sessionId, moduleId, coverageData);
    } else {
      return null;
    }
  }
}

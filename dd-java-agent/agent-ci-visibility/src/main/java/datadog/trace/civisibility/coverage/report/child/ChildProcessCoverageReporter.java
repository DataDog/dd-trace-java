package datadog.trace.civisibility.coverage.report.child;

import datadog.trace.api.DDTraceId;
import datadog.trace.civisibility.ipc.ModuleSignal;
import javax.annotation.Nullable;

/**
 * Creates coverage data payload that is sent from a child process (JVM forked to run tests) to the
 * parent process (build system). The format of transmitted coverage data differs depending on how
 * total coverage percentage is calculated (it is calculated differently if ITR is enabled).
 *
 * <p>The data sent isn't per-test coverage but coverage for the process as a whole.
 */
public interface ChildProcessCoverageReporter {
  @Nullable
  ModuleSignal createCoverageSignal(DDTraceId sessionId, long moduleId);
}

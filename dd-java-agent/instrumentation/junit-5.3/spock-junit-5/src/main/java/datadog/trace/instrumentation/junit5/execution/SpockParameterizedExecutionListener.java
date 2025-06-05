package datadog.trace.instrumentation.junit5.execution;

import datadog.trace.instrumentation.junit5.JUnitPlatformUtils;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.reporting.ReportEntry;

public class SpockParameterizedExecutionListener implements EngineExecutionListener {
  private final EngineExecutionListener delegate;
  private final Map<TestDescriptor, CompletableFuture<?>> pending;

  public SpockParameterizedExecutionListener(
      EngineExecutionListener delegate, Map<TestDescriptor, CompletableFuture<?>> pending) {
    this.delegate = delegate;
    this.pending = pending;
  }

  @Override
  public void dynamicTestRegistered(TestDescriptor testDescriptor) {
    delegate.dynamicTestRegistered(testDescriptor);
    if (JUnitPlatformUtils.isRetry(testDescriptor)) {
      // register generated retry descriptor
      pending.put(testDescriptor, new CompletableFuture<>());
    }
  }

  @Override
  public void executionSkipped(TestDescriptor testDescriptor, String reason) {
    delegate.executionSkipped(testDescriptor, reason);
  }

  @Override
  public void executionStarted(TestDescriptor testDescriptor) {
    delegate.executionStarted(testDescriptor);
  }

  @Override
  public void executionFinished(
      TestDescriptor testDescriptor, TestExecutionResult testExecutionResult) {
    delegate.executionFinished(testDescriptor, testExecutionResult);
  }

  @Override
  public void reportingEntryPublished(TestDescriptor testDescriptor, ReportEntry entry) {
    delegate.reportingEntryPublished(testDescriptor, entry);
  }
}

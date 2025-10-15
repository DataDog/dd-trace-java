package datadog.trace.instrumentation.junit5;

import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.reporting.ReportEntry;

public class CompositeEngineListener implements EngineExecutionListener {

  private final EngineExecutionListener tracingListener;
  private final EngineExecutionListener delegate;

  public CompositeEngineListener(
      EngineExecutionListener tracingListener, EngineExecutionListener delegate) {
    this.tracingListener = tracingListener;
    this.delegate = delegate;
  }

  @Override
  public void dynamicTestRegistered(TestDescriptor testDescriptor) {
    // tracing listener is not interested in this event
    delegate.dynamicTestRegistered(testDescriptor);
  }

  @Override
  public void reportingEntryPublished(TestDescriptor testDescriptor, ReportEntry entry) {
    // tracing listener is not interested in this event
    delegate.dynamicTestRegistered(testDescriptor);
  }

  @Override
  public void executionStarted(TestDescriptor testDescriptor) {
    if (!TestDataFactory.shouldBeTraced(testDescriptor)) {
      return;
    }
    tracingListener.executionStarted(testDescriptor);
    delegate.executionStarted(testDescriptor);
  }

  @Override
  public void executionSkipped(TestDescriptor testDescriptor, String reason) {
    if (!TestDataFactory.shouldBeTraced(testDescriptor)) {
      return;
    }
    tracingListener.executionSkipped(testDescriptor, reason);
    delegate.executionSkipped(testDescriptor, reason);
  }

  @Override
  public void executionFinished(
      TestDescriptor testDescriptor, TestExecutionResult testExecutionResult) {
    if (!TestDataFactory.shouldBeTraced(testDescriptor)) {
      return;
    }
    tracingListener.executionFinished(testDescriptor, testExecutionResult);
    delegate.executionFinished(testDescriptor, testExecutionResult);
  }
}

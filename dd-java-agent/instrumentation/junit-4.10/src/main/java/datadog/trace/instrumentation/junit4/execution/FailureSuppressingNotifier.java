package datadog.trace.instrumentation.junit4.execution;

import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.instrumentation.junit4.JUnit4Utils;
import datadog.trace.instrumentation.junit4.TracingListener;
import java.util.List;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;

public class FailureSuppressingNotifier extends RunNotifier {

  private final TestExecutionPolicy executionPolicy;

  public FailureSuppressingNotifier(TestExecutionPolicy executionPolicy, RunNotifier notifier) {
    this.executionPolicy = executionPolicy;

    List<RunListener> listeners = JUnit4Utils.runListenersFromRunNotifier(notifier);
    for (RunListener listener : listeners) {
      addListener(listener);
    }
  }

  @Override
  public void fireTestFailure(Failure failure) {
    if (!executionPolicy.suppressFailures()) {
      super.fireTestFailure(failure);
      return;
    }

    List<RunListener> listeners = JUnit4Utils.runListenersFromRunNotifier(this);
    for (RunListener listener : listeners) {
      TracingListener tracingListener = JUnit4Utils.toTracingListener(listener);
      if (tracingListener != null) {
        tracingListener.testFailure(failure);
      } else {
        // Converting failure into assumption failure,
        // so that the build does not fail.
        listener.testAssumptionFailure(failure);
      }
    }
  }
}

package datadog.trace.instrumentation.junit4.retry;

import datadog.trace.api.civisibility.retry.TestRetryPolicy;
import datadog.trace.instrumentation.junit4.JUnit4Utils;
import datadog.trace.instrumentation.junit4.TracingListener;
import java.util.List;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;

public class RetryAwareNotifier extends RunNotifier {

  private final TestRetryPolicy retryPolicy;

  private boolean failed;

  public RetryAwareNotifier(TestRetryPolicy retryPolicy, RunNotifier notifier) {
    this.retryPolicy = retryPolicy;

    List<RunListener> listeners = JUnit4Utils.runListenersFromRunNotifier(notifier);
    for (RunListener listener : listeners) {
      addListener(listener);
    }
  }

  @Override
  public void fireTestFailure(Failure failure) {
    this.failed = true;

    if (!retryPolicy.retryPossible() || !retryPolicy.suppressFailures()) {
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

  public boolean getAndResetFailedFlag() {
    boolean failed = this.failed;
    this.failed = false;
    return failed;
  }
}

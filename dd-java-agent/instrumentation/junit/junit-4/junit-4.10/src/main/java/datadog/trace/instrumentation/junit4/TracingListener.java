package datadog.trace.instrumentation.junit4;

import org.junit.runner.Description;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

public abstract class TracingListener extends RunListener {

  public abstract void testFailure(Failure failure);

  public abstract void testSuiteStarted(final Description description);

  public abstract void testSuiteFinished(final Description description);
}

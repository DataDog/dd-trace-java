package org.example;

import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunNotifier;

@RunWith(TestFailedSuiteCustomRunner.SuiteFailureRunner.class)
public class TestFailedSuiteCustomRunner {

  public static class SuiteFailureRunner extends TestSucceedCustomRunner.CustomRunner {
    public SuiteFailureRunner(Class<?> testClass) {
      super(testClass);
    }

    @Override
    protected void emitTestEvents(RunNotifier notifier) {
      // Like ScalaTest's JUnitRunner when a suite aborts before running any test.
      Description suite = Description.createSuiteDescription(testClass.getName());
      notifier.fireTestFailure(new Failure(suite, new IllegalStateException("suite aborted")));
      notifier.fireTestFinished(suite);
    }
  }
}

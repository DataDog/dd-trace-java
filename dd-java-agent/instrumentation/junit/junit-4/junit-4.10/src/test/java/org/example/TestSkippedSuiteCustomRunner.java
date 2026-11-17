package org.example;

import org.junit.AssumptionViolatedException;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunNotifier;

@RunWith(TestSkippedSuiteCustomRunner.SuiteAssumptionFailureRunner.class)
public class TestSkippedSuiteCustomRunner {

  public static class SuiteAssumptionFailureRunner extends TestSucceedCustomRunner.CustomRunner {
    public SuiteAssumptionFailureRunner(Class<?> testClass) {
      super(testClass);
    }

    @Override
    protected void emitTestEvents(RunNotifier notifier) {
      Description suite = Description.createSuiteDescription(testClass.getName());
      notifier.fireTestAssumptionFailed(
          new Failure(suite, new AssumptionViolatedException("suite skipped")));
      notifier.fireTestFinished(suite);
    }
  }
}

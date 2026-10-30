package org.example;

import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runner.Runner;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunNotifier;

@RunWith(TestSucceedCustomRunner.CustomRunner.class)
public class TestSucceedCustomRunner {

  public static class AnotherSuite extends TestSucceedCustomRunner {}

  public static class CustomRunner extends Runner {
    private final Class<?> testClass;

    public CustomRunner(Class<?> testClass) {
      this.testClass = testClass;
    }

    @Override
    public Description getDescription() {
      Description suite = Description.createSuiteDescription(testClass);
      suite.addChild(Description.createTestDescription(testClass, "adds"));
      suite.addChild(Description.createTestDescription(testClass, "existing failure"));
      suite.addChild(Description.createTestDescription(testClass, "ignored"));
      return suite;
    }

    @Override
    public void run(RunNotifier notifier) {
      // Like ScalaTest's JUnitRunner, emit test callbacks without suite lifecycle callbacks.
      notifier.fireTestIgnored(testDescription("ignored"));
      Description passing = testDescription("adds");
      notifier.fireTestStarted(passing);
      notifier.fireTestFinished(passing);
      Description failing = testDescription("existing failure");
      notifier.fireTestStarted(failing);
      notifier.fireTestFailure(new Failure(failing, new AssertionError("existing failure")));
      notifier.fireTestFinished(failing);
    }

    private Description testDescription(String name) {
      return Description.createSuiteDescription(name + "(" + testClass.getName() + ")");
    }
  }
}

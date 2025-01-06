package datadog.context;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

public class SourceFileExtension implements TestExecutionListener {
  public void executionStarted(TestIdentifier testIdentifier) {
    System.out.println("EXECUTIONSTARTED.");
  }

  public void testPlanExecutionStarted(TestPlan testPlan) {
    System.out.println("TESTPLANEXECUTIONSTARTED.");
  }

  public void executionFinished(
      TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
    System.out.println("EXECUTIONFINISHED.");
    // get test source file info here
  }

  public void testPlanExecutionFinished(TestPlan testPlan) {
    System.out.println("TESTPLANEXECUTIONFINISHED.");
    // get xml report file
    // modify report with test source file info
    // save resulting file
  }
}

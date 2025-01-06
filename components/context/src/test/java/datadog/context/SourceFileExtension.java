package datadog.context;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    // this should happen in testPlanExecutionFinished after all tests are run, but this method is
    // easier to test locally for now

    Map<String, String> sourceFiles = TestSourceFileExtension.getSourceFiles();

    // hard coded for now
    // get xml report file
    Path path =
        Paths.get(
            "/Users/sarah.chen/Source/github.com/DataDog/dd-trace-java/components/context/build/test-results/test/TEST-datadog.context.ContextKeyTest.xml");
    try {
      String content = new String(Files.readAllBytes(path));

      // modify report with test source file info
      // use regex pattern to get class name
      Pattern pattern = Pattern.compile("<testcase(.*?)classname=\"(.*?)\"(.*?)>");
      Matcher matcher = pattern.matcher(content);
      StringBuffer result = new StringBuffer();
      while (matcher.find()) {
        String attributes = matcher.group(1);
        String className = matcher.group(2);
        String timeAttribute = matcher.group(3);

        // add source file attribute
        String fileAttribute = "";
        if (sourceFiles.containsKey(className)) {
          fileAttribute = " file=\"" + sourceFiles.get(className) + "\"";
        }
        String newTestCase =
            "<testcase"
                + attributes
                + "classname=\""
                + className
                + "\""
                + fileAttribute
                + timeAttribute
                + ">";
        matcher.appendReplacement(result, newTestCase);
      }
      // add the rest
      matcher.appendTail(result);

      // set old path to new xml result
      // System.out.println("result: " + result); shows that result is correct, but haven't figured
      // out a way to overwrite the original path yet

    } catch (Exception e) {
      System.out.println("didnt work.");
    }
  }

  public void testPlanExecutionFinished(TestPlan testPlan) {
    System.out.println("TESTPLANEXECUTIONFINISHED.");
    // get xml report file
    // modify report with test source file info
    // save resulting file
  }
}

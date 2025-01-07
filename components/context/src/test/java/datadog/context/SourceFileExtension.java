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

    // for each test
    for (String sourceFile : sourceFiles.keySet()) {
      String pathString =
          Paths.get("").toAbsolutePath() + "/build/test-results/test/TEST-" + sourceFile + ".xml";

      // get xml report file
      Path filePath = Paths.get(pathString);
      try {
        String fileContent = new String(Files.readAllBytes(filePath));

        // modify report with test source file info
        // use regex pattern to get class name
        Pattern pattern = Pattern.compile("<testcase(.*?)classname=\"(.*?)\"(.*?)>");
        Matcher matcher = pattern.matcher(fileContent);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
          String beg_attributes = matcher.group(1);
          String className = matcher.group(2);
          String end_attributes = matcher.group(3);

          // add source file attribute
          String fileAttribute = "";
          if (sourceFiles.containsKey(className)) {
            fileAttribute = " file=\"" + sourceFiles.get(className) + "\"";
          }
          String newTestCase =
              "<testcase"
                  + beg_attributes
                  + "classname=\""
                  + className
                  + "\""
                  + fileAttribute
                  + end_attributes
                  + ">";
          matcher.appendReplacement(result, newTestCase);
        }
        // add the rest
        matcher.appendTail(result);

        // set old filePath to new xml result
        //        System.out.println("result: " + result);
        Files.write(filePath, result.toString().getBytes()); // does not work
      } catch (Exception e) {
        System.out.println("Modifying XML files did not work.");
      }
    }
  }

  public void testPlanExecutionFinished(TestPlan testPlan) {
    System.out.println("TESTPLANEXECUTIONFINISHED.");
    // get xml report file
    // modify report with test source file info
    // save resulting file
  }
}

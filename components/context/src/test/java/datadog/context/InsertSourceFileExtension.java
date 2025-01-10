package datadog.context;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

public class InsertSourceFileExtension implements TestExecutionListener {
  @Override
  public void executionStarted(TestIdentifier testIdentifier) {
    System.out.println(testIdentifier.getDisplayName() + " test started.");
  }

  @Override
  public void testPlanExecutionStarted(TestPlan testPlan) {
    // does not print when tested locally
    System.out.println("---testPlanExecutionStarted---");
  }

  @Override
  public void executionFinished(
      TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
    // get mapping of test classname to source file
    Map<String, String> sourceFiles = GatherSourceFileInfoExtension.getSourceFiles();

    // for each test...
    for (String sourceFile : sourceFiles.keySet()) {
      // get xml report file
      String filePath =
          Paths.get("").toAbsolutePath() + "/build/test-results/test/TEST-" + sourceFile + ".xml";
      try {
        String fileContent = new String(Files.readAllBytes(Paths.get(filePath)));

        // add test source file info to report
        Pattern pattern = Pattern.compile("<testcase(.*?)classname=\"(.*?)\"(.*?)>");
        Matcher matcher = pattern.matcher(fileContent);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
          String begAttributes = matcher.group(1);
          String className = matcher.group(2);
          String endAttributes = matcher.group(3);

          String fileAttribute = " file=\"" + sourceFiles.getOrDefault(className, "") + "\"";
          String newTestCase =
              "<testcase"
                  + begAttributes
                  + "classname=\""
                  + className
                  + "\""
                  + fileAttribute
                  + endAttributes
                  + ">";
          matcher.appendReplacement(result, newTestCase);
        }
        matcher.appendTail(result);

        // set old filePath to new xml result
        // this logic must be wrong or go elsewhere bc its getting overwritten. `result` output
        // seems correct.
        BufferedWriter writer = new BufferedWriter(new FileWriter(filePath));
        writer.write(result.toString());
        writer.close();
      } catch (Exception e) {
        System.out.println("Modifying XML files did not work.");
      }
    }
  }

  @Override
  public void testPlanExecutionFinished(TestPlan testPlan) {
    // does not print when tested locally
    System.out.println("---testPlanExecutionFinished---.");
  }
}

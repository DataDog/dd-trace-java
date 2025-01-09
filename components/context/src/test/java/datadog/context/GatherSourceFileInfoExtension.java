package datadog.context;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;

public class GatherSourceFileInfoExtension implements TestInstancePostProcessor {
  private static Map<String, String> sourceFiles = new HashMap<String, String>();

  @Override
  public void postProcessTestInstance(Object testInstance, ExtensionContext context) {
    getTestData(context);
  }

  public static Map<String, String> getSourceFiles() {
    return sourceFiles;
  }

  private static void getTestData(ExtensionContext context) {
    // get test class name and source file
    String testClassName = context.getTestClass().get().getName();
    String testClassPath = testClassName.replace(".", "/") + ".java";
    String absolutePath = Paths.get("").toAbsolutePath() + "/src/test/java/" + testClassPath;
    String subPath =
        absolutePath.substring(absolutePath.indexOf("dd-trace-java") + "dd-trace-java".length());
    // add info to sourceFiles map
    sourceFiles.put(testClassName, subPath);
  }
}

package datadog.context;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;

public class TestSourceFileExtension implements TestInstancePostProcessor {
  private static Map<String, String> sourceFiles = new HashMap<String, String>();

  @Override
  public void postProcessTestInstance(Object testInstance, ExtensionContext context) {
    getTestData(context);
  }

  public static Map<String, String> getSourceFiles() {
    return sourceFiles;
  }

  private static void getTestData(ExtensionContext context) {
    // get test class and test class name
    Class<?> testClass = context.getTestClass().get();
    String testClassName = testClass.getName();
    // get URL of test class file
    URL resource = testClass.getResource(testClass.getSimpleName() + ".class");
    if (resource != null) {
      // get path after "dd-trace-java" and before the final "/"
      String absolutePath = resource.getPath();
      String subPath =
          absolutePath.substring(
              absolutePath.indexOf("dd-trace-java") + "dd-trace-java".length(),
              absolutePath.lastIndexOf("/"));
      // add the test class name and source file to sourceFiles
      sourceFiles.put(testClassName, subPath);
    } else {
      sourceFiles.put(testClassName, "");
    }
  }
}

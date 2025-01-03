package datadog.context;

import java.net.URL;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;

public class TestSourceFileExtension implements TestInstancePostProcessor {
  @Override
  public void postProcessTestInstance(Object testInstance, ExtensionContext context) {
    getTestData(context);
  }

  private static void getTestData(ExtensionContext context) {
    System.out.println("----------------------------------");

    Class<?> testClass = context.getTestClass().get();
    String testClassName = testClass.getName();
    System.out.println("testClassName: " + testClassName);
    URL resource = testClass.getResource(testClass.getSimpleName() + ".class");
    if (resource != null) {
      String absolutePath = resource.getPath();
      String subPath =
          absolutePath.substring(
              absolutePath.indexOf("dd-trace-java") + "dd-trace-java".length(),
              absolutePath.lastIndexOf("/"));
      System.out.println("path: " + subPath);
    } else {
      System.out.println("no path.");
    }

    System.out.println("----------------------------------");
  }
}

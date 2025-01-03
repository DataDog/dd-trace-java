package datadog.context;

import java.net.URL;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;

public class TestSourceFileExtension implements TestInstancePostProcessor {
  @Override
  public void postProcessTestInstance(Object testInstance, ExtensionContext context) {
    System.out.println("--- in postProcessTestInstance ---");
    getTestData(context);
  }

  private static void getTestData(ExtensionContext context) {
    System.out.println("--------- in getTestData ---------");

    String displayName = context.getDisplayName();
    System.out.println("displayName: " + displayName);

    Class<?> testClass = context.getTestClass().get();
    String testClassName = testClass.getName();
    System.out.println("testClassName: " + testClassName);
    URL resource = testClass.getResource(testClass.getSimpleName() + ".class");
    if (resource != null) {
      String absolutePath = resource.getPath();
      System.out.println("absolutePath: " + absolutePath);
    } else {
      System.out.println("no absolute path.");
    }

    System.out.println("----------------------------------");
  }
}

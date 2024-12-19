package datadog.trace.test.util

import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestWatcher

class TestSourceFileExtension implements TestWatcher {
  public TestSourceFileExtension() {
    System.out.println("TestSourceFileExtension initialized!")
  }

  @Override
  public void testSuccessful(ExtensionContext context) {
    System.out.println("test was successful!")
    getTestData(context)
  }

  @Override
  public void testFailed(ExtensionContext context, Throwable cause) {
    System.out.println("test failed!")
    getTestData(context)
  }

  private void getTestData(ExtensionContext context) {
    String testClassName = context.getTestClass().get().getSimpleName()
    String testMethodName = context.getTestMethod().get().getName()
    String className = context.getClass()
    String requiredTestClassName = context.getRequiredTestClass().getName()
    String requiredTestMethodName = context.getRequiredTestMethod().getName()

    System.out.println("--------------------------")
    System.out.println("testClassName: " + testClassName)
    System.out.println("testMethodName: " + testMethodName)
    System.out.println("className: " + className)
    System.out.println("requiredTestClassName: " + requiredTestClassName)
    System.out.println("requiredTestMethodName: " + requiredTestMethodName)
    System.out.println("--------------------------")
  }
}

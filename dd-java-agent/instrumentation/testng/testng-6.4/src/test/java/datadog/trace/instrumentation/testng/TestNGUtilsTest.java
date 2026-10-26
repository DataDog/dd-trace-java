package datadog.trace.instrumentation.testng;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.tabletest.junit.TableTest;
import org.testng.ITestNGMethod;
import org.testng.ITestResult;

class TestNGUtilsTest {

  @TableTest({
    "scenario    | resultName    | expectedName ",
    "null name   |               | test_method  ",
    "empty name  | ''            | test_method  ",
    "method name | test_method   | test_method  ",
    "custom name | 'custom name' | 'custom name'"
  })
  void preservesCustomNamesAndFallsBackToMethodNames(String resultName, String expectedName) {
    ITestNGMethod method = mock(ITestNGMethod.class);
    when(method.getMethodName()).thenReturn("test_method");
    ITestResult result = mock(ITestResult.class);
    when(result.getName()).thenReturn(resultName);
    when(result.getMethod()).thenReturn(method);
    when(result.getInstanceName()).thenReturn("TestSuite");

    assertEquals(expectedName, TestNGUtils.toTestIdentifier(result).getName());
  }
}

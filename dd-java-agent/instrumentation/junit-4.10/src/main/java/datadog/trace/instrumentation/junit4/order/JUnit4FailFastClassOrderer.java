package datadog.trace.instrumentation.junit4.order;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.instrumentation.junit4.JUnit4Utils;
import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.List;
import javax.annotation.Nullable;

public class JUnit4FailFastClassOrderer implements Comparator<Class<?>> {

  @Nullable private final TestEventsHandler<TestSuiteDescriptor, TestDescriptor> testEventsHandler;

  public JUnit4FailFastClassOrderer(
      @Nullable TestEventsHandler<TestSuiteDescriptor, TestDescriptor> testEventsHandler) {
    this.testEventsHandler = testEventsHandler;
  }

  public int classExecutionPriority(Class<?> clazz) {
    TestFrameworkInstrumentation framework = JUnit4Utils.classToFramework(clazz);
    if (testEventsHandler == null || framework != TestFrameworkInstrumentation.JUNIT4) {
      return 0;
    }

    List<Method> children = JUnit4Utils.getTestMethods(clazz);
    if (children.isEmpty()) {
      return 0;
    }

    int childrenPrioritySum = 0;
    for (Method child : children) {
      TestIdentifier testIdentifier = new TestIdentifier(clazz.getName(), child.getName(), null);
      TestSourceData testSourceData = new TestSourceData(clazz, child);
      childrenPrioritySum += testEventsHandler.executionPriority(testIdentifier, testSourceData);
    }

    return childrenPrioritySum / children.size();
  }

  @Override
  public int compare(Class<?> o1, Class<?> o2) {
    return classExecutionPriority(o2) - classExecutionPriority(o1);
  }
}

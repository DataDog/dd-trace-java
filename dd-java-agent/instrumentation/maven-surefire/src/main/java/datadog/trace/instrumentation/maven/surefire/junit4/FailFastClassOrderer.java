package datadog.trace.instrumentation.maven.surefire.junit4;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.instrumentation.junit4.JUnit4Utils;
import datadog.trace.instrumentation.junit4.TestEventsHandlerHolder;
import java.lang.reflect.Method;
import java.util.List;

public abstract class FailFastClassOrderer {

  public static int classExecutionPriority(Class<?> clazz) {
    TestFrameworkInstrumentation framework = JUnit4Utils.classToFramework(clazz);
    if (framework != TestFrameworkInstrumentation.JUNIT4) {
      return 0;
    }

    TestEventsHandler<TestSuiteDescriptor, TestDescriptor> testEventsHandler =
        TestEventsHandlerHolder.HANDLERS.get(TestFrameworkInstrumentation.JUNIT4);

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
}

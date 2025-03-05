package datadog.trace.instrumentation.junit5.order;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.instrumentation.junit5.JUnitPlatformUtils;
import java.util.Comparator;
import java.util.Set;
import javax.annotation.Nullable;
import org.junit.jupiter.api.ClassDescriptor;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.ClassOrdererContext;
import org.junit.platform.engine.TestDescriptor;

public class FailFastClassOrderer implements ClassOrderer {

  private final TestEventsHandler<TestDescriptor, TestDescriptor> testEventsHandler;
  private final @Nullable ClassOrderer delegate;
  private final Comparator<ClassDescriptor> executionOrderComparator;

  public FailFastClassOrderer(
      TestEventsHandler<TestDescriptor, TestDescriptor> testEventsHandler,
      @Nullable ClassOrderer delegate) {
    this.testEventsHandler = testEventsHandler;
    this.delegate = delegate;
    this.executionOrderComparator = Comparator.comparing(this::classExecutionPriority).reversed();
  }

  private int classExecutionPriority(ClassDescriptor classDescriptor) {
    TestDescriptor testDescriptor = JUnit5OrderUtils.getTestDescriptor(classDescriptor);
    return executionPriority(testDescriptor);
  }

  private int executionPriority(TestDescriptor testDescriptor) {
    if (testDescriptor == null) {
      return 0;
    }

    if (testDescriptor.isTest() || JUnitPlatformUtils.isParameterizedTest(testDescriptor)) {
      TestIdentifier testIdentifier = JUnitPlatformUtils.toTestIdentifier(testDescriptor);
      TestSourceData testSourceData = JUnitPlatformUtils.toTestSourceData(testDescriptor);
      return testEventsHandler.executionPriority(testIdentifier, testSourceData);
    }

    Set<? extends TestDescriptor> children = testDescriptor.getChildren();
    if (children.isEmpty()) {
      return 0;
    }

    // there's no specific meaning to the average of children priorities,
    // it is just a heuristic to try to favor classes with higher percentage of failure-prone tests
    int childrenPrioritySum = 0;
    for (TestDescriptor child : children) {
      childrenPrioritySum += executionPriority(child);
    }
    return childrenPrioritySum / children.size();
  }

  @Override
  public void orderClasses(ClassOrdererContext classOrdererContext) {
    if (delegate != null) {
      // first use delegate if available
      delegate.orderClasses(classOrdererContext);
    }
    // then apply our ordering (since sorting is stable, delegate's ordering will be preserved for
    // classes with the same "known/stable" status)
    classOrdererContext.getClassDescriptors().sort(executionOrderComparator);
  }
}

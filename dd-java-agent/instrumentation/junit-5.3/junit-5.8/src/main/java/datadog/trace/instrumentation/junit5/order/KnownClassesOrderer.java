package datadog.trace.instrumentation.junit5.order;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.instrumentation.junit5.JUnitPlatformUtils;
import java.util.Comparator;
import java.util.Set;
import javax.annotation.Nullable;
import org.junit.jupiter.api.ClassDescriptor;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.ClassOrdererContext;
import org.junit.platform.engine.TestDescriptor;

public class KnownClassesOrderer implements ClassOrderer {

  private final TestEventsHandler<TestDescriptor, TestDescriptor> testEventsHandler;
  private final @Nullable ClassOrderer delegate;
  private final Comparator<ClassDescriptor> knownTestSuitesComparator;

  public KnownClassesOrderer(
      TestEventsHandler<TestDescriptor, TestDescriptor> testEventsHandler,
      @Nullable ClassOrderer delegate) {
    this.testEventsHandler = testEventsHandler;
    this.delegate = delegate;
    this.knownTestSuitesComparator = Comparator.comparing(this::knownTestsPercentage);
  }

  private int knownTestsPercentage(ClassDescriptor classDescriptor) {
    TestDescriptor testDescriptor = JUnit5OrderUtils.getTestDescriptor(classDescriptor);
    return 100 - unknownTestsPercentage(testDescriptor);
  }

  private int unknownTestsPercentage(TestDescriptor testDescriptor) {
    if (testDescriptor == null) {
      return 0;
    }
    if (testDescriptor.isTest() || JUnitPlatformUtils.isParameterizedTest(testDescriptor)) {
      TestIdentifier testIdentifier = JUnitPlatformUtils.toTestIdentifier(testDescriptor);
      return testEventsHandler.isNew(testIdentifier) ? 100 : 0;
    }
    Set<? extends TestDescriptor> children = testDescriptor.getChildren();
    if (children.isEmpty()) {
      return 0;
    }

    int uknownTestsPercentage = 0;
    for (TestDescriptor child : children) {
      uknownTestsPercentage += unknownTestsPercentage(child);
    }
    return uknownTestsPercentage / children.size();
  }

  @Override
  public void orderClasses(ClassOrdererContext classOrdererContext) {
    if (delegate != null) {
      // first use delegate if available
      delegate.orderClasses(classOrdererContext);
    }
    // then apply our ordering (since sorting is stable, delegate's ordering will be preserved for
    // classes with the same "known" status)
    classOrdererContext.getClassDescriptors().sort(knownTestSuitesComparator);
  }
}

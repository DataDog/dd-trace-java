package datadog.trace.instrumentation.junit5.order;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.instrumentation.junit5.JUnitPlatformUtils;
import java.util.Comparator;
import javax.annotation.Nullable;
import org.junit.jupiter.api.MethodDescriptor;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.MethodOrdererContext;
import org.junit.platform.engine.TestDescriptor;

public class FailFastMethodOrderer implements MethodOrderer {

  private final TestEventsHandler<TestDescriptor, TestDescriptor> testEventsHandler;
  private final @Nullable MethodOrderer delegate;
  private final Comparator<MethodDescriptor> knownTestMethodsComparator;

  public FailFastMethodOrderer(
      TestEventsHandler<TestDescriptor, TestDescriptor> testEventsHandler,
      @Nullable MethodOrderer delegate) {
    this.testEventsHandler = testEventsHandler;
    this.delegate = delegate;
    this.knownTestMethodsComparator = Comparator.comparing(this::isKnownAndStable);
  }

  private boolean isKnownAndStable(MethodDescriptor methodDescriptor) {
    TestDescriptor testDescriptor = JUnit5OrderUtils.getTestDescriptor(methodDescriptor);
    if (testDescriptor == null) {
      return true;
    }
    TestIdentifier testIdentifier = JUnitPlatformUtils.toTestIdentifier(testDescriptor);
    return !testEventsHandler.isNew(testIdentifier) && !testEventsHandler.isFlaky(testIdentifier);
  }

  @Override
  public void orderMethods(MethodOrdererContext methodOrdererContext) {
    if (delegate != null) {
      // first use delegate if available
      delegate.orderMethods(methodOrdererContext);
    }
    // then apply our ordering (since sorting is stable, delegate's ordering will be preserved for
    // methods with the same "known/stable" status)
    methodOrdererContext.getMethodDescriptors().sort(knownTestMethodsComparator);
  }
}

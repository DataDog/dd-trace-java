package datadog.trace.instrumentation.junit4.order;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.events.TestDescriptor;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.instrumentation.junit4.JUnit4Utils;
import java.util.Comparator;
import org.junit.runner.Description;

public class FailFastDescriptionComparator implements Comparator<Description> {

  private final TestEventsHandler<TestSuiteDescriptor, TestDescriptor> handler;

  public FailFastDescriptionComparator(
      TestEventsHandler<TestSuiteDescriptor, TestDescriptor> handler) {
    this.handler = handler;
  }

  private int executionPriority(Description description) {
    TestIdentifier testIdentifier = JUnit4Utils.toTestIdentifier(description);
    TestSourceData testSourceData = JUnit4Utils.toTestSourceData(description);
    return handler.executionPriority(testIdentifier, testSourceData);
  }

  @Override
  public int compare(Description o1, Description o2) {
    return executionPriority(o2) - executionPriority(o1);
  }
}

package datadog.trace.instrumentation.junit5;

import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.launcher.PostDiscoveryFilter;

public class ItrPostDiscoveryFilter implements PostDiscoveryFilter {

  @Override
  public FilterResult apply(TestDescriptor testDescriptor) {
    if (!ItrPredicate.INSTANCE.test(testDescriptor)) {
      return FilterResult.excluded("Skipped by Datadog Intelligent Test Runner");
    } else {
      return FilterResult.included(null);
    }
  }
}

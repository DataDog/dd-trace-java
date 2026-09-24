package datadog.trace.test.util;

import static datadog.trace.test.util.FlakyJUnitExtension.findFlaky;

import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.PostDiscoveryFilter;

/** Excludes non-flaky JUnit tests from flaky-only runs before execution. */
public final class FlakyJUnitFilter implements PostDiscoveryFilter {
  @Override
  public FilterResult apply(TestDescriptor descriptor) {
    if (!"true".equals(System.getProperty("run.flaky.tests"))
        || !descriptor.getUniqueId().getEngineId().filter("junit-jupiter"::equals).isPresent()) {
      return FilterResult.included("Flaky-only filtering does not apply");
    }
    TestSource source = descriptor.getSource().orElse(null);
    if (source instanceof MethodSource) {
      MethodSource method = (MethodSource) source;
      return findFlaky(method.getJavaClass(), method.getJavaMethod()) != null
          ? FilterResult.included("Flaky test")
          : FilterResult.excluded("Test is not flaky");
    }
    // Keep containers until their methods have been filtered; JUnit prunes empty containers.
    return FilterResult.included("Container may contain flaky tests");
  }
}

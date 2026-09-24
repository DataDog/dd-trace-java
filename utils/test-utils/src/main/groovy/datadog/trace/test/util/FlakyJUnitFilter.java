package datadog.trace.test.util;

import static datadog.trace.test.util.FlakyJUnitExtension.findFlaky;

import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.PostDiscoveryFilter;

/** Filters JUnit tests according to the configured flaky-test mode before execution. */
public final class FlakyJUnitFilter implements PostDiscoveryFilter {
  @Override
  public FilterResult apply(TestDescriptor descriptor) {
    String mode = System.getProperty("run.flaky.tests");
    boolean runFlakyTests = "true".equals(mode);
    boolean skipFlakyTests = "false".equals(mode);
    if ((!runFlakyTests && !skipFlakyTests)
        || !descriptor.getUniqueId().getEngineId().filter("junit-jupiter"::equals).isPresent()) {
      return FilterResult.included("Flaky filtering does not apply");
    }
    TestSource source = descriptor.getSource().orElse(null);
    if (source instanceof MethodSource) {
      MethodSource method = (MethodSource) source;
      boolean flaky = findFlaky(method.getJavaClass(), method.getJavaMethod()) != null;
      if (runFlakyTests) {
        return flaky
            ? FilterResult.included("Flaky test")
            : FilterResult.excluded("Test is not flaky");
      }
      return flaky
          ? FilterResult.excluded("Flaky test")
          : FilterResult.included("Test is not flaky");
    }
    // Keep containers until their methods have been filtered; JUnit prunes empty containers.
    return FilterResult.included("Container may contain flaky tests");
  }
}

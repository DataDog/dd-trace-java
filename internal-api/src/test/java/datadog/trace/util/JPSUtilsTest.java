package datadog.trace.util;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import datadog.trace.api.Platform;
import org.junit.jupiter.api.Test;

public class JPSUtilsTest {
  @Test
  void testJava9AndUpJFRAccess() {
    // For Java 9 and above, jvmstat cannot be called directly without module patching
    assumeTrue(Platform.isJavaVersionAtLeast(9) && !Platform.isJ9());
    // todo get instrumentation working within a test to assert that patching succeeds
  }
}

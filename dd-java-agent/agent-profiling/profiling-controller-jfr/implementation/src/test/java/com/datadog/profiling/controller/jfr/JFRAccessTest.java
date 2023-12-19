package com.datadog.profiling.controller.jfr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import datadog.trace.api.Platform;
import org.junit.jupiter.api.Test;

public class JFRAccessTest {
  @Test
  void testJava8JFRAccess() {
    // For Java 9 and above, the JFR access requires instrumentation in order to patch the module
    // access
    assumeTrue(Platform.isJavaVersion(8) && !Platform.isJ9() && !Platform.isOracleJDK8());

    // just do a sanity check that it is possible to instantiate the class and call
    // 'setStackDepth()'
    SimpleJFRAccess jfrAccess = new SimpleJFRAccess();
    assertTrue(jfrAccess.setStackDepth(42));
  }

  @Test
  void testJ9JFRAccess() {
    assumeTrue(Platform.isJ9());

    // need to run a bogus setup first
    JFRAccess.setup(null);
    // make sure that an attempt to get the instance returns the NOOP implementation and does not
    // throw exceptions
    assertEquals(JFRAccess.NOOP, JFRAccess.instance());
  }
}

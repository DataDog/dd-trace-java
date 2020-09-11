package com.datadog.profiling.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.condition.JRE.JAVA_8;

import datadog.trace.api.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Note: some additional tests for this class are located in profiling-controller-openjdk module */
@ExtendWith(MockitoExtension.class)
public class ControllerFactoryTest {

  @Mock private Config config;

  @Test
  @EnabledOnJre({JAVA_8})
  public void testCreateControllerJava8() {
    final UnsupportedEnvironmentException unsupportedEnvironmentException =
        assertThrows(
            UnsupportedEnvironmentException.class,
            () -> {
              ControllerFactory.createController(config);
            });
    String expected =
        "Not enabling profiling; it requires OpenJDK 11+, Oracle Java 11+, or Zulu Java 8 (1.8.0_212+).";
    final String javaVendor = System.getProperty("java.vendor");
    final String javaRuntimeName = System.getProperty("java.runtime.name");
    final String javaVersion = System.getProperty("java.version");
    if ("Azul Systems, Inc.".equals(javaVendor)) {
      expected = "Not enabling profiling; it requires Zulu Java 8 (1.8.0_212+).";
    } else if ("Java(TM) SE Runtime Environment".equals(javaRuntimeName)
        && "Oracle Corporation".equals(javaVendor)
        && javaVersion.startsWith("1.8")) {
      // condition for OracleJRE8 (with proprietary JFR inside)
      expected = "Not enabling profiling; it requires Oracle Java 11+.";
    } else if ("OpenJDK Runtime Environment".equals(javaRuntimeName)) {
      expected = "Not enabling profiling; it requires 1.8.0_262+ OpenJDK builds from the following vendors: AdoptOpenJDK, Amazon Corretto, Azul Zulu, BellSoft Liberica";
    }
    assertEquals(
        expected,
        unsupportedEnvironmentException.getMessage(),
        "'" + javaRuntimeName + "' / '" + javaVendor + "' / '" + javaVersion + "'");
  }
}

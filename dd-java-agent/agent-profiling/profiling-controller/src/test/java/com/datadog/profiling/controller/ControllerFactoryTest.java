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
    final String javaVendor = System.getProperty("java.vendor");
    final String javaRuntimeName = System.getProperty("java.runtime.name");
    final String javaVersion = System.getProperty("java.version");
    String expected =
        "Not enabling profiling for vendor="
            + javaVendor
            + ", version="
            + javaVersion
            + ", runtimeName="
            + javaRuntimeName
            + "; it requires ";
    if ("Azul Systems, Inc.".equals(javaVendor)) {
      expected += "Zulu Java 8 (1.8.0_212+).";
    } else if ("Java(TM) SE Runtime Environment".equals(javaRuntimeName)
        && "Oracle Corporation".equals(javaVendor)
        && javaVersion.startsWith("1.8")) {
      // condition for OracleJRE8 (with proprietary JFR inside)
      expected += "Oracle JRE/JDK 8u40+";
    } else if ("OpenJDK Runtime Environment".equals(javaRuntimeName)) {
      expected +=
          "1.8.0_272+ OpenJDK builds from the following vendors: AdoptOpenJDK, Eclipse Temurin, Amazon Corretto, Azul Zulu, BellSoft Liberica.";
    } else {
      expected += "OpenJDK 11+, Oracle Java 11+, or Zulu Java 8 (1.8.0_212+).";
    }
    assertEquals(
        expected,
        unsupportedEnvironmentException.getMessage(),
        "'" + javaRuntimeName + "' / '" + javaVendor + "' / '" + javaVersion + "'");
  }
}

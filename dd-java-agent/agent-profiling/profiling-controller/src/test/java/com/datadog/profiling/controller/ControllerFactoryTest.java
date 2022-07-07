package com.datadog.profiling.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.condition.JRE.JAVA_8;

import datadog.trace.bootstrap.config.provider.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Note: some additional tests for this class are located in profiling-controller-openjdk module */
@ExtendWith(MockitoExtension.class)
public class ControllerFactoryTest {

  @Mock private ConfigProvider configProvider;

  @Test
  @EnabledOnJre({JAVA_8})
  public void testCreateControllerJava8() {
    final UnsupportedEnvironmentException unsupportedEnvironmentException =
        assertThrows(
            UnsupportedEnvironmentException.class,
            () -> {
              ControllerFactory.createController(configProvider);
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
    if (javaVendor.equals("Azul Systems, Inc.")) {
      expected += "Zulu Java 8 (1.8.0_212+).";
    } else if (javaVendor.equals("Oracle Corporation") && !javaRuntimeName.startsWith("OpenJDK")) {
      // condition for Oracle JDK 8 (with proprietary JFR inside)
      expected += "Oracle JRE/JDK 8u40+";
    } else if (javaVendor.equals("Oracle Corporation") && javaRuntimeName.startsWith("OpenJDK")) {
      // condition for Oracle OpenJDK 8 (with open JFR inside)
      expected += "1.8.0_272+ OpenJDK builds (upstream)";
    } else if (javaRuntimeName.startsWith("OpenJDK")) {
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

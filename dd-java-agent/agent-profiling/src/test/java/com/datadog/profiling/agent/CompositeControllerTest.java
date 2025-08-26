package com.datadog.profiling.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datadog.profiling.controller.ControllerContext;
import com.datadog.profiling.controller.UnsupportedEnvironmentException;
import datadog.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/** Note: some additional tests for this class are located in profiling-controller-openjdk module */
public class CompositeControllerTest {

  @Test
  public void smokeTest() {
    UnsupportedEnvironmentException unsupportedEnvironmentException = null;
    try {
      CompositeController.build(ConfigProvider.getInstance(), new ControllerContext());
      // successfully created controller, return
      return;
    } catch (UnsupportedEnvironmentException e) {
      unsupportedEnvironmentException = e;
    }
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

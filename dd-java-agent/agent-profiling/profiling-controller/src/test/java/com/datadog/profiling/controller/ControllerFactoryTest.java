package com.datadog.profiling.controller;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.condition.JRE.JAVA_8;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;

/** Note: some additional tests for this class are located in profiling-controller-openjdk module */
public class ControllerFactoryTest {

  @Test
  @EnabledOnJre({JAVA_8})
  public void testCreateControllerJava8() {
    assertThrows(
        UnsupportedEnvironmentException.class,
        () -> {
          ControllerFactory.createController();
        });
  }
}

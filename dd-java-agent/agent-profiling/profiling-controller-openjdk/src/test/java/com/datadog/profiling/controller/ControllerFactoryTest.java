package com.datadog.profiling.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class ControllerFactoryTest {

  /** We assume that tests for this module are run only on JVMs that support JFR */
  @Test
  public void testCreateController() throws UnsupportedEnvironmentException {
    assertEquals(
        "com.datadog.profiling.controller.openjdk.OpenJdkController",
        ControllerFactory.createController().getClass().getName());
  }
}

package com.datadog.profiling.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class ControllerFactoryTest {

  @Mock private Config config;

  /** We assume that tests for this module are run only on JVMs that support JFR */
  @Test
  public void testCreateController() throws UnsupportedEnvironmentException {
    assertEquals(
        "com.datadog.profiling.controller.openjdk.OpenJdkController",
        ControllerFactory.createController(config).getClass().getName());
  }
}

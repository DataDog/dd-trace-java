package com.datadog.profiling.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class ControllerFactoryTest {

  @Mock private Config config;

  /**
   * We assume that tests for this module are run only on JVMs that support JFR. Ideally we would
   * want to have a conditional annotation to this, but currently it is somewhat hard to do well,
   * partially because jfr is available in some java8 versions and not others. Currently we just run
   * tests with java11 that is guaranteed to have JFR.
   */
  @Test
  public void testCreateController()
      throws UnsupportedEnvironmentException, ConfigurationException {
    assertEquals(
        "com.datadog.profiling.controller.openjdk.OpenJdkController",
        ControllerFactory.createController(config).getClass().getName());
  }

  @Test
  public void testConfigurationException() {
    when(config.getProfilingTemplateOverrideFile())
        .thenReturn("some-path-that-is-not-supposed-to-exist!!!");
    assertThrows(
        ConfigurationException.class,
        () -> {
          ControllerFactory.createController(config);
        });
  }
}

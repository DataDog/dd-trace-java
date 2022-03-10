package com.datadog.profiling.controller;

import static datadog.trace.api.config.ProfilingConfig.PROFILING_TEMPLATE_OVERRIDE_FILE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import datadog.trace.bootstrap.config.provider.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class ControllerFactoryTest {

  @Mock private ConfigProvider configProvider;

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
        ControllerFactory.createController(configProvider).getClass().getName());
  }

  @Test
  public void testConfigurationException() {
    when(configProvider.getString(eq(PROFILING_TEMPLATE_OVERRIDE_FILE)))
        .thenReturn("some-path-that-is-not-supposed-to-exist!!!");
    assertThrows(
        ConfigurationException.class,
        () -> {
          ControllerFactory.createController(configProvider);
        });
  }
}

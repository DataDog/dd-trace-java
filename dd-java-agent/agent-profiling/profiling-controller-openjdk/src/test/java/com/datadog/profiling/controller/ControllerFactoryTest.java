package com.datadog.profiling.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class ControllerFactoryTest {

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
        ControllerFactory.createController(ConfigProvider.createDefault()).getClass().getName());
  }

  @Test
  public void testConfigurationException() {
    Properties props = new Properties();
    props.put(
        ProfilingConfig.PROFILING_TEMPLATE_OVERRIDE_FILE,
        "some-path-that-is-not-supposed-to-exist!!!");
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    assertThrows(
        ConfigurationException.class,
        () -> {
          ControllerFactory.createController(configProvider);
        });
  }
}

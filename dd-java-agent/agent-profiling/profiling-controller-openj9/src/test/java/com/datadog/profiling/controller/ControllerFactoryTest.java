package com.datadog.profiling.controller;

import static datadog.trace.api.config.ProfilingConfig.PROFILING_AUXILIARY_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
    Properties props = new Properties();
    props.put(PROFILING_AUXILIARY_TYPE, "async");
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    assertEquals(
        "com.datadog.profiling.controller.openj9.OpenJ9Controller",
        ControllerFactory.createController(configProvider).getClass().getName());
  }
}

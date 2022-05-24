package com.datadog.profiling.controller;

import static datadog.trace.api.config.ProfilingConfig.PROFILING_ASYNC_ENABLED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
    // Enable test on OpenJ9 only
    final String javaVendor = System.getProperty("java.vendor");
    final String javaRuntimeName = System.getProperty("java.runtime.name");
    assumeTrue(
        javaVendor.equals("IBM Corporation") && javaRuntimeName.startsWith("IBM Semeru Runtime"));

    Properties props = new Properties();
    props.put(PROFILING_ASYNC_ENABLED, Boolean.toString(true));
    ConfigProvider configProvider = ConfigProvider.withPropertiesOverride(props);

    assertEquals(
        "com.datadog.profiling.controller.async.AsyncController",
        ControllerFactory.createController(configProvider).getClass().getName());
  }
}
